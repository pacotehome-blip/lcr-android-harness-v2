package com.pa.lcrdemo.dataverse;

// ═══════════════════════════════════════════════════════════════
// COMPATIBILITÉ ANDROID : API 28 (Android 9) → API 35 (Android 15)
// Tester sur Android 9 (192.168.134.105) ET Android 15 (R52X508K2DR)
// ═══════════════════════════════════════════════════════════════

import android.util.Log;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * WorkOrderUpdater — PATCH msdyn_workordersummary via Dataverse API.
 *
 * Chemin : app/src/main/java/com/pa/lcrdemo/dataverse/WorkOrderUpdater.java
 */
public class WorkOrderUpdater {

    private static final String TAG     = "WorkOrderUpdater";
    private static final String ORG_URL = "https://dev-filgo-sonic.crm3.dynamics.com";

    /**
     * Met à jour msdyn_workordersummary avec le JSON résultat de livraison.
     * Appeler depuis un thread background (btExec ou WorkManager).
     */
    public static void patchSummary(String accessToken,
                                     String workOrderGuid,
                                     String net,
                                     String gross,
                                     String ticket,
                                     String woNum,
                                     String deliveryUid) throws Exception {

        String guid = workOrderGuid.replace("{", "").replace("}", "").trim();

        // ✅ JSON minimal MVP
        JSONObject summary = new JSONObject();
        summary.put("delivery_uid", deliveryUid != null ? deliveryUid : "");
        summary.put("wonum",        woNum       != null ? woNum       : "");
        summary.put("net_l",        net         != null ? Double.parseDouble(net)   : 0);
        summary.put("gross_l",      gross       != null ? Double.parseDouble(gross) : 0);
        summary.put("ticket",       ticket      != null ? ticket      : "");
        summary.put("status",       "DONE");
        summary.put("source",       "LCR");
        summary.put("ts",           new java.util.Date().toString());

        JSONObject body = new JSONObject();
        body.put("msdyn_workordersummary", summary.toString());

        patchWorkOrderBody(accessToken, guid, body, woNum, net, gross, ticket, null);
    }

    /**
     * ✅ Lancée quand Dataverse refuse le PATCH parce que l'enregistrement a été
     * modifié entre notre lecture (GET) et notre écriture (PATCH) — typiquement
     * deux livreurs qui terminent leur livraison presque en même temps sur le
     * même WO. Capturée par patchSummaryConsolidated() pour relire-fusionner-
     * réessayer automatiquement.
     */
    private static final class ConcurrencyConflictException extends Exception {
        ConcurrencyConflictException(String msg) { super(msg); }
    }

