
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
 *
 * ✅ A1: erreurs par niveau (dans data)
 * - data.level : MEDIA | TRANSPORT | LCP | REGISTER | DELIVERY | UNKNOWN
 * - data.where : contexte court (ex: "api_connectLcp")
 * - data.detail: détail technique court (exception / rc / etc.)
 */
public final class ApiResult {

    public final int code;          // 1 OK, 0 FAIL
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
    // ✅ A1 helpers: Level tagging (non-breaking)
    // -------------------------
    public static ApiResult okLevel(String msg, String level, String where) {
        JSONObject d = new JSONObject();
        safePut(d, "level", level);
        safePut(d, "where", where);
        return ok(msg, d);
    }

    public static ApiResult okLevel(String msg, String level, String where, JSONObject data) {
        JSONObject d = (data == null) ? new JSONObject() : data;
        safePut(d, "level", level);
        safePut(d, "where", where);
        return ok(msg, d);
    }

    public static ApiResult failLevel(String msg, String err, String level, String where) {
        JSONObject d = new JSONObject();
        safePut(d, "level", level);
        safePut(d, "where", where);
        return fail(msg, err, d);
    }

    public static ApiResult failLevel(String msg, String err, String level, String where, String detail) {
        JSONObject d = new JSONObject();
        safePut(d, "level", level);
        safePut(d, "where", where);
        safePut(d, "detail", detail);
        return fail(msg, err, d);
    }

    public static ApiResult failLevel(String msg, String err, String level, String where, JSONObject data) {
        JSONObject d = (data == null) ? new JSONObject() : data;
        safePut(d, "level", level);
        safePut(d, "where", where);
        return fail(msg, err, d);
    }

    private static void safePut(JSONObject o, String k, Object v) {
        if (o == null) return;
        try { o.put(k, v == null ? JSONObject.NULL : v); } catch (Exception ignored) {}
    }

    // -------------------------
    // Serialization
    // -------------------------
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("code", code);
            o.put("msg", msg);
            // err: null -> JSON null
            o.put("err", err == null ? JSONObject.NULL : err);
            o.put("data", data == null ? new JSONObject() : data);
        } catch (Exception ignored) {
            // JSON minimal même si put() échoue
        }
        return o;
    }

    /** Helper pratique si ton serveur HTTP veut directement une string. */
    public String toJsonString() {
        return toJson().toString();
    }
}
