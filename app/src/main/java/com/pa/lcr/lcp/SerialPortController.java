
package com.pa.lcr.lcp;

public class SerialPortController {

    public interface Reader {
        int read(byte[] buffer, int timeout) throws Exception;
    }

    public interface Writer {
        int write(byte[] buffer, int timeout) throws Exception;
    }

    private final Reader reader;
    private final Writer writer;

    public SerialPortController(Reader r, Writer w) {
        this.reader = r;
        this.writer = w;
    }

    public synchronized void write(byte[] data) throws Exception {
        writer.write(data, 1000);
    }

    public synchronized byte[] readExact(int length) throws Exception {
        byte[] buffer = new byte[length];
        int offset = 0;
        long start = System.currentTimeMillis();

        while (offset < length) {
            if (System.currentTimeMillis() - start > 1500) {
                throw new Exception("Timeout readExact");
            }

            int n = reader.read(buffer, 1000);
            if (n > 0)
                offset += n;
        }
        return buffer;
    }

    public synchronized byte[] transaction(byte[] request, int responseLength) throws Exception {
        write(request);
        return readExact(responseLength);
    }
}
