
package com.pa.lcr.lcp;

import org.json.JSONObject;

/**
 * API-Face: réponse standard (0/1 + msg + err + data)
 *
 * Convention:
 * - code: 1 = OK, 0 = FAIL
 * - msg : texte terrain (ex: "Scan USB: 0 - ...")
 * - err : code court si FAIL (nullable)
 * - data: détails optionnels JSON (jamais null)
 */
public final class ApiResult {
    public final int code;           // 1 OK, 0 FAIL
    public final String msg;         // message terrain
    public final String err;         // code court (nullable)
    public final JSONObject data;    // détails optionnels

    private ApiResult(int code, String msg, String err, JSONObject data) {
        this.code = code;
        this.msg = (msg == null) ? "" : msg;
        this.err = err;
        this.data = (data == null) ? new JSONObject() : data;
    }

    // -------------------------
    // Factory helpers
    // -------------------------

    public static ApiResult ok(String msg) {
        return new ApiResult(1, msg, null, new JSONObject());
    }

    public static ApiResult ok(String msg, JSONObject data) {
        return new ApiResult(1, msg, null, data);
    }

    public static ApiResult fail(String msg, String err) {
        return new ApiResult(0, msg, err, new JSONObject());
    }

    public static ApiResult fail(String msg, String err, JSONObject data) {
        return new ApiResult(0, msg, err, data);
    }

    // -------------------------
    // Serialization
    // -------------------------

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("code", code);
            o.put("msg", msg);
            // err: null -> JSON null (plus propre côté consumer)
            o.put("err", err == null ? JSONObject.NULL : err);
            o.put("data", data == null ? new JSONObject() : data);
        } catch (Exception ignored) {
            // on garde un JSON minimal même si put() échoue (très rare)
        }
        return o;
    }

    /**
     * Helper pratique si ton serveur HTTP veut directement une string.
     */
    public String toJsonString() {
        return toJson().toString();
    }
}
