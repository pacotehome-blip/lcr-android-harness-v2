
package com.pa.lcr.lcp.util;

public interface LifecycleLogger {
    void info(String tag, String msg);
    void warn(String tag, String msg);
    void error(String tag, String msg);
}
