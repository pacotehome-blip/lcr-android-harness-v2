package com.pa.lcr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * LcrHttpService — Foreground Service Android
 *
 * Maintient l'APK Filgo vivant en background.
 * Un foreground service avec notification visible ne peut pas être tué
 * par Android pour libérer de la mémoire — contrairement à un service
 * background ordinaire.
 *
 * Rôle :
 *   - Démarrer au boot de la tablette (via LcrBootReceiver)
 *   - Afficher une notification persistante "APK Filgo actif"
 *   - Relancer automatiquement si Android tue le service (START_STICKY)
 *   - Exposer un Intent pour que FieldServiceActivity sache que le
 *     serveur HTTP est prêt avant d'injecter LcrBridge
 *
 * Chemin : app/src/main/java/com/pa/lcr/LcrHttpService.java
 *
 * AndroidManifest.xml — ajouter :
 *   <service
 *       android:name=".LcrHttpService"
 *       android:enabled="true"
 *       android:exported="false"
 *       android:foregroundServiceType="dataSync" />
 */
public class LcrHttpService extends Service {

    private static final String TAG         = "LcrHttpService";
    public  static final String CHANNEL_ID  = "lcr_http_channel";
    public  static final int    NOTIF_ID    = 1001;

    // Intent actions
    public static final String ACTION_START = "com.pa.lcr.START_HTTP";
    public static final String ACTION_STOP  = "com.pa.lcr.STOP_HTTP";
    public static final String ACTION_STATUS = "com.pa.lcr.HTTP_STATUS";

    // Broadcast envoyé quand le serveur est prêt
    public static final String BROADCAST_READY = "com.pa.lcr.HTTP_READY";

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service créé");
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Log.i(TAG, "ACTION_STOP reçu — arrêt du service");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        Log.i(TAG, "Service démarré — foreground");

        // Démarrer en foreground immédiatement avec notification persistante
        startForeground(NOTIF_ID, buildNotification("APK Filgo actif — :8765"));

        // L'APK Filgo gère lui-même son serveur HTTP.
        // Ce service garantit que le processus Android reste vivant.
        // Broadcaster "prêt" après un court délai pour laisser l'APK démarrer.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            broadcastReady();
            updateNotification("APK Filgo — serveur HTTP prêt");
        }, 1500);

        // START_STICKY : Android relance ce service si tué (mémoire basse etc.)
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // Service non lié
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.w(TAG, "Service détruit — Android va le relancer (START_STICKY)");
    }

    // ── Notification ───────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "APK Filgo — Serveur HTTP",
                NotificationManager.IMPORTANCE_LOW  // Silencieux, pas de son
            );
            channel.setDescription("Maintient le serveur HTTP LCR actif en arrière-plan");
            channel.setShowBadge(false);
            NotificationManager mgr = getSystemService(NotificationManager.class);
            if (mgr != null) mgr.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        // Intent pour ouvrir l'APK si on tape sur la notification
        Intent openIntent = new Intent(this, FieldServiceActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Intent pour arrêter le service depuis la notification
        Intent stopIntent = new Intent(this, LcrHttpService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent pendingStop = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
            builder.setPriority(Notification.PRIORITY_LOW);
        }

        return builder
            .setContentTitle("Filgo LCR")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingOpen)
            .setOngoing(true)           // Non-dismissable par l'utilisateur
            .setShowWhen(false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Arrêter", pendingStop)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager mgr = getSystemService(NotificationManager.class);
        if (mgr != null) mgr.notify(NOTIF_ID, buildNotification(text));
    }

    // ── Broadcast ──────────────────────────────────────────────────────────

    private void broadcastReady() {
        Intent intent = new Intent(BROADCAST_READY);
        intent.setPackage(getPackageName()); // Sécurité — broadcast interne seulement
        sendBroadcast(intent);
        Log.i(TAG, "Broadcast READY envoyé");
    }
}