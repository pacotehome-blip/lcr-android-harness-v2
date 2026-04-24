package com.pa.lcr.lcp;

import android.content.Context;
import com.pa.lcr.lcp.storage.DeliveryLogStore;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Implémentation simple du bridge API -> DeliveryController.
 * Le serveur HTTP utilise cette façade pour accéder aux fonctions api_*.
 *
 * NOTE:
 * - La logique anti-lag / UI-like de JobGet (rate limit, stale, fallback, flow/pause) est dans
 * DeliveryController.api_deliveryJobGet().
 * - La façade reste "thin" pour éviter de faire le travail 2 fois.
 */
public final class DeliveryApiFacadeImpl implements ApiFacade {

  private final DeliveryController controller;
  private final DeliveryLogStore logStore;
  private final Context appCtx;

  public DeliveryApiFacadeImpl(DeliveryController controller, Context context) {
    this.controller = controller;
    this.appCtx = context.getApplicationContext();
    this.logStore = new DeliveryLogStore(this.appCtx);
    this.logStore.purgeOlderThanDaysAsync(7);
  }
@Override
public ApiResult api_deliveryAlignA() {
    return ApiResult.fail("Call after connect", "NO_ACTIVE_MEDIA");
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

  // ✅ exposer Align/Recover (A) via API
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
   * ✅ JobGet : pass-through.
   * Toute la logique anti-lag / UI-like est dans DeliveryController.api_deliveryJobGet().
   */
  @Override
  public ApiResult api_deliveryJobGet(String jobId) {
    if (controller == null) {
      return ApiResult.fail("Job: 0 - Inconnu", "NO_CONTROLLER");
    }
    if (jobId == null || jobId.trim().isEmpty()) {
      return ApiResult.fail("Job: 0 - Invalide", "JOB_ID_EMPTY");
    }
    return controller.api_deliveryJobGet(jobId);
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
  

  // =========================================================
  // ✅ COMMIT 2: Registre prêt / validateRegister
  // =========================================================
  @Override
  public ApiResult api_registerValidate(
      String numero_livraison,
      Integer expected_lcrnode_dec,
      String expected_serial_id,
      Integer expected_product_number,
      String expected_compartment
  ) {
    if (controller == null) {
      return ApiResult.fail(
          "Validate: 0 - Registre non prêt / non connecté.",
          "NO_CONTROLLER"
      );
    }
    return controller.api_registerValidate(
        numero_livraison,
        expected_lcrnode_dec,
        expected_serial_id,
        expected_product_number,
        expected_compartment
    );
  }

  // =========================================================
  // ✅ Ticket: Reprint current (mono-registre)
  // =========================================================
  @Override
  public ApiResult api_ticketReprintCurrent() {
    if (controller == null) {
      return ApiResult.fail(
          "Reprint: 0 - Registre non prêt / non connecté.",
          "NO_CONTROLLER"
      );
    }
    return controller.api_ticketReprintCurrent();
  }

  private static String utcStamp() {
    SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss'Z'", Locale.ROOT);
    df.setTimeZone(TimeZone.getTimeZone("UTC"));
    return df.format(new Date());
  }

  // =========================================================
  // ✅ B2: helper public pour MultiRegisterApiFacadeImpl.api_dbDump()
  // =========================================================
  public static String utcStampPublic() {
    SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss'Z'", Locale.ROOT);
    df.setTimeZone(TimeZone.getTimeZone("UTC"));
    return df.format(new Date());
  }
}
