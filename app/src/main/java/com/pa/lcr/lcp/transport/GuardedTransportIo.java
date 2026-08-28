package com.pa.lcr.lcp.transport;

/**
 * GuardedTransportIo — B1 FSM minimaliste
 * - Le transport peut être ouvert (READY) mais un seul transport est ACTIVE à la fois.
 * - Toute lecture/écriture sur un transport non-ACTIVE échoue immédiatement.
 * - Toute exception IO sur ACTIVE marque le transport en ERROR et ferme le delegate.
 */
public final class GuardedTransportIo implements TransportIo {

    private final TransportHandle handle;
    private final TransportIo delegate;

    public GuardedTransportIo(TransportHandle handle, TransportIo delegate) {
        this.handle = handle;
        this.delegate = delegate;
    }

    @Override public String getKey() { return delegate.getKey(); }

    @Override public String describe() {
        String d = delegate.describe();
        try {
            if (MediaTransportManager.isKeyActive(getKey())) {
                return d + " [ACTIVE]";
            }
        } catch (Exception ignored) {}
        return d;
    }

    @Override public boolean isOpen() { return delegate != null && delegate.isOpen(); }
    @Override public long getGenerationId() { return delegate.getGenerationId(); }

    // ✅ AJOUTÉ (28 août 2026, demande Paul — validation latence BT) —
    // sans ce relais, l'appel passerait par le défaut de l'interface (-1)
    // même quand le delegate réel (BtSppTransportIo) calcule bien la
    // vraie valeur — GuardedTransportIo enveloppe presque toujours le
    // transport actif (LcpLink.io en est un), donc sans ce relais les
    // stats resteraient invisibles depuis DeliveryController.
    @Override public int getIoLatencyAvgMs() { return delegate.getIoLatencyAvgMs(); }
    @Override public int getIoSamples() { return delegate.getIoSamples(); }

    private void requireActive() throws Exception {
        if (!MediaTransportManager.isKeyActive(getKey())) {
            String active = MediaTransportManager.getActiveKeyStatic();
            throw new IllegalStateException("Transport not ACTIVE: key=" + getKey() + " active=" + active);
        }
    }

    @Override
    public int write(byte[] data, int timeoutMs) throws Exception {
        requireActive();
        try {
            return delegate.write(data, timeoutMs);
        } catch (IllegalStateException ise) {
            throw ise;
        } catch (Exception e) {
            try { handle.setError(handle.getDescription(), "IO write: " + e.getMessage()); } catch (Exception ignored) {}
            try { delegate.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    @Override
    public int read(byte[] buffer, int timeoutMs) throws Exception {
        requireActive();
        try {
            return delegate.read(buffer, timeoutMs);
        } catch (IllegalStateException ise) {
            throw ise;
        } catch (Exception e) {
            try { handle.setError(handle.getDescription(), "IO read: " + e.getMessage()); } catch (Exception ignored) {}
            try { delegate.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    @Override
    public void close() {
        try { delegate.close(); } catch (Exception ignored) {}
    }
}
