
package com.lcr.client;

public class MachineStatus {

    public final int deviceCode;   // dev
    public final int deliveryStatus;  // ds
    public final int deliveryCode;    // dc

    public MachineStatus(int deviceCode, int deliveryStatus, int deliveryCode) {
        this.deviceCode = deviceCode;
        this.deliveryStatus = deliveryStatus;
        this.deliveryCode = deliveryCode;
    }

    public boolean flowActive() {
        return (deliveryCode & 0x0004) != 0;
    }

    public boolean deliveryActive() {
        return (deliveryCode & 0x0008) != 0;
    }

    public boolean beginDelivery() {
        return (deliveryCode & 0x0400) != 0;
    }

    public boolean ticketPending() {
        return (deliveryCode & 0x0001) != 0;
    }

    @Override
    public String toString() {
        return "MachineStatus{dev=0x" +
                Integer.toHexString(deviceCode) +
                ", ds=0x" + Integer.toHexString(deliveryStatus) +
                ", dc=0x" + Integer.toHexString(deliveryCode) +
                "}";
    }
}
