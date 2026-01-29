
package com.pa.lcr.lcp;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * LcpLink - couche de lien LCP pour LCR-II.
 *
 * Fournit :
 *  - DUMP_TX / DUMP_RX pour tracer TX/RX
 *  - Logger configurable via setLogger
 *  - Constructeur (serialPort, to, from, sync)
 *  - Méthodes sendRecv(byte[], [int timeoutMs]) attendues par LcpOps/MainActivity
 *  - Méthodes statiques extractStatus / extractPayload (utilisées par LcpOps)
 *  - Utilitaires CRC16/XMODEM et hexdump
 *
 * NOTE I/O : transact(...) contient une SIMULATION. Branche ton I/O série réelle quand prêt.
 */
public class LcpLink {

    /** Active l'affichage du TX via le logger. */
    public static boolean DUMP_TX = false;
    /** Active l'affichage du RX via le logger. */
    public static boolean DUMP_RX = false;

    private static Consumer<String> LOGGER = s -> {};

    /** Définit le logger (ex.: MainActivity::appendAndBuffer). */
    public static void setLogger(Consumer<String> logger) {
        LOGGER = (logger != null) ? logger : (s -> {});
    }

    private static void log(String msg) {
        try { LOGGER.accept(msg); } catch (Exception ignored) {}
    }

    // --- Contexte lien ---
    private final Object serialPort; // Remplacer par le type réel de l’adaptateur série si souhaité
    private final int toAddr;        // 0..255
    private final int fromAddr;      // 0..255
    private final boolean syncMode;
    private int defaultTimeoutMs = 1000;

    /**
     * @param serialPort  adaptateur série (USB/RS-232/TCP...). Peut rester 'Object' pour compiler.
     * @param to          adresse 'to' (0..255)
     * @param from        adresse 'from' (0..255)
     * @param sync        true = effectuer une séquence SYNC au démarrage (si implémentée)
     */
    public LcpLink(Object serialPort, int to, int from, boolean sync) {
        this.serialPort = serialPort;
        this.toAddr     = to & 0xFF;
        this.fromAddr   = from & 0xFF;
        this.syncMode   = sync;
        log("LcpLink: to=" + this.toAddr + ", from=" + this.fromAddr + ", sync=" + this.syncMode);
    }

    /** Timeout par défaut pour transact (ms). */
    public void setDefaultTimeoutMs(int ms) { this.defaultTimeoutMs = Math.max(1, ms); }
    public int  getDefaultTimeoutMs()       { return defaultTimeoutMs; }

    // ------------------------------------------------------------------------
    // API attendue par LcpOps / MainActivity
    // ------------------------------------------------------------------------

    /**
     * Envoie un message LCP dont le premier octet est le code commande (MSG_*)
     * et les suivants (éventuels) sont le payload pour cette commande.
     *
     * Exemple d’appel :
     *   sendRecv(new byte[]{ (byte)MSG_GET_FIELD, (byte)(f & 0xFF) }, timeout)
     *
     * @param payload  [CMD][ARGS...]
     * @param timeoutMs timeout en millisecondes
     * @return trame de réponse "logique": [status][payload...] (sans CRC)
     */
    public byte[] sendRecv(byte[] payload, int timeoutMs) throws IOException {
        if (payload == null || payload.length == 0) {
            throw new IOException("sendRecv: payload must contain at least the command byte");
        }
        byte cmd = payload[0];
        byte[] args = (payload.length > 1) ? Arrays.copyOfRange(payload, 1, payload.length) : new byte[0];
        byte[] frame = buildCommandFrame(toAddr, fromAddr, cmd, args);
        return transact(frame, timeoutMs);
    }

    /** Surcharge utilisant le timeout par défaut. */
    public byte[] sendRecv(byte[] payload) throws IOException {
        return sendRecv(payload, defaultTimeoutMs);
    }

    // API alternative (cmd/payload séparés) si tu veux l'utiliser
    public byte[] command(byte cmd, byte[] payload) throws IOException {
        byte[] frame = buildCommandFrame(toAddr, fromAddr, cmd, payload);
        return transact(frame, defaultTimeoutMs);
    }

