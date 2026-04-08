
package com.pa.lcr.lcp.transport;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.hardware.usb.UsbDevice;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MediaTransportManager
 *
 * Option B:
 * - Gestion USB / BT
 * - Un seul transport ACTIF à la fois
 * - Auto-connect BT sans intervention UI (si déjà appairé)
 */
public final class MediaTransportManager {

    public static final String KEY_USB = "USB";

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

    // Option B: un seul média actif
    private volatile String activeKey = null;

    private MediaTransportManager(Context appCtx) {
        this.appCtx = appCtx;
        handles.put(KEY_USB, new TransportHandle(KEY_USB));
    }

    // ---------------------------------------------------------
    // USB
    // ---------------------------------------------------------

    public synchronized void onUsbReady(UsbDevice dev, UsbSerialPort port, String description) {
        TransportHandle h = handles.computeIfAbsent(KEY_USB, TransportHandle::new);
        long gen = h.getGenerationId() + 1;

        TransportIo io = new UsbTransportIo(
                KEY_USB,
                port,
                description != null ? description : "USB ready",
                gen
        );
        h.setConnected(io, io.describe());
    }

    public synchronized void onUsbDetached(String reason) {
        TransportHandle h = handles.get(KEY_USB);
        if (h == null) return;
        h.setDisconnected(reason != null ? reason : "USB detached");
        clearActiveIfMatches(KEY_USB);
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
        String mac = dev != null ? dev.getAddress() : null;
        String key = btKey(mac);

        TransportHandle h = handles.computeIfAbsent(key, TransportHandle::new);
        long gen = h.getGenerationId() + 1;

        String name = (dev != null && dev.getName() != null) ? dev.getName() : "(no-name)";
        String desc = description != null
                ? description
                : ("BT SPP " + name + " " + (mac != null ? mac : ""));

        TransportIo io = new BtSppTransportIo(key, socket, in, out, desc, gen);
        h.setConnected(io, io.describe());
    }

    public synchronized void onBtDisconnected(String mac, String reason) {
        String key = btKey(mac);
        TransportHandle h = handles.get(key);
        if (h == null) return;
        h.setDisconnected(reason != null ? reason : "BT disconnected");
        clearActiveIfMatches(key);
    }

    // ---------------------------------------------------------
    // Activation exclusive
    // ---------------------------------------------------------

    public synchronized boolean activateExclusive(String key, String reason) {
        if (key == null || key.trim().isEmpty()) return false;

        TransportHandle target = handles.get(key);
        if (target == null) return false;

        TransportIo io = target.getIo();
        if (io == null || !io.isOpen()) return false;

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
        if (key != null && key.equals(activeKey)) activeKey = null;
    }

    public String getActiveKey() {
        return activeKey;
    }

    public static String getActiveKeyStatic() {
        return (INSTANCE != null) ? INSTANCE.activeKey : null;
    }

    // ---------------------------------------------------------
    // ✅ OPTION B — AUTO CONNECT
    // ---------------------------------------------------------

    public synchronized TransportIo autoConnect(String media, String btMac, long timeoutMs) {
        String m = media != null ? media.trim().toLowerCase(Locale.ROOT) : "auto";

        // 1) USB prioritaire
        if ("usb".equals(m) || "auto".equals(m)) {
            TransportHandle h = handles.get(KEY_USB);
            if (h != null) {
                TransportIo io = h.getIo();
                if (io != null && io.isOpen()) return io;
            }
            if ("usb".equals(m)) return null;
        }

        // 2) BT explicite ou fallback
        if ("bt".equals(m) || "auto".equals(m)) {
            String key = (btMac != null && !btMac.isEmpty())
                    ? btKey(btMac)
                    : activeKey;

            if (key == null || !key.startsWith("BT:")) return null;

            TransportHandle h = handles.get(key);
            if (h == null) return null;

            TransportIo io = h.getIo();
            if (io == null) return null;

            if (!io.isOpen()) {
                try {
                    io.open();
                    long end = System.currentTimeMillis() + Math.max(1000, timeoutMs);
                    while (!io.isOpen() && System.currentTimeMillis() < end) {
                        try { wait(200); } catch (Exception ignored) {}
                    }
                } catch (Exception e) {
                    return null;
                }
            }

            if (io.isOpen()) return io;
        }

        return null;
    }

    // ---------------------------------------------------------
    // Queries
    // ---------------------------------------------------------

    public TransportIo getByKey(String key) {
        if (key == null) return null;
        TransportHandle h = handles.get(key);
        if (h == null) return null;
        TransportIo io = h.getIo();
        if (io == null || !io.isOpen()) return null;
        return io;
    }

    public List<TransportSnapshot> listSnapshots() {
        ArrayList<TransportSnapshot> out = new ArrayList<>();
        for (TransportHandle h : handles.values()) {
            if (h != null) out.add(h.snapshot());
        }
        out.sort(Comparator.comparing(s -> s.key));
        return out;
    }
}
