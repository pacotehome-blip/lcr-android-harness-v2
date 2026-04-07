
package com.pa.lcr.lcp;

public final class ApiException extends RuntimeException {

    private final ApiResult result;

    private ApiException(ApiResult result) {
        super(result.msg);
        this.result = result;
    }

    public ApiResult toApiResult() {
        return result;
    }

    public static ApiException noMedia(String detail) {
        return new ApiException(
            ApiResult.failLevel(
                "No media available",
                "NO_MEDIA_READY",
                "MEDIA",
                "resolveMedia",
                detail
            )
        );
    }
}
