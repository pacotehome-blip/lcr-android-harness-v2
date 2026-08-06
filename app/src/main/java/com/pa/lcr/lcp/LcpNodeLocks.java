package com.pa.lcr.lcp;

import java.util.concurrent.ConcurrentHashMap;

/**
 * ✅ AJOUTÉ (6 août 2026, demande Paul — "j'ai eu le même résultat par BT et
 * USB... je n'avais pas la même instance sur DeliveryController, les erreurs
 * sont arrivées sur une seule connexion") — trouvé : le verrou LCP par node
 * dans DeliveryController protégeait bien les transactions ENTRE plusieurs
 * DeliveryController pour le même registre, mais RegisterSessionManager.
 * probeSerial() crée un LcpLink TEMPORAIRE et SÉPARÉ pendant la recherche/
 * reconnexion (resolveOrCreateForNode), qui ne passait par AUCUN verrou —
 * une vraie collision de protocole possible même avec une seule instance de
 * DeliveryController active, si une sonde de reconnexion tombe pendant que
 * le live polling est en cours sur le même transport physique. Ce verrou
 * partagé, indexé par node, est maintenant utilisé par les deux classes —
 * n'importe quelle transaction LCP vers un node donné, peu importe sa
 * source (DeliveryController ou une simple sonde), se sérialise
 * correctement contre toutes les autres.
 */
public final class LcpNodeLocks {
    private LcpNodeLocks() {}

    private static final ConcurrentHashMap<Integer, Object> LOCKS = new ConcurrentHashMap<>();

    public static Object forNode(int node) {
        return LOCKS.computeIfAbsent(node, k -> new Object());
    }
}