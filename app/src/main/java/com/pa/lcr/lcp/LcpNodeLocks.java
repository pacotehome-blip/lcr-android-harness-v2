package com.pa.lcr.lcp;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * AJOUTE (6 aout 2026) - verrou LCP partage par node, entre DeliveryController
 * et RegisterSessionManager.probeSerial(), pour serialiser toute transaction LCP
 * vers un registre donne peu importe sa source.
 *
 * FIX CRITIQUE (7 aout 2026, demande Paul - "le tab comprend que le lien USB
 * est correct, qu'il a la communication avec le registre mais ca reste la") -
 * trouve : ce verrou utilisait synchronized (Object brut), sans aucun mecanisme
 * de delai - si une SESSION MORTE (transport USB deconnecte physiquement en
 * plein milieu d'une lecture bas niveau) a un thread bloque indefiniment DANS
 * le verrou (un read() qui ne retourne jamais), ce thread gardait le verrou
 * POUR TOUJOURS - bloquant du meme coup TOUTE nouvelle session sur ce meme
 * node, meme une session neuve avec une vraie communication fonctionnelle.
 * Avant le verrou partage d'aujourd'hui, chaque DeliveryController avait son
 * propre verrou - une ancienne session bloquee ne genait jamais une nouvelle.
 * Remplace par ReentrantLock + tryLock(timeout) : une tentative qui ne peut
 * pas obtenir le verrou dans un delai raisonnable echoue proprement au lieu
 * de bloquer indefiniment.
 */
public final class LcpNodeLocks {
    private LcpNodeLocks() {}

    private static final String TAG = "LcpNodeLocks";

    private static final ConcurrentHashMap<Integer, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    // ✅ AJOUTÉ (13 août 2026, demande Paul — "il y a un effet de bord
    // partout") — instrumentation. Avant ce fix, la contention sur ce
    // verrou était invisible tant qu'elle n'explosait pas en plainte
    // utilisateur (tick figé, scan gelé, boutons morts) — il fallait
    // fouiller le log a posteriori pour la déduire indirectement. Ce
    // registre garde, par node, qui détient le verrou et depuis quand,
    // pour logger immédiatement toute attente anormale.
    private static final ConcurrentHashMap<Integer, HolderInfo> HOLDERS = new ConcurrentHashMap<>();

    /** Seuil au-delà duquel une attente pour acquérir le verrou est journalisée. */
    private static final long SLOW_ACQUIRE_WARN_MS = 1000;

    /** Delai maximum d'attente avant d'abandonner plutot que de bloquer indefiniment. */
    public static final long LOCK_TIMEOUT_MS = 15000;

    private static final class HolderInfo {
        final String threadName;
        final long acquiredAtMs;
        HolderInfo(String threadName, long acquiredAtMs) {
            this.threadName = threadName;
            this.acquiredAtMs = acquiredAtMs;
        }
    }

    private static ReentrantLock lockForNode(int node) {
        return LOCKS.computeIfAbsent(node, k -> new ReentrantLock());
    }

    /**
     * Tente d'acquerir le verrou pour ce node, avec un delai maximum. Retourne le
     * verrou si l'acquisition reussit (a passer a release()), ou null si le delai
     * est depasse (une autre transaction - probablement une session morte bloquee -
     * tient le verrou trop longtemps).
     */
    public static ReentrantLock tryAcquire(int node, long timeoutMs) throws InterruptedException {
        ReentrantLock lock = lockForNode(node);
        long start = System.currentTimeMillis();
        boolean acquired = lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
        long waited = System.currentTimeMillis() - start;

        if (!acquired) {
            HolderInfo h = HOLDERS.get(node);
            String heldBy = (h != null)
                ? ("thread=" + h.threadName + " depuis " + (System.currentTimeMillis() - h.acquiredAtMs) + "ms")
                : "détenteur inconnu (relâché entre-temps ?)";
            android.util.Log.w(TAG, "tryAcquire ÉCHEC node=" + node + " — attendu " + waited
                + "ms sans obtenir le verrou (" + heldBy + ")");
            return null;
        }

        if (waited >= SLOW_ACQUIRE_WARN_MS) {
            android.util.Log.w(TAG, "tryAcquire LENT node=" + node + " — attendu " + waited
                + "ms avant d'obtenir le verrou (thread=" + Thread.currentThread().getName() + ")");
        }

        // ✅ Réentrance : si ce thread détient déjà ce verrou (holdCount>1
        // après tryLock), ne pas écraser l'horodatage d'acquisition d'origine
        // — sinon "depuis Xms" redémarrerait à chaque appel imbriqué.
        if (lock.getHoldCount() == 1) {
            HOLDERS.put(node, new HolderInfo(Thread.currentThread().getName(), System.currentTimeMillis()));
        }
        return lock;
    }

    public static void release(ReentrantLock lock) {
        if (lock != null && lock.isHeldByCurrentThread()) {
            // holdCount==1 avant unlock() → ce unlock() libère vraiment le
            // verrou (pas une simple sortie de ré-entrance) — on peut donc
            // retirer le holder associé sans risque d'écraser un autre node
            // tenu par ce même thread.
            if (lock.getHoldCount() == 1) {
                for (java.util.Map.Entry<Integer, ReentrantLock> e : LOCKS.entrySet()) {
                    if (e.getValue() == lock) {
                        HOLDERS.remove(e.getKey());
                        break;
                    }
                }
            }
            lock.unlock();
        }
    }
}
