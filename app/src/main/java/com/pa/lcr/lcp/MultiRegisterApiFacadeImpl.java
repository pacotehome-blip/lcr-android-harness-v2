
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
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import com.pa.lcrdemo.UsbReceiver;
import com.pa.lcrdemo.UsbSession; // ✅ adapte si ton UsbSession est ailleurs

import com.pa.lcr.lcp.transport.MediaTransportManager;
import com.pa.lcr.lcp.transport.TransportIo;
import com.pa.lcr.lcp.storage.DeliveryLogStore;
import com.pa.lcr.lcp.log.LogBus;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.pa.lcr.lcp.transport.TransportSnapshot;
import com.pa.lcr.lcp.transport.TransportStatus;


public final class MultiRegisterApiFacadeImpl implements ApiFacade {

    // Auto-tab: broadcast vers UI pour créer un tab si absent (no focus)
    private static final String ACTION_NODE_SEEN = "com.pa.lcrdemo.ACTION_NODE_SEEN";

    private final Context appCtx;
    private final UsbManager usbManager;
    private final RegisterSessionManager sessions;

    // ✅ Option A: manager runtime multi-transport (USB/BT)
    private final MediaTransportManager mediaMgr;

    // jobId -> node (fallback)
    private final Map<String, Integer> jobToNode = new ConcurrentHashMap<>();
    // ✅ NEW: jobId -> from (best-effort)
    private final Map<String, Integer> jobToFrom = new ConcurrentHashMap<>();
    // ✅ NEW: dernier node/from observé (hint robuste)
    private volatile int lastNodeHint = 250;
    private volatile int lastFromHint = 255;

    

    // =========================================================
    // ✅ BT autonome: bonded -> RFCOMM/SPP -> publish TransportIo -> activateExclusive
    // =========================================================
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private BluetoothAdapter btAdapterSafe() {
        try { return BluetoothAdapter.getDefaultAdapter(); }
        catch (Exception ignored) { return null; }
    }

    /** Liste les BT pairés (bonded) triés par MAC (ordre stable). */
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

    /** Retourne la clé BT:... à utiliser : bt_mac si fourni, sinon activeKey (si BT). */
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

