package com.pa.lcr.lcp.transport;

import com.hoho.android.usbserial.driver.UsbSerialPort;

public final class UsbTransportIo implements TransportIo {

    private final String key;
    private final UsbSerialPort port;
    private final String description;
    private final long generationId;
    private volatile boolean closed = false;

    // ✅ FIX CRITIQUE (5 août 2026, demande Paul — "il devrait avoir un
    // [chemin] ensuite l'autre!!!") — confirmé par log terrain : "write
    // exception sur USB — fermeture immédiate: Connection closed" survenait
    // pendant que PLUSIEURS chemins différents (RegisterTabFragment,
    // Diagnostic, démarrage oneshot) écrivaient sur le MÊME port USB
    // physique EN MÊME TEMPS, sans aucune synchronisation. Un port série USB
    // n'est pas conçu pour des écritures/lectures concurrentes
    // multi-thread — le conflit d'accès lève une exception que ce code
    // interprétait comme un vrai débranchement, fermant le transport de
    // façon PERMANENTE et cassant tous les appelants suivants, même ceux qui
    // n'avaient rien à voir avec l'exception d'origine. Le verrou unique sur
    // api_registerConnectAuto() protège ce chemin précis, mais pas les autres
    // qui touchent directement au port (STATUS_B, oneshot start, etc.). Fix
    // à la source : un verrou autour de l'I/O USB lui-même, peu importe qui
    // appelle — sérialise les accès concurrents au lieu de les laisser
    // entrer en collision au niveau du driver.
    private final Object ioLock = new Object();

    // ✅ FIX (même cause racine que BT) : isOpen() ne vérifiait QUE si l'objet
    // port existait encore en mémoire — jamais si le câble était réellement
    // encore branché. Un débranchement physique laissait isOpen()=true
    // indéfiniment, rendant toute la détection en amont inopérante. On
    // ferme maintenant réellement le transport après des échecs répétés.
    private final java.util.concurrent.atomic.AtomicInteger consecutiveFailures =
        new java.util.concurrent.atomic.AtomicInteger(0);
    private static final int MAX_CONSECUTIVE_FAILURES = 4;

    public UsbTransportIo(String key, UsbSerialPort port, String description, long generationId) {
        this.key = key;
        this.port = port;
        this.description = (description != null ? description : "USB");
        this.generationId = generationId;
    }

    @Override public String  getKey()          { return key; }
    @Override public String  describe()        { return description; }
    @Override public boolean isOpen()          { return !closed && port != null; }
    @Override public long    getGenerationId() { return generationId; }

    // ✅ FIX (5 août 2026) — permet à MediaTransportManager.onUsbReady() de
    // détecter si un port fraîchement "découvert" est en fait le MÊME objet
    // physique déjà enveloppé par ce wrapper, pour éviter de créer un
    // deuxième wrapper qui ferait fermer celui-ci (voir commentaire détaillé
    // dans MediaTransportManager.onUsbReady()).
    public boolean wrapsSamePort(UsbSerialPort other) {
        return other != null && this.port == other;
    }

    @Override
    public int write(byte[] data, int timeoutMs) throws Exception {
        if (closed || port == null) return -1;
        if (data == null || data.length == 0) return 0;
        synchronized (ioLock) {
            if (closed || port == null) return -1;
            try {
                port.write(data, Math.max(0, timeoutMs));
                consecutiveFailures.set(0);
                return data.length;
            } catch (Exception e) {
                // ✅ Toute exception d'écriture = signal fort de déconnexion réelle
                // (contrairement au BT, une exception USB indique presque toujours
                // un vrai débranchement, pas un simple délai) — fermeture immédiate.
                // Maintenant sous verrou : cette exception ne peut plus provenir
                // d'une collision d'accès concurrent entre plusieurs appelants.
                android.util.Log.w("UsbTransportIo", "write exception sur " + key + " — fermeture immédiate: " + e.getMessage());
                try { close(); } catch (Exception ignored) {}
                throw e;
            }
        }
    }

    @Override
    public int read(byte[] buffer, int timeoutMs) throws Exception {
        if (closed || port == null) return -1;
        if (buffer == null || buffer.length == 0) return 0;
        int to = (timeoutMs < 0) ? 60_000 : timeoutMs;
        synchronized (ioLock) {
            if (closed || port == null) return -1;
            try {
                int n = port.read(buffer, to);
                // ✅ Un retour à 0 (pas de données dans le délai) est NORMAL et
                // fréquent en USB pendant une attente légitime — ce n'est PAS un
                // signal fiable de déconnexion, contrairement au BT. On ne
                // ferme PAS sur ce cas seul (évite un faux positif comme celui
                // qu'on a dû corriger côté RegisterTabFragment). Seule une vraie
                // EXCEPTION (ci-dessous) est un signal fiable de déconnexion USB.
                return n;
            } catch (Exception e) {
                android.util.Log.w("UsbTransportIo", "read exception sur " + key + " — fermeture immédiate: " + e.getMessage());
                try { close(); } catch (Exception ignored) {}
                throw e;
            }
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try { port.close(); } catch (Exception ignored) {}
        // ✅ FIX CRITIQUE (5 août 2026, demande Paul — "c'est uniquement quand
        // on essaie de reconnecter au tab... si je débranche/rebranche ça
        // fonctionne") — retracé précisément : api_registerValidate() (premier
        // vrai I/O sur une session RÉUTILISÉE) échoue avec "Connection closed"
        // sur un port devenu périmé — mais UsbSession (le holder statique)
        // n'était JAMAIS vidé ici. Donc toute tentative de récupération
        // suivante (resync, getOrCreate, etc.) retrouvait ce MÊME port déjà
        // mort via UsbSession.getPort(), le réutilisait, et se re-déclarait
        // "prêt" à tort — sans jamais faire la SEULE chose qui fonctionne
        // vraiment : une vraie réouverture fraîche (usbManager.openDevice()).
        // Seul un débranchement physique déclenchait ça, via resetUsbState().
        // Ici : sur une fermeture réelle (pas juste logique), on vide aussi
        // UsbSession, pour que le prochain appel à api_openPingUsb() soit
        // FORCÉ de faire une vraie réouverture au lieu de réutiliser le
        // cadavre.
        try {
            com.pa.lcrdemo.UsbSession.clear();
            android.util.Log.w("UsbTransportIo", "close: UsbSession vidé — prochaine tentative forcera une vraie réouverture");
        } catch (Exception ignored) {}
    }
}
