
public byte[] buildFrame(byte[] payload) throws Exception {

    byte st = status.nextStatus(syncFirst);

    byte[] header = new byte[]{
            (byte) toAddr,
            (byte) fromAddr,
            st,
            (byte) payload.length
    };

    byte[] var = concat(header, payload);
    byte[] varEsc = LcpEscape.escape(var);

    int crc = LcpCrc.crcLcp(varEsc);

    byte lo = (byte) (crc & 0xFF);
    byte hi = (byte) ((crc >> 8) & 0xFF);

    byte[] crcEsc = LcpEscape.escape(new byte[]{lo, hi});

    return concat(
            new byte[]{0x7E, 0x7E},
            varEsc,
            crcEsc
    );
}
