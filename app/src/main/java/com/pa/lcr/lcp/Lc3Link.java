package com.pa.lcr.lcp;

import com.pa.lcr.lcp.transport.TransportIo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lc3Link — LC3 LectroCount³ compatible DeliveryController.
 *
 * Même API publique que LcpLink (extends LcpLink — requiert retirer "final" de LcpLink).
 * DeliveryController ne sait pas qu'il parle à un LC3.
 *
 * Chemin: app/src/main/java/com/pa/lcr/lcp/Lc3Link.java
 */
public class Lc3Link extends LcpLink {

    // ── Commandes LC3 ────────────────────────────────────────────────────
    private static final byte[] CMD_POLL_A = {(byte)0x1B,(byte)0x7C,(byte)0xE3,
                                               (byte)0x06,(byte)0xE4,(byte)0xA9,(byte)0xCB};
    private static final byte[] CMD_POLL_B = {(byte)0x1B,(byte)0x7C,(byte)0xE3,
                                               (byte)0x05,(byte)0xE4,(byte)0xA9,(byte)0xC8};
    private static final byte[] CMD_SCREEN = {(byte)0x1B,(byte)0x7C,(byte)0xE3,
                                               (byte)0x07,(byte)0xE4,(byte)0xA9,(byte)0xCA};
    private static final byte VT_M1    = 0x0C;
    private static final byte VT_ENTER = 0x0D;
    private static final byte VT_DOWN  = 0x04;
    private static final byte VT_MODE  = 0x0E;
    private static final byte VT_START = 0x02;
    private static final byte VT_STOP  = 0x13;
    private static final byte VT_PRINT = 0x10;

    // ── DC bits ───────────────────────────────────────────────────────────
    private static final int DC_TICKET_PENDING  = 0x0001;
    private static final int DC_FLOW_ACTIVE     = 0x0004;
    private static final int DC_DELIVERY_ACTIVE = 0x0008;

    // ── Champs ────────────────────────────────────────────────────────────
    private static final int FIELD_ACTIVE_PRODUCT = 0;
    private static final int FIELD_PRESET_NET     = 6;
    private static final int FIELD_GROSS_TOTAL    = 17;
    private static final int FIELD_NET_TOTAL      = 18;
    private static final int FIELD_SALE_NUMBER    = 22;
    private static final int FIELD_TICKET_NUMBER  = 23;
    private static final int FIELD_DECIMALS       = 39;
    private static final int FIELD_GROSS_COUNT    = 44;
    private static final int FIELD_NET_COUNT      = 45;
    private static final int FIELD_SERIAL_ID      = 80;

    // ── Commandes ─────────────────────────────────────────────────────────
    private static final int CMD_RUN               = 0x00;
    private static final int CMD_END               = 0x02;
    private static final int CMD_PRINT_LAST_TICKET = 0x06;

    // ── Regex ─────────────────────────────────────────────────────────────
    private static final Pattern RE_NET =
            Pattern.compile("NET VOLUME LITRES\\s+([\\d.]+)");

    // ── Transport ─────────────────────────────────────────────────────────
    private final TransportIo lc3io;
    private volatile boolean lc3closed = false;

    // ── État interne ──────────────────────────────────────────────────────
    private volatile int  pendingProduct = 11;
    private volatile long pendingPreset  = 0;
    private volatile int  accessCode     = 1;
    private volatile float lastNetPoll   = -1f;

    // ── Constructeur ──────────────────────────────────────────────────────
    public Lc3Link(TransportIo io) {
        super(null, 0, 0, false);   // LcpLink parent sans transport
        this.lc3io = io;
    }

    // ── Identité registre ─────────────────────────────────────────────────
    public static final class RegisterIdentity {
        public final boolean isLc3;
        public final String  serialId;
        public final int     nodeId;
        public final int     truckNo;
        public final String  model;

