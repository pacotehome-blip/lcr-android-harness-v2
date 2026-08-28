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
 * 2) RC=0x26/0x27: queued via 0x7D. ✅ FIX (11 août 2026, demande Paul) —
 *    preuve directe par trace TX/RX brute que RC=0x26 sur Get Machine
 *    Status/Get Delivery Status (0x23/0x28) restait bloqué EN BOUCLE
 *    INFINIE sans jamais se résoudre, alors que Check Request (0x7D)
 *    résolvait ces mêmes files d'attente avec succès pour Set Field dans
 *    le même log. Étendu à Get Machine Status/Get Delivery Status — SEUL
 *    Get Field (0x20) reste "busy/skip" sans 0x7D (jamais observé bloqué,
 *    contrairement aux deux autres).
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
    // ✅ AJOUTÉ (7 août 2026, demande Paul — "donne-moi l'info des deux pour
    // voir s'il y a une différence") — message générique LCP "Get Product
    // ID" (msgID=0x00, tout appareil LCP le supporte). Réponse : productID
    // (0x02 = LCR) + chaîne ASCIIZ "nom+révision" (ex: "SR200b2.05") — une
    // SOURCE SÉPARÉE du Field #60, pour comparer.
    private static final byte MSG_GET_PRODUCT_ID = 0x00;
    private static final byte MSG_CHECK_REQUEST = 0x7D;
    // ✅ AJOUTÉ (11 août 2026, demande Paul) — "Abort Request" (0x7E),
    // documenté comme complément de Check Request depuis ce matin, jamais
    // implémenté jusqu'ici.
    private static final byte MSG_ABORT_REQUEST = 0x7E;
    // ✅ AJOUTÉ (7 août 2026, demande Paul — "réduire la vitesse de
    // transmission entre 19200 et 4800 dans les tests de diagnostic") —
    // message générique LCP "Set Baud" (msgID=0x7C), sourcé de la doc
    // officielle. Index : 0=57600, 1=19200, 2=9600, 3=4800, 4=2400.
    private static final byte MSG_SET_BAUD = (byte) 0x7C;

    // Cadence du CHECK_REQUEST
    private static final int QP_MS = 200;

    // ✅ AJOUTÉ (13 août 2026, demande Paul — "il y a un effet de bord
    // partout") — classification UNIFIÉE des commandes LCP. Remplace les
    // timeouts/booléens "queueable" épars introduits au fil des fixes du
    // 6/7/11 août, chacun réglant un cas précis sans politique commune.
    // AVANT ce fix : un simple GET de statut (poll UI, reconnect, vérif de
    // job) pouvait, sur RC=0x26 busy, retenir sendRecv() — donc le verrou
    // LcpNodeLocks partagé par node — jusqu'à 8000ms. Pendant ce temps,
    // TOUT le reste sur ce node (scan produits, tick live, boutons) restait
    // bloqué derrière, avec effet domino visible partout (tick figé, scan
    // "gelé puis rattrape", boutons qui ne répondent pas).
    //
    // Trois classes, un seul endroit pour décider "combien de temps une
    // commande a le droit de retenir le registre avant d'abandonner" :
    //   FAST   : poll haute fréquence (tick live net/gross). Jamais mis en
    //            file — busy = retour immédiat, on retentera au prochain
    //            cycle (200ms plus tard). Rien à gagner à attendre ici.
    //   STATUS : vérification ponctuelle (GET_MACHINE_STATUS,
    //            GET_DELIVERY_STATUS — utilisées par STATUS_B, reconnect,
    //            poll de job). Peut se mettre en file, mais PLAFONNÉE —
    //            2.5s au lieu de 6-8s. Un statut n'a pas besoin d'attendre
    //            aussi longtemps qu'une action réelle.
    //   ACTION : intention utilisateur qui DOIT aboutir (SET_FIELD,
    //            ISSUE_COMMAND, PRINT_TEXT — preset, armement, scan,
    //            impression). Timeout long inchangé (30s) — c'est
    //            légitime, ce n'est pas un poll silencieux.
    enum OpClass {
        FAST(false, 800),
        STATUS(true, 2_500),
        ACTION(true, 30_000);

        final boolean queueable;
        final int timeoutMs;
        OpClass(boolean queueable, int timeoutMs) { this.queueable = queueable; this.timeoutMs = timeoutMs; }
    }

    // ✅ AJOUTÉ (13 août 2026, demande Paul — "augmenter et noter le temps
    // nécessaire et faire une moyenne par la suite") — le plafond STATUS
    // fixe de 2.5s ci-dessus s'est révélé insuffisant sur le terrain le
    // jour même (deux busy consécutifs sur GET_MACHINE_STATUS non résolus
    // dans les 2.5s, compteur d'échec de DeliveryController rendu à 4/5 —
    // à un cheveu d'une déconnexion forcée). Remplacé par un timeout
    // ADAPTATIF par registre (par instance LcpLink) : démarre au plancher
    // (OpClass.STATUS.timeoutMs), puis s'ajuste selon le temps RÉELLEMENT
    // observé pour résoudre un busy — moyenne mobile (EMA) + marge de
    // sécurité. Sur timeout franc (le plafond actuel n'a pas suffi), on
    // relève immédiatement plutôt que d'attendre que la moyenne rattrape —
    // on sait déjà que c'est insuffisant pour CE registre.
    private static final long STATUS_TIMEOUT_FLOOR_MS = OpClass.STATUS.timeoutMs;
    private static final long STATUS_TIMEOUT_CEILING_MS = 8_000; // jamais pire qu'avant le 13 août
    private static final double STATUS_TIMEOUT_MARGIN = 1.4;     // 40% au-dessus de la moyenne observée
    private static final double STATUS_EMA_ALPHA = 0.25;         // poids du dernier échantillon
    private static final long STATUS_TIMEOUT_BUMP_MS = 1_500;    // relève immédiate sur timeout franc

    private volatile long statusTimeoutMs = STATUS_TIMEOUT_FLOOR_MS;
    private volatile double statusResolveEmaMs = -1; // -1 = aucun échantillon encore

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
    // ✅ CORRIGÉ (27 août 2026, demande Paul — "je dois voir 1 à 10 et
    // chacun des multiples... on saute 1.1, 1.3") — trouvé, confirmé par
    // trace réelle QTY-RECUE/QTY-CALLBACK : à un débit rapide (~48L/min
    // mesuré), 200ms manquait des dixièmes de litre (ex: 1.0 → 1.2,
    // sautant 1.1). Réduit vers le minimum documenté du code
    // (Math.max(100, intervalMs), voir DeliveryController) pour attraper
    // plus d'incréments, même si à très haut débit un échantillonnage
    // périodique gardera une limite fondamentale face à un compteur
    // qui peut avancer plus vite que l'intervalle.
    public long getRecommendedLiveIntervalMs() { return 100L; }

    // getters utiles validate/log/UI
    public int getToAddr() { return toAddr; }
    public int getHostAddr() { return hostAddr; }
    public String getTransportKey() { return (io != null) ? io.getKey() : null; }

    // ✅ AJOUTÉ (28 août 2026, demande Paul — "on doit comprendre ce qui
    // arrive") — passthrough vers io.getIoLatencyAvgMs()/getIoSamples(),
    // pour que DeliveryController (qui n'a accès qu'à LcpLink, jamais au
    // TransportIo directement) puisse logger la vraie latence de lecture
    // BT mesurée au niveau transport, et comparer avec le cycle de tick
    // réel observé (~200-250ms) — confirme ou infirme si le plancher
    // vient de la pile BT d'Android plutôt que du reste du code.
    public int getIoLatencyAvgMs() { return (io != null) ? io.getIoLatencyAvgMs() : -1; }
    public int getIoSamples() { return (io != null) ? io.getIoSamples() : -1; }
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

    // =========================================================================
    // ✅ (4 août 2026, demande Paul) — décodage bit par bit du "Machine Code
    // Byte" (devStatus), documenté page 59 de "LCR LCP API Internal Messages"
    // (Liquid Controls, Rev. L, ©1998-2018). Non documenté nulle part dans le
    // code avant ce fix — devStatus n'était que passé brut (dev=0x%02X). Placé
    // ici (LcpLink, pas DeliveryController) car le format du byte est défini
    // par le protocole LCP générique lui-même (message "Get Machine Status" /
    // 0x23, universel SR200 → SR1000, pas spécifique LCR-II) — donc hérité
    // automatiquement par Lc3Link et tout futur type de registre qui étend
    // LcpLink, sans duplication.
    //
    // Structure du byte (page 59) :
    //   bits 0-2 (masque 0x07) — position du switch (rouge) :
    //     0x00 = entre deux positions · 0x01 = RUN · 0x02 = STOP
    //     0x03 = PRINT · 0x04 = SHIFT-PRINT · 0x05 = CALIBRATE
    //     0x07 = statut réel indisponible (ex. réponse à un message broadcast)
    //   bit 3 (masque 0x08) — imprimante RS-232 en cours d'impression
    //   bits 4-6 (masque 0x70) — état machine :
    //     0x00 = RUN (livraison démarrée, flux actif)
    //     0x10 = STOP (livraison démarrée, flux inactif)
    //     0x20 = END DELIVERY (aucune livraison, aucun flux)
    //     0x30 = AUXILIARY · 0x40 = SHIFT · 0x50 = CALIBRATE
    //     0x60 = WAIT FOR NO FLOW (arrêt demandé, flux pas encore confirmé stoppé)
    //   bit 7 (masque 0x80) — erreur détectée (vérifier delivery/printer/hw status)
    //
    // ⚠️ Note fabricant : "0x?0 — switch entre deux positions" et le "0x07"
    // listé deux fois (à la fois comme masque et comme valeur "indisponible")
    // sont ambigus dans le document source — retranscrits tels quels, pas
    // d'invention de notre part. Croisé et confirmé avec le SDK Android
    // officiel Liquid Controls (Android_SDK_Documentation, objet DevStatus,
    // p.22-23) : switch=6 = "Not used", confirmé aussi que ce même
    // découpage (errorBit/stateBits/printerBit/switchBits) est la source de
    // vérité officielle, pas une interprétation de notre part.
    public static final int DEV_SWITCH_MASK          = 0x07;
    public static final int DEV_SWITCH_RUN           = 0x01;
    public static final int DEV_SWITCH_STOP          = 0x02;
    public static final int DEV_SWITCH_PRINT         = 0x03;
    public static final int DEV_SWITCH_SHIFT_PRINT   = 0x04;
    public static final int DEV_SWITCH_CALIBRATE     = 0x05;
    public static final int DEV_SWITCH_UNAVAILABLE   = 0x07;

    public static final int DEV_PRINTER_PRINTING     = 0x08;

    public static final int DEV_STATE_MASK           = 0x70;
    public static final int DEV_STATE_RUN            = 0x00;
    public static final int DEV_STATE_STOP           = 0x10;
    public static final int DEV_STATE_END_DELIVERY   = 0x20;
    public static final int DEV_STATE_AUXILIARY      = 0x30;
    public static final int DEV_STATE_SHIFT          = 0x40;
    public static final int DEV_STATE_CALIBRATE      = 0x50;
    public static final int DEV_STATE_WAIT_NO_FLOW   = 0x60;

    public static final int DEV_ERROR_FLAG           = 0x80;

    /** Instantané décodé et lisible d'un devStatus brut. */
    public static final class DeviceStatusDecoded {
        public final int rawValue;
        public final int switchPositionCode;
        public final String switchPositionName;
        public final int machineStateCode;
        public final String machineStateName;
        public final boolean printerPrinting;
        public final boolean errorFlag;

        DeviceStatusDecoded(int rawValue, int switchPositionCode, String switchPositionName,
                             int machineStateCode, String machineStateName,
                             boolean printerPrinting, boolean errorFlag) {
            this.rawValue = rawValue;
            this.switchPositionCode = switchPositionCode;
            this.switchPositionName = switchPositionName;
            this.machineStateCode = machineStateCode;
            this.machineStateName = machineStateName;
            this.printerPrinting = printerPrinting;
            this.errorFlag = errorFlag;
        }

        @Override public String toString() {
            // ✅ FIX (6 août 2026, demande Paul — "j'ai coché erreur et il y
            // en a qu'une la dernière en bas") — trouvé : le filtre "Erreurs
            // seulement" fait une recherche naïve de la sous-chaîne "ERR"
            // (insensible à la casse) dans le texte — et "error=non" écrit
            // ici contient "ERR" (les 3 premières lettres de "ERROR"), donc
            // déclenchait le filtre à tort même quand errorFlag=false. Le mot
            // "error" n'apparaît plus du tout quand il n'y a pas d'erreur —
            // seulement affiché explicitement (et en majuscules ⚠) quand
            // errorFlag est réellement vrai.
            String errPart = errorFlag ? " ⚠PANNE" : "";
            return String.format(java.util.Locale.ROOT,
                "dev=0x%02X [switch=%s state=%s printer=%s%s]",
                rawValue, switchPositionName, machineStateName,
                printerPrinting ? "PRINTING" : "idle", errPart);
        }
    }

    public static DeviceStatusDecoded decodeDeviceStatus(int devStatus) {
        int sw = devStatus & DEV_SWITCH_MASK;
        String swName;
        switch (sw) {
            case DEV_SWITCH_RUN:         swName = "RUN"; break;
            case DEV_SWITCH_STOP:        swName = "STOP"; break;
            case DEV_SWITCH_PRINT:       swName = "PRINT"; break;
            case DEV_SWITCH_SHIFT_PRINT: swName = "SHIFT_PRINT"; break;
            case DEV_SWITCH_CALIBRATE:   swName = "CALIBRATE"; break;
            case DEV_SWITCH_UNAVAILABLE: swName = "INDISPONIBLE"; break;
            case 0x00:                   swName = "ENTRE_DEUX_POSITIONS"; break;
            case 0x06:                   swName = "NON_UTILISE"; break;
            default:                     swName = "INCONNU(0x" + Integer.toHexString(sw) + ")";
        }

        int st = devStatus & DEV_STATE_MASK;
        String stName;
        switch (st) {
            case DEV_STATE_RUN:          stName = "RUN (livraison+flux actifs)"; break;
            case DEV_STATE_STOP:         stName = "STOP (livraison active, flux inactif)"; break;
            case DEV_STATE_END_DELIVERY: stName = "END_DELIVERY (inactif)"; break;
            case DEV_STATE_AUXILIARY:    stName = "AUXILIARY"; break;
            case DEV_STATE_SHIFT:        stName = "SHIFT"; break;
            case DEV_STATE_CALIBRATE:    stName = "CALIBRATE"; break;
            case DEV_STATE_WAIT_NO_FLOW: stName = "WAIT_NO_FLOW (arrêt demandé)"; break;
            default:                     stName = "INCONNU(0x" + Integer.toHexString(st) + ")";
        }

        boolean printing = (devStatus & DEV_PRINTER_PRINTING) != 0;
        boolean error    = (devStatus & DEV_ERROR_FLAG) != 0;

        return new DeviceStatusDecoded(devStatus, sw, swName, st, stName, printing, error);
    }
    // =========================================================================

    // =========================================================================
    // ✅ (6 août 2026, demande Paul — "je veux qu'on puisse lire comme humain
    // les logs et comprendre l'état réel du registre") — même principe que
    // decodeDeviceStatus() ci-dessus, appliqué à delCode et delStatus (les
    // deux autres champs bruts hex qu'on affichait sans jamais les
    // traduire). Sourcé des tables officielles "Delivery Code Bits" et
    // "Delivery Status Bits" (LCR LCP API Internal Messages, p.57-58).
    public static final int DC_TICKET_PENDING      = 0x0001; // ticket en attente d'impression
    public static final int DC_SHIFT_TICKET_PENDING= 0x0002;
    public static final int DC_FLOW_ACTIVE         = 0x0004; // flux réellement actif
    public static final int DC_DELIVERY_ACTIVE     = 0x0008; // livraison active
    public static final int DC_GROSS_PRESET_ACTIVE = 0x0010;
    public static final int DC_NET_PRESET_ACTIVE   = 0x0020;
    public static final int DC_GROSS_PRESET_REACHED= 0x0040;
    public static final int DC_NET_PRESET_REACHED  = 0x0080;
    public static final int DC_TEMP_COMPENSATED    = 0x0100;
    public static final int DC_SOLENOID1_CLOSED    = 0x0200;
    public static final int DC_BEGIN_DELIVERY      = 0x0400; // livraison en train de démarrer
    public static final int DC_NEW_DELIVERY_QUEUED = 0x0800;
    public static final int DC_DATA_ACCESS_ERROR   = 0x1000;
    public static final int DC_CONFIG_EVENT        = 0x2000;
    public static final int DC_CALIBRATION_EVENT   = 0x4000;
    public static final int DC_TRANSACTION_SAVED   = 0x8000;

    public static final int DS_PROGRAM_CHECKSUM_ERR   = 0x0001;
    public static final int DS_TEMP_HW_ERR             = 0x0002;
    public static final int DS_WATCHDOG_RESET          = 0x0004;
    public static final int DS_COMP_FACTOR_ERR         = 0x0008;
    public static final int DS_TEMP_OUT_OF_RANGE       = 0x0010;
    public static final int DS_METER_CALIB_ERR         = 0x0020;
    public static final int DS_TOO_MANY_PULSER_REVERSALS = 0x0040;
    public static final int DS_PRESET_REACHED          = 0x0080;
    public static final int DS_NO_FLOW_TIMEOUT         = 0x0100;
    public static final int DS_STOP_REQUEST            = 0x0200;
    public static final int DS_DELIVERY_END_REQUEST    = 0x0400;
    public static final int DS_POWER_FAIL              = 0x0800;
    public static final int DS_PRESET_FIELD_ERR        = 0x1000;
    public static final int DS_LAPPAD_DISCONNECTED     = 0x2000;
    public static final int DS_TICKET_PRINTER_OFFLINE  = 0x4000;
    public static final int DS_CRITICAL_DATA_ERR       = 0x8000;

    /** Traduit delCode en une phrase courte, lisible, décrivant ce qui se passe réellement. */
    public static String describeDelCode(int delCode) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if ((delCode & DC_DELIVERY_ACTIVE) != 0) {
            parts.add((delCode & DC_FLOW_ACTIVE) != 0 ? "livraison active, produit en train de couler"
                                                        : "livraison active, mais aucun flux en ce moment");
        } else {
            parts.add("aucune livraison en cours");
        }
        if ((delCode & DC_TICKET_PENDING) != 0) parts.add("un ticket attend d'être imprimé (bloque une nouvelle livraison)");
        if ((delCode & DC_BEGIN_DELIVERY) != 0) parts.add("démarrage de livraison en cours");
        if ((delCode & DC_NEW_DELIVERY_QUEUED) != 0) parts.add("nouvelle livraison mise en file d'attente");
        if ((delCode & DC_GROSS_PRESET_REACHED) != 0) parts.add("preset gross atteint");
        if ((delCode & DC_NET_PRESET_REACHED) != 0) parts.add("preset net atteint");
        if ((delCode & DC_DATA_ACCESS_ERROR) != 0) parts.add("⚠ erreur d'accès aux données (non critique, défaut utilisé)");
        return String.join(", ", parts);
    }

    /** Traduit delStatus en une phrase courte, lisible — priorise les vraies erreurs. */
    public static String describeDelStatus(int delStatus) {
        if (delStatus == 0) return "rien à signaler";
        java.util.List<String> parts = new java.util.ArrayList<>();
        if ((delStatus & DS_CRITICAL_DATA_ERR) != 0) parts.add("⚠ erreur critique d'accès aux données — livraison bloquée/arrêtée");
        if ((delStatus & DS_TICKET_PRINTER_OFFLINE) != 0) parts.add("⚠ imprimante hors ligne, ticket requis — livraison ne peut pas démarrer");
        if ((delStatus & DS_TOO_MANY_PULSER_REVERSALS) != 0) parts.add("⚠ livraison arrêtée — trop de retours de pulser (retour d'air)");
        if ((delStatus & DS_NO_FLOW_TIMEOUT) != 0) parts.add("livraison arrêtée — aucun flux détecté (timer no-flow expiré)");
        if ((delStatus & DS_POWER_FAIL) != 0) parts.add("⚠ livraison arrêtée — coupure d'alimentation (>15s)");
        if ((delStatus & DS_LAPPAD_DISCONNECTED) != 0) parts.add("terminal RS-232 déconnecté pendant la livraison");
        if ((delStatus & DS_METER_CALIB_ERR) != 0) parts.add("⚠ erreur de calibration du compteur — livraison ne peut pas démarrer");
        if ((delStatus & DS_STOP_REQUEST) != 0) parts.add("arrêt demandé (Command #1)");
        if ((delStatus & DS_DELIVERY_END_REQUEST) != 0) parts.add("fin de livraison demandée (Command #2/#6)");
        if ((delStatus & DS_PRESET_REACHED) != 0) parts.add("preset atteint");
        if (parts.isEmpty()) parts.add("bit(s) non critique(s) actif(s) (0x" + Integer.toHexString(delStatus) + ")");
        return String.join(", ", parts);
    }
    // =========================================================================

    public interface ScanProgressCallback {
        void onProduct(String message);
    }

    public static final class ProductScanResult {
        public final int     noteIdx;
        public final String  description;
        public final boolean isPropane;
        // ✅ AJOUTÉ (20 août 2026, demande Paul — "on a le produit, slot,
        // code produit, la description, le type de produit") — code (#1)
        // et type brut (#94) captés en plus de la description (#11), qui
        // seule était lue jusqu'ici.
        public final String  productCode;
        public final int     productType; // -1 si absent/illisible, sinon 0-7 (List 2 du PDF)

        public ProductScanResult(int noteIdx, String description) {
            this(noteIdx, description, "", -1);
        }

        public ProductScanResult(int noteIdx, String description, String productCode, int productType) {
            this.noteIdx     = noteIdx;
            this.description = description != null ? description.trim() : "";
            this.productCode = productCode != null ? productCode.trim() : "";
            this.productType = productType;
            // ✅ CORRIGÉ (20 août 2026) — isPropane se basait UNIQUEMENT sur
            // un match texte dans la description ("contient propane") — le
            // même problème de fiabilité identifié plus tôt aujourd'hui
            // (le nom peut être vide ou différent, jamais garanti). Priorité
            // maintenant au vrai type (#94=5, LPG selon List 2 du PDF
            // Liquid Controls) — repli sur le texte seulement si le type
            // est absent/illisible (-1), pour rester compatible avec les
            // scans faits avant ce fix.
            this.isPropane = (productType == 5)
                    || (productType < 0 && this.description.toLowerCase(java.util.Locale.ROOT).contains("propane"));
        }

        public String toSpinnerLabel() {
            // ✅ ENRICHI (20 août 2026, demande Paul — "on a le produit,
            // slot, code produit, la description, le type de produit")
            // — affiche maintenant code + type en plus de la description,
            // dans le menu déroulant de sélection lui-même — pas besoin
            // d'un écran séparé pour voir ces informations.
            StringBuilder sb = new StringBuilder(String.valueOf(noteIdx));
            boolean any = false;
            if (!description.isEmpty()) { sb.append(" - ").append(description); any = true; }
            if (!productCode.isEmpty()) { sb.append(any ? " (" : " — code:(").append(productCode).append(")"); any = true; }
            if (productType >= 0) { sb.append(" [").append(LcpLink.decodeProductType(productType)).append("]"); }
            return sb.toString();
        }
    }

    // ✅ AJOUTÉ (20 août 2026) — décodage List 2 du PDF Liquid Controls
    // (Field #94, ProductType_WM), pour affichage lisible dans le tab.
    public static String decodeProductType(int type) {
        switch (type) {
            case 0: return "Ammonia";
            case 1: return "Aviation";
            case 2: return "Distillate";
            case 3: return "Gasoline";
            case 4: return "Methanol";
            case 5: return "LPG";
            case 6: return "Lube Oil";
            case 7: return "Aucun";
            default: return "?";
        }
    }

    // ===================== OPS PUBLIQUES =====================
    public MachineStatus opGetMachineStatus() throws IOException {
        Response r = sendRecv(buildPayload(MSG_GET_MACHINE_STATUS, null), OpClass.STATUS);
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
        Response r = sendRecv(buildPayload(MSG_ISSUE_COMMAND, new byte[]{(byte) cmd}), OpClass.ACTION);
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
        Response r = sendRecv(pl, OpClass.ACTION);
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

    // ✅ AJOUTÉ (7 août 2026, demande Paul — "je veux être en mesure de
    // récupérer le firmware du registre et je le veux dans le log du
    // support") — Field #60 "Software_NE" (TEXT), sourcé directement de la
    // doc officielle LCP ("Version of the software running in the LCR").
    // Un seul appel, réutilise opGetField() comme n'importe quel autre champ
    // texte (même patron que le #80 pour le #série).
    public static final int FIELD_SOFTWARE_VERSION = 60;

    public String opGetFirmwareVersion() throws IOException {
        byte[] raw = opGetField(FIELD_SOFTWARE_VERSION, 5000);
        if (raw == null || raw.length == 0) return "";
        String s = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        int nul = s.indexOf('\0');
        if (nul >= 0) s = s.substring(0, nul);
        return s.trim();
    }

    // ✅ AJOUTÉ (7 août 2026, demande Paul — "donne-moi l'info des deux pour
    // voir s'il y a une différence") — "Get Product ID" est un message LCP
    // GÉNÉRIQUE (tout appareil LCP le supporte, même avant de savoir si
    // c'est un LCR-II), sourcé de la doc officielle. Réponse : rc,
    // productID (0x02 attendu = LCR), et une chaîne ASCIIZ "nom+révision"
    // (ex: "SR200b2.05") — indépendante de Field #60, donc utile pour
    // vérifier s'il y a une divergence entre les deux sources.
    public String opGetProductIdRevision() throws IOException {
        Response r = sendRecv(buildPayload(MSG_GET_PRODUCT_ID, null), 5000);
        ensureOk(r, "GET_PRODUCT_ID");
        // ✅ FIX CRITIQUE (10 août 2026, audit complet contre la doc
        // officielle) — CORRECTION D'UNE ERREUR PRÉCÉDENTE : vérifié de
        // façon décisive dans sendRecv() (ligne ~800 : "int rc =
        // f.payload[0]..." puis "return new Response(rc, f.payload)" — le
        // payload n'est JAMAIS tronqué, rc reste à payload[0]) que
        // r.payload[0] = rc, PAS le premier octet de données. Mon fix
        // précédent ("r.payload exclut déjà rc") était FAUX — basé sur une
        // fausse prémisse, jamais vérifié contre le vrai code de parsing.
        // Structure réelle confirmée contre la doc (Get Product ID) :
        // payload[0]=rc, payload[1]=productID, payload[2..n]=nom ASCIIZ.
        // Il faut donc sauter DEUX octets (rc ET productID), pas un seul.
        if (r.payload == null || r.payload.length < 2) return "";
        byte[] nameBytes = new byte[r.payload.length - 2];
        System.arraycopy(r.payload, 2, nameBytes, 0, nameBytes.length);
        String s = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);
        int nul = s.indexOf('\0');
        if (nul >= 0) s = s.substring(0, nul);
        return s.trim();
    }

    public static final int BAUD_IDX_57600 = 0;
    public static final int BAUD_IDX_19200 = 1;
    public static final int BAUD_IDX_9600  = 2;
    public static final int BAUD_IDX_4800  = 3;
    public static final int BAUD_IDX_2400  = 4;

    /**
     * ⚠️ RISQUÉ — change la vitesse de transmission DU REGISTRE lui-même via
     * LCP. Le registre applique la nouvelle vitesse IMMÉDIATEMENT après cette
     * réponse — l'appelant DOIT reconfigurer son propre port physique pour
     * matcher tout de suite après, sinon toute communication ultérieure
     * échoue jusqu'à un cycle d'alimentation du registre (le registre garde
     * la nouvelle vitesse même après une déconnexion). Diagnostic seulement
     * — jamais utilisé dans le flux normal de livraison. Sur BT (SPP), le
     * lien radio lui-même n'a pas de "vitesse" au sens UART — mais le
     * module BT du registre relaie en interne vers son UART réel, donc
     * cette commande peut quand même avoir un effet (à valider sur le
     * terrain — pas garanti par la doc, qui décrit le comportement RS-232).
     */
    public void opSetBaud(int baudIndex) throws IOException {
        Response r = sendRecv(buildPayload(MSG_SET_BAUD, new byte[]{(byte) baudIndex}), 5000);
        ensureOk(r, "SET_BAUD idx=" + baudIndex);
    }

    /** ✅ AJOUTÉ (11 août 2026, demande Paul) — "Abort Request" (0x7E),
     *  tente d'annuler une requête actuellement en file d'attente dans le
     *  registre. Structure confirmée dans la doc officielle : aucun
     *  paramètre, réponse d'un seul octet (rc). Codes de retour pertinents
     *  (voir REGISTRE_ETATS_REFERENCE.md) : 40=annulée avec succès,
     *  41=annulation encore en cours de traitement, 42=trop avancée pour
     *  être annulée, 39=aucune requête en file à annuler. Retourne le rc
     *  brut plutôt que de lever une exception sur un rc non-zéro — TOUS
     *  les codes ci-dessus sont des réponses valides et informatives, pas
     *  des échecs de communication. */
    public int opAbortRequest() throws IOException {
        Response r = sendRecv(buildPayload(MSG_ABORT_REQUEST, null), 5000);
        return r.rc;
    }


    // =========================================================
    // ✅ Précision décimale NET/GROSS — responsabilité du protocole,
    // PAS de l'UI ni d'un cache générique partagé dans DeliveryController.
    // Chaque sous-classe de Link connaît sa propre façon de représenter
    // NET/GROSS (registre à registre, protocole à protocole) et doit
    // garantir que le résultat final (valeur physique réelle en litres)
    // est correct — peu importe le mécanisme interne utilisé pour y
    // arriver. LcpLink (LCR-II) lit le champ FIELD_DECIMALS (#39) du
    // registre à chaque appel — pas de cache ici, l'appelant (via
    // DeliveryController) est responsable de mettre en cache s'il le
    // souhaite pour éviter des lectures répétées inutiles.
    private static final int FIELD_DECIMALS = 39;

    public int getDecimalDigits() throws IOException {
        // ✅ CORRIGÉ (27 août 2026, demande Paul — "le registre est calibré
        // et ne peut pas se tromper... 11,5 pas 1,15") — TROUVÉ, avec
        // certitude cette fois : sur échec de lecture (timeout, instabilité
        // BT — confirmée abondamment aujourd'hui), cette méthode retournait
        // silencieusement "2" au lieu de propager l'échec. ensureDigits()
        // (DeliveryController) ne pouvait alors pas distinguer "vraiment lu
        // = 2" de "a échoué, repli sur 2" — et mettait ce repli en cache
        // POUR TOUJOURS, empoisonnant toute la session avec la mauvaise
        // échelle après une seule lecture ratée au mauvais moment. Propage
        // maintenant une vraie exception — ensureDigits() ne mettra plus
        // en cache un échec, et retentera une vraie lecture au prochain
        // appel jusqu'à obtenir une valeur confirmée.
        byte[] dec = opGetField(FIELD_DECIMALS, 3000);
        int idx = (dec != null && dec.length >= 1) ? (dec[0] & 0xFF) : 0;
        return decimalsDigitsFromIdx(idx);
    }

    /** Mapping idx registre → nombre de décimales (LCR-II, protocole LCP standard). */
    protected static int decimalsDigitsFromIdx(int idx) {
        switch (idx) {
            case 0: return 2;
            case 1: return 1;
            case 2: return 0;
            case 3: return 3;
            default: return 2;
        }
    }

    /** Timeout 30s pour SET_FIELD queueable */
    public void opSetField(int field, byte[] value) throws IOException {
        byte[] pl = new byte[2 + (value == null ? 0 : value.length)];
        pl[0] = MSG_SET_FIELD;
        pl[1] = (byte) field;
        if (value != null) System.arraycopy(value, 0, pl, 2, value.length);
        Response r = sendRecv(pl, OpClass.ACTION);
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
                // ✅ AJOUTÉ (20 août 2026) — code (#1) et type (#94), en plus
                // de la description. Échecs de lecture individuels ignorés
                // (rc=0x23 possible si champ non applicable à ce slot) —
                // ne bloque jamais le reste du scan.
                String code = "";
                try {
                    byte[] f1 = opGetField(1);
                    if (f1 != null && f1.length > 0)
                        code = new String(f1, java.nio.charset.StandardCharsets.US_ASCII)
                                   .replace("\0", "").trim();
                } catch (Exception ignored) {}
                int type = -1;
                try {
                    byte[] f94 = opGetField(94);
                    if (f94 != null && f94.length > 0) type = f94[0] & 0xFF;
                } catch (Exception ignored) {}
                result.add(new ProductScanResult(idx + 1, desc, code, type));
                if (progressLog != null) progressLog.onProduct("Produit " + (idx + 1) + ": " + desc);
            }
        } finally {
            try { opSetField(0, new byte[]{(byte) originalIdx}); } catch (Exception ignored) {}
        }
        return result;
    }

    public int[] opDeliveryStatus() throws IOException {
        Response r = sendRecv(buildPayload(MSG_GET_DELIVERY_STATUS, null), OpClass.STATUS);
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
    /** Point d'entrée classifié — TOUJOURS préférer cette forme à
     *  sendRecv(payload, timeoutMs) directement, pour que le comportement
     *  (queueable ou non, timeout) vienne d'un seul endroit (OpClass) et
     *  pas d'une décision au cas par cas dans chaque méthode op*(). */
    private Response sendRecv(byte[] payload, OpClass cls) throws IOException {
        if (cls == OpClass.STATUS) {
            // ✅ Timeout adaptatif — voir statusTimeoutMs. isStatusClass=true
            // active l'apprentissage (moyenne + relève sur timeout) dans la
            // boucle sendRecv ci-dessous.
            return sendRecv(payload, true, (int) statusTimeoutMs, true);
        }
        return sendRecv(payload, cls.queueable, cls.timeoutMs, false);
    }

    // Conservé pour opGetField(field, timeoutMs) — poll rapide, jamais
    // queueable, mais avec un timeout ajustable au cas par cas (ex: scan,
    // lecture décimales) plutôt que la valeur fixe d'OpClass.FAST.
    private Response sendRecv(byte[] payload, int timeoutMs) throws IOException {
        return sendRecv(payload, false, timeoutMs, false);
    }

    /** Nourrit la moyenne mobile après une résolution réussie en file. */
    private void onStatusResolved(long elapsedMs) {
        statusResolveEmaMs = (statusResolveEmaMs < 0)
            ? elapsedMs
            : (statusResolveEmaMs * (1 - STATUS_EMA_ALPHA) + elapsedMs * STATUS_EMA_ALPHA);
        long candidate = (long) (statusResolveEmaMs * STATUS_TIMEOUT_MARGIN);
        long newTimeout = Math.min(STATUS_TIMEOUT_CEILING_MS, Math.max(STATUS_TIMEOUT_FLOOR_MS, candidate));
        if (newTimeout != statusTimeoutMs) {
            android.util.Log.i("LcpLink", "STATUS adaptatif: moyenne=" + Math.round(statusResolveEmaMs)
                + "ms → nouveau plafond=" + newTimeout + "ms (était " + statusTimeoutMs + "ms)");
            statusTimeoutMs = newTimeout;
        }
    }

    /** Sur timeout franc : le plafond actuel est prouvé insuffisant — on le
     *  relève tout de suite plutôt que d'attendre que la moyenne rattrape. */
    private void onStatusTimedOut(long elapsedMs) {
        long bumped = Math.min(STATUS_TIMEOUT_CEILING_MS, statusTimeoutMs + STATUS_TIMEOUT_BUMP_MS);
        if (bumped != statusTimeoutMs) {
            android.util.Log.w("LcpLink", "STATUS timeout insuffisant (" + statusTimeoutMs
                + "ms, réel >= " + elapsedMs + "ms) — relevé à " + bumped + "ms");
            statusTimeoutMs = bumped;
        }
        // Nourrit quand même la moyenne avec ce plancher connu (elapsedMs
        // sous-estime le vrai temps de résolution puisqu'on a coupé avant,
        // mais c'est un signal valide : "au moins elapsedMs").
        statusResolveEmaMs = (statusResolveEmaMs < 0)
            ? elapsedMs
            : (statusResolveEmaMs * (1 - STATUS_EMA_ALPHA) + elapsedMs * STATUS_EMA_ALPHA);
    }

    private synchronized Response sendRecv(byte[] payload, boolean queueable, int timeoutMs, boolean isStatusClass) throws IOException {
        if (closed) throw new TransportException("Transport closed");
        if (io == null) throw new TransportException("Transport null");
        if (!io.isOpen()) throw new TransportException("Transport not open");

        // ✅ FIX CRITIQUE (11 août 2026, demande Paul — trouvé via trace
        // TX/RX brute directement dans le tab) — preuve DIRECTE que Get
        // Machine Status (0x23) pouvait rester bloqué en RC=0x26 EN BOUCLE
        // INFINIE sans jamais se résoudre, alors que le même mécanisme
        // (Check Request 0x7D) résolvait avec succès les RC=0x26 sur Set
        // Field. D'où l'ajout de Get Machine Status/Get Delivery Status au
        // comportement "queueable" (0x7D).
        // ✅ REVU (13 août 2026) — ce "queueable" est maintenant décidé par
        // OpClass, pas déduit du type de message ici. Voir OpClass pour la
        // politique complète (FAST/STATUS/ACTION) et pourquoi GET_MACHINE_
        // STATUS/GET_DELIVERY_STATUS sont "queueable" mais PLAFONNÉS à
        // 2.5s au lieu de 6-8s — un GET busy ne doit plus pouvoir geler
        // tout le node derrière lui.

        byte[] frame = encodeFrame(payload);
        final byte msg = (payload != null && payload.length > 0) ? payload[0] : 0;

        t("TX: " + hexDump(frame));
        synchronized (ioLock) {
            try {
                io.write(frame, 500);
            } catch (Exception e) {
                throw new TransportException("Error writing", e);
            }
        }

        long deadline = System.currentTimeMillis() + timeoutMs;
        long startMs = System.currentTimeMillis();
        boolean queued = false;
        int lastQueued = -1;
        long nextCheck = 0L;
        // ✅ FIX CRITIQUE (11 août 2026, demande Paul — "ça ramasse en peu
        // de temps... c'est malade") — trouvé via un vrai log de tab :
        // certains cycles prennent 10-15 échanges 0x7D avant de se
        // résoudre, TOUJOURS espacés du même QP_MS=200ms fixe, peu importe
        // combien de tentatives ont déjà échoué — générant un volume de
        // trafic/logs énorme sur 5 minutes. Ralentissement progressif
        // ajouté : reste à 200ms pour les toutes premières tentatives
        // (cas normal, résolution rapide), puis double jusqu'à un plafond
        // de 2s après plusieurs échecs consécutifs — la file finit quand
        // même par se résoudre (le comportement fonctionnel ne change
        // pas), mais avec beaucoup moins de trafic/bruit pendant qu'elle
        // prend son temps.
        int checkAttempts = 0;
        long checkIntervalMs = QP_MS;

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
                checkAttempts++;
                if (checkAttempts >= 5) {
                    checkIntervalMs = Math.min(checkIntervalMs * 2, 2000);
                }
                nextCheck = System.currentTimeMillis() + checkIntervalMs;
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
                // ✅ AJOUTÉ (13 août 2026, demande Paul — "dans logcat on est
                // en mesure de voir les appels ?") — jusqu'ici, seul t()
                // (TraceSink "Afficher TX/RX") voyait ce moment. On logue
                // aussi via android.util.Log, tag LcpLink — visible dans
                // logcat même sans le trace sink, pour corréler avec les
                // attentes LcpNodeLocks.
                if (!queued) {
                    android.util.Log.w("LcpLink", "sendRecv: msg=0x" + hex2(msg)
                        + " → BUSY (rc=0x" + hex2(rc) + "), entrée en file (0x7D), timeoutMs=" + timeoutMs);
                    // ✅ AJOUTÉ (13 août 2026, demande Paul — "le tick est
                    // embrouillé encore, c'est quoi l'affaire") — deux
                    // hypothèses de suite (doublon supervisionFuture, boucle
                    // clearTicketPendingSafeForAlign) n'expliquaient PAS le
                    // martelage continu de GET_MACHINE_STATUS (0x23) observé
                    // le 13 août en après-midi. Plutôt que deviner un
                    // troisième appelant depuis la lecture du code, on capture
                    // maintenant la VRAIE trace d'appel Java au moment précis
                    // où 0x23 (ou tout autre message queueable) entre en
                    // file — le prochain log dira exactement quelle méthode,
                    // quelle ligne, sans ambiguïté.
                    if (msg == MSG_GET_MACHINE_STATUS) {
                        StringBuilder st = new StringBuilder("sendRecv: msg=0x23 (GET_MACHINE_STATUS) appelé depuis:");
                        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
                        int shown = 0;
                        for (StackTraceElement e : trace) {
                            String cn = e.getClassName();
                            if (cn.contains("Thread") || cn.contains("LcpLink")) continue;
                            st.append("\n    at ").append(cn).append(".").append(e.getMethodName())
                              .append("(").append(e.getFileName()).append(":").append(e.getLineNumber()).append(")");
                            shown++;
                            if (shown >= 8) break;
                        }
                        android.util.Log.w("LcpLink", st.toString());
                    }
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
                long elapsed = System.currentTimeMillis() - startMs;
                android.util.Log.w("LcpLink", "sendRecv: msg=0x" + hex2(msg)
                    + " → résolu après " + elapsed + "ms en file");
                if (isStatusClass) onStatusResolved(elapsed);
                return new Response(norm[0] & 0xFF, norm);
            }

            if (queued) {
                long elapsed = System.currentTimeMillis() - startMs;
                android.util.Log.w("LcpLink", "sendRecv: msg=0x" + hex2(msg)
                    + " → résolu après " + elapsed + "ms en file (rc direct)");
                if (isStatusClass) onStatusResolved(elapsed);
            }
            return new Response(rc, f.payload);
        }

        if (queued) {
            long elapsed = System.currentTimeMillis() - startMs;
            android.util.Log.e("LcpLink", "sendRecv: msg=0x" + hex2(msg)
                + " → TIMEOUT après " + elapsed + "ms en file (dernier rc=0x"
                + hex2(lastQueued) + ", timeoutMs=" + timeoutMs + ")");
            if (isStatusClass) onStatusTimedOut(elapsed);
            throw new IOException("Queued timeout last=0x" + hex2(lastQueued));
        }
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
