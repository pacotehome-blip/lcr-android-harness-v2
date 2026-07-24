package com.pa.lcr.lcp;

// ═══════════════════════════════════════════════════════════════════════
// COMPATIBILITÉ ANDROID : API 28 (Android 9) → API 35 (Android 15)
// ───────────────────────────────────────────────────────────────────────
// Toute modification de ce fichier doit être testée sur :
//   · Android 9  (API 28) — Samsung SM-T397U  · ADB 192.168.134.105:5555
//   · Android 15 (API 35) — Samsung R52X508K2DR · ADB 192.168.134.126:5555
//
// Règles obligatoires :
//   1. Détecter la version à l'exécution via Build.VERSION.SDK_INT
//   2. Appliquer le comportement EXPLICITEMENT par version — pas de spéculation
//   3. Ne jamais utiliser d'API introduite après API 28 sans guard de version
//   4. registerReceiver() : RECEIVER_NOT_EXPORTED ou RECEIVER_EXPORTED sur API 34+
//   5. PendingIntent     : FLAG_IMMUTABLE sur API 31+ · FLAG_MUTABLE + guard sur API 34+
//   6. startForeground() : type obligatoire sur API 34+ — doit matcher le manifest
//
// Constantes utiles :
//   Build.VERSION_CODES.P                = 28  (Android 9)
//   Build.VERSION_CODES.Q                = 29  (Android 10)
//   Build.VERSION_CODES.S                = 31  (Android 12)
//   Build.VERSION_CODES.TIRAMISU         = 33  (Android 13)
//   Build.VERSION_CODES.UPSIDE_DOWN_CAKE = 34  (Android 14)
//   Build.VERSION_CODES.VANILLA_ICE_CREAM= 35  (Android 15)
// ═══════════════════════════════════════════════════════════════════════

import com.pa.lcr.lcp.transport.TransportIo;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * LcpLink — Transport LCP (TransportIo strict)
 *
 * ✅ RX cumulatif (Python-like)
 * ✅ API publique strictement compatible DeliveryController
 *
 * Correctifs conservés:
 * 1) CRC/RX: CRC calculé sur la partie variable RAW (incluant ESC), comme Python et doc LCP
 * 2) RC=0x26/0x27: queued via 0x7D UNIQUEMENT pour commandes modifiantes (0x21/0x24)
 *    Sur GET_* (0x20/0x23/0x28): RC=0x26/0x27 = busy/skip -> pas de 0x7D
 * 3) Timeouts queueables: opSetField/opIssueCommand -> 30s
 * 4) sendRecv(): lecture en tranches (slice) pour ne pas bloquer l’envoi des 0x7D
 * 5) 0x7D immédiat après RC=0x26 sur commande queueable (comme Python)
 *
 * ✅ Option B: tout passe par TransportIo (USB/BT/WiFi)
 */
public class LcpLink {

    // ===================== ✅ A3: TransportException =====================
    /** Exception typée pour erreurs de transport (I/O read/write/closed). */
    public static final class TransportException extends IOException {
        public TransportException(String msg) { super(msg); }
        public TransportException(String msg, Throwable cause) { super(msg, cause); }
    }


    // ===================== CONSTANTES =====================
    public static final byte SYNC = 0x7E;
    private static final byte ESC = 0x1B;

    private static final int RC_OK = 0x00;
    private static final int RC_REQUEST_QUEUED = 0x26;
    private static final int RC_NO_REQUEST_ACTIVE = 0x27;
    private static final int RC_REQUEST_ABORTED = 0x28;

    private static final byte MSG_GET_FIELD = 0x20;
    private static final byte MSG_SET_FIELD = 0x21;
    private static final byte MSG_PRINT_TEXT         = 0x22;
    private static final byte MSG_GET_MACHINE_STATUS = 0x23;
    private static final byte MSG_ISSUE_COMMAND = 0x24;
    private static final byte MSG_GET_DELIVERY_STATUS = 0x28;
    private static final byte MSG_CHECK_REQUEST = 0x7D;

    // Cadence du CHECK_REQUEST
    private static final int QP_MS = 200;