    /**
     * Point d'échange bas-niveau : écrit 'frame' sur le port série et lit la réponse.
     * ⚠️ SIMULATION ACTUELLE : si tu n'as pas encore d'I/O réelle, on génère une réponse plausible
     * conformément aux attentes de LcpOps.
     *
     * Convention de retour: [status][payload...] (sans CRC),
     * car LcpOps utilise extractStatus/extractPayload sur ce format.
     */
    public byte[] transact(byte[] frame, int timeoutMs) throws IOException {
        if (frame == null) throw new IOException("Frame is null");
        if (DUMP_TX) log("TX " + hexdump(frame));

        // --- I/O réelle à brancher ici ---
        // Exemple (pseudo):
        // serial.write(frame);
        // byte[] rsp = serial.readUntilComplete(timeoutMs);
        // if (DUMP_RX) log("RX " + hexdump(rsp));
        // return rsp;

        // --- SIMULATION par commande ---
        byte cmd = (frame.length >= 4) ? frame[3] : (byte)0x00;
        byte[] rsp = simulateResponseFor(cmd, frame);

        if (DUMP_RX) log("RX " + hexdump(rsp));
        return rsp;
    }

    /**
     * Simule des réponses plausibles pour débloquer l’app.
     * Convention: on retourne [status][payload...] (sans CRC).
     */
    private byte[] simulateResponseFor(byte cmd, byte[] txFrame) {
        final byte ST_OK = 0x00;

        switch (u8(cmd)) {
            // 0x00 - RESYNC / GET PRODUCT ID (d’après tes logs)
            case 0x00: {
                // payload exemple: [rc=0x00, productId=0x02, 'PROPANE', 0x00]
                byte[] name = "PROPANE".getBytes();
                byte[] payload = new byte[2 + name.length + 1];
                int i = 0;
                payload[i++] = 0x00;         // rc
                payload[i++] = 0x02;         // productId
                System.arraycopy(name, 0, payload, i, name.length); i += name.length;
                payload[i] = 0x00;           // zero-terminated
                return concat(new byte[]{ ST_OK }, payload);
            }

            // 0x20 - GET_FIELD: LcpOps attend p[0]==RC_OK, et renvoie p[2..] comme data (optionnelle).
            case 0x20: {
                // Renvoie minimal: rc=OK, meta=0x00 => out (p[2..]) sera vide et valide.
                byte[] payload = new byte[] { 0x00, 0x00 };
                return concat(new byte[]{ ST_OK }, payload);
            }

            // 0x21 - SET_FIELD: LcpOps attend p non-vide et p[0]==RC_OK.
            case 0x21: {
                byte[] payload = new byte[] { 0x00 }; // rc=OK
                return concat(new byte[]{ ST_OK }, payload);
            }

            // 0x23 - GET_MACHINE: LcpOps attend p.length>=8, p[0]==RC_OK.
            // dev = p[2..3], ds = p[4..5], dc = p[6..7]
            case 0x23: {
                int dev = 0x0000; // device flags (exemple)
                int ds  = 0x0000; // delivery status flags (idle)
                int dc  = 0x0000; // delivery conditions flags
                byte[] payload = new byte[] {
                        0x00, 0x00,
                        (byte)((dev >> 8) & 0xFF), (byte)(dev & 0xFF),
                        (byte)((ds  >> 8) & 0xFF), (byte)(ds  & 0xFF),
                        (byte)((dc  >> 8) & 0xFF), (byte)(dc  & 0xFF)
                };
                return concat(new byte[]{ ST_OK }, payload);
            }

            // 0x24 - ISSUE_COMMAND: LcpOps attend p non-vide et p[0]==RC_OK. (ex.: END/RESET #2)
            case 0x24: {
                byte[] payload = new byte[] { 0x00 }; // rc=OK
                return concat(new byte[]{ ST_OK }, payload);
            }

            // 0x28 - GET_DEL_STATUS: LcpOps attend p.length>=6 et p[0]==RC_OK.
            // ds=p[2..3], dc=p[4..5]
            case 0x28: {
                int ds = 0x0000; // idle
                int dc = 0x0000;
                byte[] payload = new byte[] {
                        0x00, 0x00,
                        (byte)((ds >> 8) & 0xFF), (byte)(ds & 0xFF),
                        (byte)((dc >> 8) & 0xFF), (byte)(dc & 0xFF)
                };
                return concat(new byte[]{ ST_OK }, payload);
            }

            // 0x7D - CHECK_REQUEST: utilisé par waitQueued.
            // On répond "pas de requête active" pour ne pas perturber, mais comme on ne queue jamais nos réponses
            // (on renvoie RC_OK immédiatement ailleurs), waitQueued ne devrait pas être sollicité.
            case 0x7D: {
                // RC_NO_REQUEST_ACTIVE (0x27)
                return new byte[] { ST_OK, 0x27 };
            }

            default: {
                // Par défaut : status OK, payload vide (safe).
                return new byte[] { ST_OK };
            }
        }
    }

