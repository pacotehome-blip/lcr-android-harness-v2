
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

        /* ==========================================================
           1) PERMISSION GRANTED ?
           ========================================================== */
        if (ACTION_USB_PERMISSION.equals(action)) {

            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device == null) {
                log("Permission event: device NULL");
                return;
            }

            boolean granted =
                intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);

            if (!granted) {
                log("Permission USB REFUSÉE pour " + device);
                return;
            }

            log("Permission accordée → ouverture du port pour " + device);
            openSerialPort(context, usb, device);
            return;
        }

        /* ==========================================================
           2) USB DEVICE ATTACHED?
           ========================================================== */
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {

            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

            if (device == null) {
                log("USB attach event : aucun device");
                return;
            }

            log("USB détecté : VID=" +
                Integer.toHexString(device.getVendorId()).toUpperCase() +
                " PID=" +
                Integer.toHexString(device.getProductId()).toUpperCase());

            /*  
               On demande la permission explicitement avec PendingIntent.
               REMARQUE: même si device_filter est vide, Android va quand même déclencher
               cet événement → parfait pour un SDK hybride.
            */
            PendingIntent pi = PendingIntent.getBroadcast(
                    context,
                    0,
                    new Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_IMMUTABLE
            );

            usb.requestPermission(device, pi);
        }
    }

    /* ==========================================================
       OUVERTURE DU PORT SÉRIE (19200 8N1)
       ========================================================== */
    private void openSerialPort(Context context, UsbManager usb, UsbDevice device) {

        try {
            UsbSerialDriver driver =
                    UsbSerialProber.getDefaultProber().probeDevice(device);

            if (driver == null) {
                log("Aucun driver compatible pour ce device. Ignoré.");
                return;
            }

            UsbSerialPort port = driver.getPorts().get(0);

            UsbDeviceConnection connection = usb.openDevice(device);
            if (connection == null) {
                log("Impossible d’ouvrir la connexion USB (permission ?)");
                return;
            }

            port.open(connection);
            port.setParameters(
                    19200,                     // BAUD
                    8,                         // DATA BITS
                    UsbSerialPort.STOPBITS_1,  // STOP
                    UsbSerialPort.PARITY_NONE  // PARITY
            );

            log("Port série ouvert (19200 8N1) pour VID/PID = " +
                String.format("%04X/%04X",
                        device.getVendorId(),
                        device.getProductId()));

            /*  
               Injection du port dans MainActivity
               ------------------------------------------------------------
               NOTE IMPORTANTE:
               Android envoie souvent les broadcasts au niveau Application
               donc "context" n’est parfois PAS MainActivity.

               Donc on ne fait l’injection que si on est bien dans le bon contexte.
            */
            if (context instanceof MainActivity) {
                log("Injection du port → MainActivity");
                ((MainActivity) context).setPort(port);
            } else {
                log("Context != MainActivity → injection ignorée (normal).");
            }

        } catch (Exception e) {
            log("Erreur durant openSerialPort : " + e.getMessage());
        }
    }
}