    // Timeout "queued long" pour opérations modifiantes (SET_FIELD / ISSUE_COMMAND)
    private static final int OP_QUEUEABLE_TIMEOUT_MS = 30_000;

    // Slice de lecture pour permettre l'interleaving TX 0x7D / RX
    private static final int RX_SLICE_MS = 250;

    // ===================== TRANSPORT =====================
    private final TransportIo io;
    private final Object ioLock = new Object(); // lock par instance (évite blocage cross-media)
    private final int toAddr;
    private final int hostAddr;

    private volatile boolean closed = false;

    // ===================== TRACE =====================
    public interface TraceSink {
        void onTrace(String line);
    }

    private volatile TraceSink trace;
    private volatile boolean traceTsEnabled = false;

    private static final ThreadLocal<SimpleDateFormat> TRACE_DF =
            ThreadLocal.withInitial(() ->
                    new SimpleDateFormat("HH:mm:ss.SSS", Locale.CANADA_FRENCH));

    public void setTraceSink(TraceSink sink) { this.trace = sink; }
    public void setTraceTimestampsEnabled(boolean enabled) { this.traceTsEnabled = enabled; }

    private void t(String s) {
        TraceSink ts = trace;
        if (ts == null) return;

        if (traceTsEnabled && (s.startsWith("TX:") || s.startsWith("RX:") || s.startsWith("↳"))) {
            ts.onTrace("[IO " + TRACE_DF.get().format(new Date()) + "] " + s);
        } else {
            ts.onTrace(s);
        }
    }

    // ===================== RX BUFFER =====================
    private final ByteArray rxBuf = new ByteArray();

    // ===================== SESSION =====================
    private int msgIdBit = 0;
    private boolean syncUsed = false;

    // ===================== CTOR =====================
    public LcpLink(TransportIo io, int toAddr, int hostAddr, boolean syncFirst) {
        this.io = io;
        this.toAddr = toAddr & 0xFF;
        this.hostAddr = hostAddr & 0xFF;
        // syncFirst conservé pour compat; le comportement SYNC initial est maintenu via nextStatusByte()
    }

    // ===================== LIFECYCLE =====================
    public boolean isClosed() { return closed; }

    // ✅ Intervalle recommandé pour le live tick — LCR-II 19200 baud → 200ms
    // Override dans Lc3Link pour LC3 9600 baud → 800ms
    public long getRecommendedLiveIntervalMs() { return 200L; }

    // getters utiles validate/log/UI
    public int getToAddr() { return toAddr; }
    public int getHostAddr() { return hostAddr; }
    public String getTransportKey() { return (io != null) ? io.getKey() : null; }
    public long getTransportGenerationId() { return (io != null) ? io.getGenerationId() : 0L; }

    public synchronized void close() {
        closed = true;
        try {
            synchronized (ioLock) {
                if (io != null) io.close();
            }
        } catch (Exception ignored) {}
    }

    /** Compat DeliveryController : fermeture logique uniquement */
    public synchronized void softClose() { closed = true; }

    /** Compat API — volontairement NO-OP */
    public void drainInput(int ms) {}

    /** Compat API — volontairement NO-OP */
    public void forceSyncNext(String reason) {}

    // ===================== STRUCTURES PUBLIQUES =====================
    public static final class MachineStatus {
        public final int rc;
        public final int devStatus;
        public final int prnStatus;
        public final int delStatus;
        public final int delCode;
        public MachineStatus(int rc, int dev, int prn, int ds, int dc) {
            this.rc = rc;
            this.devStatus = dev;
            this.prnStatus = prn;
            this.delStatus = ds;
            this.delCode = dc;
        }
    }

    public interface ScanProgressCallback {
        void onProduct(String message);
    }

    public static final class ProductScanResult {
        public final int     noteIdx;
        public final String  description;
        public final boolean isPropane;
        public ProductScanResult(int noteIdx, String description) {
            this.noteIdx     = noteIdx;
            this.description = description != null ? description.trim() : "";
            this.isPropane   = this.description.toLowerCase(java.util.Locale.ROOT).contains("propane");
        }
        public String toSpinnerLabel() {
            if (description.isEmpty()) return String.valueOf(noteIdx);
            return noteIdx + " - " + description;
        }
    }