        public RegisterIdentity(boolean isLc3, String serialId,
                                int nodeId, int truckNo, String model) {
            this.isLc3    = isLc3;
            this.serialId = serialId != null ? serialId : "";
            this.nodeId   = nodeId;
            this.truckNo  = truckNo;
            this.model    = model != null ? model : "";
        }

        @Override public String toString() {
            return (isLc3 ? "LC3" : "LCR-II")
                + " node=" + nodeId + " truck=" + truckNo
                + " serial=" + serialId;
        }
    }

    // ── Lifecycle (override LcpLink) ──────────────────────────────────────
    @Override public boolean isClosed() {
        return lc3closed || lc3io == null || !lc3io.isOpen();
    }
    @Override public void softClose() { lc3closed = true; }
    @Override public void close() {
        lc3closed = true;
        try { if (lc3io != null) lc3io.close(); } catch (Exception ignored) {}
    }
    @Override public void drainInput(int ms)         { /* NO-OP */ }
    @Override public void forceSyncNext(String r)    { /* NO-OP */ }
    @Override public String getTransportKey()        { return lc3io != null ? lc3io.getKey() : null; }
    @Override public long getTransportGenerationId() { return lc3io != null ? lc3io.getGenerationId() : 0L; }

    @Override
    public void setTraceSink(TraceSink sink) {
        // NO-OP — trace via android.util.Log
    }

    // ── opGetMachineStatus ────────────────────────────────────────────────
    @Override
    public MachineStatus opGetMachineStatus() throws IOException {
        int[] ds = opDeliveryStatus();
        return new MachineStatus(0, 0, 0, ds[0], ds[1]);
    }

    // ── opDeliveryStatus ──────────────────────────────────────────────────
    @Override
    public int[] opDeliveryStatus() throws IOException {
        String scr = pollScreen();
        int delCode = 0;
        if (scr.contains("PUSH START TO RESUME") ||
            scr.contains("PRESET STOP") ||
            scr.contains("PUSH PRINT")) {
            delCode |= DC_TICKET_PENDING;
        }
        Matcher m = RE_NET.matcher(scr);
        if (m.find()) {
            delCode |= DC_DELIVERY_ACTIVE;
            float net = parseFloat(m.group(1));
            if (lastNetPoll >= 0f && net != lastNetPoll) delCode |= DC_FLOW_ACTIVE;
            lastNetPoll = net;
        }
        return new int[]{ 0, delCode };
    }

    @Override
    public int[] opDeliveryStatus(int timeoutMs) throws IOException {
        return opDeliveryStatus();
    }

    // ── opGetField ────────────────────────────────────────────────────────
    @Override
    public byte[] opGetField(int field) throws IOException {
        return opGetField(field, 5000);
    }

    @Override
    public byte[] opGetField(int field, int timeoutMs) throws IOException {
        checkOpen();
        switch (field) {
            case FIELD_DECIMALS:
                return encodeU32(1);  // 1 décimale fixe (validé Mode 3)
            case FIELD_ACTIVE_PRODUCT:
                return encodeU32(pendingProduct);
            case FIELD_PRESET_NET:
                return encodeU32((int) pendingPreset);
            case FIELD_NET_COUNT:
            case FIELD_GROSS_COUNT: {
                String scr = pollScreen();
                Matcher m = RE_NET.matcher(scr);
                if (m.find()) return encodeU32(Math.round(parseFloat(m.group(1)) * 10));
                return encodeU32(0);
            }
            case FIELD_SALE_NUMBER:
                return encodeU32((int) readMode3FieldValue("SALE NUMBER"));
            case FIELD_TICKET_NUMBER:
                return encodeU32((int) readMode3FieldValue("TICKET NUMBER"));
            case FIELD_GROSS_TOTAL:
                return encodeU32(Math.round(readMode3FieldValue("TOTAL GROSS VOLUME") * 10));
            case FIELD_NET_TOTAL:
                return encodeU32(Math.round(readMode3FieldValue("TOTAL NET VOLUME") * 10));
            case FIELD_SERIAL_ID: {
                String s = readMode8Serial();
                return s.getBytes(StandardCharsets.US_ASCII);
            }
            default:
                android.util.Log.d("Lc3Link", "opGetField(" + field + ") non impl → 0");
                return encodeU32(0);
        }
    }

