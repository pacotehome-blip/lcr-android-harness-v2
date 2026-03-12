
package com.pa.lcr.lcp.log;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * LogBus central - source unique (UI/API/IO_TX/IO_RX) + compatibilité legacy:
 * - err()/io() + filterNodeUIIOAPI/filterGlobalUIIOAPI + buildText(Filter,maxLines)
 *
 * Ton modèle "flags globaux + snapshot + buildText(List)" est conservé. [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LogBus.java)
 */
public final class LogBus {

    public enum Src {
        UI,
        API,
        IO_TX,
        IO_RX
    }

    public static final class LogEvent {
        public final long ts;
        public final int node;      // non-null
        public final Src src;
        public final String msg;

        LogEvent(long ts, int node, Src src, String msg) {
            this.ts = ts;
            this.node = node;
            this.src = src;
            this.msg = msg;
        }
    }

    // -------------------------
    // Global flags (SOURCE UNIQUE)
    // -------------------------
    public static volatile boolean SHOW_UI  = true;
    public static volatile boolean SHOW_API = true;
    public static volatile boolean SHOW_IO  = false;
    public static volatile boolean SHOW_TS  = false;

    // -------------------------
    // Buffer circulaire
    // -------------------------
    private static final int MAX_EVENTS = 5000;
    private static final Deque<LogEvent> BUFFER = new ArrayDeque<>(MAX_EVENTS);

    // -------------------------
    // UI listeners
    // -------------------------
    public interface Listener {
        void onLog(LogEvent e);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    public static void addListener(Listener l) {
        if (l != null) LISTENERS.addIfAbsent(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    // -------------------------
    // Emitters (modern)
    // -------------------------
    public static void ui(int node, String msg) {
        emit(node, Src.UI, msg);
    }

    public static void api(int node, String msg) {
        emit(node, Src.API, msg);
    }

    public static void ioTx(int node, String msg) {
        emit(node, Src.IO_TX, msg);
    }

    public static void ioRx(int node, String msg) {
        emit(node, Src.IO_RX, msg);
    }

    /**
     * Legacy: io(node,msg) (utilisé par certains sinks).
     * On le route comme "UI" avec un préfixe explicite.
     */
    public static void io(int node, String msg) {
        if (msg == null) return;
        ui(node, "[IO] " + msg);
    }

    /**
     * Legacy: err(node,msg) (utilisé par tab + session manager + main). [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LogBus.java)[2](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/MainActivity.java)
     * On le route comme "API" avec un préfixe [ERR] (visible même si SHOW_UI off).
     */
    public static void err(int node, String msg) {
        if (msg == null) return;
        api(node, "[ERR] " + msg);
    }

    // Overloads legacy (Integer)
    public static void err(Integer node, String msg) {
        err(node != null ? node : 0, msg);
    }

    public static void api(Integer node, String msg) {
        api(node != null ? node : 0, msg);
    }

    public static void ui(Integer node, String msg) {
        ui(node != null ? node : 0, msg);
    }

    public static synchronized void emit(int node, Src src, String msg) {
        if (msg == null) return;

        if (BUFFER.size() >= MAX_EVENTS) {
            BUFFER.removeFirst();
        }

        LogEvent e = new LogEvent(System.currentTimeMillis(), node, src, msg);
        BUFFER.addLast(e);

        for (Listener l : LISTENERS) {
            try { l.onLog(e); } catch (Exception ignored) {}
        }
    }

    // -------------------------
    // Snapshot (PAR NODE SEULEMENT) - modern
    // -------------------------
    public static synchronized List<LogEvent> snapshotForNode(int node, int maxLines) {
        ArrayList<LogEvent> out = new ArrayList<>(Math.max(16, maxLines));
        Iterator<LogEvent> it = BUFFER.descendingIterator();
        while (it.hasNext() && out.size() < maxLines) {
            LogEvent e = it.next();
            if (e.node == node) out.add(e);
        }
        Collections.reverse(out);
        return out;
    }

    public static synchronized List<LogEvent> snapshotGlobal(int maxLines) {
        ArrayList<LogEvent> out = new ArrayList<>(Math.max(16, maxLines));
        Iterator<LogEvent> it = BUFFER.descendingIterator();
        while (it.hasNext() && out.size() < maxLines) out.add(it.next());
        Collections.reverse(out);
        return out;
    }

    // -------------------------
    // Build text UNIQUE (global + tab) - modern
    // -------------------------
    public static String buildText(List<LogEvent> events) {
        StringBuilder sb = new StringBuilder(8192);
        SimpleDateFormat df = SHOW_TS ? new SimpleDateFormat("HH:mm:ss.SSS", Locale.CANADA_FRENCH) : null;

        for (LogEvent e : events) {

            if (e.src == Src.UI && !SHOW_UI) continue;
            if (e.src == Src.API && !SHOW_API) continue;
            if ((e.src == Src.IO_TX || e.src == Src.IO_RX) && !SHOW_IO) continue;

            // Format standard compatible avec ton affichage [SRC][N=...]
            // (On garde msg tel quel; ton LogBus précédent préfixait déjà souvent les messages.)
            if (SHOW_TS && df != null) {
                sb.append(df.format(new Date(e.ts))).append(" ");
            }

            sb.append("[");
            switch (e.src) {
                case UI:    sb.append("UI"); break;
                case API:   sb.append("API"); break;
                case IO_TX: sb.append("IO"); break;
                case IO_RX: sb.append("IO"); break;
            }
            sb.append("][N=").append(e.node).append("] ");

            if (e.src == Src.IO_TX) sb.append("TX ");
            if (e.src == Src.IO_RX) sb.append("RX ");

            sb.append(e.msg).append('\n');
        }

        return sb.toString();
    }

    // =====================================================================
    // Legacy Filter API (pour MainActivity et anciens appels)
    // =====================================================================

    public interface Filter {
        boolean accept(LogEvent e);
    }

    public static Filter filterNodeUIIOAPI(final int node, final boolean includeIO, final long sinceMs) {
        return e -> {
            if (e == null) return false;
            if (e.ts < sinceMs) return false;
            if (e.node != node) return false;
            if ((e.src == Src.IO_TX || e.src == Src.IO_RX) && !includeIO) return false;
            return true;
        };
    }

    public static Filter filterGlobalUIIOAPI(final boolean includeIO, final long sinceMs) {
        return e -> {
            if (e == null) return false;
            if (e.ts < sinceMs) return false;
            if ((e.src == Src.IO_TX || e.src == Src.IO_RX) && !includeIO) return false;
            return true;
        };
    }

    /**
     * Legacy buildText(Filter,maxLines) :
     * - prend un snapshot global limité à maxLines*2 (petit cushion)
     * - applique le filter
     * - applique buildText(list) (flags SHOW_*)
     */
    public static String buildText(Filter f, int maxLines) {
        if (maxLines <= 0) maxLines = 1000;
        List<LogEvent> snap = snapshotGlobal(Math.min(MAX_EVENTS, maxLines * 2));

        ArrayList<LogEvent> out = new ArrayList<>(maxLines);
        for (LogEvent e : snap) {
            if (f == null || f.accept(e)) {
                out.add(e);
                if (out.size() >= maxLines) break;
            }
        }
        return buildText(out);
    }

    private LogBus() {}
}
