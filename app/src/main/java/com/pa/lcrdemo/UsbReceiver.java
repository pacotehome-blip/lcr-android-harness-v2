package com.pa.lcrdemo;

// ═══════════════════════════════════════════════════════════════════════════
// COMPATIBILITÉ ANDROID : API 28 (Android 9) → API 35 (Android 15)
// ───────────────────────────────────────────────────────────────────────────
// Toute modification de ce fichier doit être testée sur :
//   · Android 9  (API 28) — Samsung SM-T397U         · ADB 192.168.134.105:5555
//   · Android 15 (API 35) — Samsung R52X508K2DR     · ADB 192.168.134.126:5555
//
// Règles obligatoires :
//   1. Détecter la version à l'exécution via Build.VERSION.SDK_INT
//   2. Appliquer le comportement EXPLICITEMENT par version — pas de spéculation
//   3. Ne jamais utiliser d'API introduite après API 28 sans guard de version
//   4. registerReceiver()  : RECEIVER_NOT_EXPORTED ou RECEIVER_EXPORTED sur API 34+
//   5. PendingIntent       : FLAG_IMMUTABLE sur API 31+ · FLAG_MUTABLE + guard sur API 34+
//   6. startForeground()   : type obligatoire sur API 34+ — doit matcher le manifest
//
// Constantes utiles :
//   Build.VERSION_CODES.P                 = 28  (Android 9)
//   Build.VERSION_CODES.Q                 = 29  (Android 10)
//   Build.VERSION_CODES.S                 = 31  (Android 12)
//   Build.VERSION_CODES.TIRAMISU          = 33  (Android 13)
//   Build.VERSION_CODES.UPSIDE_DOWN_CAKE  = 34  (Android 14)
//   Build.VERSION_CODES.VANILLA_ICE_CREAM = 35  (Android 15)
// ═══════════════════════════════════════════════════════════════════════════
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.*;
import com.hoho.android.usbserial.driver.*;
public class UsbReceiver extends BroadcastReceiver {
 public static final String ACTION_USB_PERMISSION = "com.pa.lcrdemo.USB_PERMISSION";
 // ✅ événements internes (app-only)
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

        // Android 9-13  : FLAG_UPDATE_CURRENT | FLAG_MUTABLE
        // Android 14-15 : FLAG_MUTABLE exige FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT
        //                 quand l'Intent n'a pas de composant explicite (USB permission)
        int flags;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ — FLAG_MUTABLE requis par requestPermission() + implicit intent
            flags = PendingIntent.FLAG_UPDATE_CURRENT
                  | PendingIntent.FLAG_MUTABLE
                  | PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT;
        } else {
            // Android 9-13
            flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE;
        }

        Intent permIntent = new Intent(ACTION_USB_PERMISSION);
        permIntent.setPackage(context.getPackageName()); // rend l'intent explicite
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, permIntent, flags);
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
 // ✅ R1: éviter double-open si une session est déjà active
 if (UsbSession.getPort() != null) {
 try { conn.close(); } catch (Exception ignore) {}
 return;
 }
 port.open(conn);
 port.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
 // ✅ stocker la session + notifier l'app
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
 // ✅ clear session + notifier l'app
 UsbSession.clear();
 Intent det = new Intent(ACTION_USB_DETACHED);
 det.setPackage(context.getPackageName()); // app only
 context.sendBroadcast(det);
 }
}