    // ── opSetField ────────────────────────────────────────────────────────
    @Override
    public void opSetField(int field, byte[] value) throws IOException {
        checkOpen();
        switch (field) {
            case FIELD_ACTIVE_PRODUCT:
                pendingProduct = (value != null && value.length > 0) ? (value[0] & 0xFF) : 11;
                android.util.Log.d("Lc3Link", "ACTIVE_PRODUCT=" + pendingProduct);
                break;
            case FIELD_PRESET_NET:
                pendingPreset = beI32(value) & 0xFFFFFFFFL;
                android.util.Log.d("Lc3Link", "PRESET_NET=" + pendingPreset);
                break;
            default:
                android.util.Log.d("Lc3Link", "opSetField(" + field + ") ignoré");
        }
    }

    // ── opIssueCommand ────────────────────────────────────────────────────
    @Override
    public void opIssueCommand(int cmd) throws IOException {
        checkOpen();
        switch (cmd) {
            case CMD_RUN:
                startDelivery();
                break;
            case CMD_END:
                android.util.Log.d("Lc3Link", "CMD_END → Ctrl+S");
                rawWrite(new byte[]{ VT_STOP });
                sleep(500);
                drainRx(300);
                break;
            case CMD_PRINT_LAST_TICKET:
                android.util.Log.d("Lc3Link", "CMD_PRINT_LAST_TICKET → Ctrl+P");
                rawWrite(new byte[]{ VT_PRINT });
                sleep(1000);
                long dl = System.currentTimeMillis() + 15_000;
                while (System.currentTimeMillis() < dl) {
                    if ((opDeliveryStatus()[1] & DC_TICKET_PENDING) == 0) break;
                    sleep(500);
                }
                break;
            default:
                android.util.Log.d("Lc3Link", "opIssueCommand(0x"
                        + Integer.toHexString(cmd) + ") ignoré");
        }
    }

    // ── startDelivery ─────────────────────────────────────────────────────
    private void startDelivery() throws IOException {
        android.util.Log.i("Lc3Link", "startDelivery product="
                + pendingProduct + " preset=" + pendingPreset);
        // Retour Mode 1
        rawWrite(new byte[]{ VT_M1 });   sleep(150);
        rawWrite(new byte[]{ VT_ENTER }); sleep(300);
        rawWrite(new byte[]{ '0' });      sleep(500);
        drainRx(300);
        rawWrite(new byte[]{ VT_DOWN });  sleep(100);
        rawWrite(new byte[]{ VT_DOWN });  sleep(100);
        rawWrite(new byte[]{ VT_M1 });   sleep(150);
        rawWrite(new byte[]{ VT_ENTER }); sleep(500);
        drainRx(300);
        // ACCESS NUMBER
        rawWrite(new byte[]{ VT_DOWN });  sleep(400);
        // access + ENTER
        rawWrite(String.valueOf(accessCode).getBytes()); sleep(100);
        rawWrite(new byte[]{ VT_ENTER }); sleep(600);
        // product + ENTER
        rawWrite(String.valueOf(pendingProduct).getBytes()); sleep(100);
        rawWrite(new byte[]{ VT_ENTER }); sleep(600);
        // preset / 10 + ENTER  (pendingPreset=300 → envoie '30' → registre=30.0L)
        int presetVal = (int)(pendingPreset / 10);
        rawWrite(String.valueOf(presetVal).getBytes()); sleep(100);
        rawWrite(new byte[]{ VT_ENTER }); sleep(600);
        // START
        rawWrite(new byte[]{ VT_START }); sleep(800);
        drainRx(300);
        android.util.Log.i("Lc3Link", "startDelivery → START envoyé");
    }

