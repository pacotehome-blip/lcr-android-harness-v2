package com.pa.lcr.lcp.storage;

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Backup local durable, un fichier JSON par livraison, écrit dans le dossier public
 * Téléchargements — SURVIT à une désinstallation de l'app (contrairement à la BD SQLite
 * privée). Demande Paul, 31 juillet 2026, suite à la perte du ticket 10898 (livraison
 * jamais synchronisée avant désinstallation, donc perdue avec la BD locale).
 *
 * Best-effort total : un backup manqué ne doit JAMAIS faire échouer la livraison elle-même
 * ni le push Dataverse. Toujours appelé depuis un thread d'arrière-plan.
 *
 * Utilise le même pattern dual Android Q+/legacy déjà en place dans MainActivity pour
 * l'export de BD (MediaStore pour Q+, écriture directe pour Android 9-10).
 */
public class LocalDeliveryBackup {

    private static final String TAG = "LocalDeliveryBackup";

    /**
     * Écrit une copie JSON de la livraison dans Téléchargements. Nom de fichier unique par
     * wo+ticket pour éviter tout écrasement accidentel.
     *
     * @param ctx        contexte applicatif (getApplicationContext() suffit)
     * @param woNum      #wo de la livraison
     * @param ticketNo   ticket_no de la livraison
     * @param payload    JSON déjà construit (mêmes champs que ceux poussés vers Dataverse)
     */
    public static void backupDeliveryAsync(Context ctx, String woNum, String ticketNo, JSONObject payload) {
        new Thread(() -> backupDelivery(ctx, woNum, ticketNo, payload), "LocalDeliveryBackup").start();
    }

