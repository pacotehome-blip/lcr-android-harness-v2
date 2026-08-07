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
    private static final byte VT_UP    = 0x15;
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
    private static final int FIELD_PRESET_GROSS   = 7;
    private static final int FIELD_PRESET_PRICE   = 8;
    private static final int FIELD_GROSS_TOTAL    = 17;
    private static final int FIELD_NET_TOTAL      = 18;
    private static final int FIELD_SALE_NUMBER    = 22;
    private static final int FIELD_TICKET_NUMBER  = 23;
    private static final int FIELD_DECIMALS       = 39;
    private static final int FIELD_GROSS_COUNT    = 44;
    private static final int FIELD_NET_COUNT      = 45;
    private static final int FIELD_TEMPERATURE    = 46;
    private static final int FIELD_SERIAL_ID      = 80;

    // ✅ Précision décimale — responsabilité du protocole (voir LcpLink).
    // Le LC3 encode toujours NET/GROSS avec 1 décimale (voir opGetField
    // ci-dessous : FIELD_NET_COUNT/FIELD_GROSS_COUNT multiplient la valeur
    // lue à l'écran par 10 avant de l'encoder). Valeur FIXE et directe —
    // aucune lecture réseau, aucun cache partagé avec un autre type de
    // registre : élimine le risque de précision figée par erreur sur une
    // valeur héritée d'une session LCR-II antérieure.
    @Override
    public int getDecimalDigits() {
        return 1;
    }

    // ── Commandes DC ──────────────────────────────────────────────────────
    private static final int CMD_RUN               = 0x00;
    private static final int CMD_END               = 0x02;
    private static final int CMD_PRINT_LAST_TICKET = 0x06;

    // ── Regex ─────────────────────────────────────────────────────────────
    private static final Pattern RE_NET =
            Pattern.compile("NET VOLUME LITRES\\s+([\\d.]+)");

    // ── Transport ─────────────────────────────────────────────────────────
    private final TransportIo lc3io;
    private volatile boolean lc3closed = false;

    // ── Cache pollScreen ──────────────────────────────────────────────────
    private String lastPollScreen = "";
    private long   lastPollMs     = 0L;

    // ── Cache serial ──────────────────────────────────────────────────────
    private String cachedSerial = null;

    // ── Cache ticket ──────────────────────────────────────────────────────
    private volatile int  cachedTicketNo  = -1;
    private volatile long cachedTicketMs  = 0L;
    private static final long TICKET_TTL_MS = 30_000L;

    // ── Cache température ─────────────────────────────────────────────────
    private volatile float cachedTemperature = Float.NaN;

    // ── État interne ──────────────────────────────────────────────────────
    private volatile int   pendingProduct      = 11;
    private volatile long  pendingPreset       = 0;  // PRESET NET
    private volatile long  pendingPresetGross  = 0;  // PRESET GROSS
    private volatile long  pendingPresetPrice  = 0;  // PRESET PRICE
    private volatile int   accessCode          = 1;
    private volatile float lastNetPoll         = -1f;

    // ── Ratio GROSS/NET ───────────────────────────────────────────────────
    private float   grossNetRatio       = 1.0082144f; // pré-chargé depuis Mode 13
    private boolean grossNetRatioLoaded = true;

    // ── Constructeurs ─────────────────────────────────────────────────────
    public Lc3Link(TransportIo io) {
        this(io, null);
    }

    public Lc3Link(TransportIo io, String knownSerial) {
        super(null, 0, 0, false);
        this.lc3io = io;
        this.cachedSerial = (knownSerial != null && !knownSerial.isEmpty()) ? knownSerial : null;
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

    // ── Lifecycle ─────────────────────────────────────────────────────────
    // ✅ LC3 à 9600 baud — intervalle live tick plus conservateur
    @Override public long getRecommendedLiveIntervalMs() { return 800L; }

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
    @Override public void setTraceSink(TraceSink sink) { /* NO-OP */ }

    // ✅ AJOUTÉ (7 août 2026, demande Paul — "il faut que ce soit générique
    // comme demande de firmware dans DeliveryController") — sans cet
    // override, un appel sur un vrai LC3 utiliserait SILENCIEUSEMENT les
    // numéros de champ LCR-II hérités (Field #60, msgID Get Product ID
    // 0x00) — presque certainement faux, puisque LC3 n'est pas le protocole
    // Liquid Controls. DeliveryController reste générique (appelle
    // link.opGetFirmwareVersion() par polymorphisme, sans savoir si c'est
    // un LcpLink ou un Lc3Link) — c'est ICI, dans la classe concrète, que
    // la bonne réponse (ou l'absence honnête de réponse) doit être fournie.
    // Une exception claire vaut mieux qu'une valeur silencieusement fausse.
    @Override public String opGetFirmwareVersion() throws java.io.IOException {
        throw new java.io.IOException("opGetFirmwareVersion() non implémenté pour LC3 — "
            + "numéro de champ/message pas encore documenté pour ce protocole");
    }

    @Override public String opGetProductIdRevision() throws java.io.IOException {
        throw new java.io.IOException("opGetProductIdRevision() non implémenté pour LC3 — "
            + "numéro de champ/message pas encore documenté pour ce protocole");
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
        String scr = cachedPollScreen();
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
                // DC lit dec[0] comme idx → decimalsDigits(1)=1 → divise par 10
                return new byte[]{ 1, 0, 0, 0 };
            case FIELD_ACTIVE_PRODUCT:
                return encodeU32(pendingProduct);
            case FIELD_PRESET_NET:
                return encodeU32((int) pendingPreset);
            case FIELD_PRESET_GROSS:
                return encodeU32((int) pendingPresetGross);
            case FIELD_PRESET_PRICE:
                return encodeU32((int) pendingPresetPrice);
            case FIELD_NET_COUNT: {
                String scr = cachedPollScreen();
                Matcher m = RE_NET.matcher(scr);
                if (m.find()) return encodeU32(Math.round(parseFloat(m.group(1)) * 10));
                return encodeU32(0);
            }
            case FIELD_GROSS_COUNT: {
                String scr = cachedPollScreen();
                Matcher mg = Pattern.compile("GROSS VOLUME LITRES\\s+([\\d.]+)").matcher(scr);
                if (mg.find()) return encodeU32(Math.round(parseFloat(mg.group(1)) * 10));
                // Mode NET → calculer GROSS via ratio
                Matcher mn = RE_NET.matcher(scr);
                if (mn.find()) {
                    ensureGrossNetRatio();
                    float gross = parseFloat(mn.group(1)) * grossNetRatio;
                    return encodeU32(Math.round(gross * 10));
                }
                return encodeU32(0);
            }
            case FIELD_SALE_NUMBER:
                return encodeU32((int) readMode3FieldValue("SALE NUMBER"));
            case FIELD_TICKET_NUMBER: {
                long now = System.currentTimeMillis();
                if (cachedTicketNo >= 0 && (now - cachedTicketMs) < TICKET_TTL_MS) {
                    return encodeU32(cachedTicketNo);
                }
                int fresh = (int) readMode3FieldValue("TICKET NUMBER");
                if (fresh != cachedTicketNo) {
                    android.util.Log.i("Lc3Link", "TICKET NUMBER: " + cachedTicketNo + " → " + fresh);
                }
                cachedTicketNo = fresh;
                cachedTicketMs = now;
                return encodeU32(cachedTicketNo);
            }
            case FIELD_GROSS_TOTAL:
                return encodeU32(Math.round(readMode3FieldValue("TOTAL GROSS VOLUME") * 10));
            case FIELD_NET_TOTAL:
                return encodeU32(Math.round(readMode3FieldValue("TOTAL NET VOLUME") * 10));
            case FIELD_TEMPERATURE: {
                String scr = cachedPollScreen();
                Matcher m = Pattern.compile("TAB54 TEMP[^\\d]*([\\d.]+)").matcher(scr);
                if (m.find()) {
                    cachedTemperature = parseFloat(m.group(1));
                    return encodeU32(Math.round(cachedTemperature * 10));
                }
                return encodeU32(0);
            }
            case FIELD_SERIAL_ID: {
                if (cachedSerial != null) return cachedSerial.getBytes(StandardCharsets.US_ASCII);
                cachedSerial = readMode8Serial();
                return cachedSerial.getBytes(StandardCharsets.US_ASCII);
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
            case FIELD_PRESET_GROSS:
                pendingPresetGross = beI32(value) & 0xFFFFFFFFL;
                android.util.Log.d("Lc3Link", "PRESET_GROSS=" + pendingPresetGross);
                break;
            case FIELD_PRESET_PRICE:
                pendingPresetPrice = beI32(value) & 0xFFFFFFFFL;
                android.util.Log.d("Lc3Link", "PRESET_PRICE=" + pendingPresetPrice);
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
                cachedTicketNo = -1; // invalider cache ticket
                startDelivery();
                break;
            case CMD_END:
                cachedTicketNo = -1; // invalider cache ticket
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

    /**
     * NO-OP pour LC3 — impression texte custom non supportée via ce protocole.
     */
    @Override
    public void opPrintText(String line) throws IOException {
        // LC3 ne supporte pas MSG_PRINT_TEXT; ignoré silencieusement.
        android.util.Log.d("Lc3Link", "opPrintText: NO-OP LC3 (len=" + line.length() + ")");
    }

    /**
     * NO-OP pour LC3 — pas de champs date/heure (#20/#21) sur terminal VT-100.
     * La sync date/heure est gérée par l'horloge interne du LC3.
     */
    @Override
    public void opSyncDateTime() throws IOException {
        android.util.Log.d("Lc3Link", "opSyncDateTime: NO-OP LC3");
    }

    /**
     * NO-OP pour LC3 — pas de commande diagnostic reset équivalente.
     * À implémenter quand spec LC3 disponible.
     */
    @Override
    public int[] opDiagnosticReset(int maxWaitMs) throws IOException {
        android.util.Log.d("Lc3Link", "opDiagnosticReset: NO-OP LC3");
        return new int[]{ 0, 0 }; // net=0, gross=0
    }

    // ── startDelivery ─────────────────────────────────────────────────────
    private void startDelivery() throws IOException {
        android.util.Log.i("Lc3Link", "startDelivery product=" + pendingProduct
                + " presetNet=" + pendingPreset
                + " presetGross=" + pendingPresetGross
                + " presetPrice=" + pendingPresetPrice);

        // 1) Retour Mode 1
        backToMode1();

        // 2) Valider qu'on est en Mode 1 (NET VOLUME LITRES ou ACCESS NUMBER)
        String scr = readSpontaneous(800);
        if (!scr.contains("NET VOLUME LITRES") && !scr.contains("ACCESS NUMBER")) {
            android.util.Log.w("Lc3Link", "startDelivery: écran inattendu: " + scr.replace("\n", "|"));
            backToMode1();
            scr = readSpontaneous(800);
        }
        android.util.Log.i("Lc3Link", "startDelivery écran=" + scr.replace("\n", "|"));

        // 3) [1] ACCESS NUMBER
        rawWrite(new byte[]{ VT_DOWN }); sleep(400);
        rawWrite(String.valueOf(accessCode).getBytes()); sleep(100);
        rawWrite(new byte[]{ VT_ENTER }); sleep(600);

        // 4) [2] PRODUCT CODE
        rawWrite(String.valueOf(pendingProduct).getBytes()); sleep(100);
        rawWrite(new byte[]{ VT_ENTER }); sleep(600);

        // 5) [3] PRESET NET
        int presetNet = (int)(pendingPreset / 10);
        rawWrite(String.valueOf(presetNet).getBytes()); sleep(100);
        rawWrite(new byte[]{ VT_ENTER }); sleep(600);

        // 6) [4] PRESET GROSS
        int presetGross = (int)(pendingPresetGross / 10);
        rawWrite(String.valueOf(presetGross).getBytes()); sleep(100);
        rawWrite(new byte[]{ VT_ENTER }); sleep(600);

        // 7) [5] PRESET PRICE (2 décimales)
        int presetPrice = (int)(pendingPresetPrice / 100);
        rawWrite(String.valueOf(presetPrice).getBytes()); sleep(100);
        rawWrite(new byte[]{ VT_ENTER }); sleep(600);

        // 8) START
        rawWrite(new byte[]{ VT_START }); sleep(800);
        drainRx(300);
        android.util.Log.i("Lc3Link", "startDelivery → START envoyé");
    }

    // ── Méthode publique température (pour FieldService) ──────────────────
    public float getTemperature() {
        return Float.isNaN(cachedTemperature) ? 0f : cachedTemperature;
    }

    // ── Ratio GROSS/NET ───────────────────────────────────────────────────
    private void ensureGrossNetRatio() {
        if (grossNetRatioLoaded) return;
        try {
            float net   = readModeFieldValue(13, "TOTAL NET VOLUME");
            float gross = readModeFieldValue(13, "TOTAL GROSS VOLUME");
            if (net > 0 && gross > 0) {
                grossNetRatio = gross / net;
                android.util.Log.i("Lc3Link", "grossNetRatio=" + grossNetRatio);
            }
            grossNetRatioLoaded = true;
        } catch (Exception ignored) {}
    }

    private float readModeFieldValue(int mode, String fieldName) throws IOException {
        gotoMode(mode);
        try {
            for (int i = 0; i < 40; i++) {
                String scr   = readSpontaneous(800);
                String first = firstLine(scr);
                if (first.toUpperCase().contains(fieldName.toUpperCase())) {
                    Matcher m = Pattern.compile("([\\d.]+)\\s*\\.?\\s*$").matcher(first);
                    if (m.find()) return parseFloat(m.group(1));
                }
                rawWrite(new byte[]{ VT_DOWN }); sleep(200);
                rawWrite(new byte[]{ VT_ENTER });
            }
        } finally { backToMode1(); }
        return 0f;
    }

    // ── Lecture Mode 3 ────────────────────────────────────────────────────
    private float readMode3FieldValue(String fieldName) throws IOException {
        gotoMode(3);
        try {
            for (int i = 0; i < 40; i++) {
                String scr   = readSpontaneous(150);
                String first = firstLine(scr);
                if (first.toUpperCase().contains(fieldName.toUpperCase())) {
                    Matcher m = Pattern.compile("([\\d.]+)\\s*\\.?\\s*$").matcher(first);
                    if (m.find()) return parseFloat(m.group(1));
                }
                rawWrite(new byte[]{ VT_DOWN }); sleep(70);
                rawWrite(new byte[]{ VT_ENTER });
            }
        } finally { backToMode1(); }
        return 0f;
    }

    // ── Lecture Mode 8 ────────────────────────────────────────────────────
    private String readMode8Serial() throws IOException {
        gotoMode(8);
        try {
            for (int i = 0; i < 6; i++) {
                rawWrite(new byte[]{ VT_UP }); sleep(70);
                String scr = readSpontaneous(150);
                for (String line : scr.split("\n")) {
                    if (line.contains("SERIAL NUMBER")) {
                        Matcher m = Pattern.compile("(\\d+)\\s*\\.?\\s*$").matcher(line);
                        if (m.find()) return m.group(1).trim();
                    }
                }
            }
            return "LC3";
        } finally {
            backToMode1();
        }
    }

    // ── Navigation modes ──────────────────────────────────────────────────
    // Timings validés BT SPP aggressive benchmark:
    // goto=536ms (EXTREME), probe=1837ms, ticket=2777ms
    // Marge +20ms ajoutée pour variabilité BT
    private void gotoMode(int modeNum) throws IOException {
        rawWrite(new byte[]{ VT_M1 });    sleep(30);
        rawWrite(new byte[]{ VT_ENTER }); sleep(70);
        drainRx(30);
        rawWrite(new byte[]{ VT_MODE });  sleep(30);
        rawWrite(String.valueOf(modeNum).getBytes()); sleep(30);
        rawWrite(new byte[]{ VT_ENTER }); sleep(120);
        String scr = readSpontaneous(200);
        if (scr.toUpperCase().contains("KEY") && scr.contains("?")) {
            rawWrite(new byte[]{ '0' });
            rawWrite(new byte[]{ VT_ENTER });
            sleep(100);
            readSpontaneous(150);
        }
    }

    private void backToMode1() throws IOException {
        rawWrite(new byte[]{ VT_M1 }); sleep(30);
        drainRx(30);
    }

    // ── cachedPollScreen ──────────────────────────────────────────────────
    private String cachedPollScreen() throws IOException {
        if (System.currentTimeMillis() - lastPollMs < 500 && !lastPollScreen.isEmpty()) {
            return lastPollScreen;
        }
        lastPollScreen = pollScreen();
        lastPollMs = System.currentTimeMillis();
        return lastPollScreen;
    }

    // ── pollScreen ────────────────────────────────────────────────────────
    // CMD_SCREEN fiable BT (226ms avec silence=50ms)
    private String pollScreen() throws IOException {
        rawWrite(CMD_SCREEN);
        byte[] raw = readRaw(300);
        String scr = decodeVt100(raw);
        if (scr.isEmpty()) {
            rawWrite(CMD_POLL_A);
            rawWrite(CMD_POLL_B);
            scr = decodeVt100(readRaw(300));
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
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                io.write(screen, 1000);
                Thread.sleep(600);
                byte[] buf = new byte[1024];
                int n = io.read(buf, 1000);
                android.util.Log.d("Lc3Link", "probe attempt=" + attempt + " n=" + n);
                if (n > 0) {
                    byte[] data = new byte[n];
                    System.arraycopy(buf, 0, data, 0, n);
                    String scr = decodeVt100(data);
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
                Thread.sleep(300);
            } catch (Exception e) {
                android.util.Log.w("Lc3Link", "probe ex: " + e.getMessage());
                return false;
            }
        }
        android.util.Log.i("Lc3Link", "probe → pas LC3");
        return false;
    }

    // ── probeAndIdentify ──────────────────────────────────────────────────
    // ✅ Un SEUL point d'entrée pour toute identification LC3 — utilisé par
    // RegisterSessionManager, MainActivity (finalizeTcpRegisterTab,
    // scanRegistersWithIo), etc. Le retry sur échec de lecture (placeholder
    // "LC3" persistant faute d'avoir trouvé "SERIAL NUMBER" à l'écran, cause
    // fréquente : latence TCP plus longue qu'en BT direct) vit ICI, une
    // seule fois — jamais à dupliquer dans chaque appelant.
    public static RegisterIdentity probeAndIdentify(TransportIo io) {
        RegisterIdentity id = probeAndIdentifyOnce(io);
        if (id != null && id.isLc3 && "LC3".equals(id.serialId) && id.nodeId == 0) {
            android.util.Log.w("Lc3Link", "probeAndIdentify: échec complet (placeholder LC3, node=0) — nouvelle tentative");
            id = probeAndIdentifyOnce(io);
        }
        return id;
    }

    private static RegisterIdentity probeAndIdentifyOnce(TransportIo io) {
        if (!probe(io)) return new RegisterIdentity(false, null, 0, 0, "");
        Lc3Link lc3 = new Lc3Link(io);
        String serialId = "LC3";
        String model    = "";
        int    nodeId   = 0;
        int    truckNo  = 0;
        try {
            lc3.gotoMode(8);
            String scr8 = lc3.readSpontaneous(1000);
            android.util.Log.d("Lc3Link", "probeAndIdentify Mode8 scr=" + scr8.replace("\n", "|"));

            for (int i = 0; i < 6; i++) {
                lc3.rawWrite(new byte[]{ VT_UP }); sleep(70);
                String scr = lc3.readSpontaneous(150);
                android.util.Log.d("Lc3Link", "probeAndIdentify Mode8 up" + i + "=" + scr.replace("\n", "|"));
                for (String line : scr.split("\n")) {
                    if (model.isEmpty() && line.contains("APPLICATION")) {
                        model = line.trim();
                    }
                    if (nodeId == 0 && line.contains("UNIT ID")) {
                        Matcher m = Pattern.compile("(\\d+)\\s*\\.?\\s*$").matcher(line);
                        if (m.find()) nodeId = Integer.parseInt(m.group(1).trim());
                    }
                    if (truckNo == 0 && line.contains("TRUCK NUMBER")) {
                        Matcher m = Pattern.compile("(\\d+)\\s*\\.?\\s*$").matcher(line);
                        if (m.find()) truckNo = Integer.parseInt(m.group(1).trim());
                    }
                    if (line.contains("SERIAL NUMBER")) {
                        Matcher m = Pattern.compile("(\\d+)\\s*\\.?\\s*$").matcher(line);
                        if (m.find()) {
                            serialId = m.group(1).trim();
                            android.util.Log.i("Lc3Link", "SERIAL NUMBER trouvé: " + serialId);
                        }
                    }
                }
                if (!serialId.equals("LC3") && nodeId > 0) break;
            }
            if (serialId.equals("LC3") && nodeId > 0) serialId = "LC3-" + nodeId;
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
    // ─────────────────────────────────────────────────────────────────────
    // Comportement vanne post-preset — LC3 : pas de solénoïde via protocole
    // ─────────────────────────────────────────────────────────────────────

    /**
     * LC3 : le registre n'a pas de contrôle solénoïde via le protocole VT-100.
     * Après PRESET STOP, le chauffeur doit fermer la vanne manuellement.
     * Si du volume sort après DONE, c'est que la vanne n'est pas encore fermée.
     */
    @Override
    public boolean isValveControlledByRegister() {
        return false; // LC3 : fermeture manuelle requise par le chauffeur
    }

    /**
     * LC3 — message adapté : le chauffeur est responsable de la fermeture.
     */
    @Override
    public String getLeakAlertMessage(String ticketNo, double netRef,
            double netNow, double delta) {
        // LC3 : pas de controle solenoide via protocole VT-100.
        // Apres PRESET STOP, le chauffeur ferme la vanne manuellement.
        StringBuilder sb = new StringBuilder();
        sb.append("FERMEZ LA VANNE MANUELLEMENT").append("\n\n");
        sb.append("Ticket : ").append(ticketNo).append("\n");
        sb.append(String.format(java.util.Locale.ROOT, "Volume au preset   : %.3f L net\n", netRef));
        sb.append(String.format(java.util.Locale.ROOT, "Volume actuel      : %.3f L net\n", netNow));
        sb.append(String.format(java.util.Locale.ROOT, "Volume additionnel : %.3f L\n\n", delta));
        sb.append("Le registre LC3 a atteint PRESET STOP.").append("\n");
        sb.append("La vanne doit etre fermee manuellement.").append("\n");
        sb.append("Fermez la vanne physique immediatement avant de continuer.");
        return sb.toString();
    }


}