    // ── Lecture Mode 3 ────────────────────────────────────────────────────
    private float readMode3FieldValue(String fieldName) throws IOException {
        gotoMode(3);
        try {
            for (int i = 0; i < 40; i++) {
                String scr = readSpontaneous(800);
                String first = firstLine(scr);
                if (first.toUpperCase().contains(fieldName.toUpperCase())) {
                    Matcher m = Pattern.compile("([\\d.]+)\\s*\\.?\\s*$").matcher(first);
                    if (m.find()) return parseFloat(m.group(1));
                }
                rawWrite(new byte[]{ VT_DOWN });  sleep(200);
                rawWrite(new byte[]{ VT_ENTER });
            }
        } finally {
            backToMode1();
        }
        return 0f;
    }

    // ── Lecture Mode 8 ────────────────────────────────────────────────────
    private String readMode8Serial() throws IOException {
        gotoMode(8);
        try {
            String scr = readSpontaneous(1000);
            for (String line : scr.split("\n")) {
                if (line.contains("APPLICATION")) return line.trim();
            }
            return "LC3";
        } finally {
            backToMode1();
        }
    }

    // ── Navigation modes ─────────────────────────────────────────────────
    private void gotoMode(int modeNum) throws IOException {
        rawWrite(new byte[]{ VT_M1 });   sleep(300);
        rawWrite(new byte[]{ VT_ENTER }); sleep(1000);
        drainRx(300);
        rawWrite(new byte[]{ VT_MODE });  sleep(300);
        rawWrite(String.valueOf(modeNum).getBytes()); sleep(100);
        rawWrite(new byte[]{ VT_ENTER }); sleep(1200);
        String scr = readSpontaneous(1000);
        if (scr.toUpperCase().contains("KEY") && scr.contains("?")) {
            rawWrite(new byte[]{ '0' });
            rawWrite(new byte[]{ VT_ENTER });
            sleep(300);
            readSpontaneous(500);
        }
    }

    private void backToMode1() throws IOException {
        rawWrite(new byte[]{ VT_M1 }); sleep(300);
        drainRx(300);
    }

    // ── pollScreen ────────────────────────────────────────────────────────
    private String pollScreen() throws IOException {
        rawWrite(CMD_POLL_A);
        rawWrite(CMD_POLL_B);
        byte[] raw = readRaw(600);
        String scr = decodeVt100(raw);
        if (scr.isEmpty()) {
            rawWrite(CMD_SCREEN);
            scr = decodeVt100(readRaw(800));
        }
        return scr;
    }

    // ── I/O bas niveau ────────────────────────────────────────────────────
    private void rawWrite(byte[] data) throws IOException {
        try {
            if (lc3io.write(data, 2000) < 0)
                throw new TransportException("Lc3Link: write failed");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new TransportException("Lc3Link: write error", e);
        }
    }

