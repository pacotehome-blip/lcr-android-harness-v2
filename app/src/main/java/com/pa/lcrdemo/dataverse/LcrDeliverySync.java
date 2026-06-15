package com.pa.lcrdemo.dataverse;

import android.content.ContentValues;
import android.content.Context;
import android.util.Log;

import com.pa.lcr.lcp.storage.LcrDeliveryStatusDb;
import com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow;
import com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.NoteRow;
import com.pa.lcrdemo.config.LcrConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * LcrDeliverySync — Synchronisation bidirectionnelle entre SQLite APK et Dataverse.
 *
 * Chemin : app/src/main/java/com/pa/lcrdemo/dataverse/LcrDeliverySync.java
 *
 * Responsabilités :
 *   1. pushPending()     — pousse les transactions PENDING vers Dataverse (APK → Dataverse)
 *   2. syncNotes()       — récupère les notes templates depuis Dataverse (Dataverse → APK)
 *   3. syncAll()         — exécute les deux en séquence (appelé au lancement + retour réseau)
 */
public class LcrDeliverySync {

    private static final String TAG = "LcrDeliverySync";

    // Noms des tables Dataverse (avec préfixe lcr_lcr_ car publisher lcr + nom lcr_xxx)
    private static final String TABLE_DELIVERY = "lcr_lcr_delivery_statuses";
    private static final String TABLE_NOTE     = "lcr_lcr_note_templates";

    // =========================================================
    // Point d'entrée principal — appelé depuis MainActivity après MSAL
    // =========================================================

    /**
     * Sync complète : push PENDING + pull notes templates.
     * Exécuter depuis un thread background.
     */
    public static void syncAll(Context ctx, String accessToken) {
        Log.i(TAG, "syncAll: démarrage");
        try {
            pushPending(ctx, accessToken);
        } catch (Exception e) {
            Log.e(TAG, "syncAll pushPending ERR: " + e.getMessage());
        }
        try {
            syncNotes(ctx, accessToken);
        } catch (Exception e) {
            Log.e(TAG, "syncAll syncNotes ERR: " + e.getMessage());
        }
        Log.i(TAG, "syncAll: terminé");
    }

    // =========================================================
    // 1. Push PENDING → Dataverse
    // =========================================================

    /**
     * Pousse toutes les transactions PENDING vers Dataverse.
     * Marque SYNCED si succès, ERROR si échec.
     */
    public static void pushPending(Context ctx, String accessToken) throws Exception {
        LcrDeliveryStatusDb db = new LcrDeliveryStatusDb(ctx);
        List<DeliveryRow> pending = db.getPendingDeliveries();

        if (pending.isEmpty()) {
            Log.i(TAG, "pushPending: aucune transaction PENDING");
            return;
        }

        Log.i(TAG, "pushPending: " + pending.size() + " transaction(s) à pousser");

        String orgUrl = LcrConfig.getDataverseUrl(ctx);

        for (DeliveryRow row : pending) {
            try {
                String dataverseId = pushDeliveryRow(row, orgUrl, accessToken);
                db.markSynced(row.id, dataverseId);
                Log.i(TAG, "pushPending: OK id=" + row.id + " wo=" + row.woNum
                    + " dataverseId=" + dataverseId);
            } catch (Exception e) {
                db.markError(row.id, e.getMessage());
                Log.e(TAG, "pushPending: ERREUR id=" + row.id + " wo=" + row.woNum
                    + " err=" + e.getMessage());
            }
        }
    }

