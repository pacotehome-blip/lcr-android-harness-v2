
package com.pa.lcr.lcp.log;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public final class LogBus {
    public enum Src {
        UI,
        API,
        IO_TX,
        IO_RX
    }
    public static final class LogEvent {
        public final long ts;
        public final int node;
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
    public static volatile boolean SHOW_UI = true;
    public static volatile boolean SHOW_API = true;
    public static volatile boolean SHOW_IO = false;
    public static volatile boolean SHOW_TS = false;
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
    private static final CopyOnWriteArrayList<Listener> LISTENERS =
            new CopyOnWriteArrayList<>();
    public static void addListener(Listener l) {
        if (l != null) LISTENERS.addIfAbsent(l);
    }
    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }
    // -------------------------
    // Emitters (corrigé: non-bloquant)
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
    public static void emit(int node, Src src, String msg) {
        if (msg == null) return;
        LogEvent e;
        synchronized (LogBus.class) {
            if (BUFFER.size() >= MAX_EVENTS) {
                BUFFER.removeFirst();
            }
            e = new LogEvent(
                    System.currentTimeMillis(),
                    node,
                    src,
                    msg
            );
            BUFFER.addLast(e);
        }
        // ✅ notifier hors du lock (CRITIQUE)
        for (Listener l : LISTENERS) {
            try { l.onLog(e); } catch (Exception ignored) {}
        }
    }
    // -------------------------
    // Snapshot (PAR NODE SEULEMENT)
    // -------------------------
    public static synchronized List<LogEvent> snapshotForNode(int node, int maxLines) {
        ArrayList<LogEvent> out = new ArrayList<>(Math.max(16, maxLines));
        Iterator<LogEvent> it = BUFFER.descendingIterator();
        while (it.hasNext() && out.size() < maxLines) {
            LogEvent e = it.next();
            if (e.node == node) {
                out.add(e);
            }
        }
        Collections.reverse(out);
        return out;
    }
    // -------------------------
    // ✅ Snapshot GLOBAL (pour MainActivity)
    // -------------------------
    /** Snapshot global des derniers événements (maxLines), sans filtre de temps. */
    public static synchronized List<LogEvent> snapshotGlobal(int maxLines) {
        ArrayList<LogEvent> out = new ArrayList<>(Math.max(16, maxLines));
        Iterator<LogEvent> it = BUFFER.descendingIterator();
        while (it.hasNext() && out.size() < maxLines) {
            out.add(it.next());
        }
        Collections.reverse(out);
        return out;
    }
    /** Snapshot global des derniers événements (maxLines) filtrés par timestamp >= sinceMs. */
    public static synchronized List<LogEvent> snapshotGlobal(int maxLines, long sinceMs) {
        ArrayList<LogEvent> out = new ArrayList<>(Math.max(16, maxLines));
        Iterator<LogEvent> it = BUFFER.descendingIterator();
        while (it.hasNext() && out.size() < maxLines) {
            LogEvent e = it.next();
            if (e.ts >= sinceMs) {
                out.add(e);
            }
        }
        Collections.reverse(out);
        return out;
    }
    // -------------------------
    // Build text UNIQUE (global + tab)
    // -------------------------
    public static String buildText(List<LogEvent> events) {
        StringBuilder sb = new StringBuilder(8192);
        SimpleDateFormat df = SHOW_TS
                ? new SimpleDateFormat("HH:mm:ss.SSS", Locale.CANADA_FRENCH)
                : null;
        for (LogEvent e : events) {
            if (e.src == Src.UI && !SHOW_UI) continue;
            if (e.src == Src.API && !SHOW_API) continue;
            if ((e.src == Src.IO_TX || e.src == Src.IO_RX) && !SHOW_IO) continue;
            if (SHOW_TS && df != null) {
                sb.append(df.format(new Date(e.ts))).append(" ");
            }
            // ✅ Node prefix (log général + tab)
            sb.append("[").append(e.node).append("] ");
            // ✅ FIX: éviter "TX TX:" / "RX RX:"
            String m = (e.msg == null) ? "" : e.msg;
            boolean msgAlreadyHasTx = m.startsWith("TX:");
            boolean msgAlreadyHasRx = m.startsWith("RX:");
            switch (e.src) {
                case IO_TX:
                    if (!msgAlreadyHasTx) sb.append("TX ");
                    break;
                case IO_RX:
                    if (!msgAlreadyHasRx) sb.append("RX ");
                    break;
                case API:
                    sb.append("API ");
                    break;
                default:
                    break;
            }
            sb.append(m).append('\n');
        }
        return sb.toString();
    }
    private LogBus() {}
}
