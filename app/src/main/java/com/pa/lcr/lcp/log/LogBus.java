
package com.pa.lcr.lcp.log;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Log global unique + vues filtrées.
 * Affichage standard: [SRC][N=xxx] message
 *
 * SRC = UI | IO | API | ERR
 */
public final class LogBus {

    public enum Source { UI, IO, API, ERR }

    public static final class Event {
        public final long tsMs;
        public final Source source;
        public final Integer node;   // null => inconnu / global
        public final String line;    // message brut

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

    private static final int MAX_EVENTS = 4000;
    private static final ArrayDeque<Event> ring = new ArrayDeque<>(MAX_EVENTS + 16);
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    // évite double préfixe si la ligne commence déjà par [UI] / [API] / [IO] / [ERR]
    private static final Pattern LEADING_TAG = Pattern.compile("^\\[(UI|API|IO|ERR)\\]\\s*");

    private LogBus() {}

    public static void addListener(Listener l) {
        if (l != null) listeners.addIfAbsent(l);
    }

    public static void removeListener(Listener l) {
        if (l != null) listeners.remove(l);
    }

    public static void append(Source src, Integer node, String line) {
        String clean = (line == null) ? "" : LEADING_TAG.matcher(line.trim()).replaceFirst("");
        final Event e = new Event(System.currentTimeMillis(), src, node, clean);

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

    public interface Filter { boolean accept(Event e); }

    public static String buildText(Filter f, int maxLines) {
        final ArrayList<Event> snap;
        synchronized (ring) { snap = new ArrayList<>(ring); }

        StringBuilder sb = new StringBuilder(64 * 1024);
        int n = 0;

        for (Event e : snap) {
            if (f != null && !f.accept(e)) continue;
            sb.append(formatPrefix(e)).append(e.line).append('\n');
            n++;
            if (maxLines > 0 && n >= maxLines) break;
        }
        return sb.toString();
    }

    private static String formatPrefix(Event e) {
        String node = (e.node != null) ? ("[N=" + e.node + "]") : "[N=?]";
        return "[" + e.source.name() + "]" + node + " ";
    }

    // =========================
    // Filtres prêts-à-l’emploi
    // =========================

    public static Filter filterNodeUIIOAPI(int node, boolean includeIO, long sinceMs) {
        return e -> {
            if (e == null) return false;
            if (e.tsMs < sinceMs) return false;
            if (e.node == null || e.node != node) return false;
            if (e.source == Source.IO) return includeIO;
            return true;
        };
    }

    public static Filter filterGlobalUIIOAPI(boolean includeIO, long sinceMs) {
        return e -> {
            if (e == null) return false;
            if (e.tsMs < sinceMs) return false;
            if (e.source == Source.IO) return includeIO;
            return true;
        };
    }
}
