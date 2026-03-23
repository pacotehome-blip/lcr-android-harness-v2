
package com.pa.lcr.lcp.transport;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.hardware.usb.UsbDevice;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class MediaTransportManager {

    public static final String KEY_USB = "USB";

    private static volatile MediaTransportManager INSTANCE;

    public static MediaTransportManager get(Context ctx) {
        if (INSTANCE != null) return INSTANCE;
        synchronized (MediaTransportManager.class) {
            if (INSTANCE == null) INSTANCE = new MediaTransportManager(ctx.getApplicationContext());
        }
        return INSTANCE;
    }

    private final Context appCtx;
    private final Map<String, TransportHandle> handles = new ConcurrentHashMap<>();

    private MediaTransportManager(Context appCtx) {
        this.appCtx = appCtx;
        handles.put(KEY_USB, new TransportHandle(KEY_USB));
    }

    // -------------------------
    // UPSERT USB
    // -------------------------
    public synchronized void onUsbReady(UsbDevice dev, UsbSerialPort port, String description) {
        TransportHandle h = handles.get(KEY_USB);
        if (h == null) {
            h = new TransportHandle(KEY_USB);
            handles.put(KEY_USB, h);
        }
        long nextGen = h.getGenerationId() + 1;
        TransportIo io = new UsbTransportIo(KEY_USB, port,
                (description != null ? description : "USB ready"),
                nextGen);
        h.setConnected(io, io.describe());
    }

    public synchronized void onUsbDetached(String reason) {
        TransportHandle h = handles.get(KEY_USB);
        if (h == null) return;
        h.setDisconnected(reason != null ? reason : "USB detached");
    }

    // -------------------------
    // UPSERT BT
    // -------------------------
    public static String btKey(String mac) {
        if (mac == null) mac = "";
        return "BT:" + mac.toUpperCase(Locale.ROOT);
    }

    public synchronized void onBtConnected(BluetoothDevice dev,
                                           BluetoothSocket socket,
                                           InputStream in,
                                           OutputStream out,
                                           String description) {

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

        TransportIo io = new BtSppTransportIo(key, socket, in, out, desc, nextGen);
        h.setConnected(io, io.describe());
    }

    public synchronized void onBtDisconnected(String mac, String reason) {
        String key = btKey(mac);
        TransportHandle h = handles.get(key);
        if (h == null) return;
        h.setDisconnected(reason != null ? reason : "BT disconnected");
    }

    public synchronized void onBtError(String mac, String err) {
        String key = btKey(mac);
        TransportHandle h = handles.get(key);
        if (h == null) {
            h = new TransportHandle(key);
            handles.put(key, h);
        }
        h.setError(h.getDescription(), err);
    }

    // -------------------------
    // Query
    // -------------------------
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

    /** Retourne le premier transport READY selon l'ordre donné (ex: ["BT:..", "USB"]). */
    public TransportIo pickReady(List<String> preferredKeys) {
        if (preferredKeys != null) {
            for (String k : preferredKeys) {
                TransportHandle h = handles.get(k);
                if (h != null && h.getStatus() == TransportStatus.READY && h.getIo() != null && h.getIo().isOpen()) {
                    return h.getIo();
                }
            }
        }
        // fallback: n'importe quel READY
        for (TransportHandle h : handles.values()) {
            if (h != null && h.getStatus() == TransportStatus.READY && h.getIo() != null && h.getIo().isOpen()) {
                return h.getIo();
            }
        }
        return null;
    }

    public TransportIo getByKey(String key) {
        if (key == null) return null;
        TransportHandle h = handles.get(key);
        if (h == null) return null;
        TransportIo io = h.getIo();
        if (io == null || !io.isOpen()) return null;
        return io;
    }
}