        // ✅ Option A init
        this.mediaMgr = MediaTransportManager.get(this.appCtx);
    }

    // =========================
    // ✅ Option A: Media check (USB/BT) - diagnostic simple
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

                if (p != null) {
                    return ApiResult.ok("MediaCheck: 1 - USB connecté", d);
                }
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
    // ✅ BT LIST — bonded + runtime (diagnostic)
    // =========================================================
    @Override
    public ApiResult api_btList() {
        if (mediaMgr == null) {
            return ApiResult.fail("MTM null", "ERR_MEDIA_MTM_NULL");
        }
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
	// ✅ BT ACTIVATE — délégation vers MediaTransportManager
	// (même logique que ApiFacadeImpl, mais intégrée au multi-registre)
	// =========================================================
	@Override
	public ApiResult api_btActivate() {
		if (mediaMgr == null) {
			return ApiResult.fail("MTM null", "ERR_MEDIA_MTM_NULL");
		}

		// 0) Si un BT actif est déjà open, activer seulement
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

		// 1) Bonded -> connect SPP -> publish -> activate
		ArrayList<BluetoothDevice> bonded = listBondedSorted();
		if (bonded.isEmpty()) {
			return ApiResult.fail("BT activate: 0 - Aucun BT pairé", "ERR_NO_BONDED_BT");
		}

		JSONObject lastErr = null;
		for (BluetoothDevice dev : bonded) {
			if (dev == null) continue;
			String mac = dev.getAddress();
			if (mac == null || mac.trim().isEmpty()) continue;
			String key = MediaTransportManager.btKey(mac);

			// déjà ouvert en runtime ?
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

				// publier TransportIo dans MediaTransportManager
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

		if (lastErr != null) {
			return ApiResult.fail("BT activate: 0 - Connexion échouée", "ERR_BT_CONNECT_FAILED", lastErr);
		}
		return ApiResult.fail("BT activate: 0 - Connexion échouée", "ERR_BT_CONNECT_FAILED");
	}


    // =========================================================
    // ✅ Media Auto-Connect (API) — VERSION FINALE JAVA
    // - Amorçage BT inclus
    // - Alignée EXACTEMENT avec le comportement du UI
    // - AUCUNE modification du UI
    // =========================================================
    
    public ApiResult api_mediaAutoConnect(Integer lcrnode_dec, Integer from_dec) {
        int node = normNode(lcrnode_dec);
        int from = normFrom(from_dec);

        // -----------------------------------------------------
        // A) PRIORITÉ ABSOLUE : média déjà actif (BT ou USB)
        //    → si TransportIo ouvert, on le prend TEL QUEL
        // -----------------------------------------------------
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

        // -----------------------------------------------------
        // B) AMORÇAGE BT : si aucun BT n'est connu du runtime
        //    → forcer UNE activation BT
        // -----------------------------------------------------
        try {
            boolean hasBtRuntime = false;
            if (mediaMgr != null) {
                for (TransportSnapshot s : mediaMgr.listSnapshots()) {
                    if (s != null && s.key != null && s.key.startsWith("BT:")) {
                        hasBtRuntime = true;
                        break;
                    }
                }
                if (!hasBtRuntime) {
                    // Amorçage explicite comme le UI
                    api_btActivate();
                }
            }
        } catch (Exception ignored) {}

        // -----------------------------------------------------
        // C) SCAN DE TOUS LES BT PAIRÉS (ordre APK)
        //    → activer → tester → LCP
        // -----------------------------------------------------
        if (mediaMgr != null) {
            for (TransportSnapshot snap : mediaMgr.listSnapshots()) {
                if (snap == null) continue;
                if (snap.key == null || !snap.key.startsWith("BT:")) continue;

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

        // -----------------------------------------------------
        // D) FALLBACK USB (par défaut)
        // -----------------------------------------------------
        try {
        TransportIo io = (mediaMgr != null)
                ? mediaMgr.getByKey(MediaTransportManager.KEY_USB)
                : null;

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
            TransportIo io2 = (mediaMgr != null)
                    ? mediaMgr.getByKey(MediaTransportManager.KEY_USB)
                    : null;
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

            // -----------------------------------------------------
            // E) RIEN DE DISPONIBLE
            // -----------------------------------------------------
            return ApiResult.fail("Media auto-connect: 0 - No media available", "ERR_NO_MEDIA_AVAILABLE");
        }


	// =========================================================
	// ✅ LCP CONNECT — MEDIA‑AWARE (USB / BT)
	// =========================================================
	@Override
	public ApiResult api_connectLcp(
			Integer lcrnode_dec,
			Integer from_dec,
			String media,
			String bt_mac) {

		int node = normNode(lcrnode_dec);
		int from = normFrom(from_dec);
// pac		String m = (media == null) ? "usb" : media.trim().toLowerCase(Locale.ROOT);
//		if (m.isEmpty()) m = "usb";

        String m = (media == null) ? null : media.trim().toLowerCase(Locale.ROOT);
        if (m == null || m.isEmpty()) {
            String activeKey = MediaTransportManager.getActiveKeyStatic();
            if (activeKey != null && activeKey.startsWith("BT:")) {
                m = "bt";
            } else {
                m = "usb";
            }
        }

		// --- USB (comportement legacy inchangé)
		if ("usb".equals(m)) {
			DeliveryController dc = requireSession(node, from);
			if (dc == null)
				return ApiResult.fail("Connect LCP: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
			return dc.api_connectLcp();
		}

		// --- BT
		if ("bt".equals(m) || "bluetooth".equals(m)) {
			String key = resolveBtKeyOrActive(bt_mac);
			if (key == null) {
				return ApiResult.fail("Connect LCP: 0 - Aucun BT actif (appelle bt/activate)", "ERR_NO_ACTIVE_BT");
			}

			
			TransportIo io = (mediaMgr != null) ? mediaMgr.getByKey(key) : null;
			if (io == null || !io.isOpen()) {
				return ApiResult.fail("Connect LCP: 0 - BT non connecté", "ERR_BT_NOT_CONNECTED");
			}

			DeliveryController dc = sessions.getOrCreate(key, node, from, io);
			if (dc == null) {
				return ApiResult.fail("Connect LCP: 0 - Controller introuvable", "NO_CONTROLLER");
			}

            String serial = sessions.getExpectedSerial(node);
            String transportKey = key;
            notifyNodeSeenFull(node, from, serial, transportKey);

			return dc.api_connectLcp();
		}

		return ApiResult.fail("Connect LCP: 0 - media invalide", "ERR_MEDIA_INVALID");
	}
	// =========================================================
	// ✅ One Stop connect register (USB / BT)
	// =========================================================

    public ApiResult api_registerConnectAuto(String serialId, Integer lcrnode) {
        // 1. Déterminer le média actif
        String activeKey = MediaTransportManager.getActiveKeyStatic();
        String mediaKey = null;
        if (activeKey != null && activeKey.startsWith("BT:")) {
            mediaKey = activeKey;
        } else {
            mediaKey = MediaTransportManager.KEY_USB;
        }
        TransportIo io = mediaMgr.getByKey(mediaKey);
        if (io == null || !io.isOpen()) {
            return ApiResult.fail("Aucun média actif (BT ou USB)", "ERR_MEDIA_NOT_READY");
        }

        // 2. Essayer de connecter le registre sur le média actif
        for (int node = 1; node <= 250; node++) {
            String serial = probeSerial(io, node, 255);
            boolean matchSerial = serialId != null && serial != null && serial.equals(serialId);
            boolean matchNode = lcrnode != null && node == lcrnode;
            if (matchSerial || matchNode) {
                DeliveryController dc = sessions.getOrCreate(mediaKey, node, 255, io);
                ApiResult tick = (dc != null) ? dc.api_tickSnapshot() : null;
                double net = (tick != null && tick.data != null) ? tick.data.optDouble("net", 0.0) : 0.0;
                double gross = (tick != null && tick.data != null) ? tick.data.optDouble("gross", 0.0) : 0.0;
                String statut = (tick != null && tick.data != null) ? tick.data.optString("statut", "?") : "?";
                // Broadcast pour tab UI
                notifyNodeSeenFull(node, 255, serial, mediaKey);
                JSONObject d = new JSONObject();
                d.put("node", node);
                d.put("serial", serial);
                d.put("media", mediaKey.startsWith("BT:") ? "bt" : "usb");
                d.put("statut", statut);
                d.put("net", net);
                d.put("gross", gross);
                return ApiResult.ok("Registre trouvé sur " + (mediaKey.startsWith("BT:") ? "BT" : "USB"), d);
            }
        }

        // 3. Si non trouvé, scanner l’autre média
        String otherKey = mediaKey.startsWith("BT:") ? MediaTransportManager.KEY_USB : resolveBtKeyOrActive(null);
        TransportIo ioOther = mediaMgr.getByKey(otherKey);
        if (ioOther != null && ioOther.isOpen()) {
            for (int node = 1; node <= 250; node++) {
                String serial = probeSerial(ioOther, node, 255);
                boolean matchSerial = serialId != null && serial != null && serial.equals(serialId);
                boolean matchNode = lcrnode != null && node == lcrnode;
                if (matchSerial || matchNode) {
                    DeliveryController dc = sessions.getOrCreate(otherKey, node, 255, ioOther);
                    ApiResult tick = (dc != null) ? dc.api_tickSnapshot() : null;
                    double net = (tick != null && tick.data != null) ? tick.data.optDouble("net", 0.0) : 0.0;
                    double gross = (tick != null && tick.data != null) ? tick.data.optDouble("gross", 0.0) : 0.0;
                    String statut = (tick != null && tick.data != null) ? tick.data.optString("statut", "?") : "?";
                    notifyNodeSeenFull(node, 255, serial, otherKey);
                    JSONObject d = new JSONObject();
                    d.put("node", node);
                    d.put("serial", serial);
                    d.put("media", otherKey.startsWith("BT:") ? "bt" : "usb");
                    d.put("statut", statut);
                    d.put("net", net);
                    d.put("gross", gross);
                    return ApiResult.ok("Registre trouvé sur " + (otherKey.startsWith("BT:") ? "BT" : "USB"), d);
                }
            }
        }

        // 4. Rien trouvé
        JSONObject d = new JSONObject();
        d.put("scanSuggested", true);
        return ApiResult.fail("Registre non trouvé sur BT ou USB", "ERR_REGISTER_NOT_FOUND", d);
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

    /**
     * Open/Ping USB:
     * - Si UsbSession port déjà prêt -> OK
     * - Sinon: ouvre port série, setParameters, UsbSession.set(dev, port) -> OK
     * - Broadcast UsbReceiver.ACTION_USB_READY si succès (tabs auto-attach).
     */
    @Override
    public ApiResult api_openPingUsb() {
        try {
            // 0) Déjà prêt ?
            UsbSerialPort existing = UsbSession.getPort();
            if (existing != null) {
                JSONObject d = new JSONObject();
                d.put("usb_ready", 1);
                return ApiResult.ok("Open/Ping USB: 1 - USB prêt (port ouvert)", d);
            }

            if (usbManager == null) {
                return ApiResult.fail("Open/Ping USB: 0 - USB manager null.", "ERR_USB_OPEN_FAILED");
            }

            // 1) Trouver un device
            Map<String, UsbDevice> devs = usbManager.getDeviceList();
            if (devs == null || devs.isEmpty()) {
                JSONObject d = new JSONObject();
                d.put("usb_devices", 0);
                return ApiResult.fail("Open/Ping USB: 0 - Aucun périphérique USB détecté.", "ERR_MEDIA_NOT_PRESENT", d);
            }

            Iterator<UsbDevice> it = devs.values().iterator();
            UsbDevice dev = it.hasNext() ? it.next() : null;
            if (dev == null) {
                return ApiResult.fail("Open/Ping USB: 0 - Aucun périphérique USB détecté.", "ERR_MEDIA_NOT_PRESENT");
            }

            // 2) Permission ?
            if (!usbManager.hasPermission(dev)) {
                JSONObject d = new JSONObject();
                d.put("vid", dev.getVendorId());
                d.put("pid", dev.getProductId());
                d.put("deviceName", dev.getDeviceName());
                return ApiResult.fail(
                        "Open/Ping USB: 0 - Permission requise (accorde USB une fois via UI).",
                        "ERR_USB_PERMISSION_REQUIRED",
                        d
                );
            }

            // 3) Driver ?
            UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(dev);
            if (driver == null || driver.getPorts() == null || driver.getPorts().isEmpty()) {
                JSONObject d = new JSONObject();
                d.put("vid", dev.getVendorId());
                d.put("pid", dev.getProductId());
                return ApiResult.fail("Open/Ping USB: 0 - Driver USB série introuvable.",
                        "ERR_USB_DRIVER_NOT_FOUND", d);
            }

            // 4) Ouvrir connexion + port
            UsbDeviceConnection conn = usbManager.openDevice(dev);
            if (conn == null) {
                JSONObject d = new JSONObject();
                d.put("vid", dev.getVendorId());
                d.put("pid", dev.getProductId());
                return ApiResult.fail("Open/Ping USB: 0 - openDevice() a échoué (conn null).",
                        "ERR_USB_OPEN_FAILED", d);
            }

            UsbSerialPort port = driver.getPorts().get(0);
            try {
                port.open(conn);
                port.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

                // publier la session globale
                UsbSession.set(dev, port);

                // ✅ Publish USB transport to MediaTransportManager
                try {
                    if (mediaMgr != null) {
                        mediaMgr.onUsbReady(dev, port, "USB ready (API open-ping)");
                    }
                } catch (Exception ignored) {}

                // signaler à l’UI que l’USB est prêt (tabs auto-attach)
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
                return ApiResult.fail("Open/Ping USB: 0 - Échec ouverture port.",
                        "ERR_USB_OPEN_FAILED", d);
            }

        } catch (Exception e) {
            JSONObject d = new JSONObject();
            try { d.put("detail", (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName()); } catch (Exception ignored) {}
            return ApiResult.fail("Open/Ping USB: 0 - Failed", "ERR_USB_OPEN_FAILED", d);
        }
    }

    // =========================
    // ✅ Option 3: Média OFF — bloquer START seulement si DELIVERY_ACTIVE=0
    // =========================
    private static final int MASK_DELIVERY_ACTIVE = 0x0008; // 0x28

    private static final class MediaCtx {
        final String media;          // usb/bt
        final String transportKey;   // USB or BT:xx
        final boolean mediaReady;    // TransportIo open?
        final DeliveryController dc; // may be null
        final boolean deliveryActiveCache; // best-effort from tickSnapshot cache
        MediaCtx(String media, String transportKey, boolean mediaReady, DeliveryController dc, boolean deliveryActiveCache) {
            this.media = media;
            this.transportKey = transportKey;
            this.mediaReady = mediaReady;
            this.dc = dc;
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
                if (key == null) {
                    tk = "BT:";
                    io = null;
                } else {
                    tk = key;
                    io = (mediaMgr != null) ? mediaMgr.getByKey(tk) : null;
                }
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

        if (ready) {
            try { dc = sessions.getOrCreate(tk, node, from, io); } catch (Exception ignored) {}
        }

        if (dc == null) {
            try { dc = sessions.getController(tk, node); } catch (Exception ignored) {}
        }

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
            final String tkFinal = ticketKey;
            store.openAttemptAsync(sFinal, tkFinal, DeliveryLogStore.SOURCE_API, null, attemptId -> {
                store.addEventAsync(attemptId, DeliveryLogStore.LEVEL_INFO,
                        "TAB_MEDIA_STATUS", "API reports media OFF", data.toString());
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
        } catch (Exception ignored) {
            return null;
        }
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

    private void notifyNodeSeen(int node, int from) {
        lastNodeHint = node;
        lastFromHint = from;
        try {
            Intent i = new Intent(ACTION_NODE_SEEN);
            i.setPackage(appCtx.getPackageName());
            i.putExtra("node", node);
            i.putExtra("from", from);
            appCtx.sendBroadcast(i);
        } catch (Exception ignored) {}
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


    private String probeSerial(TransportIo io, int nodeDec, int fromDec) {
        try {
            LcpLink tmp = new LcpLink(io, nodeDec, fromDec, true);
            byte[] b = tmp.opGetField(80, 500);
            if (b == null || b.length == 0) return null;
            return new String(b, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    private DeliveryController requireSession(Integer nodeDec, Integer fromDec) {
        UsbSerialPort port = UsbSession.getPort();
        if (port == null) return null;
        int n = normNode(nodeDec);
        int f = normFrom(fromDec);
        DeliveryController dc = sessions.getOrCreate(n, f, port);   // AJOUT
        if (dc != null) {                                           // AJOUT
            String serial = sessions.getExpectedSerial(n);          // AJOUT
            String transportKey = MediaTransportManager.KEY_USB;    // AJOUT
            notifyNodeSeenFull(n, f, serial, transportKey);         // AJOUT
        }                                                           // AJOUT
        return dc;
    }

    private void recordJobId(ApiResult r, int node, int from) {
        try {
            JSONObject j = r.toJson();
            JSONObject data = j.optJSONObject("data");
            if (data == null) return;
            String jobId = data.optString("jobId", "").trim();
            if (!jobId.isEmpty()) {
                jobToNode.put(jobId, node);
                jobToFrom.put(jobId, from);
            }
        } catch (Exception ignored) {}
    }

    private DeliveryController resolveJobController(String jobId, Integer nodeDec) {
        if (jobId == null || jobId.trim().isEmpty()) return null;

        Integer node = nodeDec;
        if (node != null) {
            int n = normNode(node);
            int f = lastFromHint;
            return requireSession(n, f);
        }

        Integer mappedNode = jobToNode.get(jobId);
        Integer mappedFrom = jobToFrom.get(jobId);
        if (mappedNode != null) {
            int n = normNode(mappedNode);
            int f = normFrom(mappedFrom != null ? mappedFrom : lastFromHint);
            return requireSession(n, f);
        }

        int n = lastNodeHint;
        int f = lastFromHint;
        return requireSession(n, f);
    }

    @Override public ApiResult api_connectLcp() { return api_connectLcp(null, null); }
    @Override public ApiResult api_deliveryAlignA() { return api_deliveryAlignA(null, null); }
    @Override public ApiResult api_deliveryStartC(int product1to16, double presetNet) { return api_deliveryStartC(null, null, product1to16, presetNet); }
    @Override public ApiResult api_deliveryJobGet(String jobId) { return api_deliveryJobGet(jobId, null); }
    @Override public ApiResult api_deliveryOneShotStart(String numero_livraison, int product1to16, double presetNetL, String compartment) {
        return api_deliveryOneShotStart(null, null, numero_livraison, product1to16, presetNetL, compartment);
    }
    @Override public ApiResult api_deliveryContinue(String jobId) { return api_deliveryContinue(jobId, null); }
    @Override public ApiResult api_deliveryTerminate(String jobId) { return api_deliveryTerminate(jobId, null); }

    @Override public ApiResult api_ticketReprintCurrent() { return api_ticketReprintCurrent(null, null); }

    @Override
    public ApiResult api_registerValidate(String numero_livraison,
                                         Integer expected_lcrnode_dec,
                                         String expected_serial_id,
                                         Integer expected_product_number,
                                         String expected_compartment) {
        return api_registerValidate(numero_livraison, expected_lcrnode_dec, null,
                expected_serial_id, expected_product_number, expected_compartment);
    }

    @Override
    public ApiResult api_connectLcp(Integer lcrnode_dec, Integer from_dec) {
        int node = normNode(lcrnode_dec);
        int from = normFrom(from_dec);
        DeliveryController dc = requireSession(node, from);
        if (dc == null) return ApiResult.fail("Connect LCP: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
        return dc.api_connectLcp();
    }

    @Override
    public ApiResult api_deliveryAlignA(Integer lcrnode_dec, Integer from_dec) {
        int node = normNode(lcrnode_dec);
        int from = normFrom(from_dec);
        DeliveryController dc = requireSession(node, from);
        if (dc == null) return ApiResult.fail("Align A: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
        return dc.api_deliveryAlignA();
    }

    @Override
    public ApiResult api_deliveryStartC(Integer lcrnode_dec, Integer from_dec, int product1to16, double presetNet) {
        return api_deliveryStartC(lcrnode_dec, from_dec, product1to16, presetNet, "usb", null);
    }

    public ApiResult api_deliveryStartC(Integer lcrnode_dec, Integer from_dec, int product1to16, double presetNet,
                                       String media, String bt_mac) {
        int node = normNode(lcrnode_dec);
        int from = normFrom(from_dec);

        MediaCtx mc = resolveMediaCtx(media, bt_mac, node, from);
        ApiResult gate = option3_startGate(mc, node, "Delivery C");
        if (gate != null) return gate;

        DeliveryController dc = mc.dc;
        if (dc == null) {
            return ApiResult.fail("Delivery C: 0 - Controller introuvable", "NO_CONTROLLER");
        }

        ApiResult r = dc.api_deliveryStartC(product1to16, presetNet);
        recordJobId(r, node, from);
        return r;
    }

    @Override
    public ApiResult api_deliveryOneShotStart(Integer lcrnode_dec, Integer from_dec,
                                             String numero_livraison, int product1to16, double presetNetL, String compartment) {
        return api_deliveryOneShotStart(lcrnode_dec, from_dec, numero_livraison, product1to16, presetNetL, compartment, "usb", null);
    }

    public ApiResult api_deliveryOneShotStart(Integer lcrnode_dec, Integer from_dec,
                                             String numero_livraison, int product1to16, double presetNetL, String compartment,
                                             String media, String bt_mac) {
        int node = normNode(lcrnode_dec);
        int from = normFrom(from_dec);

        MediaCtx mc = resolveMediaCtx(media, bt_mac, node, from);
        ApiResult gate = option3_startGate(mc, node, "OneShot");
        if (gate != null) return gate;

        DeliveryController dc = mc.dc;
        if (dc == null) {
            return ApiResult.fail("OneShot: 0 - Controller introuvable", "NO_CONTROLLER");
        }

        ApiResult r = dc.api_deliveryOneShotStart(numero_livraison, product1to16, presetNetL, compartment);
        recordJobId(r, node, from);
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
    public ApiResult api_registerValidate(String numero_livraison,
                                         Integer expected_lcrnode_dec,
                                         Integer from_dec,
                                         String expected_serial_id,
                                         Integer expected_product_number,
                                         String expected_compartment) {
        int node = normNode(expected_lcrnode_dec);
        int from = normFrom(from_dec);
        DeliveryController dc = requireSession(node, from);
        if (dc == null) return ApiResult.fail("Validate: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
        return dc.api_registerValidate(numero_livraison, expected_lcrnode_dec,
                expected_serial_id, expected_product_number, expected_compartment);
    }

    @Override
    public ApiResult api_ticketReprintCurrent(Integer lcrnode_dec, Integer from_dec) {
        int node = normNode(lcrnode_dec);
        int from = normFrom(from_dec);
        DeliveryController dc = requireSession(node, from);
        if (dc == null) return ApiResult.fail("Reprint: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
        return dc.api_ticketReprintCurrent();
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
        int from = lastFromHint;
        long since = (since_seq != null) ? since_seq : 0L;
        long wait = (wait_ms != null) ? wait_ms.longValue() : 25_000L;
        DeliveryController dc = requireSession(node, from);
        if (dc == null) {
            return ApiResult.fail("Tick: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
        }
        return dc.api_tickWait(since, wait);
    }

    private ApiResult failTransportLevel(String media, String btMac, String where) {
        String m = (media == null) ? "usb" : media.trim().toLowerCase(Locale.ROOT);
        JSONObject d = new JSONObject();
        try { d.put("level", "MEDIA"); } catch (Exception ignored) {}
        try { d.put("where", where); } catch (Exception ignored) {}
        try { d.put("media", m); } catch (Exception ignored) {}

        if ("usb".equals(m)) {
            return ApiResult.fail("Transport: 0 - USB non connecté", "ERR_USB_NOT_CONNECTED", d);
        }
        if ("bt".equals(m) || "bluetooth".equals(m)) {
            String key = resolveBtKeyOrActive(btMac);
            if (key == null) {
                return ApiResult.fail("Transport: 0 - Aucun BT actif", "ERR_NO_ACTIVE_BT", d);
            }
            try { d.put("transportKey", key); } catch (Exception ignored) {}
            return ApiResult.fail("Transport: 0 - BT non connecté", "ERR_BT_NOT_CONNECTED", d);
        }
        return ApiResult.fail("Transport: 0 - media invalide", "ERR_MEDIA_INVALID", d);
    }

    @Override
    public ApiResult api_deliveryAlignA(Integer lcrnode_dec, Integer from_dec, String media, String bt_mac) {
        int node = normNode(lcrnode_dec);
        int from = normFrom(from_dec);
        String m = (media == null) ? "usb" : media.trim().toLowerCase(Locale.ROOT);
        if (m.isEmpty()) m = "usb";

        if ("usb".equals(m)) {
            DeliveryController dc = requireSession(node, from);
            if (dc == null) return ApiResult.fail("Align A: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
            return dc.api_deliveryAlignA();
        }
        if ("bt".equals(m) || "bluetooth".equals(m)) {
            String key = resolveBtKeyOrActive(bt_mac);
            if (key == null) {
                return ApiResult.fail("Align A: 0 - Aucun BT actif (appelle bt/activate)", "ERR_NO_ACTIVE_BT");
            }
            
            TransportIo io = (mediaMgr != null) ? mediaMgr.getByKey(key) : null;
            if (io == null || !io.isOpen()) {
                return ApiResult.fail("Align A: 0 - BT non connecté", "ERR_BT_NOT_CONNECTED");
            }
            DeliveryController dc = sessions.getOrCreate(key, node, from, io);
            if (dc == null) return ApiResult.fail("Align A: 0 - BT non prêt.", "ERR_BT_NOT_CONNECTED");
            String serial = sessions.getExpectedSerial(node);
            String transportKey = key;
            notifyNodeSeenFull(node, from, serial, transportKey);
            return dc.api_deliveryAlignA();
        }
        return ApiResult.fail("Align A: 0 - media invalide", "ERR_MEDIA_INVALID");
    }
}