    private static void backupDelivery(Context ctx, String woNum, String ticketNo, JSONObject payload) {
        try {
            String safeWo = (woNum != null ? woNum : "wo").replaceAll("[^A-Za-z0-9_-]", "_");
            // ✅ CORRIGÉ (28 août 2026, demande Paul — "le nom du fichier
            // n'est pas concluant") — trouvé : ne traitait que le cas
            // null comme "pas de ticket" — une chaîne VIDE ("") passait
            // ce test et gardait le nom littéral, causant une collision
            // (même nom de fichier pour deux événements différents).
            // Traite maintenant vide ET null de la même façon.
            String safeTicket = (ticketNo != null && !ticketNo.trim().isEmpty() ? ticketNo : String.valueOf(System.currentTimeMillis()))
                    .replaceAll("[^A-Za-z0-9_-]", "_");
            String fileName = "filgo_livraison_" + safeWo + "_" + safeTicket + ".json";
            byte[] bytes = payload.toString(2).getBytes(StandardCharsets.UTF_8);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                backupViaMediaStore(ctx, fileName, bytes);
            } else {
                backupViaLegacyFile(ctx, fileName, bytes);
            }
        } catch (Exception e) {
            // Best-effort seulement — jamais bloquant pour la livraison elle-même.
            Log.w(TAG, "backupDelivery ERR (ticket=" + ticketNo + "): " + e.getMessage());
            try { com.pa.lcr.lcp.log.LogBus.err(0, "LocalDeliveryBackup.backupDelivery", e); } catch (Exception ignored) {}
        }
    }

    // ✅ FIX (7 août 2026, demande Paul) — visibilité élargie (package-private,
    // au lieu de private) pour être réutilisée par DeliveryLogStore.backupAndClearAllAsync().
    static void backupViaMediaStore(Context ctx, String fileName, byte[] bytes) {
        try {
            // ✅ CORRIGÉ (28 août 2026, demande Paul — "j'ai des duplicatats
            // pourquoi???") — trouvé : MediaStore.insert() ne remplace
            // JAMAIS un fichier existant portant le même DISPLAY_NAME —
            // il crée systématiquement une NOUVELLE entrée avec un
            // suffixe "(1)", "(2)", etc. pour éviter toute collision de
            // nom. Chaque tentative de "mise à jour" d'un backup déjà
            // écrit (filet de sécurité qui se met à jour, ou deux
            // chemins de fin de livraison écrivant sur le même nom)
            // créait donc un fichier séparé au lieu de vraiment le
            // remplacer. Cherche maintenant d'abord si une entrée avec ce
            // nom exact existe déjà — si oui, écrit PAR-DESSUS (troncature)
            // via son URI existant, jamais une nouvelle insertion.
            Uri outUri = null;
            String[] projection = { MediaStore.MediaColumns._ID };
            String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=?";
            String[] selectionArgs = { fileName };
            // ✅ AJOUTÉ (28 août 2026, demande Paul — "après le
            // correction... j'ai fait retour au bon de travail" — le
            // fichier "(2)" a quand même été créé malgré le correctif) —
            // logs précis pour voir si la recherche trouve vraiment le
            // fichier existant, ou échoue silencieusement pour une
            // raison encore à identifier (permission, format de requête,
            // etc.).
            Log.i(TAG, "backupViaMediaStore: recherche fichier existant — DISPLAY_NAME=\"" + fileName + "\"");
            try (android.database.Cursor c = ctx.getContentResolver().query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection,
                    selection, selectionArgs, null)) {
                Log.i(TAG, "backupViaMediaStore: requête exécutée — curseur=" + (c != null)
                    + " count=" + (c != null ? c.getCount() : -1));
                if (c != null && c.moveToFirst()) {
                    long id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                    outUri = android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
                    Log.i(TAG, "backupViaMediaStore: fichier existant TROUVÉ — id=" + id + " uri=" + outUri);
                } else {
                    Log.i(TAG, "backupViaMediaStore: aucun fichier existant trouvé pour ce nom — nouvelle insertion à suivre");
                }
            } catch (Exception e) {
                Log.w(TAG, "backupViaMediaStore: recherche fichier existant EXCEPTION: "
                    + e.getClass().getSimpleName() + " — " + e.getMessage());
            }

            if (outUri == null) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                outUri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            }
            if (outUri == null) {
                Log.w(TAG, "backupViaMediaStore: insert MediaStore a échoué pour " + fileName);
                return;
            }
            try (OutputStream out = ctx.getContentResolver().openOutputStream(outUri, "wt")) {
                if (out == null) {
                    Log.w(TAG, "backupViaMediaStore: output stream null pour " + fileName);
                    return;
                }
                out.write(bytes);
                out.flush();
            }
            Log.i(TAG, "backupViaMediaStore: OK — " + fileName);
        } catch (Exception e) {
            Log.w(TAG, "backupViaMediaStore ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LocalDeliveryBackup.backupViaMediaStore", e); } catch (Exception ignored) {}
        }
    }

    // ✅ FIX (7 août 2026, demande Paul) — visibilité élargie, voir commentaire ci-dessus.
    static void backupViaLegacyFile(Context ctx, String fileName, byte[] bytes) {
        try {
            // Best-effort : si la permission legacy (Android 9-10) n'a pas été accordée,
            // on échoue silencieusement plutôt que de la redemander ici (pas d'Activity
            // disponible depuis ce contexte applicatif pour un prompt de permission).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                int perm = ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
                if (perm != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "backupViaLegacyFile: permission WRITE_EXTERNAL_STORAGE non accordée — backup sauté pour "
                            + fileName);
                    return;
                }
            }
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File dest = new File(downloads, fileName);
            try (OutputStream out = new FileOutputStream(dest)) {
                out.write(bytes);
                out.flush();
            }
            Log.i(TAG, "backupViaLegacyFile: OK — " + fileName);
        } catch (Exception e) {
            Log.w(TAG, "backupViaLegacyFile ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LocalDeliveryBackup.backupViaLegacyFile", e); } catch (Exception ignored) {}
        }
    }

    // =========================================================
    // Restauration (demandé 31 juillet 2026) — scanne Téléchargements pour les fichiers
    // filgo_livraison_*.json et réinsère ceux qui manquent dans LcrDeliveryStatusDb, en
    // PENDING pour qu'ils repartent automatiquement vers Dataverse via le mécanisme de
    // sync existant (triggerNow() + retry périodique). N'écrase JAMAIS une ligne déjà
    // présente localement (vérifié par ticket_no via getByTicketNo).
    // =========================================================

    public interface RestoreCallback {
        /** Appelé sur le thread d'arrière-plan une fois le scan terminé. */
        void onDone(int restored, int skippedAlreadyPresent, int failed, List<String> messages);
    }

    // =========================================================
    // ✅ AJOUTÉ (7 août 2026, demande Paul — "que fais-tu du fichier JSON
    // dans Téléchargements") — sur une BD VRAIMENT vierge (réinstallation,
    // BD corrompue), même le repli getLastDeliveryForSerial() de
    // RegisterTabFragment échouerait, puisque la BD elle-même est vide. Les
    // fichiers filgo_livraison_*.json, eux, SURVIVENT à ce scénario — c'est
    // exactement leur raison d'être (voir backupDeliveryAsync). Cette
    // méthode cherche, parmi tous les backups JSON présents, le plus RÉCENT
    // (par backup_ts) qui correspond au #série donné, et retourne son
    // wo_num — dernier filet de sécurité avant d'abandonner.
    // =========================================================
    public static String findMostRecentWoForSerialFromJsonBackups(Context ctx, String serialId) {
        try {
            List<String> messages = new ArrayList<>();
            List<byte[]> files = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ? listBackupFilesMediaStore(ctx, messages)
                    : listBackupFilesLegacy(ctx, messages);

            String bestWoNum = null;
            long bestTs = -1;
            for (byte[] raw : files) {
                try {
                    JSONObject j = new JSONObject(new String(raw, StandardCharsets.UTF_8));
                    String fileSerial = j.optString("serial_id", "");
                    if (!serialId.equalsIgnoreCase(fileSerial)) continue;
                    String woNum = j.optString("wo_num", "");
                    if (woNum.isEmpty()) continue;
                    long ts = j.optLong("backup_ts", 0L);
                    if (ts > bestTs) {
                        bestTs = ts;
                        bestWoNum = woNum;
                    }
                } catch (Exception ignored) {}
            }
            return bestWoNum;
        } catch (Exception e) {
            Log.w(TAG, "findMostRecentWoForSerialFromJsonBackups ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LocalDeliveryBackup.findMostRecentWoForSerialFromJsonBackups", e); } catch (Exception ignored) {}
            return null;
        }
    }

    public static void restoreAllAsync(Context ctx, RestoreCallback cb) {
        new Thread(() -> {
            List<String> messages = new ArrayList<>();
            int restored = 0, skipped = 0, failed = 0;

            List<byte[]> files = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ? listBackupFilesMediaStore(ctx, messages)
                    : listBackupFilesLegacy(ctx, messages);

            LcrDeliveryStatusDb lcrDb = new LcrDeliveryStatusDb(ctx.getApplicationContext());
            try {
                for (byte[] raw : files) {
                    try {
                        JSONObject j = new JSONObject(new String(raw, StandardCharsets.UTF_8));
                        String ticketNo = j.optString("ticket_no", "");
                        String woNum = j.optString("wo_num", "");
                        if (ticketNo.isEmpty() || woNum.isEmpty()) {
                            failed++;
                            messages.add("Fichier ignoré — ticket_no ou wo_num vide");
                            continue;
                        }

                        LcrDeliveryStatusDb.DeliveryRow existing = lcrDb.getByTicketNo(ticketNo);
                        if (existing != null) {
                            skipped++;
                            continue; // déjà présent localement — ne jamais écraser
                        }

                        ContentValues cv = new ContentValues();
                        cv.put(LcrDeliveryStatusDb.COL_WO_NUM, woNum);
                        cv.put(LcrDeliveryStatusDb.COL_WO_ID_GUID, j.optString("wo_id_guid", ""));
                        cv.put(LcrDeliveryStatusDb.COL_TICKET_NO, ticketNo);
                        cv.put(LcrDeliveryStatusDb.COL_SALE_NO, j.optString("sale_no", ""));
                        cv.put(LcrDeliveryStatusDb.COL_NET_L, j.optDouble("net_l", 0.0));
                        cv.put(LcrDeliveryStatusDb.COL_GROSS_L, j.optDouble("gross_l", 0.0));
                        cv.put(LcrDeliveryStatusDb.COL_SERIAL_ID, j.optString("serial_id", ""));
                        cv.put(LcrDeliveryStatusDb.COL_LCRNODE, j.optInt("lcrnode", 0));
                        cv.put(LcrDeliveryStatusDb.COL_TYPE, LcrDeliveryStatusDb.TYPE_ORIGINAL);
                        cv.put(LcrDeliveryStatusDb.COL_SOURCE, "RESTORE_BACKUP");
                        cv.put(LcrDeliveryStatusDb.COL_STOP_TYPE, "LIVRAISON");
                        // ✅ PENDING — pas SYNCED : on ne sait pas si Dataverse l'a déjà reçue avant la
                        // perte locale ; le prochain push se chargera de vérifier/écrire, plutôt que
                        // de supposer que c'est déjà fait.
                        cv.put(LcrDeliveryStatusDb.COL_SYNC_STATUS, LcrDeliveryStatusDb.SYNC_PENDING);
                        cv.put(LcrDeliveryStatusDb.COL_PAYLOAD_JSON, j.optString("payload_complet", ""));

                        long newId = lcrDb.insertDelivery(cv);
                        restored++;
                        messages.add("Restauré : ticket=" + ticketNo + " wo=" + woNum + " (id=" + newId + ")");
                        Log.i(TAG, "restoreAllAsync: ticket=" + ticketNo + " restauré en PENDING (id=" + newId + ")");
                    } catch (Exception e) {
                        failed++;
                        messages.add("Erreur sur un fichier : " + e.getMessage());
                        Log.w(TAG, "restoreAllAsync: erreur parsing fichier: " + e.getMessage());
                        try { com.pa.lcr.lcp.log.LogBus.err(0, "LocalDeliveryBackup.restoreAllAsync", e); } catch (Exception ignored) {}
                    }
                }
            } finally {
                try { lcrDb.close(); } catch (Exception ignored) {}
            }

            final int fRestored = restored, fSkipped = skipped, fFailed = failed;
            if (cb != null) cb.onDone(fRestored, fSkipped, fFailed, messages);
        }, "LocalDeliveryRestore").start();
    }

    private static List<byte[]> listBackupFilesMediaStore(Context ctx, List<String> messages) {
        List<byte[]> out = new ArrayList<>();
        try {
            String[] projection = { MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME };
            String selection = MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
            String[] selectionArgs = { "filgo_livraison_%.json" };

            try (Cursor c = ctx.getContentResolver().query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, null)) {
                if (c == null) {
                    messages.add("Requête MediaStore a retourné null");
                    return out;
                }
                int idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
                while (c.moveToNext()) {
                    long id = c.getLong(idCol);
                    Uri fileUri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, String.valueOf(id));
                    try (InputStream in = ctx.getContentResolver().openInputStream(fileUri)) {
                        if (in == null) continue;
                        out.add(readAll(in));
                    } catch (Exception e) {
                        messages.add("Lecture échouée pour un fichier MediaStore : " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            messages.add("listBackupFilesMediaStore ERR: " + e.getMessage());
            Log.w(TAG, "listBackupFilesMediaStore ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LocalDeliveryBackup.listBackupFilesMediaStore", e); } catch (Exception ignored) {}
        }
        return out;
    }

    private static List<byte[]> listBackupFilesLegacy(Context ctx, List<String> messages) {
        List<byte[]> out = new ArrayList<>();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                int perm = ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE);
                if (perm != PackageManager.PERMISSION_GRANTED) {
                    messages.add("Permission READ_EXTERNAL_STORAGE non accordée — restauration impossible");
                    return out;
                }
            }
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File[] matches = downloads.listFiles((dir, name) ->
                    name.startsWith("filgo_livraison_") && name.endsWith(".json"));
            if (matches == null) return out;
            for (File f : matches) {
                try (InputStream in = new java.io.FileInputStream(f)) {
                    out.add(readAll(in));
                } catch (Exception e) {
                    messages.add("Lecture échouée pour " + f.getName() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            messages.add("listBackupFilesLegacy ERR: " + e.getMessage());
            Log.w(TAG, "listBackupFilesLegacy ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LocalDeliveryBackup.listBackupFilesLegacy", e); } catch (Exception ignored) {}
        }
        return out;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) buffer.write(chunk, 0, n);
        return buffer.toByteArray();
    }

    // =========================================================
    // ✅ FIX (6 août 2026, demande Paul — "considérer la charge imposée sur la
    // tablette... entretien systématique pour conserver les 7 derniers
    // jours") — ces fichiers backup n'étaient JAMAIS nettoyés, s'accumulant
    // indéfiniment dans Téléchargements. Purge PRUDENTE : ne supprime un
    // fichier de plus de 7 jours QUE si la livraison correspondante est
    // confirmée SYNCED dans la BD locale (le filet de sécurité original —
    // survivre à une désinstallation avant sync — reste intact pour tout ce
    // qui n'est pas encore confirmé synchronisé, peu importe son âge).
    // =========================================================
    public static void purgeOldSyncedBackupsAsync(Context ctx, int days) {
        new Thread(() -> purgeOldSyncedBackups(ctx, days), "LocalDeliveryBackupPurge").start();
    }

    private static void purgeOldSyncedBackups(Context ctx, int days) {
        long cutoffMs = System.currentTimeMillis() - (days * 24L * 60L * 60L * 1000L);
        List<String> messages = new ArrayList<>();
        int deleted = 0, kept = 0, errors = 0;

        LcrDeliveryStatusDb lcrDb = new LcrDeliveryStatusDb(ctx.getApplicationContext());
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    String[] projection = { MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME };
                    String selection = MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
                    String[] selectionArgs = { "filgo_livraison_%.json" };
                    try (Cursor c = ctx.getContentResolver().query(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, null)) {
                        if (c == null) return;
                        int idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
                        int nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
                        while (c.moveToNext()) {
                            long id = c.getLong(idCol);
                            String name = c.getString(nameCol);
                            Uri fileUri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, String.valueOf(id));
                            try {
                                byte[] raw;
                                try (InputStream in = ctx.getContentResolver().openInputStream(fileUri)) {
                                    if (in == null) { errors++; continue; }
                                    raw = readAll(in);
                                }
                                JSONObject j = new JSONObject(new String(raw, StandardCharsets.UTF_8));
                                long backupTs = j.optLong("backup_ts", 0L);
                                String ticketNo = j.optString("ticket_no", "");
                                if (backupTs == 0L || backupTs >= cutoffMs) { kept++; continue; }
                                LcrDeliveryStatusDb.DeliveryRow row = ticketNo.isEmpty() ? null : lcrDb.getByTicketNo(ticketNo);
                                boolean synced = row != null && LcrDeliveryStatusDb.SYNC_SYNCED.equals(row.syncStatus);
                                if (synced) {
                                    ctx.getContentResolver().delete(fileUri, null, null);
                                    deleted++;
                                } else {
                                    kept++;
                                }
                            } catch (Exception e) {
                                errors++;
                                messages.add(name + ": " + e.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "purgeOldSyncedBackups (MediaStore) ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LocalDeliveryBackup.purgeOldSyncedBackups", e); } catch (Exception ignored) {}
                }
            } else {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        int perm = ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
                        if (perm != PackageManager.PERMISSION_GRANTED) return;
                    }
                    File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    File[] matches = downloads.listFiles((dir, name) ->
                            name.startsWith("filgo_livraison_") && name.endsWith(".json"));
                    if (matches == null) return;
                    for (File f : matches) {
                        try {
                            byte[] raw;
                            try (InputStream in = new java.io.FileInputStream(f)) { raw = readAll(in); }
                            JSONObject j = new JSONObject(new String(raw, StandardCharsets.UTF_8));
                            long backupTs = j.optLong("backup_ts", 0L);
                            String ticketNo = j.optString("ticket_no", "");
                            if (backupTs == 0L || backupTs >= cutoffMs) { kept++; continue; }
                            LcrDeliveryStatusDb.DeliveryRow row = ticketNo.isEmpty() ? null : lcrDb.getByTicketNo(ticketNo);
                            boolean synced = row != null && LcrDeliveryStatusDb.SYNC_SYNCED.equals(row.syncStatus);
                            if (synced) {
                                if (f.delete()) deleted++; else errors++;
                            } else {
                                kept++;
                            }
                        } catch (Exception e) {
                            errors++;
                            messages.add(f.getName() + ": " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "purgeOldSyncedBackups (legacy) ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LocalDeliveryBackup.purgeOldSyncedBackups", e); } catch (Exception ignored) {}
                }
            }
        } finally {
            try { lcrDb.close(); } catch (Exception ignored) {}
        }

        Log.i(TAG, "purgeOldSyncedBackups: supprimés=" + deleted + " conservés=" + kept
                + " erreurs=" + errors + (messages.isEmpty() ? "" : (" détail=" + messages)));
    }

    // =========================================================
    // Recherche ciblée par ticket_no (demandé 3 août 2026) — utilisée quand un
    // ticket_no donné par le registre est introuvable À LA FOIS en local
    // (LcrDeliveryStatusDb) ET sur Dataverse (pullDeliveryByTicket). Contrairement
    // à restoreAllAsync() qui restaure INCONDITIONNELLEMENT tous les backups
    // manquants, cette méthode ne cherche qu'UN ticket précis, et s'il existe
    // plusieurs fichiers backup pour ce même ticket (cas rare — plusieurs écritures
    // avant qu'un push Dataverse ait pu réussir), elle garde celui dont "backup_ts"
    // est le plus récent. Ne touche jamais la BD elle-même — l'appelant décide de
    // l'insertion (voir RegisterTabFragment.lookupWoForTicket()).
    // =========================================================

    /** Résultat d'une recherche backup ciblée par ticket_no. */
    public static class BackupMatch {
        public final JSONObject json;
        public final long backupTs;
        BackupMatch(JSONObject json, long backupTs) {
            this.json = json;
            this.backupTs = backupTs;
        }
    }

    /**
     * Cherche, parmi tous les backups JSON de Téléchargements, ceux dont
     * ticket_no == ticketNo, et retourne celui au backup_ts le plus élevé.
     * Retourne null si aucun backup ne correspond à ce ticket.
     */
    public static BackupMatch findLatestByTicketNo(Context ctx, String ticketNo) {
        if (ticketNo == null || ticketNo.trim().isEmpty()) return null;

        List<String> messages = new ArrayList<>();
        List<byte[]> files = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ? listBackupFilesMediaStore(ctx, messages)
                : listBackupFilesLegacy(ctx, messages);

        BackupMatch best = null;
        for (byte[] raw : files) {
            try {
                JSONObject j = new JSONObject(new String(raw, StandardCharsets.UTF_8));
                if (!ticketNo.equals(j.optString("ticket_no", ""))) continue;

                long ts = j.optLong("backup_ts", 0L);
                if (best == null || ts > best.backupTs) {
                    best = new BackupMatch(j, ts);
                }
            } catch (Exception e) {
                Log.w(TAG, "findLatestByTicketNo: fichier ignoré (parsing) — " + e.getMessage());
                try { com.pa.lcr.lcp.log.LogBus.err(0, "LocalDeliveryBackup.findLatestByTicketNo", e); } catch (Exception ignored) {}
            }
        }

        if (best == null) {
            Log.i(TAG, "findLatestByTicketNo: aucun backup trouvé pour ticket=" + ticketNo);
        } else {
            Log.i(TAG, "findLatestByTicketNo: match ticket=" + ticketNo
                    + " backup_ts=" + best.backupTs);
        }
        return best;
    }

    /**
     * Construit un ContentValues prêt pour LcrDeliveryStatusDb.insertDelivery(),
     * à partir du JSON d'un BackupMatch. Marque toujours SYNC_PENDING et
     * source=RESTORE_BACKUP — même convention que restoreAllAsync(), pour que
     * le service de sync existant (DeliverySyncScheduler/DeliverySyncWorker)
     * le pousse vers Dataverse au prochain triggerNow()/cycle périodique.
     */
    public static ContentValues toContentValues(JSONObject j) {
        ContentValues cv = new ContentValues();
        cv.put(LcrDeliveryStatusDb.COL_WO_NUM, j.optString("wo_num", ""));
        cv.put(LcrDeliveryStatusDb.COL_WO_ID_GUID, j.optString("wo_id_guid", ""));
        cv.put(LcrDeliveryStatusDb.COL_TICKET_NO, j.optString("ticket_no", ""));
        cv.put(LcrDeliveryStatusDb.COL_SALE_NO, j.optString("sale_no", ""));
        cv.put(LcrDeliveryStatusDb.COL_NET_L, j.optDouble("net_l", 0.0));
        cv.put(LcrDeliveryStatusDb.COL_GROSS_L, j.optDouble("gross_l", 0.0));
        cv.put(LcrDeliveryStatusDb.COL_SERIAL_ID, j.optString("serial_id", ""));
        cv.put(LcrDeliveryStatusDb.COL_LCRNODE, j.optInt("lcrnode", 0));
        // ✅ CORRIGÉ (28 août 2026, demande Paul — "mais avant original ou
        // annulé c'est bien ca" — confirmé, une vraie ligne devait exister
        // avec net=0/gross=0 ET le bon type) — trouvé : codé en dur à
        // TYPE_ORIGINAL, peu importe ce que le JSON de backup contenait
        // réellement. Une annulation restaurée après réinstall redevenait
        // une "vraie" livraison réussie. Préserve maintenant le type
        // exact du JSON (ANNULATION ou ORIGINAL) — ORIGINAL reste le repli
        // par défaut pour les anciens backups d'avant ce correctif, qui
        // n'avaient jamais ce champ "type" du tout.
        cv.put(LcrDeliveryStatusDb.COL_TYPE, j.optString("type", LcrDeliveryStatusDb.TYPE_ORIGINAL));
        cv.put(LcrDeliveryStatusDb.COL_SOURCE, "RESTORE_BACKUP");
        cv.put(LcrDeliveryStatusDb.COL_STOP_TYPE, "LIVRAISON");
        cv.put(LcrDeliveryStatusDb.COL_SYNC_STATUS, LcrDeliveryStatusDb.SYNC_PENDING);
        cv.put(LcrDeliveryStatusDb.COL_PAYLOAD_JSON, j.optString("payload_complet", ""));
        return cv;
    }
}