
package com.pa.lcrdemo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.*;
import android.app.PendingIntent;

import com.hoho.android.usbserial.driver.*;

public class UsbReceiver extends BroadcastReceiver {

    public static final String ACTION_USB_PERMISSION =
            "com.pa.lcrdemo.USB_PERMISSION";

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
        UsbDevice device =
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (device == null) return;

        UsbManager mgr =
                (UsbManager) context.getSystemService(Context.USB_SERVICE);

        PendingIntent pi = PendingIntent.getBroadcast(
                context,
                0,
                new Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
        );

        mgr.requestPermission(device, pi);
    }

    private void handlePermission(Context context, Intent intent) {
        UsbDevice device =
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (device == null) return;

        boolean granted =
                intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false);

        if (!granted) return;

        UsbManager mgr =
                (UsbManager) context.getSystemService(Context.USB_SERVICE);

        UsbSerialDriver driver =
                UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) return;

        UsbDeviceConnection conn = mgr.openDevice(device);
        if (conn == null) return;

        UsbSerialPort port = driver.getPorts().get(0);

        try {
            port.open(conn);
            port.setParameters(
                    19200,
                    8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
            );

            // ✅ Notification propre à l'Activity
            if (context instanceof MainActivity) {
                ((MainActivity) context).onUsbPortReady(port);
            }

        } catch (Exception e) {
            try { port.close(); } catch (Exception ignore) {}
        }
    }

    private void handleDetach(Context context, Intent intent) {
        UsbDevice device =
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (device == null) return;

        if (context instanceof MainActivity) {
            ((MainActivity) context).onUsbDetached();
        }
    }
}
