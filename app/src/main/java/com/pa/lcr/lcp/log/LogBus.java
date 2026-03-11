
package com.pa.lcr.lcp.log;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Log principal unique (global) + vues filtrées (par node / source).
 *
 * - Stocke des lignes déjà formatées (ex: "[API] ...", "TX:", "RX:", "[UI 12:34:56.789] ...")
 * - Permet de reconstruire un texte filtré pour:
 *     - le log principal (global)
 *     - un tab registre (filtre node==X, UI+IO+API)
 *
 * NOTE: Le "clear" d'un tab ou du MAIN ne doit pas effacer le log global.
 *       On gère donc les "clear view" via un sinceMs (baseline) côté vue.
 */
public final class LogBus {

    public enum Source { UI, IO, API, ERR }

    public static final class Event {
        public final long tsMs;
        public final Source source;
        public final Integer node;   // null => global/non-routable (ou inconnu)
        public final String line;    // ligne déjà formatée

        public Event(long tsMs, Source source, Integer node, String line) {
            this.tsMs = tsMs;
            this.source = (source == null ? Source.UI : source);
            this.node = node;
            this.line = (line == null ? "" : line);
        }
    }

    public interface Listener {
        void onAppended(Event e);
    }

    // Ring buffer (évite croissance infinie)
    private static final int MAX_EVENTS = 4000;
    private static final ArrayDeque<Event> ring = new ArrayDeque<>(MAX_EVENTS + 16);

    // Listeners thread-safe
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    // Tous les listeners sont notifiés sur le thread UI
    private static final Handler main = new Handler(Looper.getMainLooper());

    private LogBus() {}

    public static void addListener(Listener l) {
        if (l != null) listeners.addIfAbsent(l);
    }

    public static void removeListener(Listener l) {
        if (l != null) listeners.remove(l);
    }

    /** Ajoute un événement au log global, puis notifie les listeners (sur le thread UI). */
    public static void append(Source src, Integer node, String line) {
        final Event e = new Event(System.currentTimeMillis(), src, node, line);

        synchronized (ring) {
            ring.addLast(e);
            while (ring.size() > MAX_EVENTS) ring.removeFirst();
        }

        main.post(() -> {
            for (Listener l : listeners) {
                try { l.onAppended(e); } catch (Exception ignored) {}
            }
        });
    }

    // Helpers
    public static void ui(Integer node, String line)  { append(Source.UI,  node, line); }
    public static void io(Integer node, String line)  { append(Source.IO,  node, line); }
    public static void api(Integer node, String line) { append(Source.API, node, line); }
    public static void err(Integer node, String line) { append(Source.ERR, node, line); }

    /** Filtre simple pour reconstruire une vue (global ou par node). */
    public interface Filter {
        boolean accept(Event e);
    }

    /**
     * Reconstruit un texte (multi-lignes) depuis le ring buffer.
     *
     * @param f filtre (peut être null => tout)
     * @param maxLines limite de lignes (0 ou négatif => illimité)
     */
    public static String buildText(Filter f, int maxLines) {
        final ArrayList<Event> snap;
        synchronized (ring) {
            snap = new ArrayList<>(ring);
        }

        StringBuilder sb = new StringBuilder(64 * 1024);
        int n = 0;

        for (Event e : snap) {
            if (f != null && !f.accept(e)) continue;
            sb.append(e.line).append('\n');
            n++;
            if (maxLines > 0 && n >= maxLines) break;
        }

        return sb.toString();
    }

    // =========================================================
    // Filtres prêts-à-l’emploi
    // =========================================================

    /**
     * Filtre: événements du node, incluant UI + API + ERR, et IO seulement si includeIO.
     * sinceMs sert à implémenter "clear view" local (ex: clear du tab courant).
     */
    public static Filter filterNodeUIIOAPI(int node, boolean includeIO, long sinceMs) {
        return e -> {
            if (e == null) return false;
            if (e.tsMs < sinceMs) return false;
            if (e.node == null || e.node != node) return false;

            // UI + API + ERR toujours inclus
            if (e.source == Source.IO) return includeIO;
            return true;
        };
    }

    /**
     * Filtre: global (tous nodes), incluant UI + API + ERR, et IO seulement si includeIO.
     * sinceMs sert à implémenter "clear view" local du log MAIN (sans toucher aux tabs).
     */
    public static Filter filterGlobalUIIOAPI(boolean includeIO, long sinceMs) {
        return e -> {
            if (e == null) return false;
            if (e.tsMs < sinceMs) return false;

            // UI + API + ERR toujours inclus
            if (e.source == Source.IO) return includeIO;
            return true;
        };
    }
}