    /**
     * ✅ Met à jour msdyn_workordersummary avec TOUTES les livraisons connues
     * pour ce WO (peu importe combien de livraisons/annulations) — un seul
     * payload consolidé, pour que FieldService Mobile affiche l'historique
     * complet sans dépendre d'une sous-grille séparée sur lcr_delivery_status.
     *
     * ✅ FIX (append, pas écrasement) : msdyn_workordersummary est un simple
     * champ texte — un PATCH remplace tout son contenu. Si l'APK a été
     * réinstallée (BD locale vidée), OU si un autre livreur/tablette a fait
     * d'autres livraisons sur ce même WO entre-temps, un PATCH basé
     * uniquement sur les livraisons locales effacerait l'historique déjà
     * présent côté Dataverse. On lit donc D'ABORD le contenu existant, on
     * fusionne avec les livraisons locales (dédupliquées par ticket_no — les
     * données locales ont priorité si le même ticket existe des deux côtés),
     * puis on envoie le résultat fusionné.
     *
     * ✅ FIX (concurrence) : si deux livreurs terminent presque en même temps,
     * les deux pourraient lire le même état AVANT que l'un des deux n'écrive
     * — un simple lire-fusionner-écrire ne suffit pas dans ce cas précis.
     * Protection par ETag (concurrence optimiste Dataverse) : le PATCH inclut
     * l'ETag lu au GET ; si l'enregistrement a changé entre-temps, Dataverse
     * refuse (412) et on relit-fusionne-réessaie automatiquement (jusqu'à 3
     * fois) au lieu d'écraser aveuglément.
     *
     * @param deliveries tableau JSON où chaque élément est le payload riche
     *                   d'une livraison/annulation (déjà construit par l'appelant,
     *                   typiquement depuis LcrDeliveryStatusDb.getAllForWo()).
     */
    public static void patchSummaryConsolidated(String accessToken,
                                                  String workOrderGuid,
                                                  String woNum,
                                                  org.json.JSONArray deliveries) throws Exception {

        String guid = workOrderGuid.replace("{", "").replace("}", "").trim();

        final int MAX_ATTEMPTS = 3;
        Exception lastErr = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            // ✅ Lire l'existant (+ ETag) côté Dataverse avant d'écraser —
            // best-effort côté contenu (jamais bloquant si la lecture échoue),
            // mais l'ETag doit être frais à CHAQUE tentative pour que la
            // protection de concurrence ait un sens.
            String etag = null;
            org.json.JSONArray existingLivraisons = new org.json.JSONArray();
            try {
                ExistingSummary existing = getExistingSummary(accessToken, guid);
                etag = existing.etag;
                existingLivraisons = existing.livraisons;
            } catch (Exception e) {
                Log.w(TAG, "Lecture historique existant ERR (non bloquant, on continue avec local seul): "
                    + e.getMessage());
            }

            org.json.JSONArray merged = mergeLivraisons(existingLivraisons, deliveries);

            // Dernière livraison non-annulée — pour les champs "courants" pratiques
            double lastNet = 0, lastGross = 0;
            String lastTicket = "";
            for (int i = merged.length() - 1; i >= 0; i--) {
                JSONObject d = merged.optJSONObject(i);
                if (d == null) continue;
                String type = d.optString("type", "");
                if ("ANNULATION".equals(type)) continue;
                lastNet    = d.optDouble("net_l", 0);
                lastGross  = d.optDouble("gross_l", 0);
                lastTicket = d.optString("ticket_no", "");
                break;
            }

            // Calculer le total cumulatif de toutes les livraisons non-annulées
            double totalNet = 0, totalGross = 0;
            for (int i = 0; i < merged.length(); i++) {
                JSONObject d = merged.optJSONObject(i);
                if (d == null) continue;
                if ("ANNULATION".equals(d.optString("type", ""))) continue;
                totalNet   += d.optDouble("net_l",   0);
                totalGross += d.optDouble("gross_l", 0);
            }

            JSONObject summary = new JSONObject();
            summary.put("wonum",            woNum != null ? woNum : "");
            summary.put("net_l",            lastNet);
            summary.put("gross_l",          lastGross);
            summary.put("total_net_l",      totalNet);
            summary.put("total_gross_l",    totalGross);
            summary.put("ticket",           lastTicket);
            summary.put("status",           "DONE");
            summary.put("source",           "LCR");
            summary.put("ts",               new java.util.Date().toString());
            summary.put("livraisons",       merged);
            summary.put("livraisons_count", merged.length());

            JSONObject body = new JSONObject();
            body.put("msdyn_workordersummary", summary.toString());

            try {
                patchWorkOrderBody(accessToken, guid, body, woNum,
                    String.valueOf(lastNet), String.valueOf(lastGross), lastTicket, etag);
                return; // ✅ succès
            } catch (ConcurrencyConflictException e) {
                lastErr = e;
                Log.w(TAG, "Conflit de concurrence (tentative " + attempt + "/" + MAX_ATTEMPTS
                    + ") — un autre appareil a modifié ce WO entre-temps, relecture-fusion-réessai");
                try { Thread.sleep(500L * attempt); } catch (InterruptedException ignored) {}
            }
        }

