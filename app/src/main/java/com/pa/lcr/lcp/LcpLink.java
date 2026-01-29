
package com.pa.lcr.lcp;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * LcpLink - couche de lien LCP pour LCR-II.
 *
 * Cette implémentation fournit :
 *  - DUMP_TX / DUMP_RX pour tracer TX/RX
 *  - Logger configurable via setLogger
 *  - Constructeur (serialPort, to, from, sync)
 *  - Méthodes statiques extractStatus / extractPayload (utilisées par LcpOps)
 *  - Utilitaires CRC16/XMODEM et hexdump
 *
 * Remarque: la méthode transact(...) est un PLUG (à compléter avec ton I/O série réel).
 * Elle renvoie une trame de réponse minimale [status=0x00] pour éviter les NullPointer/IO
 * lors d'un simple build/launch. Adapte-la à ta stack (UsbSerialPort, jSerialComm, etc.).
 */
public class LcpLink {

    /** Active l'affichage du TX via le logger. */
    public static boolean DUMP_TX = false;
    /** Active l'affichage du RX via le logger. */
    public static boolean DUMP_RX = false;

    private static Consumer<String> LOGGER = s -> {};

    /** Définit le logger (par ex.: MainActivity::appendAndBuffer). */
    public static void setLogger(Consumer<String> logger) {
        LOGGER = (logger != null) ? logger : (s -> {});
    }

    private static void log(String msg) {
        try {
            LOGGER.accept(msg);
        } catch (Exception ignored) {
        }
    }

    // --- Contexte lien ---
    private final Object serialPort; // Remplace 'Object' par le type réel de ton adaptateur série si besoin
    private final int toAddr;        // 0..255
    private final int fromAddr;      // 0..255
    private final boolean syncMode;
    private int defaultTimeoutMs = 1000;

    /**
     * @param serialPort  instance d'adaptateur série (USB/RS-232/JNI...). Peut rester 'Object' pour compiler.
     * @param to          adresse 'to' (0..255)
     * @param from        adresse 'from' (0..255)
     * @param sync        true = effectuer une séquence SYNC au démarrage (à implémenter dans transact si besoin)
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
    // API haute-niveau (exemples) - garde les signatures simples et stables
    // ------------------------------------------------------------------------

    /**
     * Envoie une commande (cmd + payload) et retourne la réponse brute.
     * Adapte buildCommandFrame(...) et transact(...) à ton protocole exact si nécessaire.
     */
    public byte[] command(byte cmd, byte[] payload) throws IOException {
        byte[] frame = buildCommandFrame(toAddr, fromAddr, cmd, payload);
        return transact(frame, defaultTimeoutMs);
    }

    /**
     * Point d'échange bas-niveau : écrit 'frame' sur le port série et lit la réponse.
     * <p>
     * ⚠️ Placeholder : à remplacer par ta vraie I/O série.
     * Pour l’instant, retourne une réponse minimale [status=0x00] pour éviter les erreurs à l’exécution.
     */
    public byte[] transact(byte[] frame, int timeoutMs) throws IOException {
        if (frame == null) throw new IOException("Frame is null");
        if (DUMP_TX) log("TX " + hexdump(frame));

        if (serialPort == null) {
            // Aucun port attaché : on échoue de manière contrôlée
            // (si tu préfères, renvoie plutôt une "fausse" réponse OK comme ci-dessous).
            // throw new IOException("serialPort is null: bind a real serial adapter to LcpLink");
        }

        // TODO: Implémente ici l’écriture de 'frame' et la lecture d'une réponse encodée LCP.
        //       Exemple (pseudo):
        //       serial.write(frame);
        //       byte[] rsp = serial.readUntilCrcOrTimeout(timeoutMs);
        //       if (DUMP_RX) log("RX " + hexdump(rsp));
        //       return rsp;

        // Réponse minimale: status=0x00, payload vide
        byte[] rsp = new byte[] { (byte) 0x00 };
        if (DUMP_RX) log("RX " + hexdump(rsp));
        return rsp;
    }

    // ------------------------------------------------------------------------
    // Helpers de parsing/assemblage (adapte au format exact si nécessaire)
    // ------------------------------------------------------------------------

    /**
     * Extrait le status d'une réponse LCP.
     * Convention minimale: premier octet = status.
     * Adapte si ton protocole diffère (p.ex. status à un autre offset).
     */
    public static int extractStatus(byte[] frame) {
        if (frame == null || frame.length == 0) return 0;
        return frame[0] & 0xFF;
    }

    /**
     * Extrait le payload d'une réponse LCP (après le status).
     * Adapte si ton protocole diffère.
     */
    public static byte[] extractPayload(byte[] frame) {
        if (frame == null || frame.length <= 1) return new byte[0];
        byte[] out = new byte[frame.length - 1];
        System.arraycopy(frame, 1, out, 0, out.length);
        return out;
    }

    /**
     * Construit une trame "commande" LCP: [0x22][TO][FROM][CMD][PAYLOAD...][CRC16/XMODEM hi][CRC16 lo]
     * ⚠️ Cette forme est un canevas courant. Ajuste si ton dialecte LCR-II diffère.
     */
    public static byte[] buildCommandFrame(int to, int from, byte cmd, byte[] payload) {
        int plen = (payload == null) ? 0 : payload.length;
        // 0x22 = SOH/prologue souvent observé; adapte si besoin.
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

    /** Vérifie le CRC16/XMODEM d'une trame (les 2 derniers octets sont censés contenir le CRC). */
    public static boolean verifyFrameCrc(byte[] frame) {
        if (frame == null || frame.length < 6) return false;
        int bodyLen = frame.length - 2;
        int crcCalc = crc16Xmodem(frame, 0, bodyLen);
        int crcGot  = ((frame[bodyLen] & 0xFF) << 8) | (frame[bodyLen + 1] & 0xFF);
        return (crcCalc == crcGot);
    }

    /** CRC16/XMODEM standard (poly 0x1021, init 0x0000, no refin/refout, xorout 0x0000). */
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

    /** Hexdump lisible (p.ex. "22 01 02 A0 00 9F"). */
    public static String hexdump(byte[] a) {
        if (a == null) return "(null)";
        StringBuilder sb = new StringBuilder(a.length * 3);
        for (int i = 0; i < a.length; i++) {
            sb.append(String.format("%02X", a[i] & 0xFF));
            if (i + 1 < a.length) sb.append(' ');
        }
        return sb.toString();
    }

    // Helpers "unsigned"
    public static int u8(byte v) { return v & 0xFF; }
}
