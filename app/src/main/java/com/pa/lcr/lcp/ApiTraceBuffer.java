
package com.pa.lcr.lcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * API-Face: buffer circulaire pour tracer REQ/RESP et événements.
 *
 * - Thread-safe (synchronized sur les opérations de buffer)
 * - Listeners thread-safe (CopyOnWriteArrayList)
 * - Capacité fixe (ex: 500 lignes)
 */
public final class ApiTraceBuffer {

    public interface Listener {
        void onTraceChanged();
    }

    private final int capacity;
    private final ArrayList<String> buf;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    public ApiTraceBuffer(int capacity) {
        this.capacity = Math.max(50, capacity);
        this.buf = new ArrayList<>(this.capacity);
    }

    /**
     * Ajoute une ligne au buffer (ring buffer).
     */
    public void add(String line) {
        if (line == null) line = "";
        synchronized (this) {
            if (buf.size() >= capacity) {
                // drop oldest
                buf.remove(0);
            }
            buf.add(line);
        }
        // notifie sans garder le lock
        for (Listener l : listeners) {
            try { l.onTraceChanged(); } catch (Exception ignored) {}
        }
    }

    /**
     * Snapshot immuable des lignes.
     */
    public List<String> snapshot() {
        synchronized (this) {
            return Collections.unmodifiableList(new ArrayList<>(buf));
        }
    }

    /**
     * Efface le buffer.
     */
    public void clear() {
        synchronized (this) {
            buf.clear();
        }
        for (Listener l : listeners) {
            try { l.onTraceChanged(); } catch (Exception ignored) {}
        }
    }

    public void addListener(Listener l) {
        if (l != null) listeners.addIfAbsent(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }
}