        throw new RuntimeException("PATCH échoué après " + MAX_ATTEMPTS
            + " tentatives (conflits de concurrence répétés)", lastErr);
    }

    /** Résultat combiné d'une lecture : ETag + livraisons existantes. */
    private static final class ExistingSummary {
        final String etag;
        final org.json.JSONArray livraisons;
        ExistingSummary(String etag, org.json.JSONArray livraisons) {
            this.etag = etag;
            this.livraisons = livraisons;
        }
    }

    /**
     * Lit le contenu actuel de msdyn_workordersummary sur Dataverse, l'ETag
     * de l'enregistrement au moment de la lecture, et en extrait le tableau
     * "livraisons" déjà présent (vide si champ absent, vide, ou non parsable
     * — ne jamais faire échouer l'appelant pour ça).
     */
    private static ExistingSummary getExistingSummary(String accessToken, String guid) throws Exception {
        String urlStr = ORG_URL + "/api/data/v9.2/msdyn_workorders(" + guid
            + ")?$select=msdyn_workordersummary";
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Authorization",    "Bearer " + accessToken);
            conn.setRequestProperty("Accept",           "application/json");
            conn.setRequestProperty("OData-MaxVersion", "4.0");
            conn.setRequestProperty("OData-Version",    "4.0");

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "GET msdyn_workordersummary HTTP " + code + " — traité comme historique vide");
                return new ExistingSummary(null, new org.json.JSONArray());
            }

            java.io.InputStream is = conn.getInputStream();
            byte[] respBytes = is.readAllBytes();
            String respStr = new String(respBytes, StandardCharsets.UTF_8);
            JSONObject resp = new JSONObject(respStr);

            // ✅ Dataverse inclut l'ETag soit dans l'en-tête HTTP "ETag", soit
            // dans la propriété "@odata.etag" du corps JSON — on tente les deux.
            String etag = conn.getHeaderField("ETag");
            if (etag == null || etag.isEmpty()) {
                etag = resp.optString("@odata.etag", null);
            }

            String summaryStr = resp.optString("msdyn_workordersummary", "");
            if (summaryStr.isEmpty()) return new ExistingSummary(etag, new org.json.JSONArray());

            JSONObject existingSummary = new JSONObject(summaryStr);
            org.json.JSONArray livraisons = existingSummary.optJSONArray("livraisons") != null
                ? existingSummary.getJSONArray("livraisons")
                : new org.json.JSONArray();
            return new ExistingSummary(etag, livraisons);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Fusionne les livraisons existantes (Dataverse) avec les livraisons
     * locales, dédupliquées par ticket_no. En cas de conflit (même ticket des
     * deux côtés), la version LOCALE gagne (plus fraîche/complète — celle
     * qu'on vient de construire depuis LcrDeliveryStatusDb). Les tickets qui
     * n'existent que côté Dataverse (livraisons d'avant une réinstallation de
     * l'APK, jamais dans la BD locale actuelle) sont préservés tels quels.
     */
    private static org.json.JSONArray mergeLivraisons(org.json.JSONArray existing, org.json.JSONArray local) {
        java.util.LinkedHashMap<String, JSONObject> byTicket = new java.util.LinkedHashMap<>();

        for (int i = 0; i < existing.length(); i++) {
            JSONObject d = existing.optJSONObject(i);
            if (d == null) continue;
            String ticket = d.optString("ticket_no", "");
            String key = !ticket.isEmpty() ? ticket : ("__no_ticket_" + i);
            byTicket.put(key, d);
        }
        for (int i = 0; i < local.length(); i++) {
            JSONObject d = local.optJSONObject(i);
            if (d == null) continue;
            String ticket = d.optString("ticket_no", "");
            String key = !ticket.isEmpty() ? ticket : ("__no_ticket_local_" + i);
            byTicket.put(key, d); // local écrase existing si même ticket
        }

        org.json.JSONArray merged = new org.json.JSONArray();
        for (JSONObject d : byTicket.values()) merged.put(d);
        return merged;
    }

    private static void patchWorkOrderBody(String accessToken, String guid,
                                            JSONObject body, String woNum,
                                            String net, String gross, String ticket,
                                            String etag) throws Exception {
        String urlStr = ORG_URL + "/api/data/v9.2/msdyn_workorders(" + guid + ")";
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod("PATCH");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Authorization",    "Bearer " + accessToken);
            conn.setRequestProperty("Content-Type",     "application/json; charset=utf-8");
            conn.setRequestProperty("Accept",           "application/json");
            conn.setRequestProperty("OData-MaxVersion", "4.0");
            conn.setRequestProperty("OData-Version",    "4.0");
            // ✅ Concurrence optimiste : si etag connu, Dataverse refuse (412) le
            // PATCH si l'enregistrement a été modifié depuis notre lecture (GET) —
            // ex. un autre livreur/tablette qui a terminé une livraison sur ce
            // même WO entre-temps.
            if (etag != null && !etag.isEmpty()) {
                conn.setRequestProperty("If-Match", etag);
            }

            byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int code = conn.getResponseCode();
            if (code == 204 || code == 200) {
                Log.i(TAG, "PATCH OK " + code + " — wonum=" + woNum
                    + " net=" + net + " gross=" + gross + " ticket=" + ticket);
            } else if (code == 412) {
                throw new ConcurrencyConflictException(
                    "412 Precondition Failed — WO modifié par un autre appareil depuis la lecture");
            } else {
                String err = "";
                try {
                    byte[] errBytes = conn.getErrorStream().readAllBytes();
                    err = new String(errBytes, StandardCharsets.UTF_8);
                } catch (Exception ignored) {}
                throw new RuntimeException("PATCH HTTP " + code + ": " + err.substring(0, Math.min(200, err.length())));
            }
        } finally {
            conn.disconnect();
        }
    }
}