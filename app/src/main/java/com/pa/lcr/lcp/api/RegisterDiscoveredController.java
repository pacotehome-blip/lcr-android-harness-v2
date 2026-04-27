
package com.pa.lcr.lcp.api;

import com.pa.lcr.lcp.ApiResult;
import com.pa.lcr.lcp.discovery.DiscoveredRegister;
import com.pa.lcr.lcp.discovery.DiscoveredRegisterStore;
import org.json.JSONArray;
import org.json.JSONObject;

public final class RegisterDiscoveredController {

    private final DiscoveredRegisterStore store;

    public RegisterDiscoveredController(DiscoveredRegisterStore store) {
        this.store = store;
    }

    public ApiResult list() {
        JSONArray arr = new JSONArray();

        for (DiscoveredRegister r : store.all()) {
            JSONObject o = new JSONObject();
            o.put("serialId", r.serialId);
            o.put("lcrnode", r.lcrnode);
            o.put("media", r.media);
            o.put("transportKey", r.transportKey);
            o.put("firstSeen", r.firstSeenMs);
            o.put("lastSeen", r.lastSeenMs);
            o.put("configured", r.configured);
            arr.put(o);
        }

        return ApiResult.ok(
                "REGISTER_DISCOVERED",
                new JSONObject().put("registers", arr)
        );
    }
}