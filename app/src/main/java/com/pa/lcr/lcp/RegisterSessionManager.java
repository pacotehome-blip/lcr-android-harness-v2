package com.pa.lcr.lcp;

import android.content.Context;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.pa.lcr.lcp.log.LogBus;
import com.pa.lcr.lcp.storage.DeliveryLogStore;
import com.pa.lcr.lcp.transport.MediaTransportManager;
import com.pa.lcr.lcp.transport.TransportIo;
import com.pa.lcr.lcp.transport.TransportSnapshot;
import com.pa.lcr.lcp.transport.TransportStatus;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * RegisterSessionManager — v7 finale (node + serial -> 1 média attaché)
 *
 * ✅ Option B (TransportIo strict): sessions indexées par transportKey + ":" + node
 * ✅ v7: pin média par registre (node + serial #80) => resolveOrCreateForNode()
 * ✅ LogBus: chaque log porte le node (le tab filtre snapshotForNode(node))
 * ✅ Compat UI legacy maintenue
 */
public final class RegisterSessionManager {

    private static volatile RegisterSessionManager INSTANCE;

    public static RegisterSessionManager get(Context ctx) {
        if (INSTANCE == null) {
            synchronized (RegisterSessionManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new RegisterSessionManager(ctx.getApplicationContext());
                }
            }
        }
        return INSTANCE;
    }

    private final Context appCtx;

    // ✅ CORRECTIF AJOUTÉ (requis par ApiFacadeImpl)
    public Context getAppContext() { return appCtx; }

    private final DeliveryLogStore store;

    // ✅ Option B: key = transportKey + ":" + node
    private final Map<String, NodeSession> sessions = new java.util.concurrent.ConcurrentHashMap<>();

    // ✅ Verrou PAR CLÉ (transport+node) — remplace le verrou global partagé
    // (synchronized sur toute l'instance) qui bloquait TOUS les registres
    // entre eux, même sur des transports totalement indépendants (ex: un
    // deep link pour ouvrir un nouveau registre BT devait attendre qu'une
    // sonde LC3 sur TCP se termine, ~1-2s, avant de pouvoir démarrer).
    private final java.util.concurrent.ConcurrentHashMap<String, Object> creationLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    private Object lockFor(String key) {
        return creationLocks.computeIfAbsent(key, k -> new Object());
    }

    // ✅ v7: identité registre (node + serial) et pin du média
    // - expectedSerialByNode: serial attendu (scan / validate) pour un node
    // - pinnedTransportByRegKey: (node#serial) -> transportKey choisi
    // ✅ FIX (perf/concurrence) : ConcurrentHashMap au lieu de LinkedHashMap —
    // permet un accès concurrent sûr sans verrou global partagé entre
    // transports indépendants (voir getOrCreate/resolveOrCreateForNode
    // ci-dessous, qui n'utilisent plus qu'un verrou PAR CLÉ transport+node).
    // Aucun code existant ne dépendait de l'ordre d'insertion de ces maps.
    private final Map<Integer, String> expectedSerialByNode = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> pinnedTransportByRegKey = new java.util.concurrent.ConcurrentHashMap<>();
    // transportKey → serialId connu
    private final java.util.concurrent.ConcurrentHashMap<String, String> knownLc3TransportKeys =
        new java.util.concurrent.ConcurrentHashMap<>();
    // ✅ FIX : transportKey → node CONFIRMÉ pour ce serial LC3. Sans cette
    // vérification, knownLc3TransportKeys (indexé par transport SEUL) faisait
    // réutiliser aveuglément le serial connu pour N'IMPORTE QUEL node demandé
    // sur ce même transport — créant un onglet fantôme si un appel arrivait
    // avec un node différent du vrai (ex: node=250 demandé par erreur alors
    // que le LC3 réel est au node=245 sur ce même transport TCP).
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> knownLc3NodeByTransportKey =
        new java.util.concurrent.ConcurrentHashMap<>();
    private RegisterSessionManager(Context appCtx) {
        this.appCtx = appCtx;
        this.store = new DeliveryLogStore(appCtx);
        this.store.purgeOlderThanDaysAsync(7);
        // ✅ FIX (6 août 2026, demande Paul — "entretien systématique pour
        // conserver les 7 derniers jours") — même politique pour les backups
        // JSON dans Téléchargements (purge prudente : uniquement ceux déjà
        // confirmés SYNCED, voir LocalDeliveryBackup.purgeOldSyncedBackupsAsync).
        try {
            com.pa.lcr.lcp.storage.LocalDeliveryBackup.purgeOldSyncedBackupsAsync(appCtx, 7);
        } catch (Exception ignored) {}
        // ✅ AJOUTÉ (7 août 2026, demande Paul) — même politique de rétention 7 jours
        // pour les backups Support créés par le nouveau bouton "Vider (avec backup)".
        try {
            com.pa.lcr.lcp.storage.DeliveryLogStore.purgeOldSupportBackupsAsync(appCtx, 7);
        } catch (Exception ignored) {}
        // Charger les transports LC3 connus depuis SharedPreferences
        try {
            android.content.SharedPreferences prefs =
                appCtx.getSharedPreferences("lc3_known_transports", 0);
            for (String key : prefs.getAll().keySet()) {
                knownLc3TransportKeys.put(key, prefs.getString(key, ""));
            }
        } catch (Exception ignored) {}
    }

    public DeliveryLogStore getStore() { return store; }

    // ✅ v7: clé registre = node#serial (serial = #80)
    private static String regKey(int nodeDec, String serialId) {
        int node = nodeDec;
        String s = (serialId == null) ? "" : serialId.trim();
        return node + "#" + s;
    }

    /** Permet au scan/validate d'enregistrer le serial attendu pour un node. */
    public synchronized void bindExpectedSerial(int nodeDec, String serialId) {
        int node = nodeDec;
        if (serialId == null || serialId.trim().isEmpty()) return;
        expectedSerialByNode.put(node, serialId.trim());
    }

    public synchronized String getExpectedSerial(int nodeDec) {
        int node = nodeDec;
        return expectedSerialByNode.get(node);
    }
    /** ✅ Rattrapage UI — retourne toutes les sessions connues (node + serial + transportKey) */
    public synchronized List<int[]> listKnownNodeSerials() {
        List<int[]> result = new ArrayList<>();
        for (Map.Entry<Integer, String> e : expectedSerialByNode.entrySet()) {
            if (e == null || e.getKey() == null || e.getValue() == null) continue;
            result.add(new int[]{e.getKey()});
        }
        return result;
    }

    /** ✅ Rattrapage UI — retourne node + serial + transportKey pinné */
    public synchronized List<String[]> listKnownRegisters() {
        List<String[]> result = new ArrayList<>();
        for (Map.Entry<Integer, String> e : expectedSerialByNode.entrySet()) {
            if (e == null || e.getKey() == null || e.getValue() == null) continue;
            int node = e.getKey();
            String serial = e.getValue();
            String rk = regKey(node, serial);
            String transport = pinnedTransportByRegKey.get(rk);
            result.add(new String[]{
                String.valueOf(node),
                serial,
                transport != null ? transport : ""
            });
        }
        return result;
    }
    private static String key(String transportKey, int nodeDec) {
        int node = nodeDec;
        String k = (transportKey == null || transportKey.trim().isEmpty()) ? "?" : transportKey.trim();
        return k + ":" + node;
    }

    // =========================================================
    // ✅ v7: Résolution média par registre (node + serial)
    // - Si serial attendu connu: choisir le transport READY dont #80 match
    // - Sinon: réutiliser une session existante unique pour ce node
    // =========================================================
    // ✅ FIX (perf) : plus de synchronized sur toute l'instance — les maps
    // partagées sont maintenant des ConcurrentHashMap (thread-safe sans
    // verrou externe), et la création réelle de session passe par
    // getOrCreate() qui protège désormais par verrou PAR CLÉ transport+node.
    // Avant ce correctif, la boucle de sonde (étape 2 ci-dessous, qui peut
    // interroger plusieurs transports READY) bloquait TOUT appel concurrent
    // à n'importe quelle méthode synchronized de cette classe — y compris
    // pour un registre totalement indépendant sur un autre transport.
    public DeliveryController resolveOrCreateForNode(int nodeDec, int fromDec) {
        int node = nodeDec;
        int from = fromDec & 0xFF;

        MediaTransportManager mgr = MediaTransportManager.get(appCtx);

        // 0) si un transport est déjà pinné pour node#serial, on le réutilise
        String expectedSerial = expectedSerialByNode.get(node);
        if (expectedSerial != null && !expectedSerial.trim().isEmpty()) {
            String rk = regKey(node, expectedSerial);
            String pinned = pinnedTransportByRegKey.get(rk);
            if (pinned != null) {
                TransportIo io = mgr.getByKey(pinned);
                if (io != null && io.isOpen()) {
                    return getOrCreate(pinned, node, from, io);
                }
            }
        }

        // 1) si on a déjà une session existante unique pour ce node, la réutiliser
        NodeSession one = null;
        for (Map.Entry<String, NodeSession> e : sessions.entrySet()) {
            if (e == null) continue;
            String k = e.getKey();
            if (k == null) continue;
            if (!k.endsWith(":" + node)) continue;
            NodeSession s = e.getValue();
            if (s == null) continue;

            if (one == null) one = s;
            else { one = null; break; } // plusieurs sessions (USB+BT) => pas au hasard
        }
        if (one != null) {
            TransportIo io = mgr.getByKey(one.transportKey);
            if (io != null && io.isOpen()) return getOrCreate(one.transportKey, node, from, io);
        }

        // 2) si serial attendu connu: probe tous les transports READY et choisir celui dont #80 match
        if (expectedSerial != null && !expectedSerial.trim().isEmpty()) {
            String want = expectedSerial.trim();
            List<TransportSnapshot> snaps = mgr.listSnapshots();
            if (snaps != null) {
                for (TransportSnapshot s : snaps) {
                    if (s == null || s.key == null) continue;
                    if (s.status != TransportStatus.READY) continue;

                    TransportIo io = mgr.getByKey(s.key);
                    if (io == null || !io.isOpen()) continue;

                    String serial = probeSerial(io, node, from);
                    if (serial != null && serial.equalsIgnoreCase(want)) {
                        pinnedTransportByRegKey.put(regKey(node, want), s.key);
                        return getOrCreate(s.key, node, from, io);
                    }
                }
            }
        }

        // 3) fallback: pickReady (USB puis n'importe quel READY)
        try {
            ArrayList<String> pref = new ArrayList<>();
            pref.add(MediaTransportManager.KEY_USB);
            TransportIo io = mgr.pickReady(pref);
            if (io == null) io = mgr.pickReady(null);
            if (io != null && io.isOpen()) return getOrCreate(io.getKey(), node, from, io);
        } catch (Exception ignored) {}

        return null;
    }

    private void t(String s) { /* log stub */ }

    // Lecture best-effort du serial (#80) sur un transport donné
    // Tente LCR-II (LcpLink) d'abord, puis LC3 (Lc3Link)
    private String probeSerial(TransportIo io, int nodeDec, int fromDec) {
        // ✅ FIX (6 août 2026, demande Paul) — verrou partagé par node (voir
        // LcpNodeLocks) : cette sonde crée un LcpLink temporaire, séparé de
        // tout DeliveryController — sans ce verrou, elle pouvait entrer en
        // collision de protocole avec le live polling d'une session déjà
        // active sur le même transport physique, même avec une seule
        // instance de DeliveryController.
        synchronized (LcpNodeLocks.forNode(nodeDec)) {
        // Essai LCR-II
        try {
            LcpLink tmp = new LcpLink(io, nodeDec, fromDec, true);
            byte[] b = tmp.opGetField(80, 500);
            if (b != null && b.length > 0) {
                String s = new String(b, StandardCharsets.UTF_8);
                int nul = s.indexOf('\0');
                if (nul >= 0) s = s.substring(0, nul);
                s = s.trim();
                if (!s.isEmpty()) return s;
            }
        } catch (Exception ignored) {}

        // Essai LC3 — seulement si transport non connu comme LCR-II
        String ioKey = io != null ? io.getKey().trim() : "";
        boolean isKnownLc3 = knownLc3TransportKeys.containsKey(ioKey);
        boolean isUsb = "USB".equalsIgnoreCase(ioKey);
        boolean skipLc3Probe = isUsb || (!isKnownLc3 && knownLc3TransportKeys.size() > 0
                && ioKey.toUpperCase().startsWith("BT:"));
        if (!skipLc3Probe) try {
            if (Lc3Link.probe(io)) {

                Lc3Link lc3 = new Lc3Link(io);
                byte[] b = lc3.opGetField(80, 3000);
                if (b != null && b.length > 0) {
                    String s = new String(b, StandardCharsets.UTF_8).trim();
                    if (!s.isEmpty()) return "LC3:" + s;
                }
                return "LC3";
            }
        } catch (Exception ignored) {}

        return null;
        } // fin synchronized (LcpNodeLocks.forNode(nodeDec)) — voir haut de probeSerial()
    }

    // =========================================================
    // ✅ Option B: API principale (TransportIo)
    // =========================================================
    public synchronized DeliveryController getController(String transportKey, int nodeDec) {
        NodeSession s = sessions.get(key(transportKey, nodeDec));
        return (s != null) ? s.dc : null;
    }

    // ✅ (4 août 2026, demande Paul : "on ne doit jamais oublier l'arrivée du
    // deeplink peu importe le transport trouvé" + "valide aussi pour l'API")
    // — vérifie si une livraison est en cours (RUNNING_FLOWING/PAUSED) sur
    // N'IMPORTE QUEL node connu pour un transport donné. Utilisé par
    // MultiRegisterApiFacadeImpl avant tout activateExclusive() déclenché par
    // un appel API (BT auto-connect, register/connect-auto, etc.) pour ne
    // jamais voler l'exclusivité d'un transport à une livraison déjà active,
    // peu importe le point d'entrée (UI ou API).
    public synchronized boolean hasRunningDeliveryOn(String transportKey) {
        if (transportKey == null || transportKey.trim().isEmpty()) return false;
        String prefix = transportKey.trim() + ":";
        for (Map.Entry<String, NodeSession> e : sessions.entrySet()) {
            if (e.getKey() == null || !e.getKey().startsWith(prefix)) continue;
            NodeSession s = e.getValue();
            if (s == null || s.dc == null) continue;
            try {
                DeliveryState st = s.dc.getState();
                if (st == DeliveryState.RUNNING_FLOWING || st == DeliveryState.RUNNING_PAUSED) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    /** v7: retrouve le transportKey associé à un controller (si présent). */
    public synchronized String findTransportKeyForController(DeliveryController dc) {
        if (dc == null) return null;
        for (NodeSession s : sessions.values()) {
            if (s == null) continue;
            if (s.dc == dc) return s.transportKey;
        }
        return null;
    }

    // ✅ FIX (4 août 2026, demande Paul — "comment peut-il avoir un USB(OFF)
    // qui arrive avec ça") — retrouve le transport RÉEL actuellement utilisé
    // pour un node donné, peu importe lequel. Utilisé par RegisterTabFragment
    // pour se réaligner sur le bon transport après un api_registerConnectAuto()
    // réussi, au lieu de rester bloqué à revérifier le transport ORIGINAL sur
    // lequel ce tab a été créé (qui peut ne plus être celui qui répond).
    public synchronized String findTransportKeyForNode(int nodeDec) {
        String suffix = ":" + nodeDec;
        for (Map.Entry<String, NodeSession> e : sessions.entrySet()) {
            if (e.getKey() == null || !e.getKey().endsWith(suffix)) continue;
            NodeSession s = e.getValue();
            if (s != null) return s.transportKey;
        }
        return null;
    }

    public DeliveryController getOrCreate(String transportKey, int nodeDec, int fromDec, TransportIo io) {
        int node = nodeDec;
        int from = fromDec & 0xFF;
        if (io == null || !io.isOpen()) return null;

        String tk = (transportKey == null || transportKey.trim().isEmpty()) ? io.getKey() : transportKey.trim();
        String k = key(tk, node);

        // ✅ FIX (perf) : verrou PAR CLÉ, jamais un verrou global partagé.
        // La sonde LC3/LCR-II ci-dessous peut prendre jusqu'à ~1-2s (réseau
        // TCP) — elle ne bloque maintenant QUE les appels concurrents pour
        // CE MÊME transport+node, jamais un registre totalement indépendant
        // (ex: un nouveau BT pendant qu'un TCP-LC3 existant est sondé).
        synchronized (lockFor(k)) {
            return getOrCreateLocked(tk, k, node, from, io);
        }
    }

    private DeliveryController getOrCreateLocked(String tk, String k, int node, int from, TransportIo io) {
        // ✅ B1 FSM: activer exclusivement ce transport avant IO (évite USB/BT zombies)
        // try { MediaTransportManager.get(appCtx).activateExclusive(tk, "RSM.getOrCreate"); } catch (Exception ignored) {}

        // Multi-registre: pas d'activateExclusive ici — le tab actif gère l'activation
        android.util.Log.d("RSM", "getOrCreate transport=" + tk + " node=" + node);

        // ✅ FIX CRITIQUE (4 août 2026, demande Paul — "on ouvre par #série+node,
        // le transport n'est pas une obligation... comment ça se fait qu'il y a
        // deux transports qui cherchent en même temps") — trouvé : `sessions`
        // est indexée par transportKey+node (ex. "BT:xx:250" vs "USB:250"),
        // JAMAIS par node+#série. Donc si une session existe déjà pour ce node
        // sur UN AUTRE transport (ex. BT), un appel getOrCreate("USB", node,...)
        // pour ce MÊME registre physique ne la trouve JAMAIS — sessions.get(k)
        // cherche une clé différente — et crée une DEUXIÈME session complète
        // (nouveau DeliveryController, nouveau scheduler) en parallèle de la
        // première, pour le même appareil. Les deux existent ensuite
        // simultanément, chacune sondant/contrôlant le même matériel sans se
        // connaître. C'est une cause plausible de plusieurs symptômes de la
        // journée (deux transports semblant "chercher en même temps", switch
        // BT↔USB qui ne se complète jamais). Ici : avant de créer une nouvelle
        // session, on retire d'abord toute session existante pour CE MÊME
        // node sur un AUTRE transport — un seul registre physique = une seule
        // session, peu importe lequel des transports l'atteint.
        // ✅ FIX CRITIQUE #2 (5 août 2026, demande Paul — "je n'ai jamais eu de
        // trouble avec la connexion USB... je dis ça mais je dis rien") —
        // confirmé par log terrain : le fix précédent (migration node+serial)
        // détruisait la session USB qui FONCTIONNAIT (STATE=CONNECTED)
        // immédiatement au profit d'une tentative BT qui a ensuite ÉCHOUÉ
        // (timeout, abandon) — laissant le registre complètement déconnecté
        // alors qu'USB marchait une seconde plus tôt. La migration était
        // PRÉVENTIVE (avant même de savoir si la nouvelle tentative allait
        // réussir). Corrigé : si une session SAINE existe déjà pour ce node
        // sur un AUTRE transport, on la RÉUTILISE directement — on ne la
        // détruit plus pour une tentative qui pourrait très bien échouer.
        // On ne remplace une session que si l'ancienne est déjà morte/
        // déconnectée (auquel cas la nettoyer est sans danger).
        try {
            String nodeSuffix = ":" + node;
            for (String otherKey : new java.util.ArrayList<>(sessions.keySet())) {
                if (otherKey == null || otherKey.equals(k) || !otherKey.endsWith(nodeSuffix)) continue;
                NodeSession other = sessions.get(otherKey);
                if (other == null) continue;

                boolean otherHealthy = false;
                try {
                    otherHealthy = other.dc != null && !other.dc.isStopped()
                        && other.dc.getState() != DeliveryState.DISCONNECTED;
                    // ✅ FIX CRITIQUE (5 août 2026, demande Paul — "USB fonctionne
                    // maintenant, le BT lui capote, fait la même chose!!!") —
                    // confirmé par log terrain : ce check ne vérifiait QUE l'état
                    // interne du DeliveryController (dc.getState()), jamais si le
                    // TRANSPORT sous-jacent était réellement encore ouvert. Le
                    // transport peut mourir (ex. UsbTransportIo.close() suite à
                    // une vraie déconnexion) SANS que dc.getState() ne soit mis à
                    // jour vers DISCONNECTED — laissant ce check croire à tort
                    // qu'une session "RUNNING_FLOWING" était saine, alors que son
                    // transport était mort depuis longtemps. Résultat observé :
                    // blocage de 44 secondes du passage légitime vers BT, la
                    // session USB "fantôme" refusant de céder la place jusqu'à ce
                    // que Diagnostic force le passage. Ici : on vérifie aussi que
                    // le transport de la session existante est réellement ouvert
                    // avant de la considérer "saine".
                    if (otherHealthy) {
                        TransportIo otherIo = MediaTransportManager.get(appCtx).getByKey(other.transportKey);
                        boolean otherTransportOpen = (otherIo != null && otherIo.isOpen());
                        if (!otherTransportOpen) {
                            android.util.Log.w("RSM", "getOrCreate: session " + otherKey
                                + " se dit RUNNING/CONNECTED mais son transport (" + other.transportKey
                                + ") est FERMÉ — session fantôme, pas saine malgré son état interne");
                            otherHealthy = false;
                        }
                    }
                } catch (Exception ignored) {}

                if (otherHealthy) {
                    android.util.Log.i("RSM", "getOrCreate: session SAINE déjà active pour node="
                        + node + " sur " + otherKey + " (état=" + other.dc.getState()
                        + ") — réutilisation directe, tentative sur " + tk + " abandonnée pour ne rien casser");
                    return other.dc;
                }

                // ✅ FIX (5 août 2026, demande Paul — "si je suis à
                // RUNNING_FLOWING, je veux garder le tab ouvert en attendant
                // d'avoir le nouveau transport pour que je puisse faire un
                // Status pour reconnecter et partir l'écran diagnostique") —
                // pour une session fantôme (transport mort) dont l'état était
                // RUNNING_FLOWING/RUNNING_PAUSED au moment de la mort (une
                // vraie livraison était en cours), on ne la ferme/supprime
                // plus automatiquement ici. Le tab reste visible tel quel,
                // et c'est un clic explicite sur Status (STATUS_B) — pas une
                // migration automatique et silencieuse — qui doit déclencher
                // la reconnexion/Diagnostic. Migration automatique silencieuse
                // réservée aux sessions mortes qui N'ÉTAIENT PAS en pleine
                // livraison (CONNECTED/PRESTART/ENDING) — là, pas de perte de
                // contrôle utilisateur possible, migrer sans bruit est sûr.
                DeliveryState otherStateAtDeath = null;
                try { otherStateAtDeath = (other.dc != null) ? other.dc.getState() : null; } catch (Exception ignored) {}
                boolean wasRunning = otherStateAtDeath == DeliveryState.RUNNING_FLOWING
                        || otherStateAtDeath == DeliveryState.RUNNING_PAUSED;
                if (wasRunning) {
                    android.util.Log.w("RSM", "getOrCreate: session " + otherKey + " fantôme (transport mort) "
                        + "mais était " + otherStateAtDeath + " — tab CONSERVÉ tel quel, aucune migration "
                        + "automatique. Abandon de cette tentative sur " + tk + " — attente d'un Status manuel.");
                    com.pa.lcr.lcp.log.LogBus.api(node, "[RSM-GHOST-KEEP] node=" + node + " transport="
                        + other.transportKey + " mort pendant " + otherStateAtDeath
                        + " — tab conservé, reconnexion manuelle requise (Status)");
                    return null;
                }

                android.util.Log.w("RSM", "getOrCreate: session existante pour le MÊME node="
                    + node + " trouvée sur un AUTRE transport (" + otherKey + "), mais MORTE (état="
                    + (other.dc != null ? other.dc.getState() : "?") + ") — migration vers " + tk);
                com.pa.lcr.lcp.log.LogBus.api(node, "[RSM-MIGRATE] node=" + node
                    + " change de transport (ancienne session morte) : " + other.transportKey + " → " + tk);
                try { other.scheduler.shutdown(); } catch (Exception ignored) {}
                try { other.dc.shutdown(false); } catch (Exception ignored) {}
                sessions.remove(otherKey);
            }
        } catch (Exception ignored) {}

        NodeSession existing = sessions.get(k);

        if (existing != null) {
            // ✅ FIX (le vrai bug) : avant de réutiliser aveuglément une session
            // mise en cache (même génération de transport), vérifier que son
            // #série enregistré correspond bien au #série ATTENDU pour ce node
            // (via bindExpectedSerial/expectedSerialByNode). Sans cette
            // vérification, une session créée UNE FOIS par erreur (ex: un LC3
            // mal identifié, node défauté à 250 par coïncidence avec le vrai
            // node du LCR-II) restait en cache indéfiniment et se faisait
            // réutiliser pour TOUTE demande future sur ce (transport,node) —
            // même quand la demande concernait en réalité un tout autre
            // registre. C'est exactement ce qui créait le tab TCP fantôme
            // avant que le deep link ne trouve enfin le vrai BT.
            String expected = expectedSerialByNode.get(node);
            boolean serialMismatch = expected != null && !expected.trim().isEmpty()
                    && existing.serialId != null && !existing.serialId.trim().isEmpty()
                    && !expected.trim().equalsIgnoreCase(existing.serialId.trim());
            if (serialMismatch) {
                android.util.Log.w("RSM", "getOrCreate: session en cache INVALIDÉE pour " + k
                        + " — serial attendu=" + expected + " mais session cachée avait serial=" + existing.serialId);
                try { existing.scheduler.shutdown(); } catch (Exception ignored) {}
                try { existing.dc.shutdown(false); } catch (Exception ignored) {}
                sessions.remove(k);
                // ne PAS return — continue plus bas pour tenter une vraie (re)identification
            } else if (existing.dc.isStopped()) {
                // ✅ FIX (2026-07-28, preuve logcat) : un DeliveryController sur
                // lequel shutdown() a été appelé est MORT DÉFINITIVEMENT —
                // shutdown() fait io.shutdownNow() et liveTickScheduler.shutdownNow()
                // sur des ExecutorService déclarés final, donc non remplaçables, et
                // met stopped=true de façon irréversible.
                //
                // Le cache le rendait quand même, parce que la seule condition de
                // réutilisation était generationId — or la génération ne change QUE
                // si le socket est réellement rouvert. Sur un shutdown logique
                // (softClose, escalade, invalidation) le socket BT reste le même,
                // donc même génération, donc réutilisation d'un cadavre.
                //
                // Symptôme observé (logcat 2026-07-28 23:49) : toute méthode
                // ASYNCHRONE (requestStatus, requestLiveSample — tout ce qui passe
                // par io.execute) échouait avec
                //   "rejected from ThreadPoolExecutor[Terminated, pool size = 0,
                //    completed tasks = 39]"
                // tandis que les méthodes SYNCHRONES (api_registerValidate)
                // réussissaient, puisqu'elles ne passent pas par l'executor.
                // Résultat : validerTransportEtRegistrePuis annonçait "-> OK" et
                // validerConnexion() concluait "OK" à son tour (io.isOpen() vrai,
                // getState() lisible en mémoire), donc AUCUN diagnostic n'était
                // déclenché et le bouton Status ne produisait strictement aucune
                // réaction à l'écran.
                android.util.Log.w("RSM", "getOrCreate: session en cache INVALIDÉE pour " + k
                        + " — DeliveryController stopped (executor Terminated), recréation forcée");
                try { existing.scheduler.shutdown(); } catch (Exception ignored) {}
                sessions.remove(k);
                // ne PAS return — on continue plus bas pour recréer une vraie session.
                // Pas d'appel à existing.dc.shutdown() ici : il est déjà arrêté.
            } else if (existing.generationId == io.getGenerationId()) {
                return existing.dc;
            }
            // ✅ FIX : l'ancien test "existing.dc.getState() != null" était toujours vrai
            // (DeliveryController.state est initialisé à DISCONNECTED et n'est jamais null,
            // du début à la fin du cycle de vie — voir DeliveryController.getState()).
            // Ce "garde-fou" réutilisait donc systématiquement l'ancien DeliveryController
            // sur un changement de génération de transport (ex: reconnexion BT après coupure),
            // alors que son executor "io" (final, non remplaçable) était déjà shutdownNow()
            // depuis la fermeture précédente — d'où les "Task rejected from ThreadPoolExecutor
            // [Terminated]" en boucle après reconnexion, et le diagnostic qui restait bloqué
            // en DISCONNECTED. Sur changement de génération, on recrée toujours.
            try { existing.scheduler.shutdown(); } catch (Exception ignored) {}
            try { existing.dc.shutdown(false); } catch (Exception ignored) {}
            sessions.remove(k);
        }

        // ── Détection automatique LCR-II vs LC3 ──────────────────
        Lc3Link.RegisterIdentity identity = null;
        boolean isUiThread = android.os.Looper.myLooper() == android.os.Looper.getMainLooper();
        if (!isUiThread) {
            try {
                identity = Lc3Link.probeAndIdentify(io);
            } catch (Exception probeEx) {
                android.util.Log.w("RSM", "probeAndIdentify exception: " + probeEx.getMessage());
            }
        } else {
            // Sur UI thread — vérifier si le transport est BT ou TCP (LC3 probable)
            // Si oui, forcer isLc3=true si le key contient "BT:"/"TCP:" et qu'on a
            // un serial LC3 connu (pré-enregistré via markAsLc3Transport).
            // ✅ FIX : "tcp:" ajouté — sans ça, un registre LC3 branché en TCP
            // (N-Port raw) était toujours traité comme LCR-II générique sur le
            // thread UI, faussant la lecture NET/GROSS (mauvaise sous-classe Link).
            String tkLower = tk.toLowerCase(java.util.Locale.ROOT);
            Integer confirmedNode = knownLc3NodeByTransportKey.get(tk);
            boolean nodeMatchesConfirmed = (confirmedNode != null && confirmedNode == node);
            if ((tkLower.startsWith("bt:") || tkLower.startsWith("tcp:")) && knownLc3TransportKeys.containsKey(tk)
                    && nodeMatchesConfirmed) {
                android.util.Log.i("RSM", "UI thread: transport BT/TCP LC3 connu → assumé LC3 (node confirmé=" + confirmedNode + ")");

                // Chercher serial dans expectedSerialByNode ou pinnedTransportByRegKey
                // Chercher serial dans la map LC3 d'abord, puis expectedSerialByNode

                String knownSerial = knownLc3TransportKeys.get(tk);
                if (knownSerial == null || knownSerial.isEmpty()) {
                    knownSerial = expectedSerialByNode.get(node);
                }
                android.util.Log.i("RSM", "UI thread LC3 knownSerial=" + knownSerial + " node=" + node + " tk=" + tk);
                identity = new Lc3Link.RegisterIdentity(true,
                    knownSerial != null ? knownSerial : "", node, 0, "");

            }
            android.util.Log.w("RSM", "getOrCreate sur UI thread — probe LC3 " + (identity != null ? "assumé LC3" : "skippé"));
        }
        
        boolean isLc3 = (identity != null && identity.isLc3);
        android.util.Log.i("RSM", "probe → " + (identity != null ? identity.toString() : "null")
                + "  transport=" + tk);

        LcpLink link;
        if (isLc3) {
            // ✅ FIX (la vraie source du tab TCP fantôme) : "LC3" est le placeholder
            // interne de Lc3Link.probeAndIdentify quand la lecture réelle échoue —
            // il n'est PAS vide, donc il passait le test "!isEmpty()" ci-dessous et
            // polluait expectedSerialByNode/pinnedTransportByRegKey avec "LC3"
            // comme si c'était un vrai #série. Contrairement à la branche LCR-II
            // (qui abandonne proprement si le serial est vide), cette branche LC3
            // n'avait AUCUN garde-fou équivalent — elle créait une session
            // "CONNECTED" à partir d'une identité invalide (node=0, serial=LC3),
            // que resolveOrCreateForNode() réutilisait ensuite pour n'importe quel
            // autre node demandé sur ce même transport.
            boolean identityValid = identity.serialId != null && !identity.serialId.trim().isEmpty()
                    && !identity.serialId.trim().equals("LC3") && identity.nodeId > 0;
            if (!identityValid) {
                android.util.Log.w("RSM", "getOrCreate LC3 identité invalide (placeholder/node=0) — abandon transport=" + tk + " node=" + node);
                return null;
            }
            knownLc3TransportKeys.put(tk, identity.serialId != null ? identity.serialId : "");
            int lc3Node = (identity.nodeId > 0) ? identity.nodeId : node;
            link = new Lc3Link(io, identity.serialId.isEmpty() ? null : identity.serialId);
            if (identity.serialId != null && !identity.serialId.isEmpty()) {
                expectedSerialByNode.put(lc3Node, identity.serialId);
                pinnedTransportByRegKey.put(regKey(lc3Node, identity.serialId), tk);
                android.util.Log.i("RSM", "LC3: node=" + lc3Node
                        + " serial=" + identity.serialId);
            }
        } else {
            link = new LcpLink(io, node, from, true);
        }
        DeliveryController dc = new DeliveryController(link);
        dc.setLogStore(store);

        NodeScheduler scheduler = new NodeScheduler(node);
        MuxListener mux = new MuxListener();
        mux.addListener(new LogBusSink(node, scheduler));
        mux.addListener(scheduler);

        dc.setListener(mux);
        try { dc.initialize(); }
        catch (Exception e) {
            // ✅ (4 août 2026) — si l'initialisation du controller échoue ici, la
            // session semble créée (tab visible) mais ses mécanismes internes
            // (état CONNECTED, poll auto, etc.) peuvent ne jamais démarrer —
            // silencieusement avant ce fix.
            com.pa.lcr.lcp.log.LogBus.err(node, "RegisterSessionManager.getOrCreateLocked.dc.initialize", e);
        }

        // ✅ v7: serial déjà connu via identity ou knownLc3TransportKeys — pas de opGetField(80) ici
        String serialId0 = null;
        if (isLc3) {
            // Pour LC3 — serial déjà dans cachedSerial du Lc3Link via constructeur
            serialId0 = (identity != null && !identity.serialId.isEmpty()) ? identity.serialId : null;
        } else if (isUiThread) {
            // ✅ FIX (4 août 2026, demande Paul : "j'ai encore du trouble avec
            // l'échange de connexion entre BT et USB sur le même registre") —
            // AVANT ce fix, le chemin LC3 évitait déjà tout I/O bloquant sur le
            // thread UI (probe skippé plus haut), mais le chemin LCR-II faisait
            // quand même un vrai appel bloquant link.opGetField(80, 3000) —
            // jusqu'à 3s de blocage potentiel, et surtout : au moment exact
            // d'un switch BT↔USB pour le MÊME registre, le transport peut être
            // brièvement instable/pas encore prêt, faisant échouer cette
            // lecture (catch silencieux) → abandon de la session, donc le
            // switch ne se complète jamais. Ici : sur le thread UI, on utilise
            // le #série déjà connu pour ce node (expectedSerialByNode, alimenté
            // par la session BT/USB précédente sur ce même registre) au lieu
            // de retenter une lecture réseau. Le vrai opGetField(80) reste fait
            // normalement quand getOrCreate est appelé hors thread UI (ex. le
            // premier scan/connexion initiale, où il n'y a pas encore de
            // #série connu).
            serialId0 = expectedSerialByNode.get(node);
            android.util.Log.i("RSM", "getOrCreate sur UI thread (LCR-II) — #série depuis cache: "
                + (serialId0 != null ? serialId0 : "AUCUN"));
        } else {
            // ✅ REVERT (4 août 2026, demande Paul) — le retry 3× ajouté plus tôt
            // (basé sur une seule ligne de log, jamais assez validé) allongeait
            // chaque tentative jusqu'à ~9s dans le pire cas. Ton rapport de
            // "temps mort à l'ouverture, USB plus détecté du tout" après ce
            // changement pointe directement vers cet ajout — plusieurs appels
            // getOrCreate se chevauchent déjà normalement (USB, BT, re-BT) au
            // démarrage/deep link, et multiplier chacun par 3 empile les
            // délais au lieu de les résoudre. Retour à une seule tentative
            // (comportement original), la visibilité LogBus.err() est
            // conservée — elle, elle n'ajoute aucun délai et reste utile.
            try {
                byte[] b80 = link.opGetField(80, 3000);
                if (b80 != null && b80.length > 0) {
                    String ss = new String(b80, StandardCharsets.UTF_8);
                    int nul = ss.indexOf('\0');
                    if (nul >= 0) ss = ss.substring(0, nul);
                    ss = ss.trim();
                    if (!ss.isEmpty()) serialId0 = ss;
                }
            } catch (Exception e) {
                // ✅ FIX (4 août 2026, demande Paul : "est-ce qu'on aurait pu
                // tracer ça avec le log Support?") — RÉPONSE : non, ce catch
                // avalait silencieusement la vraie cause d'échec (timeout,
                // IO, etc.) sans jamais toucher LogBus, donc invisible en
                // Support même si on avait su où chercher.
                com.pa.lcr.lcp.log.LogBus.err(node,
                    "RegisterSessionManager.getOrCreateLocked.opGetField80", e);
            }

        }
        // ✅ LCR-II sans serial — registre pas prêt, abandonner sans créer de session
        if (!isLc3 && (serialId0 == null || serialId0.isEmpty())) {
            android.util.Log.w("RSM", "getOrCreate LCR-II sans serial — abandon transport=" + tk + " node=" + node);
            // ✅ FIX (4 août 2026, demande Paul) — élevé aussi vers LogBus :
            // c'est le point de décision exact qui a causé "l'échange BT/USB
            // qui ne se complète pas" — avant ce fix, seul logcat le montrait.
            com.pa.lcr.lcp.log.LogBus.api(node, "[RSM-ABANDON] getOrCreate LCR-II sans #série — "
                + "session non créée. transport=" + tk + " node=" + node
                + " (uiThread=" + isUiThread + ")");
            // ✅ FIX : si un pin (node,serial) pointait vers CE transport, l'effacer.
            // Sans ça, resolveOrCreateForNode() retente indéfiniment ce même
            // transport erroné à chaque appel (pin jamais invalidé), créant un
            // tab TCP visible à chaque tentative avant que le vrai transport
            // (ex: BT) ne soit enfin trouvé et migré.
            try {
                for (String rk : new java.util.ArrayList<>(pinnedTransportByRegKey.keySet())) {
                    String pinnedTk = pinnedTransportByRegKey.get(rk);
                    if (tk.equalsIgnoreCase(pinnedTk) && rk.startsWith(node + "#")) {
                        pinnedTransportByRegKey.remove(rk);
                        android.util.Log.i("RSM", "Pin périmé retiré: " + rk + " -> " + tk);
                    }
                }
            } catch (Exception ignored) {}
            try { dc.shutdown(false); } catch (Exception ignored) {}
            return null;
        }
        if (serialId0 != null) {
            expectedSerialByNode.put(node, serialId0);
            pinnedTransportByRegKey.put(regKey(node, serialId0), tk);
        }

        NodeSession s = new NodeSession(dc, mux, scheduler, tk, io.getGenerationId(), serialId0);
        sessions.put(k, s);

        scheduler.bindController(dc);
        return dc;

    }

    public synchronized void attachUiListener(String transportKey, int nodeDec, DeliveryControllerPort.Listener uiListener) {
        if (uiListener == null) return;
        NodeSession s = sessions.get(key(transportKey, nodeDec));
        if (s == null) return;
        s.mux.addListener(uiListener);
        s.scheduler.setUiSubscribed(true);
    }

    public synchronized void detachUiListener(String transportKey, int nodeDec, DeliveryControllerPort.Listener uiListener) {
        if (uiListener == null) return;
        NodeSession s = sessions.get(key(transportKey, nodeDec));
        if (s == null) return;
        s.mux.removeListener(uiListener);
        s.scheduler.setUiSubscribed(false);
    }

    // =========================================================
    // ✅ LEGACY COMPAT (UI/RegisterTabFragment) — fallback READY
    // =========================================================
    @Deprecated
    public synchronized DeliveryController getOrCreate(int nodeDec, int fromDec, UsbSerialPort port) {
        TransportIo io = null;
        try {
            MediaTransportManager mgr = MediaTransportManager.get(appCtx);
            if (mgr != null) {
                io = mgr.getByKey(MediaTransportManager.KEY_USB);
                if (io == null) io = mgr.pickReady(null);
            }
        } catch (Exception ignored) {}
        if (io == null || !io.isOpen()) return null;
        return getOrCreate(io.getKey(), nodeDec, fromDec, io);
    }

    @Deprecated
    public synchronized void attachUiListener(int nodeDec, DeliveryControllerPort.Listener uiListener) {
        if (uiListener == null) return;
        int node = nodeDec;
        for (Map.Entry<String, NodeSession> e : sessions.entrySet()) {
            if (e == null) continue;
            String k = e.getKey();
            if (k == null) continue;
            if (!k.endsWith(":" + node)) continue;
            NodeSession s = e.getValue();
            if (s == null) continue;
            s.mux.addListener(uiListener);
            s.scheduler.setUiSubscribed(true);
        }
    }

    @Deprecated
    public synchronized void detachUiListener(int nodeDec, DeliveryControllerPort.Listener uiListener) {
        if (uiListener == null) return;
        int node = nodeDec;
        for (Map.Entry<String, NodeSession> e : sessions.entrySet()) {
            if (e == null) continue;
            String k = e.getKey();
            if (k == null) continue;
            if (!k.endsWith(":" + node)) continue;
            NodeSession s = e.getValue();
            if (s == null) continue;
            s.mux.removeListener(uiListener);
            s.scheduler.setUiSubscribed(false);
        }
    }

    // =========================================================
    // Clear
    // =========================================================
    public synchronized void clearAll(boolean closeTransport) {
        for (NodeSession s : sessions.values()) {
            try { s.scheduler.shutdown(); } catch (Exception ignored) {}
            try { s.dc.shutdown(closeTransport); } catch (Exception ignored) {}
        }
        sessions.clear();
    }

    // =========================================================
    // Internals
    // =========================================================
    private static final class NodeSession {
        final DeliveryController dc;
        final MuxListener mux;
        final NodeScheduler scheduler;
        final String transportKey;
        final long generationId;
        final String serialId; // ✅ FIX v7

        NodeSession(DeliveryController dc,
                    MuxListener mux,
                    NodeScheduler scheduler,
                    String transportKey,
                    long generationId,
                    String serialId) {
            this.dc = dc;
            this.mux = mux;
            this.scheduler = scheduler;
            this.transportKey = transportKey;
            this.generationId = generationId;
            this.serialId = serialId;
        }
    }

    private static final class MuxListener implements DeliveryControllerPort.Listener {
        private final CopyOnWriteArrayList<DeliveryControllerPort.Listener> listeners =
                new CopyOnWriteArrayList<>();

        void addListener(DeliveryControllerPort.Listener l) {
            if (l == null) return;
            listeners.addIfAbsent(l);
        }

        void removeListener(DeliveryControllerPort.Listener l) {
            if (l == null) return;
            listeners.remove(l);
        }

        @Override public void onStateChanged(DeliveryState state) {
            for (DeliveryControllerPort.Listener l : listeners) {
                try { l.onStateChanged(state); } catch (Exception ignored) {}
            }
        }

        @Override public void onProductsUpdated(List<ProductUiItem> products, int activeIndex0) {
            for (DeliveryControllerPort.Listener l : listeners) {
                try { l.onProductsUpdated(products, activeIndex0); } catch (Exception ignored) {}
            }
        }

        @Override public void onLog(String message) {
            for (DeliveryControllerPort.Listener l : listeners) {
                try { l.onLog(message); } catch (Exception ignored) {}
            }
        }

        @Override public void onError(String context, Throwable error) {
            for (DeliveryControllerPort.Listener l : listeners) {
                try { l.onError(context, error); } catch (Exception ignored) {}
            }
        }

        @Override public void onLiveQty(double net, double gross) {
            for (DeliveryControllerPort.Listener l : listeners) {
                try { l.onLiveQty(net, gross); } catch (Exception ignored) {}
            }
        }

        @Override public void onLiveStatus(String liveText) {
            for (DeliveryControllerPort.Listener l : listeners) {
                try { l.onLiveStatus(liveText); } catch (Exception ignored) {}
            }
        }

        @Override public void onTicketInfo(String ticketNo, String deliveryUid) {
            for (DeliveryControllerPort.Listener l : listeners) {
                try { l.onTicketInfo(ticketNo, deliveryUid); } catch (Exception ignored) {}
            }
        }

        // ✅ FIX CRITIQUE (4 août 2026, demande Paul) — MuxListener n'implémentait
        // PAS onDiagnosticReset() ni onDeliveryFinished(), donc héritait
        // silencieusement du no-op par défaut de DeliveryControllerPort.Listener.
        // Résultat réel : DeliveryController.listener EST TOUJOURS ce MuxListener
        // (voir RegisterSessionManager.getOrCreateLocked → dc.setListener(mux)),
        // donc chaque appel à listener.onDeliveryFinished(...)/onDiagnosticReset(...)
        // depuis DeliveryController ne faisait RIEN — jamais relayé vers
        // RegisterTabFragment, malgré que ces méthodes y soient correctement
        // implémentées. Le mécanisme de backup/push automatique du 3 août et
        // l'audit trail du diagnostic reset étaient donc du code mort en
        // pratique. C'est la classe de bug exacte du ticket 10909, toujours
        // active avant ce fix.
        @Override public void onDiagnosticReset(String woNum, double netBeforeL, double grossBeforeL) {
            for (DeliveryControllerPort.Listener l : listeners) {
                try { l.onDiagnosticReset(woNum, netBeforeL, grossBeforeL); }
                catch (Exception e) {
                    com.pa.lcr.lcp.log.LogBus.err(-1, "MuxListener.onDiagnosticReset", e);
                }
            }
        }

        @Override public void onDeliveryFinished(String serialId, String ticketNo, String saleNo,
                                                    double netL, double grossL) {
            for (DeliveryControllerPort.Listener l : listeners) {
                try { l.onDeliveryFinished(serialId, ticketNo, saleNo, netL, grossL); }
                catch (Exception e) {
                    com.pa.lcr.lcp.log.LogBus.err(-1, "MuxListener.onDeliveryFinished", e);
                }
            }
        }
    }

    private static final class NodeScheduler implements DeliveryControllerPort.Listener {
        private final int node;
        private final ScheduledExecutorService exec;
        private DeliveryController dc;

        private volatile boolean uiSubscribed = false;

        private volatile long lastLiveMs = 0L;
        private volatile long lastStatusMs = 0L;

        private volatile long liveBackoffMs = 0L;
        private volatile long statusBackoffMs = 0L;

        private volatile long lastTickSeqSeen = -1L;
        private volatile int noChangeCount = 0;

        // ✅ FIX CRITIQUE (5 août 2026, demande Paul — "trouve moi pourquoi")
        // — trouvé : tick() faisait un retour immédiat, SANS AUCUN I/O, dès
        // que l'état passait à CONNECTED (juste après la fin d'une
        // livraison) — seul RUNNING_FLOWING/RUNNING_PAUSED déclenchait un
        // vrai ping matériel. Le port USB restait donc en silence TOTAL
        // (zéro octet transmis) pour toute la durée de l'attente entre deux
        // livraisons. ~11s de silence complet sur un port série USB (chip
        // Prolific PL2303 ici, connu pour être capricieux) suffit largement
        // pour que la connexion soit abandonnée au niveau matériel/OS sans
        // jamais prévenir le logiciel — qui continue de croire "CONNECTED"
        // jusqu'à la prochaine vraie écriture, qui échoue alors avec
        // "Connection closed". Un débranchement/rebranchement physique
        // crée toujours une connexion fraîche, d'où pourquoi ça "marchait"
        // dans ce cas précis. Fix : un ping léger périodique même pendant
        // CONNECTED (pas seulement pendant une livraison active), pour ne
        // jamais laisser le port rester totalement silencieux.
        private volatile long lastKeepAliveMs = 0L;
        private static final long KEEP_ALIVE_MS = 5000;

        private static final long LIVE_MS = 350;
        private static final long STATUS_MS = 2500;

        NodeScheduler(int node) {
            this.node = node;
            final int nodeId = node;
            this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "NodeScheduler-" + nodeId);
                t.setDaemon(true);
                return t;
            });
        }

        void bindController(DeliveryController dc) {
            this.dc = dc;
            exec.scheduleWithFixedDelay(this::tick, 200, 200, TimeUnit.MILLISECONDS);
        }

        void setUiSubscribed(boolean v) { this.uiSubscribed = v; }

        void noteBusyRc26() {
            liveBackoffMs = Math.min(2000, Math.max(liveBackoffMs * 2, 400));
            statusBackoffMs = Math.min(2000, Math.max(statusBackoffMs * 2, 400));
        }

        void resetBackoff() {
            liveBackoffMs = 0L;
            statusBackoffMs = 0L;
        }

        private void tick() {
            DeliveryController c = dc;
            if (c == null) return;
            if (!uiSubscribed) return;
            DeliveryState st = c.getState();
            if (st == DeliveryState.DISCONNECTED) return;

            if (st == DeliveryState.CONNECTED || st == DeliveryState.PRESTART || st == DeliveryState.ENDING) {
                // ✅ FIX (voir commentaire du champ lastKeepAliveMs) — ping
                // léger périodique pour empêcher le port de rester totalement
                // silencieux entre deux livraisons.
                long now0 = System.currentTimeMillis();
                if (now0 - lastKeepAliveMs >= KEEP_ALIVE_MS) {
                    lastKeepAliveMs = now0;
                    try { c.requestStatusKeepAlive(); } catch (Exception ignored) {}
                }
                return;
            }

            boolean running = (st == DeliveryState.RUNNING_FLOWING) || (st == DeliveryState.RUNNING_PAUSED);
            if (!running) return;

            try {
                ApiResult tr = c.api_tickSnapshot();
                JSONObject td = (tr != null) ? tr.data : null;
                long seq = (td != null) ? td.optLong("seq", -1L) : -1L;
                if (seq >= 0) {
                    if (lastTickSeqSeen >= 0 && seq == lastTickSeqSeen) {
                        noChangeCount++;
                    } else {
                        noChangeCount = 0;
                        lastTickSeqSeen = seq;
                        liveBackoffMs = 0L;
                        statusBackoffMs = 0L;
                    }
                    if (noChangeCount >= 3) {
                        liveBackoffMs = Math.min(2000, Math.max(liveBackoffMs, 200));
                        liveBackoffMs = Math.min(2000, liveBackoffMs + 200);
                    }
                    if (noChangeCount >= 6) {
                        statusBackoffMs = Math.min(4000, Math.max(statusBackoffMs, 500));
                        statusBackoffMs = Math.min(4000, statusBackoffMs + 500);
                    }
                }
            } catch (Exception ignored) {}

            long now = System.currentTimeMillis();

            long liveInterval = LIVE_MS + liveBackoffMs;
            if (now - lastLiveMs >= liveInterval) {
                lastLiveMs = now;
                try {
                    c.requestLiveSample();
                    if (liveBackoffMs > 0 && noChangeCount == 0) liveBackoffMs = Math.max(0, liveBackoffMs - 200);
                } catch (Exception ignored) {}
            }

            long stInterval = STATUS_MS + statusBackoffMs;
            if (now - lastStatusMs >= stInterval) {
                lastStatusMs = now;
                try {
                    // ✅ FIX (6 août 2026, demande Paul) — ce sondage automatique
                    // (toutes les ~2.5s pendant une livraison active) se faisait
                    // aussi passer pour un vrai clic manuel sur Status — préexistant,
                    // pas lié à mon fix du 5 août. Même correction : étiqueté
                    // honnêtement, plus de faux "UI_STATUS_B" répété.
                    c.requestStatusKeepAlive();
                    if (statusBackoffMs > 0 && noChangeCount == 0) statusBackoffMs = Math.max(0, statusBackoffMs - 200);
                } catch (Exception ignored) {}
            }
        }

        @Override public void onStateChanged(DeliveryState state) {
            if (state == DeliveryState.CONNECTED) resetBackoff();
        }

        @Override public void onProductsUpdated(List<ProductUiItem> products, int activeIndex0) { }
        @Override public void onLog(String message) { }
        @Override public void onError(String context, Throwable error) { }
        @Override public void onLiveQty(double net, double gross) { }
        @Override public void onLiveStatus(String liveText) { }
        @Override public void onTicketInfo(String ticketNo, String deliveryUid) { }

        void shutdown() {
            try { exec.shutdownNow(); } catch (Exception ignored) {}
        }
    }

    private static final class LogBusSink implements DeliveryControllerPort.Listener {
        private final int node;
        private final NodeScheduler scheduler;

        LogBusSink(int node, NodeScheduler scheduler) {
            this.node = node;
            this.scheduler = scheduler;
        }

        @Override public void onStateChanged(DeliveryState state) {
            // ✅ FIX (7 août 2026, demande Paul — "je veux voir de manière
            // évidente dans l'onglet Support les connexions et
            // déconnexions") — avant ce fix, "STATE=CONNECTED"/
            // "STATE=DISCONNECTED" étaient du texte brut, noyés parmi tout
            // le reste, sans aucune distinction visuelle. Un marqueur texte
            // clair + une couleur dédiée dans Support (voir
            // DeliveryDb.createDiagnosticEventsView + MainActivity) rendent
            // maintenant ces transitions immédiatement repérables d'un coup
            // d'œil, sans avoir à lire chaque ligne. Remplace l'ancienne
            // ligne "STATE=..." — rien d'autre dans le code n'en dépendait
            // (vérifié), donc pas de doublon inutile.
            if (state == DeliveryState.CONNECTED) {
                LogBus.ui(node, "[CONNEXION] Registre connecté (node=" + node + ")");
            } else if (state == DeliveryState.DISCONNECTED) {
                LogBus.ui(node, "[DÉCONNEXION] Registre déconnecté (node=" + node + ")");
            } else {
                LogBus.ui(node, "STATE=" + (state != null ? state.name() : "null"));
            }
        }

        @Override public void onProductsUpdated(List<ProductUiItem> products, int activeIndex0) { }

        @Override public void onLog(String message) {
            if (message == null) return;
            String s = message.trim();
            if (s.startsWith("TX:") || s.startsWith("[TX]")) { LogBus.ioTx(node, s); return; }
            if (s.startsWith("RX:") || s.startsWith("[RX]")) { LogBus.ioRx(node, s); return; }
            if (s.startsWith("[API") || s.startsWith("[API]")) { LogBus.api(node, s); return; }
            LogBus.ui(node, s);
        }

        @Override public void onError(String context, Throwable error) {
            String msg = (error != null && error.getMessage() != null) ? error.getMessage() : "";
            LogBus.api(node, "[ERR][" + context + "] " + msg);
            if (msg.contains("rc=0x26") || msg.contains("rc=0X26")) {
                if (scheduler != null) scheduler.noteBusyRc26();
            }
        }

        @Override public void onLiveQty(double net, double gross) { }
        @Override public void onLiveStatus(String liveText) { }
        @Override public void onTicketInfo(String ticketNo, String deliveryUid) { }
    }
    public synchronized void markAsLc3Transport(String transportKey) {
        markAsLc3Transport(transportKey, null);
    }

    public synchronized void markAsLc3Transport(String transportKey, String serialId) {
        markAsLc3Transport(transportKey, serialId, -1);
    }

    // ✅ FIX : nouvelle surcharge avec node CONFIRMÉ — utilisée par
    // finalizeTcpRegisterTab/scanRegistersWithIo qui connaissent déjà le vrai
    // node au moment de la détection LC3. node=-1 (surcharges historiques)
    // signifie "node inconnu" — dans ce cas, le raccourci UI-thread de
    // getOrCreate n'assume PLUS jamais LC3 automatiquement (voir plus bas),
    // pour éviter de réutiliser un serial connu sur un node non confirmé.
    public synchronized void markAsLc3Transport(String transportKey, String serialId, int node) {
        if (transportKey != null && !transportKey.trim().isEmpty()) {
            String existing = knownLc3TransportKeys.get(transportKey.trim());
            String serial = (serialId != null && !serialId.isEmpty()) ? serialId :
                            (existing != null ? existing : "");
            knownLc3TransportKeys.put(transportKey.trim(), serial);
            if (node > 0) knownLc3NodeByTransportKey.put(transportKey.trim(), node);
            android.util.Log.i("RSM", "markAsLc3Transport: " + transportKey + " serial=" + serial + " node=" + node);
             // Persister dans SharedPreferences
            try {
                appCtx.getSharedPreferences("lc3_known_transports", 0)
                    .edit().putString(transportKey.trim(), serial).apply();
            } catch (Exception ignored) {}           
        }
    }
    
    
}
