package com.pa.lcrdemo;

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

    // ✅ ApiServer géré directement dans le foreground service
    private com.pa.lcr.lcp.ApiServer apiServer;
    private static final int API_PORT = 8765;

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
            stopApiServer();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        Log.i(TAG, "Service démarré — foreground");

        // Démarrer en foreground immédiatement
        startForeground(NOTIF_ID, buildNotification("APK Filgo — démarrage..."));

        // ✅ Démarrer le serveur API dans le foreground service
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            startApiServer();
            broadcastReady();
        }, 1000);

        // START_STICKY : Android relance ce service si tué
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopApiServer();
        Log.w(TAG, "Service détruit — Android va le relancer (START_STICKY)");
    }

    // =========================================================
    // API Server — géré dans le foreground service
    // =========================================================

    // =========================================================
    // ✅ Serveur HTTP simple port 8766 — sans SSL, pour Field Service WebView
    // =========================================================
    private java.net.ServerSocket httpServerSocket;
    private java.util.concurrent.ExecutorService httpExecutor;
    private volatile boolean httpRunning = false;
    private static final int HTTP_PORT = 8766;

    // ✅ Dernier résultat de livraison — écrit par DeepLinkHandler
    public static volatile String lastResultJson = null;

    private void startApiServer() {
        if (apiServer != null && apiServer.isRunning()) {
            Log.i(TAG, "ApiServer déjà running");
            updateNotification("APK Filgo — HTTPS:8765 HTTP:8766 actifs");
            return;
        }
        try {
            com.pa.lcr.lcp.ApiFacade facade =
                new com.pa.lcr.lcp.MultiRegisterApiFacadeImpl(this);
            apiServer = new com.pa.lcr.lcp.ApiServer(
                facade, line -> Log.d(TAG, line), API_PORT, this);
            apiServer.start();
            Log.i(TAG, "ApiServer HTTPS démarré sur port " + API_PORT);
        } catch (Exception e) {
            Log.e(TAG, "ApiServer HTTPS start FAIL: " + e.getMessage());
        }

        // ✅ Démarrer aussi le serveur HTTP simple sur 8766
        startHttpServer();
        updateNotification("APK Filgo — HTTPS:8765 HTTP:8766 actifs");
    }

    private void startHttpServer() {
        if (httpRunning) return;
        httpRunning = true;
        httpExecutor = java.util.concurrent.Executors.newFixedThreadPool(4);
        httpExecutor.execute(() -> {
            try {
                httpServerSocket = new java.net.ServerSocket(HTTP_PORT,
                    50, java.net.InetAddress.getByName("127.0.0.1"));
                Log.i(TAG, "Serveur HTTP démarré sur port " + HTTP_PORT);
                while (httpRunning) {
                    try {
                        java.net.Socket client = httpServerSocket.accept();
                        httpExecutor.execute(() -> handleHttpClient(client));
                    } catch (Exception e) {
                        if (httpRunning) Log.e(TAG, "HTTP accept ERR: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "HTTP server start FAIL: " + e.getMessage());
            }
        });
    }

    private void handleHttpClient(java.net.Socket socket) {
        try {
            java.io.BufferedReader in = new java.io.BufferedReader(
                new java.io.InputStreamReader(socket.getInputStream()));
            java.io.OutputStream out = socket.getOutputStream();

            String line = in.readLine();
            if (line == null) { socket.close(); return; }
            Log.d(TAG, "HTTP REQ: " + line);

            boolean isOptions = line.startsWith("OPTIONS");
            boolean isGet     = line.startsWith("GET /v1/delivery/last-result")
                             || line.startsWith("GET /v1/ping");

            String body = "";
            if (line.startsWith("GET /v1/delivery/last-result")) {
                String last = lastResultJson;
                if (last == null) {
                    body = "{"code":0,"msg":"No result yet"}";
                } else {
                    // Vérifier fraîcheur
                    try {
                        org.json.JSONObject j = new org.json.JSONObject(last);
                        long ts  = j.optLong("ts", 0);
                        long age = System.currentTimeMillis() - ts;
                        if (age > 10 * 60 * 1000L) {
                            body = "{"code":0,"msg":"Result expired"}";
                        } else {
                            body = "{"code":1,"msg":"OK","data":" + last + "}";
                        }
                    } catch (Exception e) {
                        body = "{"code":0,"msg":"Parse error"}";
                    }
                }
            } else if (line.startsWith("GET /v1/ping")) {
                body = "{"code":1,"msg":"PING OK","port":" + HTTP_PORT + "}";
            } else if (!isOptions) {
                String resp = "HTTP/1.1 404 Not Found
Connection: close

";
                out.write(resp.getBytes("UTF-8"));
                out.flush();
                socket.close();
                return;
            }

            String response = "HTTP/1.1 200 OK
"
                + "Content-Type: application/json; charset=UTF-8
"
                + "Access-Control-Allow-Origin: *
"
                + "Access-Control-Allow-Methods: GET, OPTIONS
"
                + "Access-Control-Allow-Headers: Content-Type, Accept
"
                + "Content-Length: " + body.getBytes("UTF-8").length + "
"
                + "Connection: close

"
                + body;

            out.write(response.getBytes("UTF-8"));
            out.flush();
            socket.close();

        } catch (Exception e) {
            Log.e(TAG, "HTTP handle ERR: " + e.getMessage());
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private void stopApiServer() {
        try {
            if (apiServer != null && apiServer.isRunning()) {
                apiServer.stop();
                Log.i(TAG, "ApiServer HTTPS arrêté");
            }
        } catch (Exception ignored) {
        } finally {
            apiServer = null;
        }

        // Arrêter aussi le serveur HTTP
        httpRunning = false;
        try {
            if (httpServerSocket != null) httpServerSocket.close();
            if (httpExecutor != null) httpExecutor.shutdownNow();
            Log.i(TAG, "Serveur HTTP arrêté");
        } catch (Exception ignored) {}
    }

    public static boolean isApiRunning() {
        return false;
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
        // Intent pour ouvrir MainActivity si on tape sur la notification
        Intent openIntent = new Intent(this, MainActivity.class);
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