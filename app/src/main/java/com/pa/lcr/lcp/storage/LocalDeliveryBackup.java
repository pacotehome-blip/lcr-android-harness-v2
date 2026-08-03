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
            String safeTicket = (ticketNo != null ? ticketNo : String.valueOf(System.currentTimeMillis()))
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
        }
    }

    private static void backupViaMediaStore(Context ctx, String fileName, byte[] bytes) {
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

            Uri outUri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (outUri == null) {
                Log.w(TAG, "backupViaMediaStore: insert MediaStore a échoué pour " + fileName);
                return;
            }
            try (OutputStream out = ctx.getContentResolver().openOutputStream(outUri)) {
                if (out == null) {
                    Log.w(TAG, "backupViaMediaStore: output stream null pour " + fileName);
                    return;
                }
                out.write(bytes);
                out.flush();
            }
            Log.i(TAG, "backupViaMediaStore: OK — " + fileName);
        } catch (Exception e) {
            Log.w(TAG, "backupViaMediaStore ERR: " + e.getMessage());
        }
    }

    private static void backupViaLegacyFile(Context ctx, String fileName, byte[] bytes) {
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
            Log.w(TAG, "backupViaLegacyFile ERR: " + e.getMessage());
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
            Log.w(TAG, "listBackupFilesMediaStore ERR: " + e.getMessage());
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
            Log.w(TAG, "listBackupFilesLegacy ERR: " + e.getMessage());
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
}