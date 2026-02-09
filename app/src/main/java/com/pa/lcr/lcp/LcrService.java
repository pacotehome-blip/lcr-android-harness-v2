
package com.example.lcr;

import java.io.IOException;

public class LcrService {

    private final SerialPortController port;

    public LcrService(SerialPortController port) {
        this.port = port;
    }

    public byte[] readRegister(int reg, int length) throws IOException {
        byte[] req = LcrFrame.buildReadFrame(reg, length);
        return port.transaction(req, length + 5); // cadre : STX CMD HI LO LEN + data + CRC
    }

    public void writeRegister(int reg, byte[] payload) throws IOException {
        byte[] req = LcrFrame.buildWriteFrame(reg, payload);
        port.transaction(req, 4); // Confirme minimal ACK (à ajuster selon ton LCR)
    }

    public byte[] poll() throws IOException {
        byte[] req = LcrFrame.buildPollFrame();
        return port.transaction(req, 8); // Format poll 0x28 typique LCR-II
    }
}
