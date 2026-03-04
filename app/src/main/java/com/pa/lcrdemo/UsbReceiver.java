
package com.pa.lcrdemo;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.*;
import com.hoho.android.usbserial.driver.*;

public class UsbReceiver extends BroadcastReceiver {

    public static final String ACTION_USB_PERMISSION = "com.pa.lcrdemo.USB_PERMISSION";

    // ✅ Nouveaux événements internes (app-only)
    public static final String ACTION_USB_READY = "com.pa.lcrdemo.USB_READY";
    public static final String ACTION_USB_DETACHED = "com.pa.lcrdemo.USB_DETACHED";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        switch (action) {
            case UsbManager.ACTION_USB_DEVICE_ATTACHED:
                handleAttach(context, intent);
                break;
            case UsbManager.ACTION_USB_DEVICE_DETACHED:
                handleDetach(context, intent);
                break;
            case ACTION_USB_PERMISSION:
                handlePermission(context, intent);
                break;
        }
    }

    private void handleAttach(Context context, Intent intent) {
        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (device == null) return;

        UsbManager mgr = (UsbManager) context.getSystemService(Context.USB_SERVICE);

        PendingIntent pi = PendingIntent.getBroadcast(
                context,
                0,
                new Intent(ACTION_USB_PERMISSION),
                // ✅ FIX: OR des flags
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
        );

        mgr.requestPermission(device, pi);
    }

    private void handlePermission(Context context, Intent intent) {
        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (device == null) return;

        boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
        if (!granted) return;

        UsbManager mgr = (UsbManager) context.getSystemService(Context.USB_SERVICE);

        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) return;

        UsbDeviceConnection conn = mgr.openDevice(device);
        if (conn == null) return;

        UsbSerialPort port = driver.getPorts().get(0);

        try {
            port.open(conn);
            port.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

            // ✅ Stocker la session en mémoire + notifier l’app
            UsbSession.set(device, port);

            Intent ready = new Intent(ACTION_USB_READY);
            ready.setPackage(context.getPackageName()); // app only
            context.sendBroadcast(ready);

        } catch (Exception e) {
            try { port.close(); } catch (Exception ignore) {}
            UsbSession.clear();
        }
    }

    private void handleDetach(Context context, Intent intent) {
        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (device == null) return;

        // ✅ Clear session + notifier l’app
        UsbSession.clear();

        Intent det = new Intent(ACTION_USB_DETACHED);
        det.setPackage(context.getPackageName()); // app only
        context.sendBroadcast(det);
    }
}
