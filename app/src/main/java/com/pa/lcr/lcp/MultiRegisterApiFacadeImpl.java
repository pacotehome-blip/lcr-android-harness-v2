package com.pa.lcr.lcp;

import java.util.UUID;
import java.util.Set;
import java.util.Comparator;
import java.util.ArrayList;
import java.io.OutputStream;
import java.io.InputStream;
import org.json.JSONArray;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import org.json.JSONException;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import com.pa.lcrdemo.UsbReceiver;
import com.pa.lcrdemo.UsbSession;

import com.pa.lcr.lcp.transport.BtSppTransportIo;
import com.pa.lcr.lcp.transport.MediaTransportManager;
import com.pa.lcr.lcp.transport.TransportIo;
import com.pa.lcr.lcp.storage.BtSignalStore;
import com.pa.lcr.lcp.storage.DeliveryLogStore;
import com.pa.lcr.lcp.log.LogBus;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.pa.lcr.lcp.transport.TransportSnapshot;
import com.pa.lcr.lcp.transport.TransportStatus;


public final class MultiRegisterApiFacadeImpl implements ApiFacade {

    private static final String ACTION_NODE_SEEN = "com.pa.lcrdemo.ACTION_NODE_SEEN";

    private final Context appCtx;
    private final UsbManager usbManager;
    private final RegisterSessionManager sessions;
    private final MediaTransportManager mediaMgr;

    // jobId -> node/from
    private final Map<String, Integer> jobToNode      = new ConcurrentHashMap<>();
    private final Map<String, Integer> jobToFrom      = new ConcurrentHashMap<>();
    private final Map<String, String>  jobToTransport = new ConcurrentHashMap<>();
    private volatile int lastNodeHint = 250;
    private volatile int lastFromHint = 255;

    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // =========================================================
    // BT Signal store (accès direct pour scan RSSI)
    // =========================================================
    private final BtSignalStore btSignalStore;
    private final com.pa.lcr.lcp.storage.TruckProfileStore truckProfileStore;

    private BluetoothAdapter btAdapterSafe() {
        try { return BluetoothAdapter.getDefaultAdapter(); }
        catch (Exception ignored) { return null; }
    }

