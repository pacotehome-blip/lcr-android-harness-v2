
package com.pa.lcrdemo;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbDeviceConnection;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

public class UsbReceiver extends BroadcastReceiver {

    public static final String ACTION_USB_PERMISSION =
            "com.pa.lcrdemo.USB_PERMISSION";

    private void log(String s) {
        System.out.println("[USB] " + s);
    }

    @Override
    public void onReceive(Context context, Intent intent) {

        UsbManager usb = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        String action = intent.getAction();

        /* =================== Permission USB ==================== */
        if (ACTION_USB_PERMISSION.equals(action)) {

            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);

            if (!granted) {
                log("Permission USB REFUSÉE");
                return;
            }

            log("Permission USB accordée → ouverture du port…");
            openSerialPort(context, usb, device);
            return;
        }

        /* =================== USB détecté ====================== */
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {

            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            log("USB détecté : " + device);

            PendingIntent pi = PendingIntent.getBroadcast(
                    context,
                    0,
                    new Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_IMMUTABLE
            );

            usb.requestPermission(device, pi);
        }
    }

    /* =================== Ouverture du port ==================== */
    private void openSerialPort(Context context, UsbManager usb, UsbDevice device) {

        try {
            UsbSerialDriver driver =
                    UsbSerialProber.getDefaultProber().probeDevice(device);

            if (driver == null) {
                log("Aucun driver compatible trouvé pour " + device);
                return;
            }

            UsbSerialPort port = driver.getPorts().get(0);

            UsbDeviceConnection connection = usb.openDevice(device);
            if (connection == null) {
                log("Impossible d’ouvrir la connexion USB");
                return;
            }

            port.open(connection);
            port.setParameters(
                    19200,
                    8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
            );

            log("Port série ouvert et configuré (19200 8N1).");

            if (context instanceof MainActivity) {
                ((MainActivity) context).setPort(port);
                log("Port injecté dans MainActivity.");
            } else {
                log("WARN: Le context n’est pas MainActivity.");
            }

        } catch (Exception e) {
            log("Erreur ouverture port: " + e.getMessage());
        }
    }
}
