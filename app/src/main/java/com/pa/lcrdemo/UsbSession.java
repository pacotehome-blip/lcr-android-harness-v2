
package com.pa.lcrdemo;

import android.hardware.usb.UsbDevice;
import com.hoho.android.usbserial.driver.UsbSerialPort;

/**
 * Session USB série ouverte par UsbReceiver.
 * Permet à MainActivity de récupérer le port ouvert (UsbSerialPort n'est pas Parcelable).
 */
public final class UsbSession {
    private static UsbSerialPort port;
    private static UsbDevice device;

    private UsbSession() {}

    public static synchronized void set(UsbDevice dev, UsbSerialPort p) {
        device = dev;
        port = p;
    }

    public static synchronized UsbSerialPort getPort() {
        return port;
    }

    public static synchronized UsbDevice getDevice() {
        return device;
    }

    public static synchronized void clear() {
        port = null;
        device = null;
    }
}
