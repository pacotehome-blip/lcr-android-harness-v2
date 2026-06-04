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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public class LcrHttpService extends Service {

    private static final String TAG = "LcrHttpService";
    public static final int HTTP_PORT = 8765;
    public static final String ACTION_STOP = "com.pa.lcrdemo.STOP_HTTP";
    public static final String BROADCAST_READY = "com.pa.lcrdemo.HTTP_READY";

    private static final String CHANNEL_ID = "lcr_http_channel";
    private static final int NOTIF_ID = 42;

    // Shared result storage: set by MainActivity after delivery ends
    private static final AtomicReference<String> sLastResult = new AtomicReference<>(null);
    private static volatile long sResultTimestamp = 0;
    private static final long RESULT_TTL_MS = 60_000;

    private ServerSocket mServerSocket;
    private ExecutorService mExecutor;
    private volatile boolean mRunning = false;

    // Called by MainActivity to publish the delivery result JSON
    public static void publishResult(String json) {
        sLastResult.set(json);
        sResultTimestamp = System.currentTimeMillis();
        Log.i(TAG, "Result published: " + json);
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIF_ID, buildNotification("HTTP service starting…"));

        mRunning = true;
        mExecutor = Executors.newCachedThreadPool();
        mExecutor.execute(this::serverLoop);

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mRunning = false;
        try {
            if (mServerSocket != null) mServerSocket.close();
        } catch (IOException ignored) {}
        if (mExecutor != null) mExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── Server loop ──────────────────────────────────────────────────────────

    private void serverLoop() {
        try {
            mServerSocket = new ServerSocket(HTTP_PORT);
            Log.i(TAG, "Listening on port " + HTTP_PORT);
            updateNotification("Listening on port " + HTTP_PORT);
            broadcastReady();

            while (mRunning) {
                try {
                    Socket client = mServerSocket.accept();
                    mExecutor.execute(() -> handleClient(client));
                } catch (IOException e) {
                    if (mRunning) Log.w(TAG, "Accept error", e);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Server error", e);
        }
    }

    // ── Request handler ──────────────────────────────────────────────────────

    private void handleClient(Socket socket) {
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             OutputStream out = socket.getOutputStream()) {

            // Read request line
            String requestLine = in.readLine();
            if (requestLine == null) return;
            Log.d(TAG, "Request: " + requestLine);

            // Drain headers
            while (true) {
                String h = in.readLine();
                if (h == null || h.isEmpty()) break;
            }

            // Parse method + path
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path   = parts.length > 1 ? parts[1] : "/";

            // Strip query string
            int q = path.indexOf('?');
            String cleanPath = q >= 0 ? path.substring(0, q) : path;

            // OPTIONS preflight
            if ("OPTIONS".equalsIgnoreCase(method)) {
                writeOptions(out);
                return;
            }

            String body;

            if ("/v1/delivery/result".equals(cleanPath)) {
                String last = sLastResult.get();
                if (last == null) {
                    body = buildJson("code", "0", "msg", "No result yet");
                } else {
                    long age = System.currentTimeMillis() - sResultTimestamp;
                    if (age > RESULT_TTL_MS) {
                        sLastResult.set(null);
                        body = buildJson("code", "0", "msg", "Result expired");
                    } else {
                        // last is already a JSON object string — embed it as the data value
                        body = "{\"code\":1,\"msg\":\"OK\",\"data\":" + last + "}";
                    }
                }
            } else if ("/v1/ping".equals(cleanPath)) {
                body = "{\"code\":1,\"msg\":\"PING OK\",\"port\":" + HTTP_PORT + "}";
            } else {
                write404(out);
                return;
            }

            writeOk(out, body);

        } catch (Exception e) {
            Log.e(TAG, "Client error", e);
        }
    }

    // ── JSON builder helper ──────────────────────────────────────────────────

    /** Build a flat JSON object from key/value string pairs. Values are quoted. */
    private String buildJson(String... kvPairs) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kvPairs.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append('"').append(kvPairs[i]).append("\":\"").append(kvPairs[i + 1]).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    // ── HTTP response writers ────────────────────────────────────────────────

    private void writeOk(OutputStream out, String body) throws IOException {
        byte[] bodyBytes = body.getBytes("UTF-8");
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 200 OK\r\n");
        sb.append("Content-Type: application/json; charset=UTF-8\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Allow-Methods: GET, OPTIONS\r\n");
        sb.append("Access-Control-Allow-Headers: Content-Type, Accept\r\n");
        sb.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        sb.append("Connection: close\r\n");
        sb.append("\r\n");
        writeRaw(out, sb.toString());
        out.write(bodyBytes);
        out.flush();
    }

    private void write404(OutputStream out) throws IOException {
        String body = "{\"code\":0,\"msg\":\"Not found\"}";
        byte[] bodyBytes = body.getBytes("UTF-8");
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 404 Not Found\r\n");
        sb.append("Content-Type: application/json; charset=UTF-8\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        sb.append("Connection: close\r\n");
        sb.append("\r\n");
        writeRaw(out, sb.toString());
        out.write(bodyBytes);
        out.flush();
    }

    private void writeOptions(OutputStream out) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 204 No Content\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Allow-Methods: GET, OPTIONS\r\n");
        sb.append("Access-Control-Allow-Headers: Content-Type, Accept\r\n");
        sb.append("Content-Length: 0\r\n");
        sb.append("Connection: close\r\n");
        sb.append("\r\n");
        writeRaw(out, sb.toString());
        out.flush();
    }

    private void writeRaw(OutputStream out, String text) throws IOException {
        out.write(text.getBytes("UTF-8"));
    }

    // ── Notification helpers ─────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "LCR HTTP Service", NotificationManager.IMPORTANCE_LOW);
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