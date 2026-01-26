
package com.pa.lcrdemo;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

public class UsbReceiver extends BroadcastReceiver {

    public static final String ACTION_USB_PERMISSION =
            "com.android.example.USB_PERMISSION";

    @Override
    public void onReceive(Context context, Intent intent) {

        String action = intent.getAction();

        if (ACTION_USB_PERMISSION.equals(action)) {
            synchronized (this) {
                UsbDevice device =
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                boolean granted =
                        intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);

                if (granted) {
                    // Permission accordée : on peut continuer
                    System.out.println("[USB] Permission accordée pour le device");
                } else {
                    // Permission refusée
                    System.out.println("[USB] Permission REFUSÉE");
                }
            }
        }
    }
}
