
package com.pa.lcr.lcp.ui.delivery;

import android.content.Context;

import com.pa.lcr.lcp.storage.DeliveryLogStore;

/**
 * Optional UI-side delivery controller traceability.
 * Use it if UI LIVE/END workflow is separate from API DeliveryController.
 */
public class UiDeliveryController {

    private final DeliveryLogStore store;

    // UI attempt scope (one UI delivery session)
    private long attemptId = -1;
    private String serialId = "";
    private String ticketNo = "";
    private String saleNo = "";

    public UiDeliveryController(Context context) {
        this.store = new DeliveryLogStore(context);
        this.store.purgeOlderThanDaysAsync(7);
    }

    /**
     * Call when UI begins a delivery attempt (e.g. after connect + armed/ready).
     */
    public void beginUiAttempt(String serialId, String ticketNo, String saleNo) {
        this.serialId = serialId != null ? serialId : "";
        this.ticketNo = ticketNo != null ? ticketNo : "";
        this.saleNo = saleNo != null ? saleNo : "";

        // create / refresh summary first
        store.upsertSummaryAsync(this.serialId, this.ticketNo, this.saleNo,
                "PENDING", DeliveryLogStore.SOURCE_UI, null,
                null, null);

        // open attempt
        store.openAttemptAsync(this.serialId, this.ticketNo, DeliveryLogStore.SOURCE_UI, null, id -> {
            attemptId = id;
            store.addEventAsync(attemptId, DeliveryLogStore.LEVEL_INFO, "UI_BEGIN",
                    "UI attempt started", null);
        });
    }

    /**
     * Call for UI significant events (connect, armed, continue pressed, flow state, etc.)
     */
    public void event(String type, String message, String dataJson) {
        if (attemptId <= 0) return;
        store.addEventAsync(attemptId, DeliveryLogStore.LEVEL_INFO, type, message, dataJson);
    }

    /**
     * Call when UI detects DONE according to your LIVE/END rules.
     * resultJson must contain the same RESULT structure as API.
     */
    public void done(String resultJson) {
        if (attemptId <= 0) return;

        store.addEventAsync(attemptId, DeliveryLogStore.LEVEL_INFO, "DONE",
                "UI detected DONE", resultJson);

        store.upsertSummaryAsync(serialId, ticketNo, saleNo,
                "DONE", DeliveryLogStore.SOURCE_UI, null,
                resultJson, null);

        store.closeAttemptAsync(attemptId, "DONE", resultJson, null);
        attemptId = -1;
    }

    /**
     * Call when UI hits an error.
     */
    public void error(String errorJson) {
        if (attemptId <= 0) return;

        store.addEventAsync(attemptId, DeliveryLogStore.LEVEL_ERROR, "ERROR",
                "UI error", errorJson);

        store.upsertSummaryAsync(serialId, ticketNo, saleNo,
                "ERROR", DeliveryLogStore.SOURCE_UI, null,
                null, errorJson);

        store.closeAttemptAsync(attemptId, "ERROR", null, errorJson);
        attemptId = -1;
    }
}
