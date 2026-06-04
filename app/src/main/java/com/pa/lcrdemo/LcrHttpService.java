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
    public static final String ACTION_START = "com.pa.lcrdemo.START_HTTP";
    public static final String ACTION_STOP  = "com.pa.lcrdemo.STOP_HTTP";
    public static final String BROADCAST_READY = "com.pa.lcrdemo.HTTP_READY";

    // Compatibilité avec DeepLinkHandler — utiliser publishResult() de préférence
    public static volatile String lastResultJson   = null;
    public static volatile String lastResultWoNum  = null;
    public static volatile String lastResultWoGuid = null;
    public static volatile long   lastResultTs     = 0;

    private static final String CHANNEL_ID = "lcr_http_channel";
    private static final int NOTIF_ID = 42;

    private static final AtomicReference<String> sLastResult = new AtomicReference<>(null);
    private static volatile long sResultTimestamp = 0;
    private static final long RESULT_TTL_MS = 60_000;

    private ServerSocket mServerSocket;
    private ExecutorService mExecutor;
    private volatile boolean mRunning = false;

    public static void publishResult(String json) {
        lastResultJson = json;
        lastResultTs   = System.currentTimeMillis();
        sLastResult.set(json);
        sResultTimestamp = System.currentTimeMillis();
        Log.i(TAG, "Result published: " + json);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

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
        startForeground(NOTIF_ID, buildNotification("HTTP service starting\u2026"));
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

    // ── Server loop ───────────────────────────────────────────────────────────

    private void serverLoop() {
        try {
            mServerSocket = new ServerSocket(HTTP_PORT);
            Log.i(TAG, "Listening on port " + HTTP_PORT);
            updateNotification("Listening on port " + HTTP_PORT);
            broadcastReady();
            while (mRunning) {
                try {
                    final Socket client = mServerSocket.accept();
                    mExecutor.execute(new Runnable() {
                        @Override public void run() { handleClient(client); }
                    });
                } catch (IOException e) {
                    if (mRunning) Log.w(TAG, "Accept error", e);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Server error", e);
        }
    }

    // ── Request handler ───────────────────────────────────────────────────────

    private void handleClient(Socket socket) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            OutputStream out = socket.getOutputStream();

            // Read request line
            String requestLine = in.readLine();
            if (requestLine == null) { socket.close(); return; }
            Log.d(TAG, "Request: " + requestLine);

            // Drain headers
            while (true) {
                String h = in.readLine();
                if (h == null || h.isEmpty()) break;
            }

            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path   = parts.length > 1 ? parts[1] : "/";
            int q = path.indexOf('?');
            String cleanPath = q >= 0 ? path.substring(0, q) : path;

            if ("OPTIONS".equalsIgnoreCase(method)) {
                writeOptions(out);
                socket.close();
                return;
            }

            String body;

            if ("/v1/delivery/result".equals(cleanPath)) {
                String last = sLastResult.get();
                if (last == null) {
                    body = "{\"code\":0,\"msg\":\"No result yet\"}";
                } else {
                    long age = System.currentTimeMillis() - sResultTimestamp;
                    if (age > RESULT_TTL_MS) {
                        sLastResult.set(null);
                        body = "{\"code\":0,\"msg\":\"Result expired\"}";
                    } else {
                        body = "{\"code\":1,\"msg\":\"OK\",\"data\":" + last + "}";
                    }
                }
            } else if ("/v1/ping".equals(cleanPath)) {
                body = "{\"code\":1,\"msg\":\"PING OK\",\"port\":" + HTTP_PORT + "}";
            } else {
                write404(out);
                socket.close();
                return;
            }

            writeOk(out, body);
            socket.close();

        } catch (Exception e) {
            Log.e(TAG, "Client error", e);
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // ── HTTP response writers ─────────────────────────────────────────────────

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
        out.write(sb.toString().getBytes("UTF-8"));
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
        out.write(sb.toString().getBytes("UTF-8"));
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
        out.write(sb.toString().getBytes("UTF-8"));
        out.flush();
    }

    // ── Notification helpers ──────────────────────────────────────────────────

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
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Arr\u00eater", pendingStop)
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
        Log.i(TAG, "Broadcast READY envoy\u00e9");
    }
}