package com.pa.lcr.lcp;

import com.pa.lcr.lcp.transport.TransportIo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lc3Link — Implémentation LC3 LectroCount³ compatible DeliveryController.
 *
 * Même API publique que LcpLink. DeliveryController ne sait pas qu'il parle à un LC3.
 *
 * Protocole: VT-100 via TCP/NPort (RS-232 9600 8N1)
 *
 * Séquences validées terrain (PCAP awevv5/awevv6 + tests 2026-05-20):
 *   Poll NET  : E3 06 + E3 05 → réponse VT-100 "NET VOLUME LITRES X.X"
 *   Navigation: Ctrl+L + ENTER → Ctrl+N + mode + ENTER → Ctrl+D
 *   Delivery  : Ctrl+D → '1'+ENTER → '11'+ENTER → preset+ENTER → Ctrl+B
 *   Print     : Ctrl+P (0x10)
 *   Stop      : Ctrl+S (0x13)
 *
 * Mapping opGetField:
 *   #0  ACTIVE_PRODUCT  → état interne (setField stocke, getField retourne)
 *   #6  PRESET_NET      → état interne
 *   #17 GROSS_TOTAL     → Mode 3 TOTAL GROSS VOLUME
 *   #18 NET_TOTAL       → Mode 3 TOTAL NET VOLUME
 *   #22 SALE_NUMBER     → Mode 3 SALE NUMBER
 *   #23 TICKET_NUMBER   → Mode 3 TICKET NUMBER
 *   #39 DECIMALS        → fixe = 1 (résolution 0.1L validée Mode 3)
 *   #44 GROSS_COUNT     → E3 06+05 poll NET (LC3 n'a qu'un compteur)
 *   #45 NET_COUNT       → E3 06+05 poll NET
 *   #80 SERIAL_ID       → Mode 8 APPLICATION string
 *
 * DC_* bits de opDeliveryStatus:
 *   DC_TICKET_PENDING  0x0001 → "PUSH START TO RESUME" ou "PRESET STOP"
 *   DC_FLOW_ACTIVE     0x0004 → NET change entre deux polls
 *   DC_DELIVERY_ACTIVE 0x0008 → NET VOLUME LITRES visible en Mode 1
 */
public final class Lc3Link {

    // ── Constantes transport ──────────────────────────────────────────────
    private static final byte[] CMD_POLL_A = {(byte)0x1B,(byte)0x7C,(byte)0xE3,
                                               (byte)0x06,(byte)0xE4,(byte)0xA9,(byte)0xCB};
    private static final byte[] CMD_POLL_B = {(byte)0x1B,(byte)0x7C,(byte)0xE3,
                                               (byte)0x05,(byte)0xE4,(byte)0xA9,(byte)0xC8};
    private static final byte[] CMD_SCREEN = {(byte)0x1B,(byte)0x7C,(byte)0xE3,
                                               (byte)0x07,(byte)0xE4,(byte)0xA9,(byte)0xCA};

    private static final byte VT_M1    = 0x0C;  // Ctrl+L → retour Mode 1
    private static final byte VT_ENTER = 0x0D;
    private static final byte VT_DOWN  = 0x04;  // Ctrl+D → champ suivant
    private static final byte VT_MODE  = 0x0E;  // Ctrl+N → ENTER MODE NO.
    private static final byte VT_START = 0x02;  // Ctrl+B → START livraison
    private static final byte VT_STOP  = 0x13;  // Ctrl+S → STOP
    private static final byte VT_PRINT = 0x10;  // Ctrl+P → PRINT

    // ── DC bits (identique DeliveryController) ───────────────────────────
    public static final int DC_TICKET_PENDING  = 0x0001;
    public static final int DC_FLOW_ACTIVE     = 0x0004;
    public static final int DC_DELIVERY_ACTIVE = 0x0008;

    // ── Champs opGetField (identique DeliveryController) ─────────────────
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

    // ── Commandes opIssueCommand ──────────────────────────────────────────
    private static final int CMD_RUN               = 0x00;
    private static final int CMD_END               = 0x02;
    private static final int CMD_PRINT_LAST_TICKET = 0x06;

    // ── Regex ─────────────────────────────────────────────────────────────
    private static final Pattern RE_NET =
            Pattern.compile("NET VOLUME LITRES\\s+([\\d.]+)");
    private static final Pattern RE_FIELD =
            Pattern.compile("^(.+?)\\s{2,}([\\d.]+)\\s*\\.?\\s*$");