    // ------------------------------------------------------------------------
    // Helpers de parsing/assemblage
    // ------------------------------------------------------------------------

    /** Extrait le status d'une réponse (convention: premier octet = status). */
    public static int extractStatus(byte[] frame) {
        if (frame == null || frame.length == 0) return 0;
        return frame[0] & 0xFF;
    }

    /** Extrait le payload d'une réponse (après le status). */
    public static byte[] extractPayload(byte[] frame) {
        if (frame == null || frame.length <= 1) return new byte[0];
        byte[] out = new byte[frame.length - 1];
        System.arraycopy(frame, 1, out, 0, out.length);
        return out;
    }

    /**
     * Construit une trame "commande" LCP:
     *   [0x22][TO][FROM][CMD][PAYLOAD...][CRC16/XMODEM hi][CRC16 lo]
     * Ajuste si ton dialecte diffère.
     */
    public static byte[] buildCommandFrame(int to, int from, byte cmd, byte[] payload) {
        int plen = (payload == null) ? 0 : payload.length;
        int headerLen = 4;
        byte[] frame = new byte[headerLen + plen + 2]; // +2 = CRC16
        frame[0] = 0x22;
        frame[1] = (byte) (to & 0xFF);
        frame[2] = (byte) (from & 0xFF);
        frame[3] = cmd;
        if (plen > 0) System.arraycopy(payload, 0, frame, headerLen, plen);

        int crc = crc16Xmodem(frame, 0, headerLen + plen);
        int idx = headerLen + plen;
        frame[idx]     = (byte) ((crc >> 8) & 0xFF);
        frame[idx + 1] = (byte) (crc & 0xFF);
        return frame;
    }

    /** Vérifie le CRC16/XMODEM (les 2 derniers octets sont le CRC). */
    public static boolean verifyFrameCrc(byte[] frame) {
        if (frame == null || frame.length < 6) return false;
        int bodyLen = frame.length - 2;
        int crcCalc = crc16Xmodem(frame, 0, bodyLen);
        int crcGot  = ((frame[bodyLen] & 0xFF) << 8) | (frame[bodyLen + 1] & 0xFF);
        return (crcCalc == crcGot);
    }

    /** CRC16/XMODEM (poly 0x1021, init 0x0000). */
    public static int crc16Xmodem(byte[] data, int off, int len) {
        int crc = 0x0000;
        for (int i = 0; i < len; i++) {
            crc ^= (data[off + i] & 0xFF) << 8;
            for (int b = 0; b < 8; b++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc <<= 1;
                }
                crc &= 0xFFFF;
            }
        }
        return crc & 0xFFFF;
    }

    /** Hexdump lisible (ex.: "22 FA FF 28 58 20"). */
    public static String hexdump(byte[] a) {
        if (a == null) return "(null)";
        StringBuilder sb = new StringBuilder(a.length * 3);
        for (int i = 0; i < a.length; i++) {
            sb.append(String.format("%02X", a[i] & 0xFF));
            if (i + 1 < a.length) sb.append(' ');
        }
        return sb.toString();
    }

    // Utils
    private static byte[] concat(byte[] a, byte[] b) {
        if (a == null || a.length == 0) return (b == null ? new byte[0] : b.clone());
        if (b == null || b.length == 0) return a.clone();
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // Helpers "unsigned"
    public static int u8(byte v) { return v & 0xFF; }
}
