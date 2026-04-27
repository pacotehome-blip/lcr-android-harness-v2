
package com.pa.lcr.lcp.discovery;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DiscoveredRegisterStore {

    private final Map<String, DiscoveredRegister> bySerial =
            new ConcurrentHashMap<>();

    public void upsert(String serialId,
                       int lcrnode,
                       String media,
                       String transportKey) {

        bySerial.compute(serialId, (k, existing) -> {
            if (existing == null) {
                return new DiscoveredRegister(
                        serialId,
                        lcrnode,
                        media,
                        transportKey
                );
            }
            existing.touch();
            return existing;
        });
    }

    public DiscoveredRegister get(String serialId) {
        return bySerial.get(serialId);
    }

    public Collection<DiscoveredRegister> all() {
        return bySerial.values();
    }
}
