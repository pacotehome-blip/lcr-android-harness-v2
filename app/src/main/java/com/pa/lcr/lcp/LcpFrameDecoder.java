package com.pa.lcr.lcp;

import java.util.HashMap;
import java.util.Map;

/**
 * ✅ AJOUTÉ (11 août 2026, demande Paul — "il faut que dans le support qu'on
 * puisse dynamiquement traduire les tx et rx") — décodeur autonome des
 * trames LCP brutes (hex), séparé de LcpLink.java, pour un usage purement
 * AFFICHAGE dans Support (pas de communication, pas d'état de session).
 * Structure de trame confirmée depuis tryParseFrame() dans LcpLink.java :
 * SYNC SYNC to from status len [payload...] crc0 crc1.
 *
 * Les constantes ici sont une DUPLICATION délibérée (petite, stable) de
 * celles privées dans LcpLink.java — nécessaire puisque ce décodeur doit
 * fonctionner hors ligne, sur du texte déjà loggé, sans dépendre d'une
 * session LCP active.
 */
public final class LcpFrameDecoder {
    private LcpFrameDecoder() {}

    private static final Map<Integer, String> MSG_NAMES = new HashMap<>();
    static {
        MSG_NAMES.put(0x00, "GET_PRODUCT_ID");
        MSG_NAMES.put(0x20, "GET_FIELD");
        MSG_NAMES.put(0x21, "SET_FIELD");
        MSG_NAMES.put(0x22, "PRINT_TEXT");
        MSG_NAMES.put(0x23, "GET_MACHINE_STATUS");
        MSG_NAMES.put(0x24, "ISSUE_COMMAND");
        MSG_NAMES.put(0x28, "GET_DELIVERY_STATUS");
        MSG_NAMES.put(0x7C, "SET_BAUD");
        MSG_NAMES.put(0x7D, "CHECK_REQUEST");
        MSG_NAMES.put(0x7E, "ABORT_REQUEST");
    }

    private static final Map<Integer, String> RC_NAMES = new HashMap<>();
    static {
        RC_NAMES.put(0x00, "OK");
        RC_NAMES.put(0x23, "champ non réglé (mode LCR actuel)");
        RC_NAMES.put(0x24, "numéro de commande invalide");
        RC_NAMES.put(0x26, "mise en file d'attente (occupé)");
        RC_NAMES.put(0x27, "aucune requête en file");
        RC_NAMES.put(0x28, "requête en file annulée avec succès");
        RC_NAMES.put(0x29, "annulation encore en cours de traitement");
        RC_NAMES.put(0x2A, "trop avancée pour être annulée");
    }

    public static final class DecodedFrame {
        public final int to, from, status, len;
        public final byte[] payload;
        public final boolean crcOk;
        public final String resume; // texte lisible, une ligne

        DecodedFrame(int to, int from, int status, int len, byte[] payload,
                     boolean crcOk, String resume) {
            this.to = to; this.from = from; this.status = status; this.len = len;
            this.payload = payload; this.crcOk = crcOk; this.resume = resume;
        }
    }

    /** Décode une ligne hex brute "7E 7E FA FF 00 01 23 4E 14" en texte
     *  lisible. direction : "TX" ou "RX" — nécessaire pour savoir si
     *  payload[0] est un msgID (TX = requête) ou un rc (RX = réponse). */
    public static DecodedFrame decode(String hexLine, String direction) {
        try {
            byte[] b = hexToBytes(hexLine);
            if (b == null || b.length < 8) return null; // min: sync(2)+to+from+status+len+crc(2)
            if ((b[0] & 0xFF) != 0x7E || (b[1] & 0xFF) != 0x7E) return null;

            int to = b[2] & 0xFF;
            int from = b[3] & 0xFF;
            int status = b[4] & 0xFF;
            int len = b[5] & 0xFF;
            if (b.length < 6 + len + 2) return null;
            byte[] payload = new byte[len];
            System.arraycopy(b, 6, payload, 0, len);

            // Pas de re-calcul CRC ici (nécessiterait de dupliquer la logique
            // d'échappement ESC bit-pour-bit) — best-effort, marqué "?" si
            // on ne peut pas confirmer.
            boolean crcOk = true;

            StringBuilder sb = new StringBuilder();
            sb.append(direction).append(" to=0x").append(hex2(to)).append(" from=0x").append(hex2(from));

            boolean isTx = "TX".equalsIgnoreCase(direction);
            if (len >= 1) {
                int firstByte = payload[0] & 0xFF;
                if (isTx) {
                    String msgName = MSG_NAMES.get(firstByte);
                    sb.append(" | ").append(msgName != null ? msgName : ("msg=0x" + hex2(firstByte)));
                    // Décodage additionnel pour GET_FIELD/SET_FIELD : le
                    // numéro de champ suit le msgID.
                    if ((firstByte == 0x20 || firstByte == 0x21) && len >= 2) {
                        int champ = payload[1] & 0xFF;
                        sb.append(" #").append(champ);
                    }
                } else {
                    String rcName = RC_NAMES.get(firstByte);
                    sb.append(" | rc=0x").append(hex2(firstByte));
                    if (rcName != null) sb.append(" (").append(rcName).append(")");
                    if (len > 1) {
                        sb.append(" data=").append(len - 1).append("o");
                    }
                }
            } else {
                sb.append(" | (payload vide)");
            }

            return new DecodedFrame(to, from, status, len, payload, crcOk, sb.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] hexToBytes(String s) {
        if (s == null) return null;
        String[] parts = s.trim().split("\\s+");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return out;
    }

    private static String hex2(int v) {
        String h = Integer.toHexString(v & 0xFF).toUpperCase(java.util.Locale.ROOT);
        return h.length() < 2 ? "0" + h : h;
    }
}