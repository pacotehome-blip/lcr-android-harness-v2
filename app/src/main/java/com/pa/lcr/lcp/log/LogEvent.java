
package com.pa.lcr.lcp.log;

public final class LogEvent {
    public enum Source { UI, IO, API }

    public final long tsMs;
    public final Source source;
    public final Integer lcrnode;   // null => global/non-routable
    public final String message;

    public LogEvent(long tsMs, Source source, Integer lcrnode, String message) {
        this.tsMs = tsMs;
        this.source = source;
        this.lcrnode = lcrnode;
        this.message = (message == null) ? "" : message;
    }
}