    private byte[] readRaw(int timeoutMs) throws IOException {
        byte[] tmp    = new byte[4096];
        byte[] result = new byte[0];
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                int n = lc3io.read(tmp, 50);
                if (n < 0) throw new TransportException("Lc3Link: transport closed");
                if (n > 0) {
                    byte[] next = new byte[result.length + n];
                    System.arraycopy(result, 0, next, 0, result.length);
                    System.arraycopy(tmp, 0, next, result.length, n);
                    result = next;
                    deadline = Math.max(deadline, System.currentTimeMillis() + 200);
                }
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new TransportException("Lc3Link: read error", e);
            }
        }
        return result;
    }

    private String readSpontaneous(int timeoutMs) throws IOException {
        return decodeVt100(readRaw(timeoutMs));
    }

    private void drainRx(int ms) {
        try { readRaw(ms); } catch (Exception ignored) {}
    }

    // ── Décodeur VT-100 ───────────────────────────────────────────────────
    static String decodeVt100(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < data.length) {
            int b = data[i] & 0xFF;
            if (b == 0x1B && i + 1 < data.length) {
                int next = data[i+1] & 0xFF;
                if (next == 0x5B) {
                    i += 2;
                    while (i < data.length && !(data[i] >= 0x40 && data[i] <= 0x7E)) i++;
                    i++;
                } else if (next == 0x48) { sb.append('\n'); i += 2; }
                else { i += 2; }
            } else if (b == 0x0D || b == 0x0A) { sb.append('\n'); i++; }
            else if (b >= 0x20 && b < 0x7F)    { sb.append((char) b); i++; }
            else { i++; }
        }
        StringBuilder out = new StringBuilder();
        for (String line : sb.toString().split("\n")) {
            String l = line.trim();
            if (!l.isEmpty()) {
                if (out.length() > 0) out.append('\n');
                out.append(l);
            }
        }
        return out.toString();
    }

    // ── probe ─────────────────────────────────────────────────────────────
    public static boolean probe(TransportIo io) {
        byte[] screen = {(byte)0x1B,(byte)0x7C,(byte)0xE3,
                          (byte)0x07,(byte)0xE4,(byte)0xA9,(byte)0xCA};
        // Drain résidus avant de commencer
        drainIo(io, 400);

        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                int written = io.write(screen, 1000);
                android.util.Log.d("Lc3Link", "probe attempt=" + attempt + " write=" + written);

                // Attente initiale : laisser le LC3 préparer sa réponse
                // À 9600 baud : 7 bytes TX = ~7ms, réponse ~91 bytes = ~95ms + délai hardware
                Thread.sleep(300);

                // Lecture avec spinner — compatible port.read() non-bloquant (USB PL2303)
                // Utilise timeout=0 (non-bloquant) + sleep(5) comme BtSppTransportIo
                byte[] result = new byte[0];
                byte[] tmp = new byte[1024];
                long deadline = System.currentTimeMillis() + 1500;
                while (System.currentTimeMillis() < deadline) {
                    int n = io.read(tmp, 0); // timeout=0 : non-bloquant
                    if (n > 0) {
                        android.util.Log.d("Lc3Link", "probe got n=" + n);
                        byte[] combined = new byte[result.length + n];
                        System.arraycopy(result, 0, combined, 0, result.length);
                        System.arraycopy(tmp, 0, combined, result.length, n);
                        result = combined;
                        deadline = Math.max(deadline, System.currentTimeMillis() + 200);
                    } else {
                        Thread.sleep(5);
                    }
                }

                android.util.Log.d("Lc3Link", "probe attempt=" + attempt + " total=" + result.length);
                if (result.length > 0) {
                    String scr = decodeVt100(result);
                    android.util.Log.d("Lc3Link", "probe scr=" + scr.replace("\n", "|"));
                    if (scr.contains("NET VOLUME LITRES")    ||
                        scr.contains("PUSH START TO RESUME") ||
                        scr.contains("PRESET STOP")          ||
                        scr.contains("ACCESS NUMBER")        ||
                        scr.contains("DISPLAY TERMINAL")     ||
                        scr.contains("VT-100")) {
                        android.util.Log.i("Lc3Link", "probe → LC3 ✅");
                        return true;
                    }
                }
                // Drain avant prochain essai
                drainIo(io, 300);
            } catch (Exception e) {
                android.util.Log.w("Lc3Link", "probe ex: " + e.getMessage());
                return false;
            }
        }
        android.util.Log.i("Lc3Link", "probe → pas LC3");
        return false;
    }

    private static void drainIo(TransportIo io, int ms) {
        try {
            byte[] sink = new byte[1024];
            long dl = System.currentTimeMillis() + ms;
            while (System.currentTimeMillis() < dl) {
                int n = io.read(sink, 0); // non-bloquant
                if (n <= 0) Thread.sleep(5);
            }
        } catch (Exception ignored) {}
    }

    // ── probeAndIdentify ──────────────────────────────────────────────────
    public static RegisterIdentity probeAndIdentify(TransportIo io) {
        if (!probe(io)) return new RegisterIdentity(false, null, 0, 0, "");
        Lc3Link lc3 = new Lc3Link(io);
        String serialId = "LC3";
        String model    = "";
        int    nodeId   = 0;
        int    truckNo  = 0;
        try {
            lc3.gotoMode(4);
            for (int i = 0; i < 15; i++) {
                String scr   = lc3.readSpontaneous(800);
                String first = firstLine(scr);
                if (first.contains("UNIT ID")) {
                    Matcher m = Pattern.compile("(\\d+)\\s*\\.?\\s*$").matcher(first);
                    if (m.find()) nodeId = Integer.parseInt(m.group(1).trim());
                }
                if (first.contains("TRUCK NUMBER")) {
                    Matcher m = Pattern.compile("(\\d+)\\s*\\.?\\s*$").matcher(first);
                    if (m.find()) truckNo = Integer.parseInt(m.group(1).trim());
                }
                if (nodeId > 0 && truckNo > 0) break;
                lc3.rawWrite(new byte[]{ VT_DOWN });  sleep(200);
                lc3.rawWrite(new byte[]{ VT_ENTER });
            }
            lc3.backToMode1();
            lc3.gotoMode(8);
            String scr8 = lc3.readSpontaneous(1000);
            android.util.Log.d("Lc3Link", "probeAndIdentify Mode8 scr=" + scr8.replace("\n", "|"));
            for (String line : scr8.split("\n")) {
                if (line.contains("APPLICATION")) {
                    model = line.trim();
                    serialId = "LC3-" + nodeId; // fallback si SERIAL NUMBER non trouvé
                    break;
                }
            }
            // Scroller pour trouver SERIAL NUMBER (2 champs après APPLICATION)
            for (int i = 0; i < 4; i++) {
                lc3.rawWrite(new byte[]{ VT_DOWN }); sleep(300);
                String scr = lc3.readSpontaneous(800);
                android.util.Log.d("Lc3Link", "probeAndIdentify Mode8 field" + i + "=" + scr.replace("\n", "|"));
                for (String line : scr.split("\n")) {
                    if (line.contains("SERIAL NUMBER")) {
                        Matcher m = Pattern.compile("(\\d+)\\s*\\.?\\s*$").matcher(line);
                        if (m.find()) {
                            serialId = m.group(1).trim();
                            android.util.Log.i("Lc3Link", "SERIAL NUMBER trouvé: " + serialId);
                        }
                        break;
                    }
                }
                if (!serialId.startsWith("LC3-") && !serialId.equals("LC3")) break;
            }
            lc3.backToMode1();
        } catch (Exception e) {
            android.util.Log.w("Lc3Link", "probeAndIdentify: " + e.getMessage());
        }
        android.util.Log.i("Lc3Link", "identity=" + nodeId
                + " truck=" + truckNo + " serial=" + serialId);
        return new RegisterIdentity(true, serialId, nodeId, truckNo, model);
    }

    // ── Utilitaires ───────────────────────────────────────────────────────
    private void checkOpen() throws IOException {
        if (lc3closed || lc3io == null || !lc3io.isOpen())
            throw new TransportException("Lc3Link: fermé");
    }

    private static byte[] encodeU32(int v) {
        return new byte[]{(byte)(v>>24),(byte)(v>>16),(byte)(v>>8),(byte)v};
    }

    private static int beI32(byte[] b) {
        if (b == null || b.length < 4) return 0;
        return ((b[0]&0xFF)<<24)|((b[1]&0xFF)<<16)|((b[2]&0xFF)<<8)|(b[3]&0xFF);
    }

    private static float parseFloat(String s) {
        try { return Float.parseFloat(s.trim()); } catch (Exception e) { return 0f; }
    }

    private static String firstLine(String scr) {
        if (scr == null || scr.isEmpty()) return "";
        return scr.split("\n")[0].trim();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}