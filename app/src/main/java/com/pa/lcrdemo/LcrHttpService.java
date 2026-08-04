package com.pa.lcrdemo;

// ═══════════════════════════════════════════════════════════════════════════
// COMPATIBILITÉ ANDROID : API 28 (Android 9) → API 35 (Android 15)
// ───────────────────────────────────────────────────────────────────────────
// Toute modification de ce fichier doit être testée sur :
//   · Android 9  (API 28) — Samsung SM-T397U         · ADB 192.168.134.105:5555
//   · Android 15 (API 35) — Samsung R52X508K2DR     · ADB 192.168.134.126:5555
//
// Règles obligatoires :
//   1. Détecter la version à l'exécution via Build.VERSION.SDK_INT
//   2. Appliquer le comportement EXPLICITEMENT par version — pas de spéculation
//   3. Ne jamais utiliser d'API introduite après API 28 sans guard de version
//   4. registerReceiver()  : RECEIVER_NOT_EXPORTED ou RECEIVER_EXPORTED sur API 34+
//   5. PendingIntent       : FLAG_IMMUTABLE sur API 31+ · FLAG_MUTABLE + guard sur API 34+
//   6. startForeground()   : type obligatoire sur API 34+ — doit matcher le manifest
//
// Constantes utiles :
//   Build.VERSION_CODES.P                 = 28  (Android 9)
//   Build.VERSION_CODES.Q                 = 29  (Android 10)
//   Build.VERSION_CODES.S                 = 31  (Android 12)
//   Build.VERSION_CODES.TIRAMISU          = 33  (Android 13)
//   Build.VERSION_CODES.UPSIDE_DOWN_CAKE  = 34  (Android 14)
//   Build.VERSION_CODES.VANILLA_ICE_CREAM = 35  (Android 15)
// ═══════════════════════════════════════════════════════════════════════════

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

public class LcrHttpService extends Service {

    private static final String TAG        = "LcrHttpService";
    public  static final String CHANNEL_ID = "lcr_http_channel";
    public  static final int    NOTIF_ID   = 1001;

    public static final String ACTION_START  = "com.pa.lcr.START_HTTP";
    public static final String ACTION_STOP   = "com.pa.lcr.STOP_HTTP";
    public static final String ACTION_STATUS = "com.pa.lcr.HTTP_STATUS";
    public static final String BROADCAST_READY = "com.pa.lcr.HTTP_READY";

    // Serveur HTTPS 8765
    private com.pa.lcr.lcp.ApiServer apiServer;
    private static final int API_PORT = 8765;

    // Serveur HTTP 8766 — sans SSL pour Field Service WebView
    private java.net.ServerSocket httpServerSocket;
    private java.util.concurrent.ExecutorService httpExecutor;
    private volatile boolean httpRunning = false;
    private static final int HTTP_PORT = 8766;

    // ✅ État réel exposé statiquement — permet à MainActivity (onglet API)
    // d'afficher le statut du VRAI service permanent, au lieu d'une instance
    // locale séparée. isApiRunning() était un stub retournant toujours false
    // avant ce fix (3 août 2026).
    private static volatile boolean sHttpsRunning = false;
    private static volatile boolean sHttpRunning = false;

    public static boolean isHttpRunning() { return sHttpRunning; }
    public static int getApiPort() { return API_PORT; }
    public static int getHttpPort() { return HTTP_PORT; }

    // Dernier résultat livraison — écrit par DeepLinkHandler
    public static volatile String lastResultJson = null;

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

        Log.i(TAG, "Service démarré — foreground — Android API " + Build.VERSION.SDK_INT);

        // ✅ Démarrage foreground selon la version Android détectée à l'exécution
        //
        // Android 9  (API 28) : startForeground(id, notif) — pas de type requis
        // Android 10 (API 29) : idem
        // Android 11 (API 30) : idem
        // Android 12 (API 31) : idem — foregroundServiceType optionnel dans manifest
        // Android 13 (API 33) : idem — foregroundServiceType recommandé
        // Android 14 (API 34) : startForeground(id, notif, type) OBLIGATOIRE
        //                        type doit correspondre au manifest foregroundServiceType
        // Android 15 (API 35) : idem Android 14 + validation stricte specialUse
        //
        // Manifest déclare : foregroundServiceType="dataSync|connectedDevice|specialUse"
        // Les types non supportés sur les API inférieures sont ignorés par Android.

        Notification notif = buildNotification("APK Filgo — démarrage...");

