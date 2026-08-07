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

    private static final ConcurrentHashMap<Integer, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    /** Delai maximum d'attente avant d'abandonner plutot que de bloquer indefiniment. */
    public static final long LOCK_TIMEOUT_MS = 15000;

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
        if (lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) {
            return lock;
        }
        return null;
    }

    public static void release(ReentrantLock lock) {
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