    private ArrayList<BluetoothDevice> listBondedSorted() {
        BluetoothAdapter ad = btAdapterSafe();
        ArrayList<BluetoothDevice> out = new ArrayList<>();
        if (ad == null) return out;
        try {
            Set<BluetoothDevice> bonded = ad.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    if (d == null) continue;
                    String mac = d.getAddress();
                    if (mac == null || mac.trim().isEmpty()) continue;
                    out.add(d);
                }
            }
        } catch (Exception ignored) {}
        out.sort(Comparator.comparing(d -> d.getAddress().toUpperCase(Locale.ROOT)));
        return out;
    }

    private String resolveBtKeyOrActive(String bt_mac) {
        String mac = (bt_mac == null) ? "" : bt_mac.trim();
        if (!mac.isEmpty()) return MediaTransportManager.btKey(mac);
        String activeKey = MediaTransportManager.getActiveKeyStatic();
        if (activeKey != null && activeKey.startsWith("BT:")) return activeKey;
        return null;
    }

    public MultiRegisterApiFacadeImpl(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.usbManager = (UsbManager) this.appCtx.getSystemService(Context.USB_SERVICE);
        this.sessions = RegisterSessionManager.get(this.appCtx);
        this.mediaMgr = MediaTransportManager.get(this.appCtx);
        this.btSignalStore = new BtSignalStore(this.appCtx);
        this.truckProfileStore = new com.pa.lcr.lcp.storage.TruckProfileStore(this.appCtx);
    }

    // =========================
    // Media check
    // =========================
    @Override
    public ApiResult api_mediaCheck(String media, String bt_mac) {
        try {
            String m = (media == null) ? "usb" : media.trim().toLowerCase(Locale.ROOT);
            if (m.isEmpty()) m = "usb";
            JSONObject d = new JSONObject();
            d.put("media", m);
            if ("usb".equals(m)) {
                UsbSerialPort p = UsbSession.getPort();
                d.put("transportKey", "USB");
                d.put("connected", (p != null) ? 1 : 0);
                if (p != null) return ApiResult.ok("MediaCheck: 1 - USB connecté", d);
                return ApiResult.fail("MediaCheck: 0 - USB non connecté", "ERR_USB_NOT_CONNECTED", d);
            }
            if ("bt".equals(m) || "bluetooth".equals(m)) {
                String key = resolveBtKeyOrActive(bt_mac);
                if (key == null) {
                    d.put("connected", 0);
                    return ApiResult.fail("MediaCheck: 0 - Aucun BT actif", "ERR_NO_ACTIVE_BT", d);
                }
                d.put("transportKey", key);
                if (mediaMgr == null) {
                    d.put("connected", 0);
                    return ApiResult.fail("MediaCheck: 0 - BT non connecté", "ERR_BT_NOT_CONNECTED", d);
                }
                TransportIo io = mediaMgr.getByKey(key);
                boolean ok = (io != null && io.isOpen());
                d.put("connected", ok ? 1 : 0);
                if (ok) return ApiResult.ok("MediaCheck: 1 - BT connecté", d);
                return ApiResult.fail("MediaCheck: 0 - BT non connecté", "ERR_BT_NOT_CONNECTED", d);
            }
            if ("wifi".equals(m)) {
                d.put("connected", 0);
                return ApiResult.fail("MediaCheck: 0 - Wi-Fi non supporté (bientôt)", "ERR_WIFI_NOT_SUPPORTED", d);
            }
            d.put("connected", 0);
            return ApiResult.fail("MediaCheck: 0 - media invalide", "ERR_MEDIA_INVALID", d);
        } catch (Exception e) {
            JSONObject d = new JSONObject();
            try { d.put("detail", (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName()); } catch (Exception ignored) {}
            return ApiResult.fail("MediaCheck: 0 - Failed", "ERR_MEDIA_CHECK_FAILED", d);
        }
    }

    // =========================================================
    // BT LIST
    // =========================================================
    @Override
    public ApiResult api_btList() {
        if (mediaMgr == null) return ApiResult.fail("MTM null", "ERR_MEDIA_MTM_NULL");
        JSONObject d = new JSONObject();
        JSONArray bondedArr = new JSONArray();
        JSONArray runtimeArr = new JSONArray();
        try {
            for (BluetoothDevice dev : listBondedSorted()) {
                JSONObject o = new JSONObject();
                try { o.put("name", dev.getName() != null ? dev.getName() : JSONObject.NULL); } catch (Exception ignored) {}
                try { o.put("mac", dev.getAddress() != null ? dev.getAddress() : JSONObject.NULL); } catch (Exception ignored) {}
                bondedArr.put(o);
            }
        } catch (Exception e) {
            JSONObject ed = new JSONObject();
            try { ed.put("detail", e.getMessage()); } catch (Exception ignored) {}
            return ApiResult.fail("BT list failed", "ERR_BT_LIST_FAILED", ed);
        }
        try {
            for (TransportSnapshot s : mediaMgr.listSnapshots()) {
                if (s == null || s.key == null) continue;
                if (!s.key.startsWith("BT:")) continue;
                JSONObject o = new JSONObject();
                try { o.put("key", s.key); } catch (Exception ignored) {}
                try { o.put("status", s.status != null ? String.valueOf(s.status) : JSONObject.NULL); } catch (Exception ignored) {}
                runtimeArr.put(o);
            }
        } catch (Exception ignored) {}
        try { d.put("bonded", bondedArr); } catch (Exception ignored) {}
        try { d.put("runtime", runtimeArr); } catch (Exception ignored) {}
        try { d.put("activeKey", MediaTransportManager.getActiveKeyStatic()); } catch (Exception ignored) {}
        return ApiResult.ok("BT list: 1 - OK", d);
    }

    // =========================================================
    // BT ACTIVATE
    // =========================================================
    @Override
    public ApiResult api_btActivate() {
        if (mediaMgr == null) return ApiResult.fail("MTM null", "ERR_MEDIA_MTM_NULL");
        try {
            String activeKey = MediaTransportManager.getActiveKeyStatic();
            if (activeKey != null && activeKey.startsWith("BT:")) {
                TransportIo io0 = mediaMgr.getByKey(activeKey);
                if (io0 != null && io0.isOpen()) {
                    mediaMgr.activateExclusive(activeKey, "API_BT_AUTO");
                    JSONObject d = new JSONObject();
                    d.put("transportKey", activeKey);
                    d.put("activeKey", MediaTransportManager.getActiveKeyStatic());
                    return ApiResult.ok("BT activate: 1 - OK (already open)", d);
                }
            }
        } catch (Exception ignored) {}

        ArrayList<BluetoothDevice> bonded = listBondedSorted();
        if (bonded.isEmpty()) return ApiResult.fail("BT activate: 0 - Aucun BT pairé", "ERR_NO_BONDED_BT");

        JSONObject lastErr = null;
        for (BluetoothDevice dev : bonded) {
            if (dev == null) continue;
            String mac = dev.getAddress();
            if (mac == null || mac.trim().isEmpty()) continue;
            String key = MediaTransportManager.btKey(mac);
            try {
                TransportIo existing = mediaMgr.getByKey(key);
                if (existing != null && existing.isOpen()) {
                    mediaMgr.activateExclusive(key, "API_BT_AUTO");
                    JSONObject d = new JSONObject();
                    d.put("transportKey", key);
                    d.put("activeKey", MediaTransportManager.getActiveKeyStatic());
                    return ApiResult.ok("BT activate: 1 - OK (already open)", d);
                }
            } catch (Exception ignored) {}
            BluetoothSocket sock = null;
            try {
                sock = dev.createRfcommSocketToServiceRecord(SPP_UUID);
                sock.connect();
                InputStream in = sock.getInputStream();
                OutputStream out = sock.getOutputStream();
                mediaMgr.onBtConnected(dev, sock, in, out, "BT ready (API)");
                boolean ok = mediaMgr.activateExclusive(key, "API_BT_AUTO");
                if (!ok) {
                    try { sock.close(); } catch (Exception ignored2) {}
                    return ApiResult.fail("BT activate failed", "ERR_BT_ACTIVATE_FAILED");
                }
                JSONObject d = new JSONObject();
                d.put("transportKey", key);
                d.put("activeKey", MediaTransportManager.getActiveKeyStatic());
                return ApiResult.ok("BT activate: 1 - OK", d);
            } catch (Exception e) {
                try { if (sock != null) sock.close(); } catch (Exception ignored) {}
                lastErr = new JSONObject();
                try { lastErr.put("mac", mac); } catch (Exception ignored) {}
                try { lastErr.put("detail", e.getMessage()); } catch (Exception ignored) {}
            }
        }
        if (lastErr != null) return ApiResult.fail("BT activate: 0 - Connexion échouée", "ERR_BT_CONNECT_FAILED", lastErr);
        return ApiResult.fail("BT activate: 0 - Connexion échouée", "ERR_BT_CONNECT_FAILED");
    }

    // =========================================================
    // ✅ BT SIGNAL GET — lecture DB + live IO snapshot
    // =========================================================
    @Override
    public ApiResult api_btSignalGet(String bt_mac) {
        try {
            JSONObject result = new JSONObject();

            // Résoudre la MAC cible
            String mac = null;
            if (bt_mac != null && !bt_mac.trim().isEmpty()) {
                mac = bt_mac.trim().toUpperCase(Locale.ROOT);
            } else {
                String activeKey = MediaTransportManager.getActiveKeyStatic();
                if (activeKey != null && activeKey.startsWith("BT:")) {
                    mac = activeKey.substring(3).trim().toUpperCase(Locale.ROOT);
                }
            }

            if (mac == null || mac.isEmpty()) {
                // Retourner tous les signaux connus
                JSONArray all = mediaMgr.getAllBtSignals();
                result.put("all", all);
                result.put("count", all.length());
                result.put("activeKey", MediaTransportManager.getActiveKeyStatic());
                return ApiResult.ok("BT Signal: 1 - OK (all)", result);
            }

            // Signal DB (RSSI + IO historique)
            JSONObject dbSignal = mediaMgr.getBtSignal(mac);

            // Live IO snapshot (temps réel, sans DB)
            String key = MediaTransportManager.btKey(mac);
            BtSppTransportIo.IoSnapshot live = mediaMgr.getLiveIoSnapshot(key);

            result.put("mac", mac);
            result.put("transportKey", key);
            result.put("activeKey", MediaTransportManager.getActiveKeyStatic());
            result.put("connected", mediaMgr.getByKey(key) != null ? 1 : 0);

            // Données DB
            if (dbSignal != null) {
                result.put("rssi", dbSignal.opt("rssi"));
                result.put("rssi_quality", dbSignal.optString("rssi_quality", "INCONNU"));
                result.put("last_scan_ms", dbSignal.opt("last_scan_ms"));
                result.put("io_score_db", dbSignal.optString("io_score", "INCONNU"));
                result.put("io_errors_db", dbSignal.optInt("io_errors", 0));
                result.put("io_timeouts_db", dbSignal.optInt("io_timeouts", 0));
                result.put("io_latency_avg_ms_db", dbSignal.optInt("io_latency_avg_ms", 0));
                result.put("io_samples_db", dbSignal.optInt("io_samples", 0));
                result.put("last_io_sample_ms", dbSignal.opt("last_io_sample_ms"));
            } else {
                result.put("rssi", JSONObject.NULL);
                result.put("rssi_quality", "INCONNU");
                result.put("last_scan_ms", JSONObject.NULL);
                result.put("io_score_db", "INCONNU");
            }

            // Live IO (session courante)
            if (live != null) {
                String liveScore = BtSignalStore.ioQuality(
                        live.errors, live.timeouts, live.samples, live.latencyAvgMs);
                result.put("io_score_live", liveScore);
                result.put("io_errors_live", live.errors);
                result.put("io_timeouts_live", live.timeouts);
                result.put("io_latency_avg_ms_live", live.latencyAvgMs);
                result.put("io_samples_live", live.samples);
                result.put("session_start_ms", live.sessionStartMs);
            } else {
                result.put("io_score_live", "INCONNU");
                result.put("io_errors_live", 0);
                result.put("io_timeouts_live", 0);
                result.put("io_latency_avg_ms_live", 0);
                result.put("io_samples_live", 0);
                result.put("session_start_ms", JSONObject.NULL);
            }

            return ApiResult.ok("BT Signal: 1 - OK", result);

        } catch (Exception e) {
            JSONObject d = new JSONObject();
            try { d.put("detail", e.getMessage() != null ? e.getMessage() : ""); } catch (Exception ignored) {}
            return ApiResult.fail("BT Signal: 0 - Failed", "ERR_BT_SIGNAL_FAILED", d);
        }
    }

    // =========================================================
    // ✅ BT SIGNAL SCAN — scan RSSI ponctuel via startDiscovery()
    // ⚠️ Bloqué si livraison active (sauf perte de connexion)
    // =========================================================
    @Override
    public ApiResult api_btSignalScan(String bt_mac) {
        try {
            // 1) Guard: bloqué si livraison active ET connexion OK
            boolean deliveryActive = isAnyDeliveryActive();
            boolean connectionLost = isBtConnectionLost();

            if (deliveryActive && !connectionLost) {
                JSONObject d = new JSONObject();
                d.put("delivery_active", 1);
                d.put("connection_lost", 0);
                d.put("suggestion", "Scan RSSI bloqué pendant livraison active. Réessayer après Terminer.");
                return ApiResult.fail(
                        "BT Signal Scan: 0 - Bloqué (livraison active)",
                        "ERR_SCAN_BLOCKED_DELIVERY_ACTIVE",
                        d
                );
            }

            BluetoothAdapter adapter = btAdapterSafe();
            if (adapter == null) {
                return ApiResult.fail("BT Signal Scan: 0 - Bluetooth non disponible", "ERR_BT_NOT_AVAILABLE");
            }

            if (!adapter.isEnabled()) {
                return ApiResult.fail("BT Signal Scan: 0 - Bluetooth désactivé", "ERR_BT_DISABLED");
            }

            // 2) Déterminer les MACs cibles (pairées)
            ArrayList<BluetoothDevice> targets = new ArrayList<>();
            if (bt_mac != null && !bt_mac.trim().isEmpty()) {
                // MAC spécifique
                String targetMac = bt_mac.trim().toUpperCase(Locale.ROOT);
                for (BluetoothDevice dev : listBondedSorted()) {
                    if (targetMac.equals(dev.getAddress().toUpperCase(Locale.ROOT))) {
                        targets.add(dev);
                        break;
                    }
                }
                if (targets.isEmpty()) {
                    JSONObject d = new JSONObject();
                    d.put("mac", targetMac);
                    return ApiResult.fail("BT Signal Scan: 0 - MAC non pairée", "ERR_BT_MAC_NOT_BONDED", d);
                }
            } else {
                targets.addAll(listBondedSorted());
            }

            if (targets.isEmpty()) {
                return ApiResult.fail("BT Signal Scan: 0 - Aucun appareil pairé", "ERR_NO_BONDED_BT");
            }
            // 3) Scan via startDiscovery() — fonctionne même avec SPP actif
            final JSONArray scanned = new JSONArray();
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<BroadcastReceiver> receiverRef = new AtomicReference<>();

            // MACs attendues
            final Set<String> expectedMacs = new java.util.HashSet<>();
            for (BluetoothDevice dev : targets) {
                expectedMacs.add(dev.getAddress().toUpperCase(Locale.ROOT));
            }
            final Set<String> foundMacs = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        String action = intent.getAction();
                        if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                            BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                            if (dev == null) return;
                            String foundMac = dev.getAddress().toUpperCase(Locale.ROOT);
                            if (!expectedMacs.contains(foundMac)) return;

                            int rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                            String quality = BtSignalStore.rssiQuality(rssi);

                            // Persister en DB
                            mediaMgr.onBtRssiScanned(foundMac, rssi, deliveryActive);

                            // Accumuler résultat
                            JSONObject row = new JSONObject();
                            try {
                                row.put("mac", foundMac);
                                row.put("name", dev.getName() != null ? dev.getName() : JSONObject.NULL);
                                row.put("rssi", rssi);
                                row.put("rssi_quality", quality);
                                row.put("ts_ms", System.currentTimeMillis());
                            } catch (Exception ignored) {}
                            synchronized (scanned) { scanned.put(row); }

                            foundMacs.add(foundMac);

                            // Tous trouvés -> arrêter tôt
                            if (foundMacs.containsAll(expectedMacs)) {
                                latch.countDown();
                            }

                        } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                            latch.countDown();
                        }
                    } catch (Exception ignored) {}
                }
            };
            receiverRef.set(receiver);

            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothDevice.ACTION_FOUND);
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            appCtx.registerReceiver(receiver, filter);

            // Annuler seulement si déjà en cours
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            boolean scanStarted = adapter.startDiscovery();
            if (!scanStarted) {
                JSONArray dbScanned = new JSONArray();
                for (BluetoothDevice dev : targets) {
                    String devMac = dev.getAddress().toUpperCase(Locale.ROOT);
                    JSONObject last = btSignalStore.getLatestByMac(devMac);
                    JSONObject row = new JSONObject();
                    row.put("mac", devMac);
                    row.put("name", dev.getName() != null ? dev.getName() : JSONObject.NULL);
                    row.put("rssi", last != null ? last.optInt("rssi", -999) : -999);
                    row.put("rssi_quality", last != null ? last.optString("rssi_quality", "INCONNU") : "INCONNU");
                    row.put("source", "DB_LAST");
                    row.put("ts_ms", System.currentTimeMillis());
                    dbScanned.put(row);
                }
                JSONObject result = new JSONObject();
                result.put("scanned", dbScanned);
                result.put("count", dbScanned.length());
                result.put("completed", 0);
                result.put("mode", "DB_FALLBACK");
                result.put("delivery_active", deliveryActive ? 1 : 0);
                return ApiResult.ok("BT Signal Scan: 1 - OK (DB fallback)", result);
            }
            // Attendre résultats (max 12 secondes)
            boolean completed = latch.await(12, TimeUnit.SECONDS);

            // Cleanup
            try { adapter.cancelDiscovery(); } catch (Exception ignored) {}
            try { appCtx.unregisterReceiver(receiver); } catch (Exception ignored) {}

            // 4) Construire réponse
            JSONObject result = new JSONObject();
            result.put("scanned", scanned);
            result.put("count", scanned.length());
            result.put("completed", completed ? 1 : 0);
            result.put("delivery_active", deliveryActive ? 1 : 0);
            result.put("connection_lost", connectionLost ? 1 : 0);

            if (scanned.length() == 0) {
                return ApiResult.fail(
                        "BT Signal Scan: 0 - Aucun appareil trouvé dans le scan",
                        "ERR_BT_SCAN_NO_RESULT",
                        result
                );
            }

            return ApiResult.ok("BT Signal Scan: 1 - OK", result);

        } catch (Exception e) {
            JSONObject d = new JSONObject();
            try { d.put("detail", e.getMessage() != null ? e.getMessage() : ""); } catch (Exception ignored) {}
            return ApiResult.fail("BT Signal Scan: 0 - Failed", "ERR_BT_SCAN_FAILED", d);
        }
    }

    // =========================================================
    // Helpers signal
    // =========================================================

    /** Vérifie si au moins une livraison est active (sur n'importe quel controller) */
    private boolean isAnyDeliveryActive() {
        try {
            if (sessions == null) return false;
            // On vérifie via le tick snapshot de chaque transport connu
            for (TransportSnapshot snap : mediaMgr.listSnapshots()) {
                if (snap == null || snap.key == null) continue;
                DeliveryController dc = sessions.getController(snap.key, lastNodeHint);
                if (dc != null && dc.isDeliveryActive()) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** Vérifie si la connexion BT active est perdue */
    private boolean isBtConnectionLost() {
        try {
            String activeKey = MediaTransportManager.getActiveKeyStatic();
            if (activeKey == null || !activeKey.startsWith("BT:")) return false;
            TransportIo io = mediaMgr.getByKey(activeKey);
            return (io == null || !io.isOpen());
        } catch (Exception ignored) {
            return false;
        }
    }

    // =========================================================
    // Media Auto-Connect
    // =========================================================
    public ApiResult api_mediaAutoConnect(Integer lcrnode_dec, Integer from_dec) {
        int node = normNode(lcrnode_dec);
        int from = normFrom(from_dec);
        try {
            String activeKey = MediaTransportManager.getActiveKeyStatic();
            if (activeKey != null && !activeKey.trim().isEmpty()) {
                TransportIo io = (mediaMgr != null) ? mediaMgr.getByKey(activeKey) : null;
                if (io != null && io.isOpen()) {
                    DeliveryController dc = sessions.getOrCreate(activeKey, node, from, io);
                    if (dc != null) {
                        JSONObject d = new JSONObject();
                        d.put("media", activeKey.startsWith("BT:") ? "bt" : "usb");
                        d.put("transportKey", activeKey);
                        return ApiResult.ok("Media auto-connect: 1 - OK (already connected)", d);
                    }
                }
            }
        } catch (Exception ignored) {}
        try {
            boolean hasBtRuntime = false;
            if (mediaMgr != null) {
                for (TransportSnapshot s : mediaMgr.listSnapshots()) {
                    if (s != null && s.key != null && s.key.startsWith("BT:")) { hasBtRuntime = true; break; }
                }
                if (!hasBtRuntime) api_btActivate();
            }
        } catch (Exception ignored) {}
        if (mediaMgr != null) {
            for (TransportSnapshot snap : mediaMgr.listSnapshots()) {
                if (snap == null || snap.key == null || !snap.key.startsWith("BT:")) continue;
                try {
                    mediaMgr.activateExclusive(snap.key, "API_AUTO_CONNECT");
                    TransportIo io = mediaMgr.getByKey(snap.key);
                    if (io != null && io.isOpen()) {
                        DeliveryController dc = sessions.getOrCreate(snap.key, node, from, io);
                        if (dc != null) {
                            ApiResult r = dc.api_connectLcp();
                            if (r != null && r.code == 1) {
                                JSONObject d = new JSONObject();
                                d.put("media", "bt");
                                d.put("transportKey", snap.key);
                                return ApiResult.ok("Media auto-connect: 1 - OK (BT)", d);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        try {
            TransportIo io = (mediaMgr != null) ? mediaMgr.getByKey(MediaTransportManager.KEY_USB) : null;
            if (io != null && io.isOpen()) {
                DeliveryController dc = sessions.getOrCreate(MediaTransportManager.KEY_USB, node, from, io);
                if (dc != null) {
                    JSONObject d = new JSONObject();
                    d.put("media", "usb");
                    d.put("transportKey", "USB");
                    return ApiResult.ok("Media auto-connect: 1 - OK (USB)", d);
                }
            }
            ApiResult ping = api_openPingUsb();
            if (ping != null && ping.code == 1) {
                TransportIo io2 = (mediaMgr != null) ? mediaMgr.getByKey(MediaTransportManager.KEY_USB) : null;
                if (io2 != null && io2.isOpen()) {
                    DeliveryController dc2 = sessions.getOrCreate(MediaTransportManager.KEY_USB, node, from, io2);
                    if (dc2 != null) {
                        JSONObject d = new JSONObject();
                        d.put("media", "usb");
                        d.put("transportKey", "USB");
                        return ApiResult.ok("Media auto-connect: 1 - OK (USB after open)", d);
                    }
                }
            }
        } catch (Exception ignored) {}
        return ApiResult.fail("Media auto-connect: 0 - No media available", "ERR_NO_MEDIA_AVAILABLE");
    }

    // =========================================================
    // LCP CONNECT — MEDIA-AWARE
    // =========================================================
    @Override
    public ApiResult api_connectLcp(Integer lcrnode_dec, Integer from_dec, String media, String bt_mac) {
        int node = normNode(lcrnode_dec);
        int from = normFrom(from_dec);
        String m = (media == null) ? null : media.trim().toLowerCase(Locale.ROOT);
        if (m == null || m.isEmpty()) {
            String activeKey = MediaTransportManager.getActiveKeyStatic();
            m = (activeKey != null && activeKey.startsWith("BT:")) ? "bt" : "usb";
        }
        if ("usb".equals(m)) {
            DeliveryController dc = requireSession(node, from);
            if (dc == null) return ApiResult.fail("Connect LCP: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
            return dc.api_connectLcp();
        }
        if ("bt".equals(m) || "bluetooth".equals(m)) {
            String key = resolveBtKeyOrActive(bt_mac);
            if (key == null) return ApiResult.fail("Connect LCP: 0 - Aucun BT actif (appelle bt/activate)", "ERR_NO_ACTIVE_BT");
            TransportIo io = (mediaMgr != null) ? mediaMgr.getByKey(key) : null;
            if (io == null || !io.isOpen()) return ApiResult.fail("Connect LCP: 0 - BT non connecté", "ERR_BT_NOT_CONNECTED");
            DeliveryController dc = sessions.getOrCreate(key, node, from, io);
            if (dc == null) return ApiResult.fail("Connect LCP: 0 - Controller introuvable", "NO_CONTROLLER");
            String serial = sessions.getExpectedSerial(node);
            try { emitRegisterState(node, from, serial, key, null, false); } catch (Exception ignored) {}
            return dc.api_connectLcp();
        }
        return ApiResult.fail("Connect LCP: 0 - media invalide", "ERR_MEDIA_INVALID");
    }

    // =========================================================
    // CONNECT-AUTO
    // =========================================================
    @Override
    public ApiResult api_registerConnectAuto(String serialId, Integer lcrnode) {
        final int from = 255;
        final boolean hasNode = (lcrnode != null && lcrnode.intValue() != 0);
        final boolean hasSerial = (serialId != null && !serialId.trim().isEmpty());
        final int node = hasNode ? normNode(lcrnode) : normNode(lastNodeHint);

        if (!hasNode && !hasSerial) {
            // ok
        } else if (!hasNode && hasSerial) {
            JSONObject d = new JSONObject();
            try { d.put("serialId", serialId); } catch (Exception ignored) {}
            try { d.put("scanSuggested", true); } catch (Exception ignored) {}
            try { d.put("scanEndpoint", "/v1/register/scan-auto"); } catch (Exception ignored) {}
            try { d.put("reason", "serialId fourni sans lcrnode; connect-auto ne scanne pas 1..250"); } catch (Exception ignored) {}
            return ApiResult.fail("Registre non trouvé (lcrnode requis ou scan-auto)", "ERR_REGISTER_NOT_FOUND", d);
        }

        ArrayList<String> tried = new ArrayList<>();
        ArrayList<String> candidates = listCandidateTransportKeysForAutoConnect();

        for (String key : candidates) {
            if (key == null || key.trim().isEmpty()) continue;
            String transportKey = key.trim();
            TransportIo io = null;
            try {
                if (mediaMgr != null) {
                    try { mediaMgr.activateExclusive(transportKey, "REGISTER_CONNECT_AUTO"); } catch (Exception ignored) {}
                    io = mediaMgr.getByKey(transportKey);
                }
            } catch (Exception ignored) {}
            tried.add(transportKey);
            if (io == null || !safeIsOpen(io)) continue;

            String serial = probeSerial(io, node, from);
            if (serial == null || serial.trim().isEmpty()) continue;
            serial = serial.trim();

            if (hasSerial && !serialId.trim().equals(serial)) continue;

            DeliveryController dc = null;
            try { dc = sessions.getOrCreate(transportKey, node, from, io); } catch (Exception ignored) {}
            try { if (dc != null) dc.api_connectLcp(); } catch (Exception ignored) {}

            JSONObject snap = null;
            try {
                ApiResult tick = (dc != null) ? dc.api_tickSnapshot() : null;
                snap = (tick != null) ? tick.data : null;
            } catch (Exception ignored) {}

            double net = 0.0, gross = 0.0;
            int delCode = 0;
            String statusText = "?";
            try {
                if (snap != null) {
                    net = snap.optDouble("net", 0.0);
                    gross = snap.optDouble("gross", 0.0);
                    delCode = snap.optInt("delCode", 0);
                    statusText = snap.optString("statut", null);
                    if (statusText == null || statusText.trim().isEmpty()) statusText = snap.optString("status", null);
                    if (statusText == null || statusText.trim().isEmpty()) statusText = snap.optString("state", "?");
                }
            } catch (Exception ignored) {}

            try { emitRegisterState(node, from, serial, transportKey, snap, false); } catch (Exception ignored) {}

            JSONObject d = new JSONObject();
            safePut(d, "node", node);
            safePut(d, "from", from);
            safePut(d, "serial", serial);
            safePut(d, "serialId", serial);
            safePut(d, "transportKey", transportKey);
            safePut(d, "activeKey", MediaTransportManager.getActiveKeyStatic());
            safePut(d, "media", transportKey.toUpperCase(Locale.ROOT).startsWith("BT:") ? "bt" : "usb");
            safePut(d, "status", statusText);
            safePut(d, "statut", statusText);
            safePut(d, "delCode", delCode);
            safePut(d, "net", net);
            safePut(d, "gross", gross);
            safePut(d, "ui", "UPSERT_TAB");
            return ApiResult.ok("Registre trouvé sur " + (transportKey.toUpperCase(Locale.ROOT).startsWith("BT:") ? "BT" : "USB"), d);
        }

        JSONObject d = new JSONObject();
        safePut(d, "node", node);
        safePut(d, "from", from);
        if (hasSerial) safePut(d, "serialId", serialId.trim());
        if (hasNode) safePut(d, "lcrnode", node);
        safePut(d, "scanSuggested", true);
        safePut(d, "scanEndpoint", "/v1/register/scan-auto");
        safePut(d, "tried", new JSONArray(tried));
        return ApiResult.fail("Registre non trouvé sur BT ou USB", "ERR_REGISTER_NOT_FOUND", d);
    }

    private ArrayList<String> listCandidateTransportKeysForAutoConnect() {
        ArrayList<String> keys = new ArrayList<>();
        try {
            String activeKey = MediaTransportManager.getActiveKeyStatic();
            if (activeKey != null && !activeKey.trim().isEmpty()) {
                String k = activeKey.trim();
                TransportIo io = (mediaMgr != null) ? mediaMgr.getByKey(k) : null;
                if (io != null && safeIsOpen(io)) keys.add(k);
            }
        } catch (Exception ignored) {}
        try {
            boolean anyBtOpen = false;
            if (mediaMgr != null) {
                for (TransportSnapshot s : mediaMgr.listSnapshots()) {
                    if (s == null || s.key == null || !s.key.startsWith("BT:")) continue;
                    TransportIo io = mediaMgr.getByKey(s.key);
                    if (io != null && safeIsOpen(io)) { anyBtOpen = true; break; }
                }
            }
            if (!anyBtOpen) { try { api_btActivate(); } catch (Exception ignored2) {} }
        } catch (Exception ignored) {}
        try {
            if (mediaMgr != null) {
                for (TransportSnapshot s : mediaMgr.listSnapshots()) {
                    if (s == null || s.key == null) continue;
                    String k = s.key.trim();
                    if (k.isEmpty()) continue;
                    TransportIo io = mediaMgr.getByKey(k);
                    if (io == null || !safeIsOpen(io)) continue;
                    if (!keys.contains(k)) keys.add(k);
                }
            }
        } catch (Exception ignored) {}
        try {
            String usbKey = MediaTransportManager.KEY_USB;
            TransportIo usbIo = (mediaMgr != null) ? mediaMgr.getByKey(usbKey) : null;
            if (usbIo == null || !safeIsOpen(usbIo)) {
                try { api_openPingUsb(); } catch (Exception ignored2) {}
                usbIo = (mediaMgr != null) ? mediaMgr.getByKey(usbKey) : null;
            }
            if (usbIo != null && safeIsOpen(usbIo) && !keys.contains(usbKey)) keys.add(usbKey);
        } catch (Exception ignored) {}
        return keys;
    }

    private static boolean safeIsOpen(TransportIo io) {
        try { return (io != null && io.isOpen()); } catch (Exception ignored) { return false; }
    }

    private static void safePut(JSONObject o, String k, Object v) {
        try { if (o != null) o.put(k, v != null ? v : JSONObject.NULL); } catch (Exception ignored) {}
    }

    // =========================
    // USB global
    // =========================
    @Override
    public ApiResult api_scanUsb() {
        try {
            int n = (usbManager != null) ? usbManager.getDeviceList().size() : 0;
            JSONObject d = new JSONObject();
            d.put("usb_devices", n);
            return (n > 0)
                    ? ApiResult.ok("Scan USB: 1 - Registre détecté (USB device présent)", d)
                    : ApiResult.fail("Scan USB: 0 - Aucun périphérique USB détecté.", "ERR_MEDIA_NOT_PRESENT", d);
        } catch (Exception e) {
            return ApiResult.fail("Scan USB: 0 - Failed", "ERR_MEDIA_NOT_PRESENT");
        }
    }

    @Override
    public ApiResult api_openPingUsb() {
        try {
            UsbSerialPort existing = UsbSession.getPort();
            if (existing != null) {
                JSONObject d = new JSONObject();
                d.put("usb_ready", 1);
                return ApiResult.ok("Open/Ping USB: 1 - USB prêt (port ouvert)", d);
            }
            if (usbManager == null) return ApiResult.fail("Open/Ping USB: 0 - USB manager null.", "ERR_USB_OPEN_FAILED");
            Map<String, UsbDevice> devs = usbManager.getDeviceList();
            if (devs == null || devs.isEmpty()) {
                JSONObject d = new JSONObject();
                d.put("usb_devices", 0);
                return ApiResult.fail("Open/Ping USB: 0 - Aucun périphérique USB détecté.", "ERR_MEDIA_NOT_PRESENT", d);
            }
            Iterator<UsbDevice> it = devs.values().iterator();
            UsbDevice dev = it.hasNext() ? it.next() : null;
            if (dev == null) return ApiResult.fail("Open/Ping USB: 0 - Aucun périphérique USB détecté.", "ERR_MEDIA_NOT_PRESENT");
            if (!usbManager.hasPermission(dev)) {
                JSONObject d = new JSONObject();
                d.put("vid", dev.getVendorId());
                d.put("pid", dev.getProductId());
                d.put("deviceName", dev.getDeviceName());
                return ApiResult.fail("Open/Ping USB: 0 - Permission requise (accorde USB une fois via UI).", "ERR_USB_PERMISSION_REQUIRED", d);
            }
            UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(dev);
            if (driver == null || driver.getPorts() == null || driver.getPorts().isEmpty()) {
                JSONObject d = new JSONObject();
                d.put("vid", dev.getVendorId());
                d.put("pid", dev.getProductId());
                return ApiResult.fail("Open/Ping USB: 0 - Driver USB série introuvable.", "ERR_USB_DRIVER_NOT_FOUND", d);
            }
            UsbDeviceConnection conn = usbManager.openDevice(dev);
            if (conn == null) {
                JSONObject d = new JSONObject();
                d.put("vid", dev.getVendorId());
                d.put("pid", dev.getProductId());
                return ApiResult.fail("Open/Ping USB: 0 - openDevice() a échoué (conn null).", "ERR_USB_OPEN_FAILED", d);
            }
            UsbSerialPort port = driver.getPorts().get(0);
            try {
                port.open(conn);
                port.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                UsbSession.set(dev, port);
                try { if (mediaMgr != null) mediaMgr.onUsbReady(dev, port, "USB ready (API open-ping)"); } catch (Exception ignored) {}
                try {
                    Intent ready = new Intent(UsbReceiver.ACTION_USB_READY);
                    ready.setPackage(appCtx.getPackageName());
                    appCtx.sendBroadcast(ready);
                } catch (Exception ignored) {}
                JSONObject d = new JSONObject();
                d.put("usb_ready", 1);
                d.put("vid", dev.getVendorId());
                d.put("pid", dev.getProductId());
                d.put("deviceName", dev.getDeviceName());
                return ApiResult.ok("Open/Ping USB: 1 - USB prêt (port ouvert)", d);
            } catch (Exception openEx) {
                try { port.close(); } catch (Exception ignored) {}
                try { conn.close(); } catch (Exception ignored) {}
                JSONObject d = new JSONObject();
                d.put("detail", (openEx.getMessage() != null) ? openEx.getMessage() : openEx.getClass().getSimpleName());
                return ApiResult.fail("Open/Ping USB: 0 - Échec ouverture port.", "ERR_USB_OPEN_FAILED", d);
            }
        } catch (Exception e) {
            JSONObject d = new JSONObject();
            try { d.put("detail", (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName()); } catch (Exception ignored) {}
            return ApiResult.fail("Open/Ping USB: 0 - Failed", "ERR_USB_OPEN_FAILED", d);
        }
    }

    // =========================
    // Option 3: Média OFF gate
    // =========================
    private static final int MASK_DELIVERY_ACTIVE = 0x0008;

    private static final class MediaCtx {
        final String media;
        final String transportKey;
        final boolean mediaReady;
        final DeliveryController dc;
        final boolean deliveryActiveCache;
        MediaCtx(String media, String transportKey, boolean mediaReady, DeliveryController dc, boolean deliveryActiveCache) {
            this.media = media; this.transportKey = transportKey;
            this.mediaReady = mediaReady; this.dc = dc;
            this.deliveryActiveCache = deliveryActiveCache;
        }
    }

    private MediaCtx resolveMediaCtx(String media, String btMac, int node, int from) {
        String m = (media == null) ? "usb" : media.trim().toLowerCase(Locale.ROOT);
        if (m.isEmpty()) m = "usb";
        String tk;
        TransportIo io = null;
        boolean ready = false;
        DeliveryController dc = null;
        try {
            if ("bt".equals(m) || "bluetooth".equals(m)) {
                String key = resolveBtKeyOrActive(btMac);
                if (key == null) { tk = "BT:"; io = null; }
                else { tk = key; io = (mediaMgr != null) ? mediaMgr.getByKey(tk) : null; }
            } else {
                tk = MediaTransportManager.KEY_USB;
                io = (mediaMgr != null) ? mediaMgr.getByKey(tk) : null;
            }
        } catch (Exception e) {
            tk = ("bt".equals(m) || "bluetooth".equals(m))
                    ? MediaTransportManager.btKey((btMac == null) ? "" : btMac.trim())
                    : MediaTransportManager.KEY_USB;
        }
        try { ready = (io != null && io.isOpen()); } catch (Exception ignored) {}
        if (ready) { try { dc = sessions.getOrCreate(tk, node, from, io); } catch (Exception ignored) {} }
        if (dc == null) { try { dc = sessions.getController(tk, node); } catch (Exception ignored) {} }
        boolean delActiveCache = false;
        try {
            if (dc != null) {
                ApiResult r = dc.api_tickSnapshot();
                JSONObject d = (r != null) ? r.data : null;
                int delCode = (d != null) ? d.optInt("delCode", 0) : 0;
                delActiveCache = (delCode & MASK_DELIVERY_ACTIVE) != 0;
            }
        } catch (Exception ignored) {}
        return new MediaCtx(m, tk, ready, dc, delActiveCache);
    }

    private void persistApiMediaStatusOff(int node, String transportKey, String origin, String detail) {
        try {
            DeliveryLogStore store = sessions.getStore();
            if (store == null) return;
            String serial = sessions.getExpectedSerial(node);
            if (serial == null || serial.trim().isEmpty()) serial = "__API__";
            String ticketKey = "TAB-" + (node & 0xFF);
            JSONObject data = new JSONObject();
            data.put("event_type", "TAB_MEDIA_STATUS");
            data.put("state", "OFF");
            data.put("media", (transportKey != null && transportKey.toUpperCase(Locale.ROOT).startsWith("BT:")) ? "BT" : "USB");
            data.put("transport_key", transportKey);
            data.put("node", (node & 0xFF));
            data.put("origin", origin != null ? origin : "-");
            data.put("detail", detail != null ? detail : "-");
            data.put("ts_ms", System.currentTimeMillis());
            store.upsertSummaryAsync(serial, ticketKey, null, "TAB_OFF", DeliveryLogStore.SOURCE_API, null, null, null);
            final String sFinal = serial;
            store.openAttemptAsync(sFinal, ticketKey, DeliveryLogStore.SOURCE_API, null, attemptId -> {
                store.addEventAsync(attemptId, DeliveryLogStore.LEVEL_INFO, "TAB_MEDIA_STATUS", "API reports media OFF", data.toString());
                store.closeAttemptAsync(attemptId, "DONE", data.toString(), null);
            });
        } catch (Exception ignored) {}
    }

    private ApiResult option3_startGate(MediaCtx mc, int node, String opName) {
        try {
            JSONObject d = new JSONObject();
            d.put("media", mc.media);
            d.put("transportKey", mc.transportKey);
            d.put("connected", mc.mediaReady ? 1 : 0);
            d.put("deliveryActive_cache", mc.deliveryActiveCache ? 1 : 0);
            if (!mc.mediaReady) {
                if (!mc.deliveryActiveCache) {
                    String msg = opName + ": 0 - MEDIA OFF (delivery inactive)";
                    LogBus.api(node, msg);
                    persistApiMediaStatusOff(node, mc.transportKey, "API_START_BLOCKED", msg);
                    return ApiResult.fail(opName + ": 0 - Média OFF / not ready (START bloqué)", "ERR_MEDIA_NOT_READY", d);
                }
                String msg = opName + ": 1 - RECOVER (media OFF, delivery active)";
                LogBus.api(node, msg);
                d.put("mode", "RECOVER");
                d.put("pendingReconnect", 1);
                persistApiMediaStatusOff(node, mc.transportKey, "API_RECOVER", msg);
                return ApiResult.ok(opName + ": 1 - RECOVER (media OFF, livraison en cours)", d);
            }
            return null;
        } catch (Exception ignored) { return null; }
    }

    private static int normNode(Integer n) {
        if (n == null) return 250;
        int v = n;
        if (v < 1 || v > 250) return 250;
        return v;
    }

    private static int normFrom(Integer f) {
        if (f == null) return 255;
        int v = f;
        if (v < 0 || v > 255) return 255;
        return v;
    }

    private void notifyNodeSeenFull(int node, int from, String serial, String transportKey) {
        try {
            Intent i = new Intent(ACTION_NODE_SEEN);
            i.setPackage(appCtx.getPackageName());
            i.putExtra("node", node);
            i.putExtra("from", from);
            if (serial != null) i.putExtra("serial", serial);
            if (transportKey != null) i.putExtra("transport", transportKey);
            appCtx.sendBroadcast(i);
        } catch (Exception ignored) {}
    }

    private void emitRegisterState(int node, int from, String serialId, String transportKey,
                                   JSONObject snap, boolean alreadyConnected) {
        notifyNodeSeenFull(node, from, serialId, transportKey);
        if (snap == null) return;
        try {
            snap.put("serialId", serialId);
            snap.put("lcrnode", node);
            snap.put("transportKey", transportKey);
            snap.put("media", transportKey != null && transportKey.startsWith("BT:") ? "bt" : "usb");
            snap.put("alreadyConnected", alreadyConnected);
        } catch (Exception ignored) {}
    }

    private String probeSerial(TransportIo io, int nodeDec, int fromDec) {
        try {
            LcpLink tmp = new LcpLink(io, nodeDec, fromDec, true);
            byte[] b = tmp.opGetField(80, 500);
            if (b == null || b.length == 0) return null;
            return new String(b, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) { return null; }
    }

    private DeliveryController requireSession(Integer nodeDec, Integer fromDec) {
        UsbSerialPort port = UsbSession.getPort();
        if (port == null) return null;
        int n = normNode(nodeDec);
        int f = normFrom(fromDec);
        DeliveryController dc = sessions.getOrCreate(n, f, port);
        if (dc != null) {
            String serial = sessions.getExpectedSerial(n);
            try { emitRegisterState(n, f, serial, MediaTransportManager.KEY_USB, null, false); } catch (Exception ignored) {}
        }
        return dc;
    }

    private void recordJobId(ApiResult r, int node, int from) {
        recordJobId(r, node, from, null);
    }

    private void recordJobId(ApiResult r, int node, int from, String transportKey) {
        try {
            JSONObject j = r.toJson();
            JSONObject data = j.optJSONObject("data");
            if (data == null) return;
            String jobId = data.optString("jobId", "").trim();
            if (!jobId.isEmpty()) {
                jobToNode.put(jobId, node);
                jobToFrom.put(jobId, from);
                String tk = transportKey != null ? transportKey
                        : (mediaMgr != null ? MediaTransportManager.getActiveKeyStatic() : null);
                if (tk != null && !tk.trim().isEmpty()) jobToTransport.put(jobId, tk.trim());
            }
        } catch (Exception ignored) {}
    }

    private DeliveryController resolveJobController(String jobId, Integer nodeDec) {
        if (jobId == null || jobId.trim().isEmpty()) return null;

        Integer mappedNode = jobToNode.get(jobId);
        Integer mappedFrom = jobToFrom.get(jobId);
        String  mappedKey  = jobToTransport.get(jobId);

        int node = nodeDec != null ? normNode(nodeDec)
                : (mappedNode != null ? normNode(mappedNode) : lastNodeHint);
        int from = normFrom(mappedFrom != null ? mappedFrom : lastFromHint);

        // ✅ 1) Transport du job (média-aware)
        if (mappedKey != null && !mappedKey.trim().isEmpty() && mediaMgr != null) {
            try {
                TransportIo io = mediaMgr.getByKey(mappedKey);
                if (io != null && io.isOpen()) {
                    DeliveryController dc = sessions.getOrCreate(mappedKey, node, from, io);
                    if (dc != null) return dc;
                }
            } catch (Exception ignored) {}
        }

        // ✅ 2) Fallback — transport actif
        try {
            String activeKey = MediaTransportManager.getActiveKeyStatic();
            if (activeKey != null && mediaMgr != null) {
                TransportIo io = mediaMgr.getByKey(activeKey);
                if (io != null && io.isOpen()) {
                    DeliveryController dc = sessions.getOrCreate(activeKey, node, from, io);
                    if (dc != null) return dc;
                }
            }
        } catch (Exception ignored) {}

        // ✅ 3) Dernier fallback USB
        return requireSession(node, from);
    }

    @Override public ApiResult api_connectLcp() { return api_connectLcp(null, null); }
    @Override public ApiResult api_deliveryAlignA() { return api_deliveryAlignA(null, null); }
    @Override public ApiResult api_deliveryStartC(int p, double v) { return api_deliveryStartC(null, null, p, v); }
    @Override public ApiResult api_deliveryJobGet(String jobId) { return api_deliveryJobGet(jobId, null); }
    @Override public ApiResult api_deliveryOneShotStart(String n, int p, double v, String c) { return api_deliveryOneShotStart(null, null, n, p, v, c); }
    @Override public ApiResult api_deliveryContinue(String jobId) { return api_deliveryContinue(jobId, null); }
    @Override public ApiResult api_deliveryTerminate(String jobId) { return api_deliveryTerminate(jobId, null); }
    @Override public ApiResult api_ticketReprintCurrent() { return api_ticketReprintCurrent(null, null); }

    @Override
    public ApiResult api_registerValidate(String numero_livraison, Integer expected_lcrnode_dec,
                                         String expected_serial_id, Integer expected_product_number, String expected_compartment) {
        return api_registerValidate(numero_livraison, expected_lcrnode_dec, null, expected_serial_id, expected_product_number, expected_compartment);
    }

    @Override
    public ApiResult api_connectLcp(Integer lcrnode_dec, Integer from_dec) {
        DeliveryController dc = requireSession(normNode(lcrnode_dec), normFrom(from_dec));
        if (dc == null) return ApiResult.fail("Connect LCP: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
        return dc.api_connectLcp();
    }

    @Override
    public ApiResult api_deliveryAlignA(Integer lcrnode_dec, Integer from_dec) {
        DeliveryController dc = requireSession(normNode(lcrnode_dec), normFrom(from_dec));
        if (dc == null) return ApiResult.fail("Align A: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
        return dc.api_deliveryAlignA();
    }

    @Override
    public ApiResult api_deliveryStartC(Integer lcrnode_dec, Integer from_dec, int product1to16, double presetNet) {
        return api_deliveryStartC(lcrnode_dec, from_dec, product1to16, presetNet, "usb", null);
    }

    public ApiResult api_deliveryStartC(Integer lcrnode_dec, Integer from_dec, int product1to16, double presetNet, String media, String bt_mac) {
        int node = normNode(lcrnode_dec); int from = normFrom(from_dec);
        MediaCtx mc = resolveMediaCtx(media, bt_mac, node, from);
        ApiResult gate = option3_startGate(mc, node, "Delivery C");
        if (gate != null) return gate;
        if (mc.dc == null) return ApiResult.fail("Delivery C: 0 - Controller introuvable", "NO_CONTROLLER");
        ApiResult r = mc.dc.api_deliveryStartC(product1to16, presetNet);
        recordJobId(r, node, from, mc.transportKey);
        return r;
    }

    @Override
    public ApiResult api_deliveryOneShotStart(Integer lcrnode_dec, Integer from_dec, String numero_livraison, int product1to16, double presetNetL, String compartment) {
        return api_deliveryOneShotStart(lcrnode_dec, from_dec, numero_livraison, product1to16, presetNetL, compartment, "usb", null);
    }

    public ApiResult api_deliveryOneShotStart(Integer lcrnode_dec, Integer from_dec, String numero_livraison, int product1to16, double presetNetL, String compartment, String media, String bt_mac) {
        int node = normNode(lcrnode_dec); int from = normFrom(from_dec);
        MediaCtx mc = resolveMediaCtx(media, bt_mac, node, from);
        ApiResult gate = option3_startGate(mc, node, "OneShot");
        if (gate != null) return gate;
        if (mc.dc == null) return ApiResult.fail("OneShot: 0 - Controller introuvable", "NO_CONTROLLER");
        ApiResult r = mc.dc.api_deliveryOneShotStart(numero_livraison, product1to16, presetNetL, compartment);
        recordJobId(r, node, from, mc.transportKey);
        return r;
    }

    @Override
    public ApiResult api_deliveryContinue(String jobId, Integer lcrnode_dec) {
        DeliveryController dc = resolveJobController(jobId, lcrnode_dec);
        if (dc == null) return ApiResult.fail("Continue: 0 - Controller introuvable (node/job).", "NO_CONTROLLER");
        return dc.api_deliveryContinue(jobId);
    }

    @Override
    public ApiResult api_deliveryTerminate(String jobId, Integer lcrnode_dec) {
        DeliveryController dc = resolveJobController(jobId, lcrnode_dec);
        if (dc == null) return ApiResult.fail("Terminate: 0 - Controller introuvable (node/job).", "NO_CONTROLLER");
        return dc.api_deliveryTerminate(jobId);
    }

    @Override
    public ApiResult api_deliveryJobGet(String jobId, Integer lcrnode_dec) {
        DeliveryController dc = resolveJobController(jobId, lcrnode_dec);
        if (dc == null) return ApiResult.fail("Job: 0 - Controller introuvable (node/job).", "NO_CONTROLLER");
        return dc.api_deliveryJobGet(jobId);
    }

    @Override
    public ApiResult api_registerValidate(String numero_livraison, Integer expected_lcrnode_dec, Integer from_dec,
                                         String expected_serial_id, Integer expected_product_number, String expected_compartment) {
        int node = normNode(expected_lcrnode_dec); int from = normFrom(from_dec);
        DeliveryController dc = requireSession(node, from);
        if (dc == null) return ApiResult.fail("Validate: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
        return dc.api_registerValidate(numero_livraison, expected_lcrnode_dec, expected_serial_id, expected_product_number, expected_compartment);
    }

    @Override
    public ApiResult api_registerValidate(String numero_livraison, Integer expected_lcrnode_dec, Integer from_dec,
                                         String expected_serial_id, Integer expected_product_number, String expected_compartment,
                                         String media, String bt_mac) {
        int node = normNode(expected_lcrnode_dec); int from = normFrom(from_dec);
        String m = (media == null) ? "" : media.trim().toLowerCase(Locale.ROOT);
        if (m.isEmpty() || "auto".equals(m)) {
            String activeKey = MediaTransportManager.getActiveKeyStatic();
            m = (activeKey != null && activeKey.startsWith("BT:")) ? "bt" : "usb";
        }
        if ("usb".equals(m)) {
            DeliveryController dc = requireSession(node, from);
            if (dc == null) return ApiResult.fail("Validate: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
            ApiResult r = dc.api_registerValidate(numero_livraison, expected_lcrnode_dec, expected_serial_id, expected_product_number, expected_compartment);
            try { emitRegisterState(node, from, sessions.getExpectedSerial(node), MediaTransportManager.KEY_USB, (r != null ? r.data : null), false); } catch (Exception ignored) {}
            return r;
        }
        if ("bt".equals(m) || "bluetooth".equals(m)) {
            String key = resolveBtKeyOrActive(bt_mac);
            if (key == null) return ApiResult.fail("Validate: 0 - Aucun BT actif (appelle bt/activate)", "ERR_NO_ACTIVE_BT");
            TransportIo io = (mediaMgr != null) ? mediaMgr.getByKey(key) : null;
            if (io == null || !io.isOpen()) return ApiResult.fail("Validate: 0 - BT non connecté", "ERR_BT_NOT_CONNECTED");
            DeliveryController dc = sessions.getOrCreate(key, node, from, io);
            if (dc == null) return ApiResult.fail("Validate: 0 - Controller introuvable", "NO_CONTROLLER");
            ApiResult r = dc.api_registerValidate(numero_livraison, expected_lcrnode_dec, expected_serial_id, expected_product_number, expected_compartment);
            try { emitRegisterState(node, from, sessions.getExpectedSerial(node), key, (r != null ? r.data : null), false); } catch (Exception ignored) {}
            return r;
        }
        return ApiResult.fail("Validate: 0 - media invalide", "ERR_MEDIA_INVALID");
    }

    @Override
    public ApiResult api_ticketReprintCurrent(Integer lcrnode_dec, Integer from_dec) {
        return api_ticketReprintCurrent(lcrnode_dec, from_dec, null, null);
    }

    @Override
    public ApiResult api_ticketReprintCurrent(Integer lcrnode_dec, Integer from_dec, String media, String bt_mac) {
        int node = normNode(lcrnode_dec); int from = normFrom(from_dec);
        String m = (media == null) ? "" : media.trim().toLowerCase(Locale.ROOT);
        if (m.isEmpty() || "auto".equals(m)) {
            String activeKey = MediaTransportManager.getActiveKeyStatic();
            m = (activeKey != null && activeKey.startsWith("BT:")) ? "bt" : "usb";
        }
        if ("usb".equals(m)) {
            DeliveryController dc = requireSession(node, from);
            if (dc == null) return ApiResult.fail("Reprint: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
            return dc.api_ticketReprintCurrent();
        }
        if ("bt".equals(m) || "bluetooth".equals(m)) {
            String key = resolveBtKeyOrActive(bt_mac);
            if (key == null) return ApiResult.fail("Reprint: 0 - Aucun BT actif (appelle bt/activate)", "ERR_NO_ACTIVE_BT");
            TransportIo io = (mediaMgr != null) ? mediaMgr.getByKey(key) : null;
            if (io == null || !io.isOpen()) return ApiResult.fail("Reprint: 0 - BT non connecté", "ERR_BT_NOT_CONNECTED");
            DeliveryController dc = sessions.getOrCreate(key, node, from, io);
            if (dc == null) return ApiResult.fail("Reprint: 0 - Controller introuvable", "NO_CONTROLLER");
            return dc.api_ticketReprintCurrent();
        }
        return ApiResult.fail("Reprint: 0 - media invalide", "ERR_MEDIA_INVALID");
    }

    @Override
    public ApiResult api_dbDump() {
        try {
            String name = "lcr_delivery_" + DeliveryApiFacadeImpl.utcStampPublic() + ".json";
            boolean ok = sessions.getStore().dumpJsonToDownloads(appCtx, name);
            if (!ok) return ApiResult.fail("DB Dump: 0 - Failed", "DB_DUMP_FAIL");
            JSONObject d = new JSONObject();
            d.put("fileName", name);
            return ApiResult.ok("DB Dump: 1 - OK", d);
        } catch (Exception e) {
            JSONObject d = new JSONObject();
            try { d.put("detail", (e.getMessage() != null) ? e.getMessage() : ""); } catch (Exception ignored) {}
            return ApiResult.fail("DB Dump: 0 - Failed", "DB_DUMP_FAIL", d);
        }
    }

    @Override
    public ApiResult api_tickWait(Integer lcrnode_dec, Long since_seq, Integer wait_ms) {
        int node = normNode(lcrnode_dec);
        long since = (since_seq != null) ? since_seq : 0L;
        long wait = (wait_ms != null) ? wait_ms.longValue() : 25_000L;
        DeliveryController dc = requireSession(node, lastFromHint);
        if (dc == null) return ApiResult.fail("Tick: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
        return dc.api_tickWait(since, wait);
    }

    @Override
    public ApiResult api_deliveryStatusB(Integer lcrnode_dec, Integer from_dec, String media, String bt_mac) {
        int node = normNode(lcrnode_dec);
        int from = normFrom(from_dec);
        String m = (media == null) ? "usb" : media.trim().toLowerCase(Locale.ROOT);
        if (m.isEmpty()) m = "usb";
        if ("bt".equals(m) || "bluetooth".equals(m)) {
            String key = resolveBtKeyOrActive(bt_mac);
            if (key == null) return ApiResult.fail("StatusB: 0 - Aucun BT actif", "ERR_NO_ACTIVE_BT");
            TransportIo io = (mediaMgr != null) ? mediaMgr.getByKey(key) : null;
            if (io == null || !io.isOpen()) return ApiResult.fail("StatusB: 0 - BT non connecté", "ERR_BT_NOT_CONNECTED");
            DeliveryController dc = sessions.getOrCreate(key, node, from, io);
            if (dc == null) return ApiResult.fail("StatusB: 0 - Controller introuvable", "NO_CONTROLLER");
            return dc.api_deliveryStatusB();
        }
        DeliveryController dc = requireSession(node, from);
        if (dc == null) return ApiResult.fail("StatusB: 0 - USB non prêt", "ERR_USB_PORT_NOT_READY");
        return dc.api_deliveryStatusB();
    }
    
    @Override
    public ApiResult api_printerStatus(Integer lcrnode_dec, Integer from_dec, String media, String bt_mac) {
        int node = normNode(lcrnode_dec);
        int from = normFrom(from_dec);
        String m = (media == null) ? "usb" : media.trim().toLowerCase(Locale.ROOT);
        if (m.isEmpty()) m = "usb";
        if ("bt".equals(m) || "bluetooth".equals(m)) {
            String key = resolveBtKeyOrActive(bt_mac);
            if (key == null) return ApiResult.fail("Printer: 0 - Aucun BT actif", "ERR_NO_ACTIVE_BT");
            TransportIo io = (mediaMgr != null) ? mediaMgr.getByKey(key) : null;
            if (io == null || !io.isOpen()) return ApiResult.fail("Printer: 0 - BT non connecté", "ERR_BT_NOT_CONNECTED");
            DeliveryController dc = sessions.getOrCreate(key, node, from, io);
            if (dc == null) return ApiResult.fail("Printer: 0 - Controller introuvable", "NO_CONTROLLER");
            return dc.api_printerStatus();
        }
        DeliveryController dc = requireSession(node, from);
        if (dc == null) return ApiResult.fail("Printer: 0 - USB non prêt", "ERR_USB_PORT_NOT_READY");
        return dc.api_printerStatus();
    }    
    
    @Override
    public ApiResult api_deliveryAlignA(Integer lcrnode_dec, Integer from_dec, String media, String bt_mac) {
        int node = normNode(lcrnode_dec); int from = normFrom(from_dec);
        String m = (media == null) ? "usb" : media.trim().toLowerCase(Locale.ROOT);
        if (m.isEmpty()) m = "usb";
        if ("usb".equals(m)) {
            DeliveryController dc = requireSession(node, from);
            if (dc == null) return ApiResult.fail("Align A: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
            return dc.api_deliveryAlignA();
        }
        if ("bt".equals(m) || "bluetooth".equals(m)) {
            String key = resolveBtKeyOrActive(bt_mac);
            if (key == null) return ApiResult.fail("Align A: 0 - Aucun BT actif (appelle bt/activate)", "ERR_NO_ACTIVE_BT");
            TransportIo io = (mediaMgr != null) ? mediaMgr.getByKey(key) : null;
            if (io == null || !io.isOpen()) return ApiResult.fail("Align A: 0 - BT non connecté", "ERR_BT_NOT_CONNECTED");
            DeliveryController dc = sessions.getOrCreate(key, node, from, io);
            if (dc == null) return ApiResult.fail("Align A: 0 - BT non prêt.", "ERR_BT_NOT_CONNECTED");
            try { emitRegisterState(node, from, sessions.getExpectedSerial(node), key, null, false); } catch (Exception ignored) {}
            return dc.api_deliveryAlignA();
        }
        return ApiResult.fail("Align A: 0 - media invalide", "ERR_MEDIA_INVALID");
    }

    // =========================================================
    // ✅ Truck Profile
    // =========================================================

    @Override
    public ApiResult api_profileSave(String truck_id, String bt_mac, String bt_name,
                                      Integer lcrnode_dec, String serial_id,
                                      Integer default_product, String compartments_json,
                                      String notes) {
        try {
            if (truck_id == null || truck_id.trim().isEmpty())
                return ApiResult.fail("Profile: 0 - truck_id requis", "ERR_TRUCK_ID_REQUIRED");
            org.json.JSONArray comp = null;
            if (compartments_json != null && !compartments_json.trim().isEmpty()) {
                try { comp = new org.json.JSONArray(compartments_json); } catch (Exception ignored) {}
            }
            JSONObject result = truckProfileStore.saveProfile(
                    truck_id.trim(), bt_mac, bt_name, lcrnode_dec,
                    serial_id, default_product, comp, notes);
            if (result == null) return ApiResult.fail("Profile: 0 - Erreur sauvegarde", "ERR_PROFILE_SAVE");
            return ApiResult.ok("Profile: 1 - Sauvegardé", result);
        } catch (Exception e) {
            return ApiResult.fail("Profile: 0 - " + e.getMessage(), "ERR_PROFILE_SAVE");
        }
    }

    @Override
    public ApiResult api_profileList() {
        try {
            org.json.JSONArray list = truckProfileStore.listProfiles();
            JSONObject d = new JSONObject();
            d.put("profiles", list);
            d.put("count", list.length());
            return ApiResult.ok("Profile: 1 - " + list.length() + " profil(s)", d);
        } catch (Exception e) {
            return ApiResult.fail("Profile: 0 - " + e.getMessage(), "ERR_PROFILE_LIST");
        }
    }

    @Override
    public ApiResult api_profileActive() {
        try {
            JSONObject profile = truckProfileStore.getActiveProfile();
            if (profile == null) return ApiResult.fail("Profile: 0 - Aucun profil actif", "ERR_NO_ACTIVE_PROFILE");
            return ApiResult.ok("Profile: 1 - Profil actif", profile);
        } catch (Exception e) {
            return ApiResult.fail("Profile: 0 - " + e.getMessage(), "ERR_PROFILE_ACTIVE");
        }
    }

    @Override
    public ApiResult api_profileActivate(String truck_id) {
        try {
            if (truck_id == null || truck_id.trim().isEmpty())
                return ApiResult.fail("Profile: 0 - truck_id requis", "ERR_TRUCK_ID_REQUIRED");
            JSONObject profile = truckProfileStore.activateProfile(truck_id.trim());
            if (profile == null)
                return ApiResult.fail("Profile: 0 - Profil introuvable: " + truck_id, "ERR_PROFILE_NOT_FOUND");
            // Auto-connect BT si mac disponible
            String btMac = profile.optString("bt_mac", null);
            String btName = profile.optString("bt_name", null);
            JSONObject connectResult = null;
            if (btMac != null && !btMac.trim().isEmpty()) {
                try {
                    ApiResult btResult = api_btActivate(btMac, btName);
                    if (btResult != null) connectResult = btResult.toJson();
                } catch (Exception ignored) {}
            }
            JSONObject d = new JSONObject();
            d.put("truck_id",      truck_id);
            d.put("profile",       profile);
            d.put("bt_connected",  connectResult != null ? 1 : 0);
            d.put("bt_result",     connectResult);
            return ApiResult.ok("Profile: 1 - Activé: " + truck_id, d);
        } catch (Exception e) {
            return ApiResult.fail("Profile: 0 - " + e.getMessage(), "ERR_PROFILE_ACTIVATE");
        }
    }

    @Override
    public ApiResult api_profileValidate(String truck_id, String actual_bt_mac,
                                          String actual_bt_name, Integer actual_lcrnode,
                                          String actual_serial_id, String delivery_uid) {
        try {
            if (truck_id == null || truck_id.trim().isEmpty())
                return ApiResult.fail("Profile: 0 - truck_id requis", "ERR_TRUCK_ID_REQUIRED");
            JSONObject result = truckProfileStore.validateAndDetectDrift(
                    truck_id.trim(), actual_bt_mac, actual_bt_name,
                    actual_lcrnode, actual_serial_id, delivery_uid);
            if (result == null)
                return ApiResult.fail("Profile: 0 - Erreur validation", "ERR_PROFILE_VALIDATE");
            boolean hasDrift = result.optInt("drift_count", 0) > 0;
            String msg = hasDrift
                    ? "Profile: 1 - " + result.optInt("drift_count") + " divergence(s) détectée(s)"
                    : "Profile: 1 - OK aucune divergence";
            return ApiResult.ok(msg, result);
        } catch (Exception e) {
            return ApiResult.fail("Profile: 0 - " + e.getMessage(), "ERR_PROFILE_VALIDATE");
        }
    }

    @Override
    public ApiResult api_profileDrift(String truck_id, boolean only_unacked) {
        try {
            org.json.JSONArray drifts = truckProfileStore.getDrifts(truck_id, only_unacked);
            JSONObject d = new JSONObject();
            d.put("truck_id",    truck_id);
            d.put("drifts",      drifts);
            d.put("count",       drifts.length());
            d.put("only_unacked", only_unacked ? 1 : 0);
            return ApiResult.ok("Profile Drift: 1 - " + drifts.length() + " divergence(s)", d);
        } catch (Exception e) {
            return ApiResult.fail("Profile Drift: 0 - " + e.getMessage(), "ERR_PROFILE_DRIFT");
        }
    }

    @Override
    public ApiResult api_profileAcknowledge(String truck_id) {
        try {
            boolean ok = truckProfileStore.acknowledgeDrift(truck_id);
            JSONObject d = new JSONObject();
            d.put("truck_id",     truck_id);
            d.put("acknowledged", ok ? 1 : 0);
            return ok
                    ? ApiResult.ok("Profile: 1 - Divergences acquittées", d)
                    : ApiResult.fail("Profile: 0 - Erreur acquittement", "ERR_PROFILE_ACK", d);
        } catch (Exception e) {
            return ApiResult.fail("Profile: 0 - " + e.getMessage(), "ERR_PROFILE_ACK");
        }
    }

    @Override
    public ApiResult api_profileDelete(String truck_id) {
        try {
            if (truck_id == null || truck_id.trim().isEmpty())
                return ApiResult.fail("Profile: 0 - truck_id requis", "ERR_TRUCK_ID_REQUIRED");
            boolean ok = truckProfileStore.deleteProfile(truck_id.trim());
            JSONObject d = new JSONObject();
            d.put("truck_id", truck_id);
            d.put("deleted",  ok ? 1 : 0);
            return ok
                    ? ApiResult.ok("Profile: 1 - Supprimé: " + truck_id, d)
                    : ApiResult.fail("Profile: 0 - Profil introuvable: " + truck_id, "ERR_PROFILE_NOT_FOUND", d);
        } catch (Exception e) {
            return ApiResult.fail("Profile: 0 - " + e.getMessage(), "ERR_PROFILE_DELETE");
        }
    }
}