    /**
     * Pousse une seule transaction vers Dataverse.
     * Si dataverse_id existe → PATCH, sinon → POST.
     * Retourne le GUID Dataverse créé ou mis à jour.
     */
    private static String pushDeliveryRow(DeliveryRow row, String orgUrl,
                                           String accessToken) throws Exception {
        JSONObject body = buildDeliveryJson(row);
        String bodyStr  = body.toString();
        byte[] bodyBytes = bodyStr.getBytes(StandardCharsets.UTF_8);

        // Si dataverse_id connu → PATCH, sinon → POST nouvelle ligne
        boolean isUpdate = row.dataverseId != null && !row.dataverseId.isEmpty();

        String urlStr = orgUrl + "/api/data/v9.2/" + TABLE_DELIVERY +
            (isUpdate ? "(" + row.dataverseId + ")" : "");

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod(isUpdate ? "PATCH" : "POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Authorization",    "Bearer " + accessToken);
            conn.setRequestProperty("Content-Type",     "application/json; charset=utf-8");
            conn.setRequestProperty("Accept",           "application/json");
            conn.setRequestProperty("OData-MaxVersion", "4.0");
            conn.setRequestProperty("OData-Version",    "4.0");
            if (!isUpdate) {
                // POST — retourner le GUID créé
                conn.setRequestProperty("Prefer", "return=representation");
            }
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int code = conn.getResponseCode();

            if (code == 204) {
                // PATCH OK — retourner l'ID existant
                return row.dataverseId;
            } else if (code == 200 || code == 201) {
                // POST OK — extraire le GUID depuis la réponse
                InputStream is = conn.getInputStream();
                byte[] respBytes = readStream(is);
                String respStr = new String(respBytes, StandardCharsets.UTF_8);
                JSONObject resp = new JSONObject(respStr);
                return resp.optString("lcr_lcr_delivery_statusid", row.woNum + "-" + row.id);
            } else {
                String err = "";
                try {
                    byte[] errBytes = readStream(conn.getErrorStream());
                    err = new String(errBytes, StandardCharsets.UTF_8);
                } catch (Exception ignored) {}
                throw new RuntimeException("HTTP " + code + ": " +
                    err.substring(0, Math.min(300, err.length())));
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Construit le JSON Dataverse depuis une DeliveryRow.
     */
    private static JSONObject buildDeliveryJson(DeliveryRow row) throws Exception {
        JSONObject j = new JSONObject();

        // Identification
        putStr(j, "lcr_wo_num",          row.woNum);
        putStr(j, "lcr_wo_id_guid",      row.woIdGuid);
        putStr(j, "lcr_tournee_id",       row.tourneeId);
        putInt(j, "lcr_transaction_no",   row.transactionNo);
        putInt(j, "lcr_stop_sequence",    row.stopSequence);
        putStr(j, "lcr_livreur_id",       row.livreurId);
        putStr(j, "lcr_camion_id",        row.camionId);
        putStr(j, "lcr_serial_id",        row.serialId);
        putInt(j, "lcr_lcrnode",          row.lcrnode);
        putStr(j, "lcr_btmac",            row.btmac);

        // Type transaction
        putStr(j, "lcr_stop_type",        row.stopType);
        putStr(j, "lcr_type",             row.type);
        putStr(j, "lcr_source",           row.source);
        putStr(j, "lcr_ticket_no_ref",    row.ticketNoRef);
        j.put("lcr_approbation_required", row.approbationRequired == 1);
        putStr(j, "lcr_approbation_status", row.approbationStatus);
        putStr(j, "lcr_approbation_by",   row.approbationBy);
        putStr(j, "lcr_approbation_ts",   row.approbationTs);

        // Données commerciales
        putStr(j, "lcr_client",           row.client);
        putInt(j, "lcr_produit_no",        row.produitNo);
        putInt(j, "lcr_compartiment_id",   row.compartimentId);
        putDbl(j, "lcr_preset_l",          row.presetL);
        putDbl(j, "lcr_prix_unitaire",     row.prixUnitaire);
        putDbl(j, "lcr_tps",               row.tps);
        putDbl(j, "lcr_tvq",               row.tvq);
        putDbl(j, "lcr_taxe_carbone",      row.taxeCarbone);
        putStr(j, "lcr_memo_dispatch",     row.memoDispatch);

        // Données terrain
        putStr(j, "lcr_ticket_no",         row.ticketNo);
        putStr(j, "lcr_sale_no",           row.saleNo);
        putDbl(j, "lcr_net_l",             row.netL);
        putDbl(j, "lcr_gross_l",           row.grossL);
        putDbl(j, "lcr_delta_net_l",       row.deltaNetL);
        putDbl(j, "lcr_delta_gross_l",     row.deltaGrossL);
        putStr(j, "lcr_preset_status",     row.presetStatus);
        putStr(j, "lcr_start_utc",         row.startUtc);
        putStr(j, "lcr_end_utc",           row.endUtc);
        putDbl(j, "lcr_duration_s",        row.durationS);

        // Inventaire
        putDbl(j, "lcr_inventaire_avant_l", row.inventaireAvantL);
        putDbl(j, "lcr_inventaire_apres_l", row.inventaireApresL);
        putStr(j, "lcr_serial_id_original", row.serialIdOriginal);
        putStr(j, "lcr_serial_id_nouveau",  row.serialIdNouveau);

        // Notes et payload
        putStr(j, "lcr_notes_livreur",     row.notesLivreur);
        putStr(j, "lcr_sync_status",       LcrDeliveryStatusDb.SYNC_SYNCED);
        putStr(j, "lcr_payload_json",      row.payloadJson);

        // Historique
        putDbl(j, "lcr_previous_net_l",    row.previousNetL);
        putDbl(j, "lcr_previous_gross_l",  row.previousGrossL);
        putStr(j, "lcr_previous_ticket_no", row.previousTicketNo);
        putDbl(j, "lcr_total_net_l",       row.totalNetL);
        putDbl(j, "lcr_total_gross_l",     row.totalGrossL);
        putInt(j, "lcr_delivery_count",    row.deliveryCount);
        putDbl(j, "lcr_preset_overage_l",  row.presetOverageL);

        // Erreurs
        putStr(j, "lcr_error_code",        row.errorCode);
        putStr(j, "lcr_error_msg",         row.errorMsg);

        return j;
    }

    // =========================================================
    // 2. Sync notes templates Dataverse → SQLite APK
    // =========================================================

    /**
     * Récupère les notes templates depuis Dataverse et remplace le cache local.
     */
    public static void syncNotes(Context ctx, String accessToken) throws Exception {
        String orgUrl = LcrConfig.getDataverseUrl(ctx);
        String urlStr = orgUrl + "/api/data/v9.2/" + TABLE_NOTE +
            "?$select=lcr_code,lcr_libelle_fr,lcr_libelle_en,lcr_categorie,lcr_active,lcr_ordre" +
            "&$filter=lcr_active eq true" +
            "&$orderby=lcr_ordre asc";

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
                Log.w(TAG, "syncNotes: HTTP " + code);
                return;
            }

            byte[] respBytes = readStream(conn.getInputStream());
            String respStr = new String(respBytes, StandardCharsets.UTF_8);
            JSONObject resp = new JSONObject(respStr);
            JSONArray values = resp.optJSONArray("value");

            if (values == null || values.length() == 0) {
                Log.i(TAG, "syncNotes: aucune note reçue");
                return;
            }

            List<ContentValues> notes = new ArrayList<>();
            for (int i = 0; i < values.length(); i++) {
                JSONObject n = values.getJSONObject(i);
                ContentValues cv = new ContentValues();
                cv.put(LcrDeliveryStatusDb.NOTE_COL_CODE,       n.optString("lcr_code"));
                cv.put(LcrDeliveryStatusDb.NOTE_COL_LIBELLE_FR, n.optString("lcr_libelle_fr"));
                cv.put(LcrDeliveryStatusDb.NOTE_COL_LIBELLE_EN, n.optString("lcr_libelle_en"));
                cv.put(LcrDeliveryStatusDb.NOTE_COL_CATEGORIE,  n.optString("lcr_categorie"));
                cv.put(LcrDeliveryStatusDb.NOTE_COL_ACTIVE,     n.optBoolean("lcr_active", true) ? 1 : 0);
                cv.put(LcrDeliveryStatusDb.NOTE_COL_ORDRE,      n.optInt("lcr_ordre", 0));
                notes.add(cv);
            }

            new LcrDeliveryStatusDb(ctx).replaceAllNotes(notes);
            Log.i(TAG, "syncNotes: " + notes.size() + " note(s) synchronisée(s)");

        } finally {
            conn.disconnect();
        }
    }

