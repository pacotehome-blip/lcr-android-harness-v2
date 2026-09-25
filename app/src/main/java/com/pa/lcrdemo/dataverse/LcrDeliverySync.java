package com.pa.lcrdemo.dataverse;

import android.content.ContentValues;
import android.content.Context;
import android.util.Log;

import com.pa.lcr.lcp.storage.LcrDeliveryStatusDb;
import com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow;
import com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.NoteRow;
import com.pa.lcr.lcp.storage.RegistreStore;
import com.pa.lcrdemo.config.LcrConfig;
import com.pa.lcr.lcp.log.LogBus;

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
    // ✅ AJOUTÉ (17 sept 2026, demande Paul) — miroir Dataverse de la table locale registre.
    private static final String TABLE_REGISTRE = "filgo_registrecompteurs";

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
            pushPendingRegistres(ctx, accessToken);
        } catch (Exception e) {
            Log.e(TAG, "syncAll pushPendingRegistres ERR: " + e.getMessage());
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
    // ✅ FIX (3 août 2026, leak introduit le même jour) : ApiTraceStore possède son propre
    // thread dédié interne par instance, jamais fermable de l'extérieur (pas de shutdown()
    // exposé). En créer une nouvelle à CHAQUE appel de pushPending() (toutes les 15 min via
    // DeliverySyncWorker + chaque retournerAuWorkOrder()/triggerNow()) fuyait un thread de
    // plus à chaque cycle, indéfiniment. Une seule instance partagée pour toute la durée
    // de l'app — même principe que ApiServer, qui n'en crée qu'une seule aussi.
    private static volatile com.pa.lcr.lcp.storage.ApiTraceStore sharedApiTraceStore;

    private static com.pa.lcr.lcp.storage.ApiTraceStore apiTraceStore(Context ctx) {
        com.pa.lcr.lcp.storage.ApiTraceStore local = sharedApiTraceStore;
        if (local == null) {
            synchronized (LcrDeliverySync.class) {
                local = sharedApiTraceStore;
                if (local == null) {
                    local = new com.pa.lcr.lcp.storage.ApiTraceStore(ctx.getApplicationContext());
                    sharedApiTraceStore = local;
                }
            }
        }
        return local;
    }

    // ✅ CORRIGÉ (17 sept 2026, demande Paul — "j'ai trois inscriptions
    // dans la bd dataverse") — confirmé par le CSV Dataverse fourni :
    // 2 enregistrements identiques (payload compact "armement", 302
    // caractères) avec des GUID différents, pour la même livraison.
    // Cause : pushPendingInternal() lit les lignes PENDING, les POST une
    // par une, et ne les marque SYNCED qu'APRÈS le POST — sans aucun
    // verrou contre une exécution concurrente. Trois déclencheurs
    // indépendants peuvent lancer ce chemin presque simultanément :
    // NetworkSync (démarrage réseau), DeliverySyncScheduler.triggerNow()
    // (juste après l'armement), et le worker périodique WorkManager
    // (15 min). Si deux tournent en même temps, les deux lisent la MÊME
    // ligne encore PENDING avant que l'un ou l'autre ne la marque
    // SYNCED — chacun POST alors son propre nouvel enregistrement
    // Dataverse pour la même livraison locale. Un verrou statique en
    // mémoire sérialise maintenant tous les appels : le deuxième
    // déclencheur attend que le premier ait fini de marquer ses lignes
    // SYNCED avant de lire à son tour — il ne trouve alors plus rien à
    // pousser pour cette livraison.
    private static final Object PUSH_PENDING_LOCK = new Object();

    public static void pushPending(Context ctx, String accessToken) throws Exception {
        synchronized (PUSH_PENDING_LOCK) {
            // ✅ FIX (4 août 2026) — même classe de bug que les 14 fuites corrigées le
            // 24 juillet dans RegisterTabFragment.java (SQLiteConnectionPool
            // exhaustion) : cette connexion n'était JAMAIS fermée. Particulièrement
            // grave ici car pushPending() tourne maintenant sur le cycle périodique
            // du worker (toutes les 15 min, depuis le fix du 3 août qui l'a enfin
            // raccroché) — donc une fuite à CHAQUE cycle, indéfiniment.
            LcrDeliveryStatusDb db = new LcrDeliveryStatusDb(ctx);
            try {
                pushPendingInternal(ctx, accessToken, db);
            } finally {
                try { db.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static void pushPendingInternal(Context ctx, String accessToken, LcrDeliveryStatusDb db) throws Exception {
        List<DeliveryRow> pending = db.getPendingDeliveries();

        if (pending.isEmpty()) {
            Log.i(TAG, "pushPending: aucune transaction PENDING");
            return;
        }

        Log.i(TAG, "pushPending: " + pending.size() + " transaction(s) à pousser");
        // ✅ (ajouté 3 août 2026, suite au ticket 10899/10900 introuvable dans Dataverse
        // mais absent de tout diagnostic) — ce fichier n'appelait jamais LogBus, seulement
        // android.util.Log (logcat) : invisible dans l'onglet Support/v_diagnostic_events,
        // peu importe le nombre d'événements affichés. Instrumentation ajoutée pour rendre
        // ce pipeline enfin visible sans dépendre d'une capture logcat bien synchronisée.
        LogBus.api(0, "[DATAVERSE-PUSH] " + pending.size() + " transaction(s) PENDING à pousser");

        String orgUrl = LcrConfig.getDataverseUrl(ctx);
        com.pa.lcr.lcp.storage.ApiTraceStore apiTraceStore = apiTraceStore(ctx);

        for (DeliveryRow row : pending) {
            long startMs = System.currentTimeMillis();
            try {
                String dataverseId = pushDeliveryRow(row, orgUrl, accessToken);
                long durationMs = System.currentTimeMillis() - startMs;
                db.markSynced(row.id, dataverseId);
                // ✅ AJOUTÉ (2 sept 2026, demande Paul — "le path doit
                // fonctionner offline et online" / question sur
                // sync_status BD vs JSON) — trouvé : la BD locale passait
                // bien PENDING→SYNCED (juste au-dessus), mais le fichier
                // JSON restait figé PENDING pour toujours, jamais mis à
                // jour après un vrai push réussi. Réécrit ici le même
                // fichier (même wo_num+ticket, grâce à la recherche-avant-
                // écriture déjà en place) avec sync_status=SYNCED — le
                // JSON reflète maintenant fidèlement l'état réel, pas
                // seulement la BD.
                try {
                    org.json.JSONObject jsonSynced = new org.json.JSONObject();
                    jsonSynced.put("job_id",      row.jobId != null ? row.jobId : "");
                    jsonSynced.put("wo_num",      row.woNum != null ? row.woNum : "");
                    jsonSynced.put("wo_id_guid",  row.woIdGuid != null ? row.woIdGuid : "");
                    jsonSynced.put("ticket_no",   row.ticketNo != null ? row.ticketNo : "");
                    jsonSynced.put("sale_no",     row.saleNo != null ? row.saleNo : "");
                    jsonSynced.put("net_l",       row.netL);
                    jsonSynced.put("gross_l",     row.grossL);
                    jsonSynced.put("serial_id",   row.serialId != null ? row.serialId : "");
                    jsonSynced.put("lcrnode",     row.lcrnode);
                    jsonSynced.put("btmac",       row.btmac != null ? row.btmac : "");
                    jsonSynced.put("type",        row.type != null ? row.type : "");
                    jsonSynced.put("backup_ts",   System.currentTimeMillis());
                    jsonSynced.put("payload_complet", row.payloadJson != null ? row.payloadJson : "{}");
                    jsonSynced.put("sync_status", com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.SYNC_SYNCED);
                    String ticketPourNomFichier = (row.ticketNo != null && !row.ticketNo.trim().isEmpty())
                        ? row.ticketNo : row.jobId;
                    com.pa.lcr.lcp.storage.LocalDeliveryBackup.backupDeliveryAsync(
                        ctx, row.woNum, ticketPourNomFichier, jsonSynced);
                } catch (Exception eJsonSync) {
                    Log.w(TAG, "Mise à jour JSON sync_status=SYNCED ERR (non-bloquant): " + eJsonSync.getMessage());
                }
                Log.i(TAG, "pushPending: OK id=" + row.id + " wo=" + row.woNum
                    + " dataverseId=" + dataverseId);
                LogBus.api(row.lcrnode, "[DATAVERSE-PUSH] OK ticket=" + row.ticketNo
                    + " wo=" + row.woNum + " dataverseId=" + dataverseId);
                // ✅ (ajouté 3 août 2026) — visible dans v_diagnostic_events (UNION api_trace),
                // donc réellement diagnosticable par DiagnosticRuleEngine, contrairement à
                // LogBus/log_bus_event (jamais inclus dans cette vue).
                apiTraceStore.addTraceAsync("POST", TABLE_DELIVERY, 201, durationMs,
                    row.serialId, row.ticketNo, null,
                    "push OK wo=" + row.woNum + " dataverseId=" + dataverseId);
            } catch (Exception e) {
                long durationMs = System.currentTimeMillis() - startMs;
                db.markError(row.id, e.getMessage());
                Log.e(TAG, "pushPending: ERREUR id=" + row.id + " wo=" + row.woNum
                    + " err=" + e.getMessage());
                LogBus.api(row.lcrnode, "[DATAVERSE-PUSH] ERR ticket=" + row.ticketNo
                    + " wo=" + row.woNum + " — " + e.getMessage()
                    + " — statut passé à ERROR, ne sera PLUS retenté par le service de sync"
                    + " (getPendingDeliveries() ne relit que PENDING)");
                apiTraceStore.addTraceAsync("POST", TABLE_DELIVERY, null, durationMs,
                    row.serialId, row.ticketNo, null,
                    "push ERR wo=" + row.woNum + " — " + e.getMessage());
            }
        }
    }

    // =========================================================
    // 1b. Push registre (table locale) → filgo_registrecompteurs
    // =========================================================

    // ✅ AJOUTÉ (17 sept 2026, demande Paul) — même classe de bug que
    // pushPending() (voir PUSH_PENDING_LOCK ci-dessus) : verrou dédié
    // pour éviter le même risque de doublon par exécution concurrente.
    private static final Object PUSH_PENDING_REGISTRES_LOCK = new Object();

    /**
     * Pousse les fiches registre PENDING vers Dataverse. Approche (a)
     * confirmée par Paul (17 sept 2026) pour gérer une modification
     * concurrente : avant tout PATCH, relit le versionnumber actuel de
     * la fiche Dataverse et le compare à celui qu'on avait en cache
     * localement depuis la dernière synchro. S'ils diffèrent, quelqu'un
     * d'autre (ex. Jacques) a modifié la fiche entre-temps — on ne
     * touche à RIEN, on log, et on laisse la fiche PENDING pour la
     * prochaine synchro (qui relira alors la version à jour). Ne pousse
     * que les champs que l'app connaît réellement (voir RegistreStore) —
     * jamais calibration/scellé/coefficient.
     */
    public static void pushPendingRegistres(Context ctx, String accessToken) throws Exception {
        synchronized (PUSH_PENDING_REGISTRES_LOCK) {
            RegistreStore store = new RegistreStore(ctx);
            List<RegistreStore.Row> pending = store.getPending();
            if (pending.isEmpty()) {
                Log.i(TAG, "pushPendingRegistres: aucune fiche PENDING");
                return;
            }
            Log.i(TAG, "pushPendingRegistres: " + pending.size() + " fiche(s) PENDING à pousser");
            LogBus.api(0, "[DATAVERSE-PUSH-REGISTRE] " + pending.size() + " fiche(s) PENDING à pousser");

            String orgUrl = LcrConfig.getDataverseUrl(ctx);
            for (RegistreStore.Row row : pending) {
                try {
                    pushRegistreRow(row, orgUrl, accessToken, store);
                } catch (Exception e) {
                    Log.e(TAG, "pushPendingRegistres: ERREUR serial=" + row.serialId + " err=" + e.getMessage());
                    LogBus.api(row.nud, "[DATAVERSE-PUSH-REGISTRE] ERR serial=" + row.serialId
                        + " — " + e.getMessage());
                }
            }
        }
    }

    private static void pushRegistreRow(RegistreStore.Row row, String orgUrl, String accessToken,
                                         RegistreStore store) throws Exception {
        // Étape 1 — relire l'état actuel de Dataverse par numero_de_serie
        // (jamais par le GUID en cache seul — c'est justement ce qu'on
        // valide). Récupère aussi le versionnumber ACTUEL pour comparaison.
        JSONObject existing = findRegistreByNumeroDeSerie(row.serialId, orgUrl, accessToken);

        if (existing == null) {
            // Aucune fiche là-bas — POST simple, rien à comparer.
            String urlStr = orgUrl + "/api/data/v9.2/" + TABLE_REGISTRE;
            JSONObject body = buildRegistreJson(row);
            JSONObject created = doJsonRequest("POST", urlStr, accessToken, body);
            String newId = created != null ? created.optString("filgo_registrecompteurid", null) : null;
            String newVersion = created != null ? created.optString("versionnumber", null) : null;
            store.markSynced(row.serialId, newId, newVersion);
            Log.i(TAG, "pushRegistreRow: créé serial=" + row.serialId + " id=" + newId);
            LogBus.api(row.nud, "[DATAVERSE-PUSH-REGISTRE] OK (créé) serial=" + row.serialId);
            return;
        }

        String dataverseId = existing.optString("filgo_registrecompteurid", null);
        String versionActuelle = existing.optString("versionnumber", null);

        // ✅ AJOUTÉ (18 sept 2026, demande Paul) — complète la fiche
        // locale avec les champs administratifs déjà présents côté
        // Dataverse, seulement là où la fiche locale ne les a pas
        // encore. Toujours fait, peu importe si le PATCH plus bas est
        // sauté (modification concurrente) — cet enrichissement est en
        // LECTURE seule côté Dataverse, aucun risque de conflit.
        try {
            store.enrichirDepuisDataverseSiManquant(row.serialId, existing);
        } catch (Exception eEnrichir) {
            Log.w(TAG, "enrichirDepuisDataverseSiManquant ERR (non-bloquant): " + eEnrichir.getMessage());
        }

        // Étape 2 — comparer au versionnumber qu'on avait en cache depuis
        // la dernière synchro. Null en cache = fiche jamais synchronisée
        // par cette app avant (ex. créée directement par Jacques dans
        // Dataverse, jamais vue ici) — on accepte de PATCH une première
        // fois dans ce cas, il n'y a rien à "écraser" puisqu'on n'a
        // jamais eu cette fiche en main.
        if (row.dataverseVersion != null && !row.dataverseVersion.equals(versionActuelle)) {
            Log.w(TAG, "pushRegistreRow: serial=" + row.serialId + " modifié entre-temps dans Dataverse"
                + " (cache=" + row.dataverseVersion + " actuel=" + versionActuelle
                + ") — PATCH sauté, reste PENDING pour la prochaine synchro");
            LogBus.api(row.nud, "[DATAVERSE-PUSH-REGISTRE] SAUTÉ (modifié entre-temps) serial=" + row.serialId);
            return;
        }

        // Étape 3 — même version (ou jamais vue avant) : PATCH sûr.
        String urlStr = orgUrl + "/api/data/v9.2/" + TABLE_REGISTRE + "(" + dataverseId + ")";
        JSONObject body = buildRegistreJson(row);
        JSONObject patched = doJsonRequest("PATCH", urlStr, accessToken, body);
        String newVersion = patched != null ? patched.optString("versionnumber", null) : null;
        store.markSynced(row.serialId, dataverseId, newVersion != null ? newVersion : versionActuelle);
        Log.i(TAG, "pushRegistreRow: mis à jour serial=" + row.serialId + " id=" + dataverseId);
        LogBus.api(row.nud, "[DATAVERSE-PUSH-REGISTRE] OK (mis à jour) serial=" + row.serialId);
    }

    /** Cherche une fiche existante par filgo_numerodeserie. Retourne null si absente. */
    private static JSONObject findRegistreByNumeroDeSerie(String serialId, String orgUrl,
                                                            String accessToken) throws Exception {
        String filter = java.net.URLEncoder.encode(
            "filgo_numerodeserie eq '" + serialId.replace("'", "''") + "'", "UTF-8");
        // ✅ ÉLARGI (18 sept 2026, demande Paul — "si Dataverse a plus
        // d'info on met à jour la table locale avec les champs qui
        // manquent") — ramène maintenant aussi les champs administratifs
        // (calibration, scellé, coefficient, identifiant LC3, nom) que
        // l'app n'écrit jamais elle-même mais qui sont utiles à afficher
        // localement. Utilisé par enrichirDepuisDataverseSiManquant()
        // ci-dessous — jamais pour écraser un champ déjà connu de l'app.
        String urlStr = orgUrl + "/api/data/v9.2/" + TABLE_REGISTRE
            + "?$select=filgo_registrecompteurid,versionnumber,filgo_registrecompteur1,"
            + "filgo_coefficientbrutnet,filgo_datedecalibration,filgo_datedescellement,"
            + "filgo_numerodescelle,filgo_identifiantunitelc3"
            + "&$top=1&$filter=" + filter;
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Authorization",    "Bearer " + accessToken);
            conn.setRequestProperty("Accept",           "application/json");
            conn.setRequestProperty("OData-MaxVersion", "4.0");
            conn.setRequestProperty("OData-Version",    "4.0");

            int code = conn.getResponseCode();
            if (code != 200) return null;

            InputStream is = conn.getInputStream();
            JSONObject resp = new JSONObject(new String(readStream(is), StandardCharsets.UTF_8));
            JSONArray values = resp.optJSONArray("value");
            if (values != null && values.length() > 0) return values.getJSONObject(0);
            return null;
        } finally {
            conn.disconnect();
        }
    }

    /** Construit le JSON Dataverse depuis une RegistreStore.Row — uniquement les champs que l'app connaît. */
    private static JSONObject buildRegistreJson(RegistreStore.Row row) throws Exception {
        JSONObject j = new JSONObject();
        j.put("filgo_numerodeserie", row.serialId);
        j.put("filgo_nud", row.nud);
        if (row.btAddr != null)           j.put("filgo_adressebluetooth", row.btAddr);
        if (row.btNom != null)            j.put("filgo_nombluetooth", row.btNom);
        if (row.ipAddr != null)           j.put("filgo_adresseip", row.ipAddr);
        if (row.ipPort != null)           j.put("filgo_portip", row.ipPort);
        if (row.transportPrefere != null) j.put("filgo_transportprefere", row.transportPrefere);
        // ✅ AJOUTÉ (17 sept 2026, demande Paul — "tu derais voir
        // cra5e_Firmware") — champ d'un autre préfixe éditeur que le
        // reste de la table (cra5e_ au lieu de filgo_), absent du CSV
        // fourni (probablement une vue qui l'exclut) — nom pris tel
        // quel, en minuscules (convention Dataverse pour les logical
        // names, jamais de majuscule même si le libellé affiché en a une).
        if (row.firmware != null && !row.firmware.trim().isEmpty()) {
            j.put("cra5e_firmware", row.firmware);
        }
        return j;
    }

    /** POST ou PATCH générique avec corps JSON, retourne la représentation créée/mise à jour. */
    private static JSONObject doJsonRequest(String method, String urlStr, String accessToken,
                                             JSONObject body) throws Exception {
        byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod(method);
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
                InputStream is = conn.getInputStream();
                return new JSONObject(new String(readStream(is), StandardCharsets.UTF_8));
            } else if (code == 204) {
                return null; // pas de corps — retour d'un PATCH sans Prefer honoré par le serveur
            } else {
                String err = "";
                try {
                    err = new String(readStream(conn.getErrorStream()), StandardCharsets.UTF_8);
                } catch (Exception ignored) {}
                throw new RuntimeException("HTTP " + code + ": " + err.substring(0, Math.min(300, err.length())));
            }
        } finally {
            conn.disconnect();
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

        // ✅ CORRIGÉ (17 sept 2026, demande Paul — "j'ai trois
        // inscriptions dans la bd dataverse", puis "si tu as besoin
        // d'un champ utilise le payload... cette info n'est plus
        // nécessaire lorsqu'elle est sync") — trouvé le VRAI deuxième
        // trou : même avec le verrou anti-concurrence (pushPending),
        // rien n'empêchait un doublon si l'app mourait ENTRE le POST
        // réussi (plus bas) et le markSynced() local — la ligne restait
        // PENDING, et le prochain cycle de sync la repoussait en un
        // nouvel enregistrement Dataverse. Pas besoin d'un nouveau
        // champ ni de coordonner avec Jacques sur une clé alternative :
        // filgo_name porte déjà la valeur déterministe voulue
        // (wo_num + "-" + ticket, ex. "apr-5004473-234", construite
        // dans buildDeliveryJson()) — depuis les données du payload
        // local, pas depuis un champ Dataverse dédié. Une requête de
        // validation dessus AVANT le POST suffit : si une ligne existe
        // déjà pour ce filgo_name, on réutilise son GUID et on saute le
        // POST — sync_status=true/false suffit après coup, ce GUID n'a
        // plus besoin d'être cherché une fois la ligne marquée SYNCED
        // localement.
        String deliveryName = body.optString("filgo_name", "");
        if (!deliveryName.isEmpty()) {
            String existingId = findExistingDataverseId(deliveryName, orgUrl, accessToken);
            if (existingId != null) {
                Log.i(TAG, "pushDeliveryRow: filgo_name=" + deliveryName
                    + " déjà présent dans Dataverse (id=" + existingId
                    + ") — POST sauté, réutilisation directe");
                return existingId;
            }
        }

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
     * ✅ AJOUTÉ (17 sept 2026, demande Paul) — requête de validation
     * avant le POST, purement à partir de filgo_name (déjà déterministe
     * depuis les données locales, wo_num + "-" + ticket) : évite un
     * doublon Dataverse si un cycle de sync précédent a réussi côté
     * serveur sans que le markSynced() local n'ait eu le temps de
     * s'exécuter (crash, app tuée, etc). Retourne le GUID de la ligne
     * existante si trouvée, null sinon — jamais bloquant : toute erreur
     * de requête est traitée comme "rien trouvé", pour ne pas empêcher
     * le POST normal en cas de souci réseau sur cette seule vérification.
     */
    private static String findExistingDataverseId(String deliveryName, String orgUrl,
                                                    String accessToken) {
        try {
            String filter = java.net.URLEncoder.encode(
                "filgo_name eq '" + deliveryName.replace("'", "''") + "'", "UTF-8");
            String urlStr = orgUrl + "/api/data/v9.2/" + TABLE_DELIVERY
                + "?$select=filgo_lcr_delivery_statusid&$top=1&$filter=" + filter;
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
                if (code != 200) return null;

                InputStream is = conn.getInputStream();
                byte[] respBytes = readStream(is);
                JSONObject resp = new JSONObject(new String(respBytes, StandardCharsets.UTF_8));
                JSONArray values = resp.optJSONArray("value");
                if (values != null && values.length() > 0) {
                    return values.getJSONObject(0).optString("filgo_lcr_delivery_statusid", null);
                }
                return null;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "findExistingDataverseId ERR (non-bloquant, POST normal fera foi): " + e.getMessage());
            return null;
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

            // ✅ FIX (4 août 2026) — même bug : instance jetée sans jamais être
            // fermée. Impossible à fermer tant qu'elle n'est pas assignée.
            LcrDeliveryStatusDb notesDb = new LcrDeliveryStatusDb(ctx);
            try {
                notesDb.replaceAllNotes(notes);
            } finally {
                try { notesDb.close(); } catch (Exception ignored) {}
            }
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
                + "&$filter=filgo_wo_num eq '" + woNum + "'"
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
    // ✅ (ajouté 3 août 2026, demande Paul : "vérifier automatiquement que les 3 sources
    // concordent") — sibling en LECTURE SEULE de pullDeliveryByTicket(): même requête GET,
    // mais ne fait JAMAIS upsertFromDataverseJson(). Une fonction de "vérification" ne doit
    // jamais avoir d'effet de bord qui modifie les données qu'elle est censée comparer.
    public static JSONObject peekDeliveryByTicket(Context ctx, String accessToken,
                                                    String serialId, Integer lcrnode, String ticketNo) {
        if (serialId == null || serialId.trim().isEmpty() || ticketNo == null || ticketNo.trim().isEmpty()) {
            return null;
        }
        try {
            String orgUrl = LcrConfig.getDataverseUrl(ctx);
            String filter = "filgo_serial_id eq '" + odataEscape(serialId) + "'"
                + (lcrnode != null ? " and filgo_lcrnode eq " + lcrnode : "")
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
                    Log.w(TAG, "peekDeliveryByTicket: HTTP " + code + " pour ticket=" + ticketNo);
                    return null;
                }

                byte[] respBytes = readStream(conn.getInputStream());
                JSONObject resp = new JSONObject(new String(respBytes, StandardCharsets.UTF_8));
                JSONArray values = resp.optJSONArray("value");
                if (values == null || values.length() == 0) return null;
                return values.getJSONObject(0);
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "peekDeliveryByTicket ERR pour ticket=" + ticketNo + ": " + e.getMessage());
            return null;
        }
    }

    // ✅ AJOUTÉ (2 sept 2026, demande Paul — "il l'est dans le payload,
    // concentre-toi regarde plus... on doit déjà avoir une validation si
    // la livraison avait déjà été synchronisé puisqu'on a le job_id") —
    // job_id n'est PAS un champ Dataverse dédié — mais il EST niché dans
    // filgo_payload_json (envoyé pour chaque livraison). OData supporte
    // contains() sur un champ texte — recherche ici SANS avoir besoin
    // d'un nouveau champ Dataverse. Retourne true si une livraison avec
    // ce job_id EXISTE DÉJÀ côté Dataverse (déjà synchronisée) — permet
    // de vérifier avant un renvoi, même quand ticket_no n'est pas encore
    // connu (armement, BD vierge).
    public static boolean deliveryExistsByJobId(Context ctx, String accessToken, String jobId) {
        if (jobId == null || jobId.trim().isEmpty()) return false;
        try {
            String orgUrl = LcrConfig.getDataverseUrl(ctx);
            String filter = "contains(filgo_payload_json, '" + odataEscape(jobId) + "')";
            String urlStr = orgUrl + "/api/data/v9.2/" + TABLE_DELIVERY
                + "?$filter=" + java.net.URLEncoder.encode(filter, "UTF-8").replace("+", "%20")
                + "&$select=filgo_lcr_delivery_statusid"
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
                    Log.w(TAG, "deliveryExistsByJobId: HTTP " + code + " pour jobId=" + jobId);
                    return false;
                }

                byte[] respBytes = readStream(conn.getInputStream());
                JSONObject resp = new JSONObject(new String(respBytes, StandardCharsets.UTF_8));
                JSONArray values = resp.optJSONArray("value");
                boolean exists = values != null && values.length() > 0;
                Log.i(TAG, "deliveryExistsByJobId: jobId=" + jobId + " — " + (exists ? "DÉJÀ présent côté Dataverse" : "absent"));
                return exists;

            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "deliveryExistsByJobId ERR pour jobId=" + jobId + ": " + e.getMessage());
            return false;
        }
    }

    public static boolean pullDeliveryByTicket(Context ctx, String accessToken,
                                                String serialId, Integer lcrnode, String ticketNo) {
        if (serialId == null || serialId.trim().isEmpty() || ticketNo == null || ticketNo.trim().isEmpty()) {
            return false;
        }
        try {
            String orgUrl = LcrConfig.getDataverseUrl(ctx);
            String filter = "filgo_serial_id eq '" + odataEscape(serialId) + "'"
                + (lcrnode != null ? " and filgo_lcrnode eq " + lcrnode : "")
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
                upsertFromDataverseJson(ctx, d, serialId, lcrnode, ticketNo);
                return true;

            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "pullDeliveryByTicket ERR pour ticket=" + ticketNo + ": " + e.getMessage());
            return false;
        }
    }

    // =========================================================
    // Pull de TOUTES les livraisons d'un même #wo (demande Paul, 31 juillet 2026)
    //
    // Un ticket_no résout UN #wo (via pullDeliveryByTicket), mais plusieurs transactions
    // peuvent partager ce même #wo avec des ticket_no différents (chaque impression du
    // registre génère un nouveau ticket) — confirmé par la contrainte locale
    // UNIQUE(wo_num, ticket_no) qui permet justement plusieurs lignes par wo_num.
    // Cette méthode rapatrie TOUTES ces transactions pour un #wo + #série donnés,
    // pas seulement la plus récente.
    //
    // @return nombre de lignes upsertées localement (0 si aucune ou en cas d'erreur)
    // =========================================================
    public static int pullAllDeliveriesForWorkOrder(Context ctx, String accessToken,
                                                      String woNum, String serialId) {
        if (woNum == null || woNum.trim().isEmpty()) return 0;
        try {
            String orgUrl = LcrConfig.getDataverseUrl(ctx);
            String filter = "filgo_wo_num eq '" + odataEscape(woNum) + "'"
                + (serialId != null && !serialId.trim().isEmpty()
                    ? " and filgo_serial_id eq '" + odataEscape(serialId) + "'" : "");
            // ✅ CORRIGÉ (25 sept 2026, demande Paul — "le total d'un WO
            // devrait être cumulatif" — trouvé, confirmé par la BD locale
            // réelle après reconstruction : les livraisons étaient
            // insérées dans l'ordre 289,285,282,284,283,286,287,288 —
            // complètement désordonné — parce que filgo_transaction_no
            // vaut 1 pour TOUTES ces lignes (chacune une visite séparée,
            // pas des tentatives multiples sur une même visite), donc ce
            // tri ne produit aucun ordre chronologique réel — Dataverse
            // retourne les égalités dans un ordre arbitraire. Résultat :
            // computeCumulativeFields() (qui s'appuie sur "la dernière
            // ligne insérée = la plus récente") chaînait dans le mauvais
            // ordre, sautant/doublant des livraisons dans le cumul.
            // Trié maintenant par filgo_end_utc, la vraie chronologie.
            String urlStr = orgUrl + "/api/data/v9.2/" + TABLE_DELIVERY
                + "?$filter=" + java.net.URLEncoder.encode(filter, "UTF-8").replace("+", "%20")
                + "&$orderby=filgo_end_utc asc"
                + "&$top=100"; // une tournée n'a jamais 100 transactions pour un même WO — garde-fou

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
                    Log.w(TAG, "pullAllDeliveriesForWorkOrder: HTTP " + code + " pour wo=" + woNum);
                    return 0;
                }

                byte[] respBytes = readStream(conn.getInputStream());
                JSONObject resp = new JSONObject(new String(respBytes, StandardCharsets.UTF_8));
                JSONArray values = resp.optJSONArray("value");
                if (values == null || values.length() == 0) {
                    Log.i(TAG, "pullAllDeliveriesForWorkOrder: aucune livraison Dataverse pour wo=" + woNum);
                    return 0;
                }

                int upserted = 0;
                for (int i = 0; i < values.length(); i++) {
                    JSONObject d = values.getJSONObject(i);
                    String rowTicketNo = optStringSafe(d, "filgo_ticket_no", null);
                    if (rowTicketNo == null || rowTicketNo.isEmpty()) continue; // ligne inexploitable, on l'ignore
                    String rowSerialId = d.optString("filgo_serial_id", serialId);
                    int rowLcrnode = d.optInt("filgo_lcrnode", 0);
                    upsertFromDataverseJson(ctx, d, rowSerialId, rowLcrnode, rowTicketNo);
                    upserted++;
                }
                Log.i(TAG, "pullAllDeliveriesForWorkOrder: wo=" + woNum + " — " + upserted + " transaction(s) upsertée(s)");
                return upserted;

            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "pullAllDeliveriesForWorkOrder ERR pour wo=" + woNum + ": " + e.getMessage());
            return 0;
        }
    }

    // =========================================================
    // Pull de TOUTES les livraisons d'une JOURNÉE pour un registre (demande Paul, 31 juillet 2026)
    //
    // Au-delà d'un même #wo, Paul veut aussi retrouver les AUTRES arrêts de la même
    // journée pour ce registre (#série) — utile quand la tournée du jour comporte
    // plusieurs #wo différents sur le même registre physique.
    //
    // @param dayUtc date au format "yyyy-MM-dd" (extraite de filgo_start_utc de la
    //               livraison déjà résolue — jamais devinée/aujourd'hui par défaut)
    // @return nombre de lignes upsertées localement (0 si aucune, date invalide, ou erreur)
    // =========================================================
    public static int pullAllDeliveriesForDay(Context ctx, String accessToken,
                                                String serialId, String dayUtc) {
        if (serialId == null || serialId.trim().isEmpty()
                || dayUtc == null || dayUtc.length() < 10) {
            return 0;
        }
        try {
            String day = dayUtc.substring(0, 10); // garde seulement yyyy-MM-dd, au cas où un timestamp complet est passé
            java.time.LocalDate d0 = java.time.LocalDate.parse(day);
            String startIso = day + "T00:00:00Z";
            String endIso   = d0.plusDays(1) + "T00:00:00Z";

            String orgUrl = LcrConfig.getDataverseUrl(ctx);
            // OData v4 : littéraux DateTime sans guillemets
            String filter = "filgo_serial_id eq '" + odataEscape(serialId) + "'"
                + " and filgo_start_utc ge " + startIso
                + " and filgo_start_utc lt " + endIso;
            String urlStr = orgUrl + "/api/data/v9.2/" + TABLE_DELIVERY
                + "?$filter=" + java.net.URLEncoder.encode(filter, "UTF-8").replace("+", "%20")
                + "&$orderby=filgo_start_utc asc"
                + "&$top=200"; // garde-fou — une journée n'a jamais 200 arrêts sur un même registre

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
                    Log.w(TAG, "pullAllDeliveriesForDay: HTTP " + code + " pour serial=" + serialId + " jour=" + day);
                    return 0;
                }

                byte[] respBytes = readStream(conn.getInputStream());
                JSONObject resp = new JSONObject(new String(respBytes, StandardCharsets.UTF_8));
                JSONArray values = resp.optJSONArray("value");
                if (values == null || values.length() == 0) {
                    Log.i(TAG, "pullAllDeliveriesForDay: aucune livraison pour serial=" + serialId + " jour=" + day);
                    return 0;
                }

                int upserted = 0;
                for (int i = 0; i < values.length(); i++) {
                    JSONObject d = values.getJSONObject(i);
                    String rowTicketNo = optStringSafe(d, "filgo_ticket_no", null);
                    if (rowTicketNo == null || rowTicketNo.isEmpty()) continue;
                    int rowLcrnode = d.optInt("filgo_lcrnode", 0);
                    upsertFromDataverseJson(ctx, d, serialId, rowLcrnode, rowTicketNo);
                    upserted++;
                }
                Log.i(TAG, "pullAllDeliveriesForDay: serial=" + serialId + " jour=" + day
                        + " — " + upserted + " livraison(s) upsertée(s)");
                return upserted;

            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "pullAllDeliveriesForDay ERR pour serial=" + serialId + " jour=" + dayUtc + ": " + e.getMessage());
            return 0;
        }
    }


    // vers ContentValues et l'upserte dans LcrDeliveryStatusDb (update si ticket déjà
    // connu localement, insert sinon). Utilisé par pullDeliveryByTicket ET
    // pullAllDeliveriesForWorkOrder pour ne pas dupliquer la logique de mapping.
    // =========================================================
    // ✅ AJOUTÉ (28 août 2026, demande Paul — "pourquoi j'ai null-132 dans
    // delivery-uid") — trouvé : JSONObject.optString(clé, repli) ne
    // protège QUE contre l'absence de la clé — si la clé existe avec une
    // valeur JSON null EXPLICITE (ex: "filgo_wo_num": null, un champ
    // réellement vide côté Dataverse), optString() retourne littéralement
    // la chaîne "null" (texte), PAS le repli fourni. Piège bien connu de
    // org.json. Ce helper vérifie explicitement isNull() avant d'appeler
    // optString(), pour qu'un null JSON produise vraiment une chaîne
    // vide, jamais le texte "null".
    private static String optStringSafe(JSONObject d, String key, String fallback) {
        if (d.isNull(key)) return fallback;
        return d.optString(key, fallback);
    }

    private static void upsertFromDataverseJson(Context ctx, JSONObject d,
                                                 String fallbackSerialId, Integer fallbackLcrnode,
                                                 String fallbackTicketNo) {
        ContentValues cv = new ContentValues();
        cv.put(LcrDeliveryStatusDb.COL_WO_NUM,      optStringSafe(d, "filgo_wo_num", ""));
        cv.put(LcrDeliveryStatusDb.COL_WO_ID_GUID,  optStringSafe(d, "filgo_wo_id_guid", ""));
        cv.put(LcrDeliveryStatusDb.COL_SERIAL_ID,   optStringSafe(d, "filgo_serial_id", fallbackSerialId));
        cv.put(LcrDeliveryStatusDb.COL_LCRNODE,     d.optInt("filgo_lcrnode", fallbackLcrnode != null ? fallbackLcrnode : 0));
        cv.put(LcrDeliveryStatusDb.COL_TICKET_NO,   optStringSafe(d, "filgo_ticket_no", fallbackTicketNo));
        cv.put(LcrDeliveryStatusDb.COL_PRODUIT_NO,  d.optInt("filgo_produit_no", 0));
        cv.put(LcrDeliveryStatusDb.COL_PRESET_L,    d.optDouble("filgo_preset_l", 0.0));
        // ✅ AJOUTÉ (25 sept 2026, demande Paul — "ça devient un vrai
        // problème... on a besoin d'avoir la quantité totale net et
        // gross") — confirmé : net_l/gross_l n'étaient jamais lus
        // depuis Dataverse dans cette reconstruction, malgré que
        // filgo_net_l/filgo_gross_l existent bel et bien côté Dataverse
        // (voir putDbl(j, "filgo_net_l", ...) à la poussée, ligne
        // ~616-617 plus bas dans ce même fichier) — laissait ces
        // colonnes à NULL pour tout ticket reconstruit après une BD
        // locale vierge, faussant tout calcul de cumul (CUMUL-WO) qui
        // dépend de ces valeurs pour un WO avec plusieurs livraisons.
        cv.put(LcrDeliveryStatusDb.COL_NET_L,       d.optDouble("filgo_net_l", 0.0));
        cv.put(LcrDeliveryStatusDb.COL_GROSS_L,     d.optDouble("filgo_gross_l", 0.0));
        cv.put(LcrDeliveryStatusDb.COL_TOURNEE_ID,  optStringSafe(d, "filgo_tournee_id", ""));
        cv.put(LcrDeliveryStatusDb.COL_TRANSACTION_NO, d.optInt("filgo_transaction_no", 1));
        cv.put(LcrDeliveryStatusDb.COL_SOURCE,      "DATAVERSE_PULL");
        cv.put(LcrDeliveryStatusDb.COL_SYNC_STATUS, LcrDeliveryStatusDb.SYNC_SYNCED);
        cv.put(LcrDeliveryStatusDb.COL_DATAVERSE_ID,
                optStringSafe(d, "filgo_lcr_delivery_statusid", ""));

        String ticketNo = optStringSafe(d, "filgo_ticket_no", fallbackTicketNo);

        LcrDeliveryStatusDb localDb = new LcrDeliveryStatusDb(ctx);
        try {
            LcrDeliveryStatusDb.DeliveryRow existing = localDb.getByTicketNo(ticketNo);
            if (existing != null) {
                localDb.updateDelivery(existing.id, cv);
            } else {
                localDb.insertDelivery(cv);
            }
        } finally {
            try { localDb.close(); } catch (Exception ignored) {}
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