        // ✅ Détection de version à l'exécution — compatible Android 9 à 15
        //
        // Android 9  (API 28) : startForeground(id, notif) — pas de type
        // Android 10 (API 29) : idem
        // Android 11 (API 30) : idem
        // Android 12 (API 31) : idem
        // Android 13 (API 33) : idem
        // Android 14 (API 34) : startForeground(id, notif, type) OBLIGATOIRE
        //                        type DOIT correspondre au manifest (dataSync)
        // Android 15 (API 35) : idem Android 14
        //
        // IMPORTANT : le type dans startForeground() doit correspondre exactement
        // à foregroundServiceType déclaré dans AndroidManifest.xml (dataSync).
        // Déclarer un type non présent dans le manifest cause une SecurityException.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ (API 34+) — type obligatoire, doit matcher le manifest
            startForeground(NOTIF_ID, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            Log.i(TAG, "startForeground TYPE_DATA_SYNC — Android 14+ (API "
                + Build.VERSION.SDK_INT + ")");

        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-13 (API 29-33) — sans type obligatoire
            startForeground(NOTIF_ID, notif);
            Log.i(TAG, "startForeground sans type — Android 10-13 (API "
                + Build.VERSION.SDK_INT + ")");

        } else {
            // Android 9 (API 28) — signature classique
            startForeground(NOTIF_ID, notif);
            Log.i(TAG, "startForeground sans type — Android 9 (API "
                + Build.VERSION.SDK_INT + ")");
        }

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            startApiServer();
            broadcastReady();
        }, 1000);

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopApiServer();
        Log.w(TAG, "Service détruit — Android va le relancer (START_STICKY)");
    }

    // =========================================================
    // HTTPS 8765
    // =========================================================

    private void startApiServer() {
        if (apiServer != null && apiServer.isRunning()) {
            Log.i(TAG, "ApiServer HTTPS déjà running");
        } else {
            try {
                com.pa.lcr.lcp.ApiFacade facade =
                    new com.pa.lcr.lcp.MultiRegisterApiFacadeImpl(this);
                apiServer = new com.pa.lcr.lcp.ApiServer(
                    facade, line -> Log.d(TAG, line), API_PORT, this);
                apiServer.start();
                sHttpsRunning = true;
                Log.i(TAG, "ApiServer HTTPS démarré port " + API_PORT);
            } catch (Exception e) {
                sHttpsRunning = false;
                Log.e(TAG, "ApiServer HTTPS FAIL: " + e.getMessage());
            }
        }

        startHttpServer();
        updateNotification("APK Filgo — HTTPS:" + API_PORT + " HTTP:" + HTTP_PORT);
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
            sHttpsRunning = false;
        }
        stopHttpServer();
    }

    // ✅ FIX 3 août 2026 : ce stub retournait toujours false, donc l'onglet
    // API ne pouvait jamais refléter le vrai état du service permanent.
    public static boolean isApiRunning() { return sHttpsRunning; }

    // =========================================================
    // HTTP 8766 — sans SSL
    // =========================================================

    private void startHttpServer() {
        if (httpRunning) return;
        httpRunning = true;
        httpExecutor = java.util.concurrent.Executors.newFixedThreadPool(4);
        httpExecutor.execute(() -> {
            try {
                httpServerSocket = new java.net.ServerSocket(
                    HTTP_PORT, 50, java.net.InetAddress.getByName("127.0.0.1"));
                sHttpRunning = true;
                Log.i(TAG, "Serveur HTTP démarré port " + HTTP_PORT);
                while (httpRunning) {
                    try {
                        java.net.Socket client = httpServerSocket.accept();
                        httpExecutor.execute(() -> handleHttpClient(client));
                    } catch (Exception e) {
                        if (httpRunning) Log.e(TAG, "HTTP accept ERR: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "HTTP server FAIL: " + e.getMessage());
            } finally {
                sHttpRunning = false;
            }
        });
    }

    private void stopHttpServer() {
        httpRunning = false;
        sHttpRunning = false;
        try { if (httpServerSocket != null) httpServerSocket.close(); } catch (Exception ignored) {}
        try { if (httpExecutor != null) httpExecutor.shutdownNow(); } catch (Exception ignored) {}
    }

    private void handleHttpClient(java.net.Socket socket) {
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(socket.getInputStream()));
            java.io.OutputStream out = socket.getOutputStream();

            String line = br.readLine();
            if (line == null) { socket.close(); return; }
            Log.d(TAG, "HTTP REQ: " + line);

            boolean isOptions    = line.startsWith("OPTIONS");
            boolean isLastResult = line.startsWith("GET /v1/delivery/last-result");
            boolean isPing       = line.startsWith("GET /v1/ping");

            String body = "";
            if (isLastResult) {
                String last = lastResultJson;
                if (last == null) {
                    body = buildJson(0, "No result yet", null);
                } else {
                    try {
                        org.json.JSONObject j = new org.json.JSONObject(last);
                        long age = System.currentTimeMillis() - j.optLong("ts", 0);
                        if (age > 10L * 60L * 1000L) {
                            body = buildJson(0, "Result expired", null);
                        } else {
                            body = buildJson(1, "OK", last);
                        }
                    } catch (Exception ex) {
                        body = buildJson(0, "Parse error", null);
                    }
                }
            } else if (isPing) {
                body = buildJson(1, "PING OK port " + HTTP_PORT, null);
            } else if (!isOptions) {
                writeRaw(out, "HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n");
                socket.close();
                return;
            }

            byte[] bodyBytes = body.getBytes("UTF-8");
            String header = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: Content-Type, Accept\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Connection: close\r\n\r\n";

            writeRaw(out, header);
            out.write(bodyBytes);
            out.flush();
            socket.close();

        } catch (Exception e) {
            Log.e(TAG, "HTTP handle ERR: " + e.getMessage());
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private static String buildJson(int code, String msg, String data) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"code\":").append(code);
        sb.append(",\"msg\":\"").append(msg.replace("\"", "\\\"")).append("\"");
        if (data != null) {
            sb.append(",\"data\":").append(data);
        }
        sb.append("}");
        return sb.toString();
    }

    private static void writeRaw(java.io.OutputStream out, String s) throws Exception {
        out.write(s.getBytes("UTF-8"));
    }

    // =========================================================
    // Notification
    // =========================================================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "APK Filgo — Serveur HTTP",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Maintient le serveur HTTP LCR actif en arrière-plan");
            channel.setShowBadge(false);
            NotificationManager mgr = getSystemService(NotificationManager.class);
            if (mgr != null) mgr.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, LcrHttpService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent pendingStop = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

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
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Arrêter", pendingStop)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager mgr = getSystemService(NotificationManager.class);
        if (mgr != null) mgr.notify(NOTIF_ID, buildNotification(text));
    }

    private void broadcastReady() {
        Intent intent = new Intent(BROADCAST_READY);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
        Log.i(TAG, "Broadcast READY envoyé");
    }
}