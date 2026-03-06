
package com.pa.lcr.lcp;

import android.content.Context;

import com.pa.lcr.lcp.storage.DeliveryLogStore;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implémentation simple du bridge API -> DeliveryController.
 * Le serveur HTTP utilise cette façade pour accéder aux fonctions api_*.
 */
public final class DeliveryApiFacadeImpl implements ApiFacade {

  private final DeliveryController controller;
  private final DeliveryLogStore logStore;
  private final Context appCtx;

  // =========================================================
  // Anti-lag API polling (UI-like):
  // - cadence minimale (coalescing)
  // - backoff après erreur de lecture
  // - fallback: renvoyer last-good au lieu de code=0 en rafale
  // =========================================================
  private static final long JOB_MIN_POLL_MS = 900;          // cadence safe (0.9s)
  private static final long JOB_BACKOFF_ON_FAIL_MS = 1200;  // backoff après fail

  private static final class JobCache {
    volatile long lastOkMs = 0;
    volatile long nextAllowedReadMs = 0;
    volatile JSONObject lastOkData = null; // uniquement le "data" last-good
    volatile String lastOkMsg = "Job: 1 - (cached)";
  }

  private final ConcurrentHashMap<String, JobCache> jobCache = new ConcurrentHashMap<>();

  public DeliveryApiFacadeImpl(DeliveryController controller, Context context) {
    this.controller = controller;
    this.appCtx = context.getApplicationContext();
    this.logStore = new DeliveryLogStore(this.appCtx);
    this.logStore.purgeOlderThanDaysAsync(7);
  }

  @Override
  public ApiResult api_scanUsb() {
    if (controller == null) {
      return ApiResult.fail(
          "Scan USB: 0 - Aucun registre détecté. Valide tes connexions au registre (câble/OTG/USB-C).",
          "NO_CONTROLLER"
      );
    }
    return controller.api_scanUsb();
  }

  @Override
  public ApiResult api_openPingUsb() {
    if (controller == null) {
      return ApiResult.fail(
          "Open/Ping USB: 0 - USB non prêt. Vérifie câble/permission.",
          "NO_CONTROLLER"
      );
    }
    return controller.api_openPingUsb();
  }

  @Override
  public ApiResult api_connectLcp() {
    if (controller == null) {
      return ApiResult.fail(
          "Connect LCP: 0 - USB non connecté.",
          "NO_CONTROLLER"
      );
    }
    return controller.api_connectLcp();
  }

  // ✅ NOUVEAU : exposer Align/Recover (A) via API
  @Override
  public ApiResult api_deliveryAlignA() {
    if (controller == null) {
      return ApiResult.fail(
          "Align A: 0 - Registre non prêt / non connecté.",
          "NO_CONTROLLER"
      );
    }
    return controller.api_deliveryAlignA();
  }

  @Override
  public ApiResult api_deliveryStartC(int product1to16, double presetNet) {
    if (controller == null) {
      return ApiResult.fail(
          "Delivery C: 0 - Registre non prêt. Faire A d'abord.",
          "NO_CONTROLLER"
      );
    }
    return controller.api_deliveryStartC(product1to16, presetNet);
  }