    // ===================== OPS PUBLIQUES =====================
    public MachineStatus opGetMachineStatus() throws IOException {
        Response r = sendRecv(buildPayload(MSG_GET_MACHINE_STATUS, null), 8000);
        ensureOk(r, "GET_MACHINE_STATUS");
        return new MachineStatus(
                r.payload[0] & 0xFF,
                r.payload[1] & 0xFF,
                r.payload[2] & 0xFF,
                u16be(r.payload[3], r.payload[4]),
                u16be(r.payload[5], r.payload[6])
        );
    }

    /** Timeout 30s pour commande queueable */
    public void opIssueCommand(int cmd) throws IOException {
        Response r = sendRecv(buildPayload(MSG_ISSUE_COMMAND, new byte[]{(byte) cmd}), OP_QUEUEABLE_TIMEOUT_MS);
        ensureOk(r, "ISSUE_COMMAND 0x" + hex2(cmd));
    }

    /**
     * Envoie une ligne de texte à l'imprimante LCR-II via MSG_PRINT_TEXT (0x22).
     * Chaque appel envoie une ligne; l'appelant gère les sauts de ligne si nécessaire.
     */
    public void opPrintText(String line) throws IOException {
        byte[] data = line.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] pl = new byte[1 + data.length];
        pl[0] = MSG_PRINT_TEXT;
        System.arraycopy(data, 0, pl, 1, data.length);
        Response r = sendRecv(pl, OP_QUEUEABLE_TIMEOUT_MS);
        ensureOk(r, "PRINT_TEXT");
    }

    /**
     * Diagnostic reset LCR-II — remet les compteurs net/gross à zéro.
     * Séquence: Auxiliary (0x03) → Print last ticket (0x06) → poll net/gross == 0.
     *
     * Utilisé quand le registre affiche une valeur négative après retour d'air
     * (ex: -0.1L) avant le démarrage d'une nouvelle livraison.
     *
     * @param maxWaitMs timeout poll (recommandé: 10000ms)
     * @return int[] {netBefore, grossBefore} en unités brutes du registre
     * @throws IOException si la communication BT échoue
     */
    public int[] opDiagnosticReset(int maxWaitMs) throws IOException {
        // Lire net/gross avant reset (fields #45 net, #44 gross)
        byte[] netRaw   = opGetField(45);
        byte[] grossRaw = opGetField(44);
        int netBefore   = toInt32(netRaw);
        int grossBefore = toInt32(grossRaw);

        android.util.Log.i("LcpLink",
            "opDiagnosticReset: avant net=" + netBefore + " gross=" + grossBefore);

        // Séquence reset: Auxiliary → Print last ticket
        opIssueCommand(0x03); // CMD_AUXILIARY
        try { Thread.sleep(300); } catch (Exception ignored) {}
        opIssueCommand(0x06); // CMD_PRINT_LAST_TICKET

        // Poll jusqu'à net >= 0 et gross >= 0
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(500); } catch (Exception ignored) {}
            try {
                byte[] n = opGetField(45);
                byte[] g = opGetField(44);
                int net   = toInt32(n);
                int gross = toInt32(g);
                android.util.Log.i("LcpLink",
                    "opDiagnosticReset poll: net=" + net + " gross=" + gross);
                if (net >= 0 && gross >= 0) {
                    android.util.Log.i("LcpLink", "opDiagnosticReset: reset OK");
                    break;
                }
            } catch (Exception ignored) {}
        }

        return new int[]{netBefore, grossBefore};
    }

    /** Convertit 4 bytes big-endian signé en int */
    private static int toInt32(byte[] b) {
        if (b == null || b.length < 4) return 0;
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16)
             | ((b[2] & 0xFF) << 8)  |  (b[3] & 0xFF);
    }

    /**
     * Synchronise date (Field #20) et heure (Field #21) du registre LCR-II
     * avec l'heure système de la tablette.
     * Format date : MM/DD/YY (selon Field #19 = 0, valeur par défaut)
     * Format heure : HH:MM:SS
     * Appelé après probeAndIdentify() à chaque connexion BT ou USB.
     */
    public void opSyncDateTime() throws IOException {
        // Lire Field #19 pour déterminer le format date (0=MM/DD/YY, 1=DD/MM/YY)
        byte[] fmt19 = null;
        try { fmt19 = opGetField(19, 800); } catch (Exception ignored) {}
        int dateFormatIdx = (fmt19 != null && fmt19.length > 0) ? (fmt19[0] & 0xFF) : 0;

        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        String dateStr, timeStr;
        if (dateFormatIdx == 1) {
            // DD/MM/YY
            dateStr = String.format(java.util.Locale.ROOT, "%02d/%02d/%02d",
                now.getDayOfMonth(), now.getMonthValue(), now.getYear() % 100);
        } else {
            // MM/DD/YY (défaut)
            dateStr = String.format(java.util.Locale.ROOT, "%02d/%02d/%02d",
                now.getMonthValue(), now.getDayOfMonth(), now.getYear() % 100);
        }
        timeStr = String.format(java.util.Locale.ROOT, "%02d:%02d:%02d",
            now.getHour(), now.getMinute(), now.getSecond());

        // Encoder en ASCIIZ (null-terminated)
        byte[] dateBytes = toAsciiz(dateStr);
        byte[] timeBytes = toAsciiz(timeStr);

        opSetField(20, dateBytes);
        opSetField(21, timeBytes);

        android.util.Log.i("LcpLink",
            "opSyncDateTime: date=" + dateStr + " heure=" + timeStr
            + " format=" + (dateFormatIdx == 1 ? "DD/MM/YY" : "MM/DD/YY"));
    }

    private static byte[] toAsciiz(String s) {
        byte[] ascii = s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] result = new byte[ascii.length + 1]; // +1 pour null terminator
        System.arraycopy(ascii, 0, result, 0, ascii.length);
        result[ascii.length] = 0x00;
        return result;
    }

    public byte[] opGetField(int field) throws IOException {
        Response r = sendRecv(buildPayload(MSG_GET_FIELD, new byte[]{(byte) field}), 5000);
        ensureOk(r, "GET_FIELD #" + field);
        byte[] out = new byte[r.payload.length - 2];
        System.arraycopy(r.payload, 2, out, 0, out.length);
        return out;
    }

    /** overload timeout court (scan rapide) */
    public byte[] opGetField(int field, int timeoutMs) throws IOException {
        Response r = sendRecv(buildPayload(MSG_GET_FIELD, new byte[]{(byte) field}), timeoutMs);
        ensureOk(r, "GET_FIELD #" + field);
        byte[] out = new byte[r.payload.length - 2];
        System.arraycopy(r.payload, 2, out, 0, out.length);
        return out;
    }

    /** Timeout 30s pour SET_FIELD queueable */
    public void opSetField(int field, byte[] value) throws IOException {
        byte[] pl = new byte[2 + (value == null ? 0 : value.length)];
        pl[0] = MSG_SET_FIELD;
        pl[1] = (byte) field;
        if (value != null) System.arraycopy(value, 0, pl, 2, value.length);
        Response r = sendRecv(pl, OP_QUEUEABLE_TIMEOUT_MS);
        ensureOk(r, "SET_FIELD #" + field);
    }

    public java.util.List<ProductScanResult> opScanAllProductNames(
            ScanProgressCallback progressLog) throws IOException {
        try { sendRecv(new byte[]{0x00}, 3000); } catch (Exception ignored) {}
        byte[] curRaw = opGetField(0);
        int originalIdx = (curRaw != null && curRaw.length > 0) ? (curRaw[0] & 0xFF) : 0;
        java.util.List<ProductScanResult> result = new java.util.ArrayList<>();
        try {
            for (int idx = 0; idx < 16; idx++) {
                try {
                    opSetField(0, new byte[]{(byte) idx});
                } catch (Exception e) {
                    result.add(new ProductScanResult(idx + 1, ""));
                    if (progressLog != null) progressLog.onProduct("Produit " + (idx + 1) + ": ");
                    continue;
                }
                try { Thread.sleep(80); } catch (Exception ignored) {}
                String desc = "";
                try {
                    byte[] f11 = opGetField(11);
                    if (f11 != null && f11.length > 0)
                        desc = new String(f11, java.nio.charset.StandardCharsets.US_ASCII)
                                   .replace("\0", "").trim();
                } catch (Exception ignored) {}
                result.add(new ProductScanResult(idx + 1, desc));
                if (progressLog != null) progressLog.onProduct("Produit " + (idx + 1) + ": " + desc);
            }
        } finally {
            try { opSetField(0, new byte[]{(byte) originalIdx}); } catch (Exception ignored) {}
        }
        return result;
    }

    public int[] opDeliveryStatus() throws IOException {
        Response r = sendRecv(buildPayload(MSG_GET_DELIVERY_STATUS, null), 6000);
        ensureOk(r, "GET_DELIVERY_STATUS");
        return new int[]{
                u16be(r.payload[2], r.payload[3]),
                u16be(r.payload[4], r.payload[5])
        };
    }

    /** overload timeout court (scan rapide) */
    public int[] opDeliveryStatus(int timeoutMs) throws IOException {
        Response r = sendRecv(buildPayload(MSG_GET_DELIVERY_STATUS, null), timeoutMs);
        ensureOk(r, "GET_DELIVERY_STATUS");
        return new int[]{
                u16be(r.payload[2], r.payload[3]),
                u16be(r.payload[4], r.payload[5])
        };
    }

    /**
     * ✅ Interprétation du bit "trop de retours de pulseur" dans le Delivery
     * Status Word — spécifique au protocole LCR-II (bit 0x0040). Méthode
     * surchargeable pour que Lc3Link (ou tout autre registre futur) puisse
     * redéfinir sa propre logique — ou retourner toujours false si ce concept
     * n'existe pas sur ce type de registre — sans jamais toucher à
     * DeliveryController, qui reste générique et appelle seulement cette
     * méthode via son link (LcpLink ou sous-classe).
     */
    public boolean isPulserReversalTerminated(int delStatus) {
        return (delStatus & 0x0040) != 0;
    }

    // ===================== SEND / RECV =====================
    private synchronized Response sendRecv(byte[] payload, int timeoutMs) throws IOException {
        if (closed) throw new TransportException("Transport closed");
        if (io == null) throw new TransportException("Transport null");
        if (!io.isOpen()) throw new TransportException("Transport not open");

        final byte msg = (payload != null && payload.length > 0) ? payload[0] : 0;

        // queued via 0x7D uniquement pour commandes modifiantes
        final boolean queueable = (msg == MSG_SET_FIELD) || (msg == MSG_ISSUE_COMMAND);

        byte[] frame = encodeFrame(payload);

        t("TX: " + hexDump(frame));
        synchronized (ioLock) {
            try {
                io.write(frame, 500);
            } catch (Exception e) {
                throw new TransportException("Error writing", e);
            }
        }

        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean queued = false;
        int lastQueued = -1;
        long nextCheck = 0L;

        while (System.currentTimeMillis() < deadline) {

            // 1) Envoi périodique 0x7D si queued
            if (queued && System.currentTimeMillis() >= nextCheck) {
                byte[] chk = encodeFrame(new byte[]{MSG_CHECK_REQUEST});
                t("TX: " + hexDump(chk));
                synchronized (ioLock) {
                    try {
                        io.write(chk, 500);
                    } catch (Exception e) {
                        throw new TransportException("Error writing", e);
                    }
                }
                nextCheck = System.currentTimeMillis() + QP_MS;
            }

            // 2) Lire en tranches courtes pour ne pas bloquer l’envoi des 0x7D
            long sliceDeadline = Math.min(deadline, System.currentTimeMillis() + RX_SLICE_MS);
            Frame f = readFrameUntil(sliceDeadline);
            if (f == null) {
                if (queued) continue;
                break;
            }

            t("RX: " + hexDump(f.raw));

            // ✅ Rejeter les trames d'un autre node — évite contamination buffer BT
            if (f.from != toAddr) {
                t("RX: ignoré — from=0x" + hex2(f.from) + " attendu=0x" + hex2(toAddr));
                continue;
            }

            int rc = (f.payload.length > 0) ? (f.payload[0] & 0xFF) : 0xFF;

            // 3) Busy/queued handling
            if (rc == RC_REQUEST_QUEUED || rc == RC_NO_REQUEST_ACTIVE) {
                if (!queueable) {
                    // GET_* : busy/skip, pas de 0x7D
                    return new Response(rc, f.payload);
                }
                // Commande queueable : on passe en mode queued + 0x7D ASAP
                queued = true;
                lastQueued = rc;
                nextCheck = System.currentTimeMillis(); // 0x7D immédiat
                continue;
            }

            if (rc == RC_REQUEST_ABORTED) {
                throw new IOException("Queued aborted");
            }

            // 4) Unwrap réponse queued: [OK, OK, ...] -> on enlève le 1er byte
            if (queued && rc == RC_OK && f.payload.length >= 2 && (f.payload[1] & 0xFF) == RC_OK) {
                byte[] norm = new byte[f.payload.length - 1];
                System.arraycopy(f.payload, 1, norm, 0, norm.length);
                return new Response(norm[0] & 0xFF, norm);
            }

            return new Response(rc, f.payload);
        }

        if (queued) throw new IOException("Queued timeout last=0x" + hex2(lastQueued));
        throw new IOException("Timeout waiting LCP response");
    }

    // ===================== RX =====================
    private void rxReadSome(int timeoutMs) throws IOException {
        byte[] tmp = new byte[64];
        int n;
        synchronized (ioLock) {
            if (closed) return;
            try {
                n = io.read(tmp, timeoutMs);
            } catch (Exception e) {
                throw new TransportException("Error reading", e);
            }
        }
        if (n > 0) rxBuf.appendBytes(tmp, 0, n);
    }

    private Frame readFrameUntil(long deadlineMs) throws IOException {
        while (!closed && System.currentTimeMillis() < deadlineMs) {
            rxReadSome(50);
            int syncPos = findSync(rxBuf);
            if (syncPos < 0) continue;
            if (syncPos > 0) rxBuf.drop(syncPos);
            Frame f = tryParseFrame(rxBuf);
            if (f != null) return f;
        }
        return null;
    }

    private int findSync(ByteArray b) {
        for (int i = 0; i + 1 < b.len; i++) {
            if ((b.buf[i] & 0xFF) == SYNC && (b.buf[i + 1] & 0xFF) == SYNC) return i;
        }
        return -1;
    }

    /**
     * CRC/RX robuste:
     * - calcule le CRC sur le flux RAW "variable part" (incluant ESC), comme Python
     */
    private Frame tryParseFrame(ByteArray b) {
        try {
            if (b.len < 6) return null;
            IntRef idx = new IntRef(2); // après "~~"
            ByteArray rawForCrc = new ByteArray();

            int to = readUnescapedAndCaptureRaw(b, rawForCrc, idx);
            int from = readUnescapedAndCaptureRaw(b, rawForCrc, idx);
            int status = readUnescapedAndCaptureRaw(b, rawForCrc, idx);
            int len = readUnescapedAndCaptureRaw(b, rawForCrc, idx);

            byte[] payload = new byte[len];
            for (int i = 0; i < len; i++) {
                payload[i] = (byte) readUnescapedAndCaptureRaw(b, rawForCrc, idx);
            }

            int crc0 = readCrcByte(b, idx);
            int crc1 = readCrcByte(b, idx);
            int recv = ((crc1 & 0xFF) << 8) | (crc0 & 0xFF);
            int calc = crcLcp(rawForCrc.buf, 0, rawForCrc.len);
            if (calc != recv) {
                b.drop(1);
                return null;
            }

            int rawLen = idx.v;
            byte[] raw = b.extract(rawLen);
            b.drop(rawLen);
            return new Frame(to, from, status, payload, raw);

        } catch (IncompleteFrameException e) {
            return null;
        }
    }

    private static final class IntRef { int v; IntRef(int v) { this.v = v; } }

    private int readUnescapedAndCaptureRaw(ByteArray b, ByteArray rawForCrc, IntRef idx)
            throws IncompleteFrameException {
        if (idx.v >= b.len) throw new IncompleteFrameException();
        int v = b.buf[idx.v] & 0xFF;
        if (v == (ESC & 0xFF)) {
            if (idx.v + 1 >= b.len) throw new IncompleteFrameException();
            rawForCrc.append(b.buf[idx.v]);
            rawForCrc.append(b.buf[idx.v + 1]);
            int unesc = b.buf[idx.v + 1] & 0xFF;
            idx.v += 2;
            return unesc;
        } else {
            rawForCrc.append(b.buf[idx.v]);
            idx.v += 1;
            return v;
        }
    }

    private int readCrcByte(ByteArray b, IntRef idx) throws IncompleteFrameException {
        if (idx.v >= b.len) throw new IncompleteFrameException();
        int v = b.buf[idx.v] & 0xFF;
        if (v == (ESC & 0xFF)) {
            if (idx.v + 1 >= b.len) throw new IncompleteFrameException();
            int unesc = b.buf[idx.v + 1] & 0xFF;
            idx.v += 2;
            return unesc;
        } else {
            idx.v += 1;
            return v;
        }
    }

    // ===================== FRAMING / CRC =====================
    private byte[] encodeFrame(byte[] payload) {
        int status = nextStatusByte();

        ByteArray var = new ByteArray();
        var.append((byte) toAddr);
        var.append((byte) hostAddr);
        var.append((byte) status);
        var.append((byte) payload.length);
        var.appendBytes(payload, 0, payload.length);

        ByteArray esc = new ByteArray();
        for (int i = 0; i < var.len; i++) esc.appendEscaped(var.buf[i]);

        int crc = crcLcp(esc.buf, 0, esc.len);

        ByteArray out = new ByteArray();
        out.append(SYNC);
        out.append(SYNC);
        out.appendBytes(esc.buf, 0, esc.len);
        out.appendEscaped((byte) (crc & 0xFF));
        out.appendEscaped((byte) ((crc >> 8) & 0xFF));
        return out.toArray();
    }

    private int nextStatusByte() {
        int st = msgIdBit & 1;
        if (!syncUsed) {
            st = 0x02;
            syncUsed = true;
        }
        msgIdBit ^= 1;
        return st;
    }

    // ===================== UTIL =====================
    private static byte[] buildPayload(byte msg, byte[] tail) {
        if (tail == null || tail.length == 0) return new byte[]{msg};
        byte[] out = new byte[1 + tail.length];
        out[0] = msg;
        System.arraycopy(tail, 0, out, 1, tail.length);
        return out;
    }

    private static void ensureOk(Response r, String ctx) throws IOException {
        if (r.rc != RC_OK) throw new IOException(ctx + " rc=0x" + hex2(r.rc));
    }

    private static int crcLcp(byte[] data, int off, int len) {
        int crc = 0x7E7E;
        for (int i = off; i < off + len; i++) {
            int b = data[i] & 0xFF;
            for (int bit = 7; bit >= 0; bit--) {
                boolean fb = (crc & 0x8000) != 0;
                crc = ((crc << 1) & 0xFFFF) | ((b >> bit) & 1);
                if (fb) crc ^= 0x1021;
            }
        }
        return crc & 0xFFFF;
    }

    private static int u16be(byte hi, byte lo) { return ((hi & 0xFF) << 8) | (lo & 0xFF); }

    private static String hex2(int v) { return String.format("%02X", v & 0xFF); }

    private static String hexDump(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(hex2(b[i] & 0xFF));
        }
        return sb.toString();
    }

    // ===================== STRUCTURES INTERNES =====================
    private static final class Frame {
        final int to, from, status;
        final byte[] payload;
        final byte[] raw;
        Frame(int to, int from, int status, byte[] payload, byte[] raw) {
            this.to = to;
            this.from = from;
            this.status = status;
            this.payload = payload;
            this.raw = raw;
        }
    }

    private static final class Response {
        final int rc;
        final byte[] payload;
        Response(int rc, byte[] payload) {
            this.rc = rc;
            this.payload = payload;
        }
    }

    private static final class IncompleteFrameException extends Exception {}

    private static final class ByteArray {
        byte[] buf = new byte[256];
        int len = 0;

        void append(byte b) { ensure(1); buf[len++] = b; }

        void appendBytes(byte[] b, int off, int l) {
            ensure(l);
            System.arraycopy(b, off, buf, len, l);
            len += l;
        }

        void appendEscaped(byte b) {
            int v = b & 0xFF;
            if (v == (ESC & 0xFF) || v == (SYNC & 0xFF)) append(ESC);
            append(b);
        }

        byte[] extract(int n) {
            byte[] out = new byte[n];
            System.arraycopy(buf, 0, out, 0, n);
            return out;
        }

        void drop(int n) {
            if (n <= 0) return;
            System.arraycopy(buf, n, buf, 0, len - n);
            len -= n;
        }

        byte[] toArray() {
            byte[] out = new byte[len];
            System.arraycopy(buf, 0, out, 0, len);
            return out;
        }

        private void ensure(int extra) {
            if (len + extra <= buf.length) return;
            byte[] nb = new byte[Math.max(buf.length * 2, len + extra + 64)];
            System.arraycopy(buf, 0, nb, 0, len);
            buf = nb;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Comportement vanne post-preset — à overrider dans Lc3Link
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Indique si le registre contrôle la vanne mécaniquement via solénoïde.
     *
     * LCR-II (LcpLink) : true — le registre coupe le solénoïde au preset.
     *   Si du volume sort après DONE, c'est une défaillance mécanique
     *   (solénoïde défaillant, fuite hydraulique, bypass manuel).
     *
     * LC3 (Lc3Link override) : false — pas de contrôle solénoïde via protocole.
     *   Le chauffeur doit fermer la vanne manuellement après PRESET STOP.
     *   Si du volume sort après DONE, le chauffeur n'a pas encore fermé.
     */
    public boolean isValveControlledByRegister() {
        return true; // LCR-II : solénoïde contrôlé par le registre
    }

    /**
     * Message d'alerte fuite vanne à afficher au chauffeur.
     * Adapté selon le type de registre et son mode de contrôle de vanne.
     *
     * @param ticketNo  numéro de ticket de la livraison terminée
     * @param netRef    volume net au moment de la coupure (litres)
     * @param netNow    volume net mesuré maintenant (litres)
     * @param delta     volume additionnel détecté (litres)
     */
    public String getLeakAlertMessage(String ticketNo, double netRef,
            double netNow, double delta) {
        // LCR-II : le registre coupe le solenoide au preset via commande hardware.
        // Si du volume sort apres DONE, defaillance mecanique ou electrique.
        StringBuilder sb = new StringBuilder();
        sb.append("VOLUME DETECTE APRES COUPURE DU REGISTRE").append("\n\n");
        sb.append("Ticket : ").append(ticketNo).append("\n");
        sb.append(String.format(java.util.Locale.ROOT, "Volume au preset   : %.3f L net\n", netRef));
        sb.append(String.format(java.util.Locale.ROOT, "Volume actuel      : %.3f L net\n", netNow));
        sb.append(String.format(java.util.Locale.ROOT, "Volume additionnel : %.3f L\n\n", delta));
        sb.append("Le registre LCR-II a coupe le solenoide au preset.").append("\n");
        sb.append("Un volume continue d'etre mesure - verifiez :").append("\n");
        sb.append("  - La vanne physique et le circuit hydraulique").append("\n");
        sb.append("  - Le solenoide (defaillance possible)").append("\n");
        sb.append("  - Toute vanne de bypass ouverte manuellement");
        return sb.toString();
    }
}
