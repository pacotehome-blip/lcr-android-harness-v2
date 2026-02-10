
package com.pa.lcr.lcp;

public class LcrService {

    private final SerialPortController port;

    public LcrService(SerialPortController port) {
        this.port = port;
    }

    public byte[] readRegister(int reg, int length) throws Exception {
        byte[] req = LcrFrame.buildReadFrame(reg, length);
        return port.transaction(req, length + 5);
    }

    public void writeRegister(int reg, byte[] payload) throws Exception {
        byte[] req = LcrFrame.buildWriteFrame(reg, payload);
        port.transaction(req, 4);
    }

    public byte[] poll() throws Exception {
        byte[] req = LcrFrame.buildPollFrame();
        return port.transaction(req, 8);
    }
}