  /**
   * ✅ Anti-lag polling job:
   * - si poll trop rapide -> renvoyer last-good (stale=true, reason=RATE_LIMIT)
   * - si read fail (ex rc=0x26) -> renvoyer last-good (stale=true, reason=READ_FAIL) + backoff
   * - sinon -> renvoyer fresh et mettre à jour last-good
   *
   * IMPORTANT: on renvoie code=1 quand on a un last-good, même si le read courant échoue,
   * pour éviter le spam code=0 côté client (PowerShell / Field Service).
   */
  @Override
  public ApiResult api_deliveryJobGet(String jobId) {
    if (controller == null) {
      return ApiResult.fail("Job: 0 - Inconnu", "NO_CONTROLLER");
    }
    if (jobId == null || jobId.trim().isEmpty()) {
      return ApiResult.fail("Job: 0 - Invalide", "JOB_ID_EMPTY");
    }

    long now = System.currentTimeMillis();
    JobCache c = jobCache.computeIfAbsent(jobId, k -> new JobCache());

    // 1) Coalescing / rate limit: ne pas relire LCP si trop tôt
    if (c.lastOkData != null && now < c.nextAllowedReadMs) {
      JSONObject data = deepCopyJson(c.lastOkData);
      try {
        data.put("stale", true);
        data.put("stale_reason", "RATE_LIMIT");
        data.put("next_read_ms", Math.max(0, c.nextAllowedReadMs - now));
        data.put("cached_age_ms", Math.max(0, now - c.lastOkMs));
      } catch (Exception ignored) {}
      return ApiResult.ok(c.lastOkMsg, data);
    }

    // 2) Tentative de read réel
    try {
      ApiResult fresh = controller.api_deliveryJobGet(jobId);

      // Si succès -> cache & retourner
      if (fresh != null && fresh.code == 1) {
        JSONObject j = fresh.toJson();
        JSONObject data = (j != null) ? j.optJSONObject("data") : null;

        if (data != null) {
          c.lastOkData = deepCopyJson(data);
          c.lastOkMs = now;
          c.lastOkMsg = (j.optString("msg", "Job: 1 - OK"));
          c.nextAllowedReadMs = now + JOB_MIN_POLL_MS;
        } else {
          // Pas de data: on garde quand même cadence pour limiter la pression
          c.nextAllowedReadMs = now + JOB_MIN_POLL_MS;
        }
        return fresh;
      }

      // Si échec mais on a cache -> fallback
      if (c.lastOkData != null) {
        c.nextAllowedReadMs = now + JOB_BACKOFF_ON_FAIL_MS;
        JSONObject data = deepCopyJson(c.lastOkData);
        try {
          data.put("stale", true);
          data.put("stale_reason", "READ_FAIL");
          data.put("read_error", (fresh != null) ? fresh.err : "JOB_READ_FAIL");
          data.put("backoff_ms", JOB_BACKOFF_ON_FAIL_MS);
          data.put("cached_age_ms", Math.max(0, now - c.lastOkMs));
        } catch (Exception ignored) {}
        return ApiResult.ok(c.lastOkMsg, data);
      }

      // Aucun cache: retourner tel quel
      return (fresh != null) ? fresh : ApiResult.fail("Job: 0 - Read error", "JOB_READ_FAIL");

    } catch (Exception e) {
      // Exception: fallback si cache
      if (c.lastOkData != null) {
        c.nextAllowedReadMs = now + JOB_BACKOFF_ON_FAIL_MS;
        JSONObject data = deepCopyJson(c.lastOkData);
        try {
          data.put("stale", true);
          data.put("stale_reason", "EXCEPTION");
          data.put("read_error", safeMsg(e));
          data.put("backoff_ms", JOB_BACKOFF_ON_FAIL_MS);
          data.put("cached_age_ms", Math.max(0, now - c.lastOkMs));
        } catch (Exception ignored) {}
        return ApiResult.ok(c.lastOkMsg, data);
      }
      return ApiResult.fail("Job: 0 - Read error", "JOB_READ_FAIL");
    }
  }

  @Override
  public ApiResult api_deliveryOneShotStart(String numero_livraison, int product1to16, double presetNetL, String compartment) {
    if (controller == null) {
      return ApiResult.fail(
          "OneShot: 0 - Registre non prêt. Faire A d'abord.",
          "NO_CONTROLLER"
      );
    }
    return controller.api_deliveryOneShotStart(numero_livraison, product1to16, presetNetL, compartment);
  }

  @Override
  public ApiResult api_deliveryContinue(String jobId) {
    if (controller == null) {
      return ApiResult.fail(
          "Continue: 0 - Registre non prêt / non connecté.",
          "NO_CONTROLLER"
      );
    }
    return controller.api_deliveryContinue(jobId);
  }

  @Override
  public ApiResult api_deliveryTerminate(String jobId) {
    if (controller == null) {
      return ApiResult.fail(
          "Terminate: 0 - Registre non prêt / non connecté.",
          "NO_CONTROLLER"
      );
    }
    return controller.api_deliveryTerminate(jobId);
  }

  // ✅ API: dump JSON -> Downloads
  @Override
  public ApiResult api_dbDump() {
    try {
      String name = "lcr_delivery_" + utcStamp() + ".json";
      boolean ok = logStore.dumpJsonToDownloads(appCtx, name);
      if (!ok) return ApiResult.fail("DB Dump: 0 - Failed", "DB_DUMP_FAIL");
      JSONObject d = new JSONObject();
      d.put("fileName", name);
      return ApiResult.ok("DB Dump: 1 - OK", d);
    } catch (Exception e) {
      JSONObject d = new JSONObject();
      try { d.put("detail", (e.getMessage() != null) ? e.getMessage() : ""); } catch (Exception ignored) {}
      return ApiResult.fail("DB Dump: 0 - Failed", "DB_DUMP_FAIL", d);
    }
  }

  private static String utcStamp() {
    SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss'Z'", Locale.ROOT);
    df.setTimeZone(TimeZone.getTimeZone("UTC"));
    return df.format(new Date());
  }

  // JSON copy simple pour éviter aliasing entre threads
  private static JSONObject deepCopyJson(JSONObject src) {
    try { return new JSONObject(src.toString()); }
    catch (Exception e) { return src; }
  }

  private static String safeMsg(Exception e) {
    if (e == null) return "";
    String m = e.getMessage();
    return (m == null) ? e.getClass().getSimpleName() : m;
  }
}