    // ── Transport ─────────────────────────────────────────────────────────
    private final TransportIo io;
    private volatile boolean closed = false;

    // ── État interne (setField stocke, getField retourne) ─────────────────
    private volatile int    pendingProduct = 11;   // PRODUCT CODE
    private volatile long   pendingPreset  = 0;    // PRESET_NET (U32 × 10^decimals)
    private volatile int    accessCode     = 1;    // ACCESS NUMBER

    // ── Dernier NET poll (pour DC_FLOW_ACTIVE) ────────────────────────────
    private volatile float  lastNetPoll    = -1f;

    // ── Trace ─────────────────────────────────────────────────────────────
    public interface TraceSink { void onTrace(String line); }
    private volatile TraceSink traceSink;
    public void setTraceSink(TraceSink sink) { this.traceSink = sink; }
    private void t(String s) { TraceSink ts = traceSink; if (ts != null) ts.onTrace(s); }

    // ── Compat LcpLink (champs hérités) ───────────────────────────────────
    public static final class TransportException extends IOException {
        public TransportException(String msg) { super(msg); }
        public TransportException(String msg, Throwable cause) { super(msg, cause); }
    }

    /** MachineStatus compatible LcpLink. */
    public static final class MachineStatus {
        public final int rc;
        public final int devStatus;
        public final int prnStatus;
        public final int delStatus;
        public final int delCode;
        public MachineStatus(int rc, int dev, int prn, int ds, int dc) {
            this.rc = rc; this.devStatus = dev; this.prnStatus = prn;
            this.delStatus = ds; this.delCode = dc;
        }
    }

    // ── Constructeur ──────────────────────────────────────────────────────
    public Lc3Link(TransportIo io) {
        this.io = io;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────
    public boolean isClosed()           { return closed || !io.isOpen(); }
    public void    softClose()          { closed = true; }
    public void    close()              { closed = true; try { io.close(); } catch (Exception ignored) {} }
    public void    drainInput(int ms)   { /* NO-OP */ }
    public void    forceSyncNext(String reason) { /* NO-OP */ }

    // Compat LcpLink getters
    public int    getToAddr()                { return 0; }
    public int    getHostAddr()              { return 0; }
    public String getTransportKey()          { return io != null ? io.getKey() : null; }
    public long   getGenerationId()          { return io != null ? io.getGenerationId() : 0L; }
    public long   getTransportGenerationId() { return getGenerationId(); }

    // ── opGetMachineStatus ────────────────────────────────────────────────
    /**
     * Compatible LcpLink.opGetMachineStatus().
     * devStatus = 0 (OK), prnStatus = 0 (imprimante LC3 toujours OK côté registre)
     * delStatus/delCode = opDeliveryStatus()
     */
    public MachineStatus opGetMachineStatus() throws IOException {
        int[] ds = opDeliveryStatus();
        return new MachineStatus(0, 0, 0, ds[0], ds[1]);
    }

    // ── opDeliveryStatus ──────────────────────────────────────────────────
    /**
     * Retourne [delStatus, delCode].
     * Lit l'écran via E3 06+05 et décode les DC_* bits depuis le texte VT-100.
     *
     * DC_DELIVERY_ACTIVE : NET VOLUME LITRES visible (livraison en cours)
     * DC_FLOW_ACTIVE     : NET a changé depuis le dernier poll
     * DC_TICKET_PENDING  : PUSH START TO RESUME ou PRESET STOP
     */
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
            if (net != lastNetPoll && lastNetPoll >= 0f) {
                delCode |= DC_FLOW_ACTIVE;
            }
            lastNetPoll = net;
        }

