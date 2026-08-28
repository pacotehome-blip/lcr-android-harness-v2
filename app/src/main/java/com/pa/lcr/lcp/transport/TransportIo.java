
package com.pa.lcr.lcp.transport;

public interface TransportIo {

    /** Identifiant stable: "USB" ou "BT:AA:BB:CC:DD:EE:FF" */
    String getKey();

    /** Info humaine (UI/logs) */
    String describe();

    /** La session est-elle ouverte/prête à lire/écrire ? */
    boolean isOpen();

    /** Génération du transport (change à chaque reconnect) */
    long getGenerationId();

    /** Write: retourne nb d'octets écrits, ou -1 si erreur */
    int write(byte[] data, int timeoutMs) throws Exception;

    /**
     * Read: retourne nb d'octets lus, 0 si timeout, ou -1 si EOF/closed
     * timeoutMs: 0 => non-bloquant (poll), <0 => bloquant, >0 => timeout.
     */
    int read(byte[] buffer, int timeoutMs) throws Exception;

    /** Ferme le transport */
    void close();

    // ✅ AJOUTÉ (28 août 2026, demande Paul — "on doit comprendre ce qui
    // arrive" — validation de l'hypothèse latence pile BT Android vs un
    // script PC à ~40ms) — latence moyenne réelle mesurée d'un read()
    // (envoi -> octets effectivement reçus), en ms. Défaut -1 (non
    // supporté) pour USB/TCP qui ne trackent pas ça — seul BtSppTransportIo
    // le calcule réellement (ioLatencySum/ioSamples déjà existants, jamais
    // exposés jusqu'ici).
    default int getIoLatencyAvgMs() { return -1; }
    default int getIoSamples() { return -1; }
}