    // =========================================================
    // Helper — chercher un enregistrement Dataverse par wo_num
    // =========================================================
    private static String findDataverseIdByWoNum(String woNum, String orgUrl,
                                                   String accessToken) {
        try {
            String urlStr = orgUrl + "/api/data/v9.2/" + TABLE_DELIVERY
                + "?$select=lcr_lcr_delivery_statusid"
                + "&$filter=lcr_wo_num eq '" + woNum + "'"
                + "&$top=1";
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            try {
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Authorization",    "Bearer " + accessToken);
                conn.setRequestProperty("Accept",           "application/json");
                conn.setRequestProperty("OData-MaxVersion", "4.0");
                conn.setRequestProperty("OData-Version",    "4.0");

                if (conn.getResponseCode() == 200) {
                    byte[] resp = readStream(conn.getInputStream());
                    JSONObject json = new JSONObject(new String(resp, StandardCharsets.UTF_8));
                    JSONArray values = json.optJSONArray("value");
                    if (values != null && values.length() > 0) {
                        return values.getJSONObject(0)
                            .optString("lcr_lcr_delivery_statusid", null);
                    }
                }
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "findDataverseIdByWoNum ERR: " + e.getMessage());
        }
        return null;
    }

    // =========================================================
    // Helper — lire un InputStream complètement (compatible Android 9 / API 28)
    // =========================================================
    private static byte[] readStream(java.io.InputStream is) throws java.io.IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = is.read(chunk)) != -1) {
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }
    private static void putStr(JSONObject j, String key, String val) throws Exception {
        if (val != null && !val.isEmpty()) j.put(key, val);
    }
    private static void putInt(JSONObject j, String key, int val) throws Exception {
        if (val != 0) j.put(key, val);
    }
    private static void putDbl(JSONObject j, String key, double val) throws Exception {
        if (val != 0.0) j.put(key, val);
    }
}