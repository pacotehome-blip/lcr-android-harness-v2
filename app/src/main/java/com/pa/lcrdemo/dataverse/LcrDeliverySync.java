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

    // Noms des tables Dataverse (préfixe filgo_ — publisher Filgo, colonnes lcr_)
    private static final String TABLE_DELIVERY = "filgo_lcr_delivery_statuses";
    private static final String TABLE_NOTE     = "filgo_lcr_note_templates";

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

        // Toujours POST — une ligne SQLite = une nouvelle ligne Dataverse
        // Chaque impression génère son propre enregistrement
        String urlStr = orgUrl + "/api/data/v9.2/" + TABLE_DELIVERY;

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Authorization",    "Bearer " + accessToken);
            conn.setRequestProperty("Content-Type",     "application/json; charset=utf-8");
            conn.setRequestProperty("Accept",           "application/json");
            conn.setRequestProperty("OData-MaxVersion", "4.0");
            conn.setRequestProperty("OData-Version",    "4.0");
            conn.setRequestProperty("Prefer",           "return=representation");
            conn.setRequestProperty("Content-Length",   String.valueOf(bodyBytes.length));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int code = conn.getResponseCode();

            if (code == 200 || code == 201) {
                // POST OK — extraire le GUID Dataverse créé
                InputStream is = conn.getInputStream();
                byte[] respBytes = readStream(is);
                String respStr = new String(respBytes, StandardCharsets.UTF_8);
                JSONObject resp = new JSONObject(respStr);
                return resp.optString("filgo_lcr_delivery_statusid", row.woNum + "-" + row.id);
            } else if (code == 204) {
                // Pas de corps retourné
                return row.woNum + "-" + row.id;
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
        putStr(j, "filgo_wo_num",          row.woNum);
        // ✅ Champ primaire requis par Dataverse
        putStr(j, "filgo_name",            row.woNum + "-" + (row.ticketNo != null ? row.ticketNo : row.id));
        putStr(j, "filgo_wo_id_guid",      row.woIdGuid);
        putStr(j, "filgo_tournee_id",       row.tourneeId);
        putInt(j, "filgo_transaction_no",   row.transactionNo);
        putInt(j, "filgo_stop_sequence",    row.stopSequence);
        putStr(j, "filgo_livreur_id",       row.livreurId);
        putStr(j, "filgo_camion_id",        row.camionId);
        putStr(j, "filgo_serial_id",        row.serialId);
        putInt(j, "filgo_lcrnode",          row.lcrnode);
        putStr(j, "filgo_btmac",            row.btmac);

        // Type transaction
        putStr(j, "filgo_stop_type",        row.stopType);
        putStr(j, "filgo_type",             row.type);
        putStr(j, "filgo_source",           row.source);
        putStr(j, "filgo_ticket_no_ref",    row.ticketNoRef);
        j.put("filgo_approbation_required", row.approbationRequired == 1);
        putStr(j, "filgo_approbation_status", row.approbationStatus);
        putStr(j, "filgo_approbation_by",   row.approbationBy);
        putStr(j, "filgo_approbation_ts",   row.approbationTs);

        // Données commerciales
        putStr(j, "filgo_client",           row.client);
        putInt(j, "filgo_produit_no",        row.produitNo);
        putInt(j, "filgo_compartiment_id",   row.compartimentId);
        putDbl(j, "filgo_preset_l",          row.presetL);
        putDbl(j, "filgo_prix_unitaire",     row.prixUnitaire);
        putDbl(j, "filgo_tps",               row.tps);
        putDbl(j, "filgo_tvq",               row.tvq);
        putDbl(j, "filgo_taxe_carbone",      row.taxeCarbone);
        putStr(j, "filgo_memo_dispatch",     row.memoDispatch);

        // Données terrain
        putStr(j, "filgo_ticket_no",         row.ticketNo);
        putStr(j, "filgo_sale_no",           row.saleNo);
        putDbl(j, "filgo_net_l",             row.netL);
        putDbl(j, "filgo_gross_l",           row.grossL);
        putDbl(j, "filgo_delta_net_l",       row.deltaNetL);
        putDbl(j, "filgo_delta_gross_l",     row.deltaGrossL);
        putStr(j, "filgo_preset_status",     row.presetStatus);
        putStr(j, "filgo_start_utc",         row.startUtc);
        putStr(j, "filgo_end_utc",           row.endUtc);
        putDbl(j, "filgo_duration_s",        row.durationS);

        // Inventaire
        putDbl(j, "filgo_inventaire_avant_l", row.inventaireAvantL);
        putDbl(j, "filgo_inventaire_apres_l", row.inventaireApresL);
        putStr(j, "filgo_serial_id_original", row.serialIdOriginal);
        putStr(j, "filgo_serial_id_nouveau",  row.serialIdNouveau);

        // Notes et payload
        putStr(j, "filgo_notes_livreur",     row.notesLivreur);
        putStr(j, "filgo_sync_status",       LcrDeliveryStatusDb.SYNC_SYNCED);
        putStr(j, "filgo_payload_json",      row.payloadJson);

        // Historique
        putDbl(j, "filgo_previous_net_l",    row.previousNetL);
        putDbl(j, "filgo_previous_gross_l",  row.previousGrossL);
        putStr(j, "filgo_previous_ticket_no", row.previousTicketNo);
        putDbl(j, "filgo_total_net_l",       row.totalNetL);
        putDbl(j, "filgo_total_gross_l",     row.totalGrossL);
        putInt(j, "filgo_delivery_count",    row.deliveryCount);
        putDbl(j, "filgo_preset_overage_l",  row.presetOverageL);

        // Erreurs
        putStr(j, "filgo_error_code",        row.errorCode);
        putStr(j, "filgo_error_msg",         row.errorMsg);

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
                cv.put(LcrDeliveryStatusDb.NOTE_COL_CODE,       n.optString("filgo_code"));
                cv.put(LcrDeliveryStatusDb.NOTE_COL_LIBELLE_FR, n.optString("filgo_libelle_fr"));
                cv.put(LcrDeliveryStatusDb.NOTE_COL_LIBELLE_EN, n.optString("filgo_libelle_en"));
                cv.put(LcrDeliveryStatusDb.NOTE_COL_CATEGORIE,  n.optString("filgo_categorie"));
                cv.put(LcrDeliveryStatusDb.NOTE_COL_ACTIVE,     n.optBoolean("filgo_active", true) ? 1 : 0);
                cv.put(LcrDeliveryStatusDb.NOTE_COL_ORDRE,      n.optInt("filgo_ordre", 0));
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
                + "?$select=filgo_lcr_delivery_statusid"
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
                            .optString("filgo_lcr_delivery_statusid", null);
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
    // Pull par ticket_no + serial_id + lcrnode (demande Paul, 31 juillet 2026)
    //
    // Dès que Dataverse est accessible (online), retrouve la livraison de CE ticket
    // (déjà lié au #série + lcrnode du registre connecté) et la rapatrie dans la table
    // locale (LcrDeliveryStatusDb) pour en retrouver le #wo, le delivery-uid, le preset,
    // le produit — même si aucune ligne locale n'existe encore (ex: APK réinstallée,
    // DB locale vidée, ou livraison créée par un autre mécanisme).
    //
    // Utilise EXACTEMENT les mêmes noms de colonnes Dataverse que pushPending() (confirmés
    // fonctionnels, pas devinés) : filgo_serial_id, filgo_lcrnode, filgo_ticket_no,
    // filgo_wo_num, filgo_wo_id_guid, filgo_preset_l, filgo_produit_no.
    //
    // @return true si une livraison a été trouvée et upsertée localement, false sinon.
    // =========================================================
    public static boolean pullDeliveryByTicket(Context ctx, String accessToken,
                                                String serialId, int lcrnode, String ticketNo) {
        if (serialId == null || serialId.trim().isEmpty() || ticketNo == null || ticketNo.trim().isEmpty()) {
            return false;
        }
        try {
            String orgUrl = LcrConfig.getDataverseUrl(ctx);
            String filter = "filgo_serial_id eq '" + odataEscape(serialId) + "'"
                + " and filgo_lcrnode eq " + lcrnode
                + " and filgo_ticket_no eq '" + odataEscape(ticketNo) + "'";
            String urlStr = orgUrl + "/api/data/v9.2/" + TABLE_DELIVERY
                + "?$filter=" + java.net.URLEncoder.encode(filter, "UTF-8").replace("+", "%20")
                + "&$orderby=filgo_transaction_no desc"
                + "&$top=1";

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
                    Log.w(TAG, "pullDeliveryByTicket: HTTP " + code + " pour ticket=" + ticketNo);
                    return false;
                }

                byte[] respBytes = readStream(conn.getInputStream());
                JSONObject resp = new JSONObject(new String(respBytes, StandardCharsets.UTF_8));
                JSONArray values = resp.optJSONArray("value");
                if (values == null || values.length() == 0) {
                    Log.i(TAG, "pullDeliveryByTicket: aucune livraison Dataverse pour ticket=" + ticketNo);
                    return false;
                }

                JSONObject d = values.getJSONObject(0);

                ContentValues cv = new ContentValues();
                cv.put(LcrDeliveryStatusDb.COL_WO_NUM,      d.optString("filgo_wo_num", ""));
                cv.put(LcrDeliveryStatusDb.COL_WO_ID_GUID,  d.optString("filgo_wo_id_guid", ""));
                cv.put(LcrDeliveryStatusDb.COL_SERIAL_ID,   d.optString("filgo_serial_id", serialId));
                cv.put(LcrDeliveryStatusDb.COL_LCRNODE,     d.optInt("filgo_lcrnode", lcrnode));
                cv.put(LcrDeliveryStatusDb.COL_TICKET_NO,   d.optString("filgo_ticket_no", ticketNo));
                cv.put(LcrDeliveryStatusDb.COL_PRODUIT_NO,  d.optInt("filgo_produit_no", 0));
                cv.put(LcrDeliveryStatusDb.COL_PRESET_L,    d.optDouble("filgo_preset_l", 0.0));
                cv.put(LcrDeliveryStatusDb.COL_TOURNEE_ID,  d.optString("filgo_tournee_id", ""));
                cv.put(LcrDeliveryStatusDb.COL_TRANSACTION_NO, d.optInt("filgo_transaction_no", 1));
                cv.put(LcrDeliveryStatusDb.COL_SOURCE,      "DATAVERSE_PULL");
                cv.put(LcrDeliveryStatusDb.COL_SYNC_STATUS, LcrDeliveryStatusDb.SYNC_SYNCED);
                cv.put(LcrDeliveryStatusDb.COL_DATAVERSE_ID,
                        d.optString("filgo_lcr_delivery_statusid", ""));

                LcrDeliveryStatusDb localDb = new LcrDeliveryStatusDb(ctx);
                try {
                    LcrDeliveryStatusDb.DeliveryRow existing = localDb.getByTicketNo(ticketNo);
                    if (existing != null) {
                        localDb.updateDelivery(existing.id, cv);
                        Log.i(TAG, "pullDeliveryByTicket: ticket=" + ticketNo + " — ligne locale mise à jour (id="
                                + existing.id + ")");
                    } else {
                        long newId = localDb.insertDelivery(cv);
                        Log.i(TAG, "pullDeliveryByTicket: ticket=" + ticketNo + " — nouvelle ligne locale (id="
                                + newId + ") wo=" + cv.getAsString(LcrDeliveryStatusDb.COL_WO_NUM));
                    }
                } finally {
                    try { localDb.close(); } catch (Exception ignored) {}
                }

                return true;

            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "pullDeliveryByTicket ERR pour ticket=" + ticketNo + ": " + e.getMessage());
            return false;
        }
    }

    private static String odataEscape(String s) {
        return s == null ? "" : s.replace("'", "''");
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