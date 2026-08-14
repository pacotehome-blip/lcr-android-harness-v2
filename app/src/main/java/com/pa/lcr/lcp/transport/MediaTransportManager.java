package com.pa.lcr.lcp.transport;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import com.hoho.android.usbserial.driver.UsbSerialPort;

import com.pa.lcr.lcp.storage.BtSignalStore;
import com.pa.lcr.lcp.log.LogBus;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class MediaTransportManager {

    public static final String KEY_USB = "USB";

    // Intervalle de persistance IO (secondes)
    private static final int IO_SAMPLE_INTERVAL_SEC = 30;

    private static volatile MediaTransportManager INSTANCE;

    public static MediaTransportManager get(Context ctx) {
        if (INSTANCE != null) return INSTANCE;
        synchronized (MediaTransportManager.class) {
            if (INSTANCE == null) {
                INSTANCE = new MediaTransportManager(ctx.getApplicationContext());
            }
        }
        return INSTANCE;
    }

    private final Context appCtx;
    private final Map<String, TransportHandle> handles = new ConcurrentHashMap<>();

    // ✅ B1 FSM: un seul transport ACTIVE à la fois
    private volatile String activeKey = null;

    // ✅ Signal BT: store + scheduler
    private BtSignalStore btSignalStore;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();
    private final Map<String, ScheduledFuture<?>> ioSamplers = new ConcurrentHashMap<>();

    private MediaTransportManager(Context appCtx) {
        this.appCtx = appCtx;
        handles.put(KEY_USB, new TransportHandle(KEY_USB));
        try {
            btSignalStore = new BtSignalStore(appCtx);
            btSignalStore.purgeOlderThanDaysAsync(30);
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------
    // USB
    // ---------------------------------------------------------

    public synchronized void onUsbReady(UsbDevice dev, UsbSerialPort port, String description) {
        TransportHandle h = handles.get(KEY_USB);
        if (h == null) {
            h = new TransportHandle(KEY_USB);
            handles.put(KEY_USB, h);
        }
        // ✅ FIX CRITIQUE (5 août 2026, demande Paul — "il y a qq chose qui
        // ferme le socket USB... aussitôt le port usb est off, comme un hard
        // déconnect", au moment précis où Diagnostic démarre) — trouvé :
        // TransportHandle.setConnected() ferme l'ANCIEN wrapper TransportIo
        // dès qu'un NOUVEAU objet wrapper est fourni (voir son propre
        // commentaire, fix du 3 août pour éviter les sockets BT zombies) —
        // mais ici, le fix de resynchronisation (api_openPingUsb) appelait
        // onUsbReady() avec le MÊME UsbSerialPort physique déjà actif,
        // enveloppé dans un NOUVEAU UsbTransportIo. setConnected() fermait
        // alors l'ANCIEN wrapper — ce qui appelle port.close() sur le port
        // PARTAGÉ par les deux wrappers, cassant la connexion physique pour
        // tout le monde, y compris le nouveau wrapper qui se croit pourtant
        // bon. Chaque "resynchronisation" se sabotait donc elle-même. Ici :
        // si le port existant est déjà le MÊME objet physique, on ne crée
        // aucun nouveau wrapper — no-op, rien à fermer, rien à casser.
        TransportIo existingIo = h.getIo();
        if (existingIo instanceof UsbTransportIo
                && ((UsbTransportIo) existingIo).wrapsSamePort(port)
                && existingIo.isOpen()) {
            android.util.Log.i("MediaTransportManager", "onUsbReady: même port déjà actif — no-op (évite de casser la connexion existante)");
            return;
        }
        long nextGen = h.getGenerationId() + 1;
        TransportIo io = new UsbTransportIo(
                KEY_USB,
                port,
                (description != null ? description : "USB ready"),
                nextGen
        );
        h.setConnected(io, io.describe());
        // ✅ AJOUTÉ (14 août 2026, demande Paul — "ajouter le résultant dans
        // le support pour chaque connexion déconnexion et tentative de
        // connexion sur un média quelconque") — node=0 en marqueur média
        // (pas de node spécifique à ce stade), même convention que
        // [DÉBUT-SESSION].
        try { LogBus.ui(0, "[MEDIA][CONNEXION] USB — " + io.describe()); } catch (Exception ignored) {}
    }

    public synchronized void onUsbDetached(String reason) {
        TransportHandle h = handles.get(KEY_USB);
        if (h == null) return;
        h.setDisconnected(reason != null ? reason : "USB detached");
        clearActiveIfMatches(KEY_USB);
        try { LogBus.ui(0, "[MEDIA][DÉCONNEXION] USB — " + (reason != null ? reason : "raison inconnue")); } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------
    // BT
    // ---------------------------------------------------------

    public static String btKey(String mac) {
        if (mac == null) mac = "";
        return "BT:" + mac.toUpperCase(Locale.ROOT);
    }

    public synchronized void onBtConnected(
            BluetoothDevice dev,
            BluetoothSocket socket,
            InputStream in,
            OutputStream out,
            String description
    ) {
        String mac = (dev != null ? dev.getAddress() : null);
        String key = btKey(mac);

        TransportHandle h = handles.get(key);
        if (h == null) {
            h = new TransportHandle(key);
            handles.put(key, h);
        }

        long nextGen = h.getGenerationId() + 1;
        String name = (dev != null && dev.getName() != null) ? dev.getName() : "(no-name)";
        String desc = (description != null)
                ? description
                : ("BT SPP " + name + " " + (mac != null ? mac : ""));

        TransportIo io = new BtSppTransportIo(
                key, socket, in, out, desc, nextGen
        );

        h.setConnected(io, io.describe());

        // ✅ Démarrer le sampler IO périodique pour ce transport BT
        startIoSampler(key);
        try { LogBus.ui(0, "[MEDIA][CONNEXION] " + key + " — " + io.describe()); } catch (Exception ignored) {}
    }

    public synchronized void onBtDisconnected(String mac, String reason) {
        String key = btKey(mac);

        // ✅ Persister snapshot IO final avant déconnexion
        persistIoSnapshot(key, BtSignalStore.SOURCE_IO_DISCONNECT);

        // ✅ Arrêter le sampler
        stopIoSampler(key);

        TransportHandle h = handles.get(key);
        if (h == null) return;
        h.setDisconnected(reason != null ? reason : "BT disconnected");
        clearActiveIfMatches(key);
        try { LogBus.ui(0, "[MEDIA][DÉCONNEXION] " + key + " — " + (reason != null ? reason : "raison inconnue")); } catch (Exception ignored) {}
    }

    public synchronized void onBtError(String mac, String err) {
        String key = btKey(mac);
        TransportHandle h = handles.get(key);
        if (h == null) {
            h = new TransportHandle(key);
            handles.put(key, h);
        }
        h.setError(h.getDescription(), err);
        try { LogBus.err(0, "MediaTransportManager.onBtError", new Exception(key + " — " + err)); } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------
    // TCP (raw passthrough, ex: Moxa N-Port) — manuel ou scan réseau
    // ---------------------------------------------------------

    public static String tcpKey(String ip, int port) {
        return TcpTransportIo.tcpKey(ip, port);
    }

    public synchronized void onTcpConnected(
            java.net.Socket socket,
            InputStream in,
            OutputStream out,
            String ip,
            int port,
            String description
    ) {
        String key = tcpKey(ip, port);

        TransportHandle h = handles.get(key);
        if (h == null) {
            h = new TransportHandle(key);
            handles.put(key, h);
        }

        long nextGen = h.getGenerationId() + 1;
        String desc = (description != null) ? description : ("TCP " + ip + ":" + port);

        TransportIo io = new TcpTransportIo(key, socket, in, out, desc, nextGen);
        h.setConnected(io, io.describe());
        try { LogBus.ui(0, "[MEDIA][CONNEXION] " + key + " — " + io.describe()); } catch (Exception ignored) {}
    }

    public synchronized void onTcpDisconnected(String ip, int port, String reason) {
        String key = tcpKey(ip, port);
        TransportHandle h = handles.get(key);
        if (h == null) return;
        h.setDisconnected(reason != null ? reason : "TCP disconnected");
        clearActiveIfMatches(key);
        try { LogBus.ui(0, "[MEDIA][DÉCONNEXION] " + key + " — " + (reason != null ? reason : "raison inconnue")); } catch (Exception ignored) {}
    }

    public synchronized void onTcpError(String ip, int port, String err) {
        String key = tcpKey(ip, port);
        TransportHandle h = handles.get(key);
        if (h == null) {
            h = new TransportHandle(key);
            handles.put(key, h);
        }
        h.setError(h.getDescription(), err);
        try { LogBus.err(0, "MediaTransportManager.onTcpError", new Exception(key + " — " + err)); } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------
    // Activation exclusive
    // ---------------------------------------------------------

    public synchronized boolean activateExclusive(String key, String reason) {
        if (key == null || key.trim().isEmpty()) return false;

        TransportHandle target = handles.get(key);
        if (target == null) return false;

        TransportIo tio = target.getIo();
        if (tio == null || !tio.isOpen()) return false;

        for (TransportHandle h : handles.values()) {
            if (h == null) continue;
            if (h.getKey().equals(key)) continue;
            try { h.setSuspended(reason); } catch (Exception ignored) {}
        }

        activeKey = key;
        try { target.setActive(reason); } catch (Exception ignored) {}

        return true;
    }

    public synchronized void clearActiveIfMatches(String key) {
        if (key == null) return;
        if (key.equals(activeKey)) activeKey = null;
    }

    public String getActiveKey() { return activeKey; }

    public static String getActiveKeyStatic() {
        return (INSTANCE != null) ? INSTANCE.activeKey : null;
    }

    public static boolean isKeyActive(String key) {
        if (key == null) return false;
        return (INSTANCE != null && key.equals(INSTANCE.activeKey));
    }

    // ---------------------------------------------------------
    // Queries
    // ---------------------------------------------------------

    public TransportSnapshot getUsbSnapshot() {
        TransportHandle h = handles.get(KEY_USB);
        return h != null ? h.snapshot() : null;
    }

    public List<TransportSnapshot> listSnapshots() {
        ArrayList<TransportSnapshot> out = new ArrayList<>();
        for (TransportHandle h : handles.values()) {
            if (h == null) continue;
            out.add(h.snapshot());
        }
        out.sort(Comparator.comparing(s -> s.key));
        return out;
    }

    public TransportIo pickReady(List<String> preferredKeys) {
        if (preferredKeys != null) {
            for (String k : preferredKeys) {
                TransportHandle h = handles.get(k);
                if (h != null
                        && h.getStatus() == TransportStatus.READY
                        && h.getIo() != null
                        && h.getIo().isOpen()) {
                    return h.getIo();
                }
            }
        }
        for (TransportHandle h : handles.values()) {
            if (h != null
                    && h.getStatus() == TransportStatus.READY
                    && h.getIo() != null
                    && h.getIo().isOpen()) {
                return h.getIo();
            }
        }
        return null;
    }

    public TransportIo getAnyReady() { return pickReady(null); }

    public TransportIo getByKey(String key) {
        if (key == null) return null;
        TransportHandle h = handles.get(key);
        if (h == null) return null;
        if (h.getStatus() == TransportStatus.ERROR
                || h.getStatus() == TransportStatus.DISCONNECTED)
            return null;
        TransportIo io = h.getIo();
        if (io == null || !io.isOpen()) return null;
        return io;
    }

    public TransportIo autoSelectConnect(String media, String btMac) {
        String m = (media != null) ? media.trim().toLowerCase(Locale.ROOT) : "auto";
        if ("usb".equals(m)) return getByKey(KEY_USB);
        if ("bt".equals(m)) {
            String key = (btMac != null && !btMac.isEmpty()) ? btKey(btMac) : activeKey;
            return getByKey(key);
        }
        TransportIo usb = getByKey(KEY_USB);
        if (usb != null) return usb;
        if (activeKey != null && activeKey.startsWith("BT:")) {
            TransportIo bt = getByKey(activeKey);
            if (bt != null) return bt;
        }
        return getAnyReady();
    }

    // =========================================================
    // ✅ BT Signal — IO sampler périodique
    // =========================================================

    private void startIoSampler(final String key) {
        stopIoSampler(key); // arrêter si déjà en cours
        try {
            ScheduledFuture<?> f = scheduler.scheduleAtFixedRate(
                    () -> persistIoSnapshot(key, BtSignalStore.SOURCE_IO_SAMPLE),
                    IO_SAMPLE_INTERVAL_SEC,
                    IO_SAMPLE_INTERVAL_SEC,
                    TimeUnit.SECONDS
            );
            ioSamplers.put(key, f);
        } catch (Exception ignored) {}
    }

    private void stopIoSampler(String key) {
        ScheduledFuture<?> f = ioSamplers.remove(key);
        if (f != null) {
            try { f.cancel(false); } catch (Exception ignored) {}
        }
    }

    /**
     * Persiste un snapshot IO en DB pour la clé BT donnée.
     * Appelé périodiquement et à la déconnexion.
     */
    private void persistIoSnapshot(String key, String source) {
        try {
            if (btSignalStore == null) return;
            TransportHandle h = handles.get(key);
            if (h == null) return;
            TransportIo raw = h.getIo();
            if (!(raw instanceof BtSppTransportIo)) return;
            BtSppTransportIo bt = (BtSppTransportIo) raw;

            BtSppTransportIo.IoSnapshot snap = bt.snapshotCounters();
            if (snap.samples <= 0) return; // rien à persister

            String mac = bt.getMac();
            boolean deliveryActive = isDeliveryActiveForKey(key);

            btSignalStore.insertIoSampleAsync(
                    mac, key,
                    snap.errors,
                    snap.timeouts,
                    snap.samples,
                    snap.latencyAvgMs,
                    deliveryActive,
                    source
            );

            // Reset compteurs après persistance périodique seulement
            // (pas à la déconnexion pour garder les stats finales)
            if (BtSignalStore.SOURCE_IO_SAMPLE.equals(source)) {
                bt.resetCounters();
            }
        } catch (Exception ignored) {}
    }

    /**
     * Vérifie si une livraison est active pour ce transport.
     * Heuristique: activeKey correspond à ce transport.
     */
    private boolean isDeliveryActiveForKey(String key) {
        try {
            return key != null && key.equals(activeKey);
        } catch (Exception ignored) {
            return false;
        }
    }

    // =========================================================
    // ✅ BT Signal — RSSI (appelé depuis MainActivity après scan)
    // =========================================================

    /**
     * Persiste un résultat RSSI obtenu via BluetoothDevice.ACTION_FOUND.
     * À appeler depuis MainActivity dans le BroadcastReceiver de découverte BT.
     */
    public void onBtRssiScanned(String mac, int rssi, boolean deliveryActive) {
        try {
            if (btSignalStore == null) return;
            if (mac == null || mac.trim().isEmpty()) return;
            String key = btKey(mac);
            btSignalStore.insertScanAsync(mac, key, rssi, deliveryActive);
        } catch (Exception ignored) {}
    }

    // =========================================================
    // ✅ BT Signal — lecture pour API
    // =========================================================

    /**
     * Retourne le dernier signal connu pour un transport BT actif.
     * Utilisé par l'endpoint GET /v1/bt/signal
     */
    public org.json.JSONObject getBtSignal(String btMac) {
        try {
            if (btSignalStore == null) return null;
            String mac = (btMac != null && !btMac.trim().isEmpty())
                    ? btMac.trim().toUpperCase(Locale.ROOT)
                    : extractMacFromActiveKey();
            if (mac == null || mac.isEmpty()) return null;
            return btSignalStore.getLatestByMac(mac);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Retourne tous les signaux BT connus (toutes MACs).
     * Utilisé par l'endpoint GET /v1/bt/signal (sans mac)
     */
    public org.json.JSONArray getAllBtSignals() {
        try {
            if (btSignalStore == null) return new org.json.JSONArray();
            return btSignalStore.getAllLatest();
        } catch (Exception ignored) {
            return new org.json.JSONArray();
        }
    }

    /**
     * Snapshot IO temps réel (sans DB) pour la clé BT active.
     * Retourne null si aucun transport BT actif.
     */
    public BtSppTransportIo.IoSnapshot getLiveIoSnapshot(String key) {
        try {
            if (key == null) key = activeKey;
            if (key == null || !key.startsWith("BT:")) return null;
            TransportHandle h = handles.get(key);
            if (h == null) return null;
            TransportIo raw = h.getIo();
            if (!(raw instanceof BtSppTransportIo)) return null;
            return ((BtSppTransportIo) raw).snapshotCounters();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String extractMacFromActiveKey() {
        try {
            String k = activeKey;
            if (k == null || !k.startsWith("BT:")) return null;
            return k.substring(3).trim().toUpperCase(Locale.ROOT);
        } catch (Exception ignored) {
            return null;
        }
    }
}