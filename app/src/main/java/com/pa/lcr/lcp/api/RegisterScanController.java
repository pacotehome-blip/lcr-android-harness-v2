
package com.pa.lcr.lcp.api;

import com.pa.lcr.lcp.ApiResult;
import com.pa.lcr.lcp.LcpLink;
import com.pa.lcr.lcp.discovery.DiscoveredRegisterStore;
import com.pa.lcr.lcp.transport.MediaTransportManager;
import com.pa.lcr.lcp.transport.TransportIo;
import com.pa.lcr.lcp.transport.TransportSnapshot;
import com.pa.lcr.lcp.transport.TransportStatus;

import java.util.List;

public final class RegisterScanController {

    private final MediaTransportManager mediaMgr;
    private final DiscoveredRegisterStore discovered;

    public RegisterScanController(
            MediaTransportManager mediaMgr,
            DiscoveredRegisterStore discovered
    ) {
        this.mediaMgr = mediaMgr;
        this.discovered = discovered;
    }

    public ApiResult scan() {

        if (mediaMgr == null) {
            return ApiResult.fail(
                    "SCAN_REGISTER: media manager null",
                    "ERR_MEDIA_MGR_NULL"
            );
        }

        List<TransportSnapshot> snaps = mediaMgr.listSnapshots();
        if (snaps == null) {
            return ApiResult.ok("SCAN_REGISTER_DONE", null);
        }

        for (TransportSnapshot snap : snaps) {

            if (snap == null) continue;
            if (snap.status != TransportStatus.READY) continue;

            TransportIo io = mediaMgr.getByKey(snap.key);
            if (io == null || !io.isOpen()) continue;

            String media =
                    snap.key != null && snap.key.startsWith("BT:")
                            ? "bt"
                            : "usb";

            for (int node = 1; node <= 250; node++) {

                String serial = probeSerial(io, node, 255);
                if (serial == null || serial.isEmpty()) continue;

                try {
                    discovered.upsert(serial, node, media, snap.key);
                } catch (Exception ignored) {
                }
            }
        }

        return ApiResult.ok("SCAN_REGISTER_DONE", null);
    }

    private String probeSerial(TransportIo io, int node, int from) {
        try {
            LcpLink link = new LcpLink(io, node, from, true);
            byte[] raw = link.opGetField(80, 400);
            return raw != null ? new String(raw).trim() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