        return new int[]{ 0, delCode };
    }

    public int[] opDeliveryStatus(int timeoutMs) throws IOException {
        return opDeliveryStatus();
    }

    // ── opGetField ────────────────────────────────────────────────────────
    /**
     * Retourne la valeur d'un champ en bytes big-endian U32 (comme LcpLink).
     *
     * Champs rapides (~300ms): 39, 44, 45, 0, 6
     * Champs lents (~3s via navigation): 17, 18, 22, 23, 80
     */
    public byte[] opGetField(int field) throws IOException {
        return opGetField(field, 5_000);
    }

    public byte[] opGetField(int field, int timeoutMs) throws IOException {
        checkOpen();
        switch (field) {
            case FIELD_DECIMALS:
                // Mode 3: # DEC PLACES VOLUME = 1 (validé terrain)
                return encodeU32(1);

            case FIELD_ACTIVE_PRODUCT:
                return encodeU32(pendingProduct);

            case FIELD_PRESET_NET:
                return encodeU32((int) pendingPreset);

            case FIELD_NET_COUNT:
            case FIELD_GROSS_COUNT: {
                // E3 06+05 poll — rapide
                String scr = pollScreen();
                Matcher m = RE_NET.matcher(scr);
                if (m.find()) {
                    float net = parseFloat(m.group(1));
                    // Convertir en U32 avec 1 décimale: 30.1L → 301
                    return encodeU32(Math.round(net * 10));
                }
                return encodeU32(0);
            }

            case FIELD_SALE_NUMBER:
                return encodeU32((int) readMode3Field("SALE NUMBER"));

            case FIELD_TICKET_NUMBER:
                return encodeU32((int) readMode3Field("TICKET NUMBER"));

            case FIELD_GROSS_TOTAL:
                return encodeU32(Math.round(readMode3Field("TOTAL GROSS VOLUME") * 10));

            case FIELD_NET_TOTAL:
                return encodeU32(Math.round(readMode3Field("TOTAL NET VOLUME") * 10));

            case FIELD_SERIAL_ID: {
                String serial = readMode8Serial();
                return serial.getBytes(StandardCharsets.US_ASCII);
            }

            default:
                t("Lc3Link: opGetField(" + field + ") non implémenté → retourne 0");
                return encodeU32(0);
        }
    }

    // ── opSetField ────────────────────────────────────────────────────────
    /**
     * Stocke les valeurs en mémoire — appliquées au prochain CMD_RUN.
     */
    public void opSetField(int field, byte[] value) throws IOException {
        checkOpen();
        switch (field) {
            case FIELD_ACTIVE_PRODUCT:
                pendingProduct = value.length > 0 ? (value[0] & 0xFF) : 11;
                t("Lc3Link: ACTIVE_PRODUCT = " + pendingProduct);
                break;

            case FIELD_PRESET_NET:
                pendingPreset = beI32(value) & 0xFFFFFFFFL;
                t("Lc3Link: PRESET_NET = " + pendingPreset);
                break;

            default:
                t("Lc3Link: opSetField(" + field + ") non implémenté — ignoré");
        }
    }

    // ── opIssueCommand ────────────────────────────────────────────────────
    /**
     * CMD_RUN (0x00)               → navigation Mode 1 + Ctrl+B (START)
     * CMD_END (0x02)               → Ctrl+S (STOP)
     * CMD_PRINT_LAST_TICKET (0x06) → Ctrl+P (PRINT) + attendre fin TICKET_PENDING
     */
    public void opIssueCommand(int cmd) throws IOException {
        checkOpen();
        switch (cmd) {
            case CMD_RUN:
                startDelivery();
                break;

            case CMD_END:
                t("Lc3Link: CMD_END → Ctrl+S");
                writeByte(VT_STOP);
                sleep(500);
                drainRx(500);
                break;

            case CMD_PRINT_LAST_TICKET:
                t("Lc3Link: CMD_PRINT_LAST_TICKET → Ctrl+P");
                writeByte(VT_PRINT);
                sleep(1000);
                // Attendre que TICKET_PENDING retombe (max 15s)
                long deadline = System.currentTimeMillis() + 15_000;
                while (System.currentTimeMillis() < deadline) {
                    int[] ds = opDeliveryStatus();
                    if ((ds[1] & DC_TICKET_PENDING) == 0) break;
                    sleep(500);
                }
                break;

            default:
                t("Lc3Link: opIssueCommand(0x" + Integer.toHexString(cmd) + ") non implémenté");
        }
    }

    // ── Navigation + START ────────────────────────────────────────────────
    /**
     * Séquence validée terrain (PCAP awevv5 + tests 2026-05-20):
     *   Ctrl+L + ENTER + '0'    → Mode 0 → repositionnement
     *   Ctrl+D + Ctrl+D         → repositionnement
     *   Ctrl+L + ENTER          → Mode 1 propre
     *   Ctrl+D                  → ACCESS NUMBER
     *   accessCode + ENTER      → PRODUCT CODE
     *   product + ENTER         → PRESET NET
     *   preset + ENTER          → valide (30L → '30')
     *   Ctrl+B                  → START
     *
     * pendingPreset en U32 × 10^1 (ex: 300 = 30.0L)
     * Valeur envoyée au registre = pendingPreset / 10 (ex: 300/10 = '30')
     */
    private void startDelivery() throws IOException {
        t("Lc3Link: startDelivery product=" + pendingProduct
          + " preset=" + pendingPreset);

        // Retour Mode 1
        writeByte(VT_M1); sleep(150);
        writeByte(VT_ENTER); sleep(300);
        write(new byte[]{'0'}); sleep(500);
        drainRx(300);

        writeByte(VT_DOWN); sleep(100);
        writeByte(VT_DOWN); sleep(100);
        writeByte(VT_M1);   sleep(150);
        writeByte(VT_ENTER); sleep(500);
        drainRx(300);

        // ACCESS NUMBER
        writeByte(VT_DOWN); sleep(400);

        // access + ENTER → PRODUCT CODE
        write(String.valueOf(accessCode).getBytes()); sleep(100);
        writeByte(VT_ENTER); sleep(600);

        // product + ENTER → PRESET NET
        write(String.valueOf(pendingProduct).getBytes()); sleep(100);
        writeByte(VT_ENTER); sleep(600);

        // preset + ENTER (U32 × 10^1 → diviser par 10 pour obtenir la valeur réelle)
        // ex: pendingPreset=300 → envoyer '30' → registre affiche 30.0L
        int presetVal = (int)(pendingPreset / 10);
        write(String.valueOf(presetVal).getBytes()); sleep(100);
        writeByte(VT_ENTER); sleep(600);

        // START
        t("Lc3Link: Ctrl+B → START");
        writeByte(VT_START); sleep(800);
        drainRx(500);
    }

    // ── Lecture Mode 3 ────────────────────────────────────────────────────
    /**
     * Navigue en Mode 3 et lit la valeur d'un champ spécifique.
     * Lent (~3-4s). Utilisé pour SALE_NUMBER, TICKET_NUMBER, totaux.
     */
    private float readMode3Field(String fieldName) throws IOException {
        gotoMode(3);
        try {
            // Parcourir les champs en Ctrl+D jusqu'à trouver fieldName
            for (int i = 0; i < 40; i++) {
                String scr = readSpontaneous(1000);
                Matcher m = RE_FIELD.matcher(scr.split("\n")[0].trim());
                if (m.matches()) {
                    String name = m.group(1).trim();
                    if (name.equalsIgnoreCase(fieldName)) {
                        return parseFloat(m.group(2));
                    }
                }
                // Ctrl+D + ENTER pour avancer
                writeByte(VT_DOWN); sleep(200);
                writeByte(VT_ENTER);
            }
        } finally {
            backToMode1();
        }
        return 0f;
    }

    /**
     * Lit le serial ID depuis Mode 8.
     * "APPLICATION 14-308 REV 3-02-56  04/21/08"
     */
    private String readMode8Serial() throws IOException {
        gotoMode(8);
        try {
            String scr = readSpontaneous(1000);
            // Chercher la ligne APPLICATION
            for (String line : scr.split("\n")) {
                if (line.contains("APPLICATION")) {
                    return line.trim();
                }
            }
            return "LC3";
        } finally {
            backToMode1();
        }
    }

    /**
     * Navigue vers un mode via Ctrl+N + mode + ENTER (validé terrain COM4).
     * Key code = '0' par défaut (tous les modes ont key=0 sur ce registre).
     */
    private void gotoMode(int modeNum) throws IOException {
        writeByte(VT_M1);   sleep(300);
        writeByte(VT_ENTER); sleep(1000);
        drainRx(300);

        writeByte(VT_MODE); sleep(300);
        write(String.valueOf(modeNum).getBytes()); sleep(100);
        writeByte(VT_ENTER); sleep(1200);

        String scr = readSpontaneous(1000);
        // Si KEY? affiché → envoyer '0' + ENTER
        if (scr.toUpperCase().contains("KEY") && scr.contains("?")) {
            write(new byte[]{'0'}); writeByte(VT_ENTER); sleep(300);
            readSpontaneous(500);
        }
    }

    private void backToMode1() throws IOException {
        writeByte(VT_M1); sleep(300);
        drainRx(300);
    }

    // ── Poll screen ───────────────────────────────────────────────────────
    /**
     * Envoie E3 06 + E3 05 et lit la réponse VT-100.
     * Utilisé pour NET live et opDeliveryStatus.
     */
    private String pollScreen() throws IOException {
        write(CMD_POLL_A);
        write(CMD_POLL_B);
        byte[] raw = readRaw(600);
        String scr = decodeVt100(raw);
        if (scr.isEmpty()) {
            // Fallback E3 07
            write(CMD_SCREEN);
            raw = readRaw(800);
            scr = decodeVt100(raw);
        }
        return scr;
    }

    // ── I/O bas niveau ────────────────────────────────────────────────────
    private void writeByte(byte b) throws IOException {
        write(new byte[]{ b });
    }

    private void write(byte[] data) throws IOException {
        if (io.write(data, 2000) < 0) {
            throw new TransportException("Lc3Link: write failed");
        }
    }

    private byte[] readRaw(int timeoutMs) throws IOException {
        byte[] tmp = new byte[4096];
        byte[] result = new byte[0];
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int n = io.read(tmp, 50);
            if (n < 0) throw new TransportException("Lc3Link: transport closed");
            if (n > 0) {
                byte[] next = new byte[result.length + n];
                System.arraycopy(result, 0, next, 0, result.length);
                System.arraycopy(tmp, 0, next, result.length, n);
                result = next;
                // Prolonger si données arrivent encore
                deadline = Math.max(deadline, System.currentTimeMillis() + 200);
            }
        }
        return result;
    }

    private String readSpontaneous(int timeoutMs) throws IOException {
        return decodeVt100(readRaw(timeoutMs));
    }

    private void drainRx(int timeoutMs) {
        try { readRaw(timeoutMs); } catch (Exception ignored) {}
    }

    // ── Décodeur VT-100 ───────────────────────────────────────────────────
    /**
     * Décode un buffer VT-100 en texte lisible.
     * Identique à decode_vt100() Python validé sur ce registre.
     */
    static String decodeVt100(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < data.length) {
            int b = data[i] & 0xFF;
            if (b == 0x1B && i + 1 < data.length) {
                int next = data[i + 1] & 0xFF;
                if (next == 0x5B) {           // ESC [
                    i += 2;
                    while (i < data.length && !(data[i] >= 0x40 && data[i] <= 0x7E)) i++;
                    i++;
                } else if (next == 0x48) {    // ESC H
                    sb.append('\n'); i += 2;
                } else {
                    i += 2;
                }
            } else if (b == 0x0D || b == 0x0A) {
                sb.append('\n'); i++;
            } else if (b >= 0x20 && b < 0x7F) {
                sb.append((char) b); i++;
            } else {
                i++;
            }
        }
        // Retourner lignes non-vides
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

    // ── RegisterProbe ─────────────────────────────────────────────────────
    /**
     * Détecte si le transport est connecté à un LC3.
     *
     * Algorithme:
     *   1. Envoyer E3 07 (SCREEN)
     *   2. Si réponse contient "NET VOLUME LITRES" → LC3 confirmé ✅
     *   3. Si réponse contient "DISPLAY TERMINAL" → LC3 confirmé ✅
     *   4. Sinon → pas un LC3
     *
     * Appelé par RegisterSessionManager.getOrCreate() après échec LCP.
     */
    public static boolean probe(TransportIo io) {
        try {
            byte[] screen = {(byte)0x1B,(byte)0x7C,(byte)0xE3,
                              (byte)0x07,(byte)0xE4,(byte)0xA9,(byte)0xCA};
            io.write(screen, 1000);
            Thread.sleep(500);
            byte[] buf = new byte[512];
            int n = io.read(buf, 1000);
            if (n <= 0) return false;
            byte[] data = new byte[n];
            System.arraycopy(buf, 0, data, 0, n);
            String scr = decodeVt100(data);
            return scr.contains("NET VOLUME LITRES") ||
                   scr.contains("DISPLAY TERMINAL") ||
                   scr.contains("VT-100");
        } catch (Exception e) {
            return false;
        }
    }

    // ── Utilitaires ───────────────────────────────────────────────────────
    private void checkOpen() throws IOException {
        if (closed || !io.isOpen())
            throw new TransportException("Lc3Link: transport fermé");
    }

    private static byte[] encodeU32(int v) {
        return new byte[]{
            (byte)(v >> 24), (byte)(v >> 16), (byte)(v >> 8), (byte)v
        };
    }

    private static int beI32(byte[] b) {
        if (b == null || b.length < 4) return 0;
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) |
               ((b[2] & 0xFF) << 8)  |  (b[3] & 0xFF);
    }

    private static float parseFloat(String s) {
        try { return Float.parseFloat(s.trim()); }
        catch (Exception e) { return 0f; }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}