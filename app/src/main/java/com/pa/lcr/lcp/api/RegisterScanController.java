
package com.pa.lcr.lcp.api;

import com.pa.lcr.lcp.ApiResult;
import com.pa.lcr.lcp.LcpLink;
import com.pa.lcr.lcp.MultiRegisterApiFacadeImpl;
import com.pa.lcr.lcp.discovery.DiscoveredRegisterStore;
import com.pa.lcr.lcp.transport.MediaTransportManager;
import com.pa.lcr.lcp.transport.TransportIo;

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

    /**
     * SCAN REGISTRES
     * - Scan tous les médias READY (USB / BT)
     * - Scan nodes 1..250
     * - Lit #80 (serial)
     * - Chaque registre découvert :
     *   - est persisté
     *   - est connecté AUTOMATIQUEMENT
     *   - notifie l’UI via la façade (même logique que connect-auto)
     */
    public ApiResult scan() {

        if (mediaMgr == null) {
            return ApiResult.fail(
                    "SCAN_REGISTER: media manager null",
                    "ERR_MEDIA_MGR_NULL"
            );
        }

        for (String transportKey : mediaMgr.listKeys()) {

            TransportIo io = mediaMgr.getByKey(transportKey);
            if (io == null || !io.isOpen()) continue;

            String media =
                    transportKey != null && transportKey.startsWith("BT:")
                            ? "bt"
                            : "usb";

            for (int node = 1; node <= 250; node++) {

                String serial = probeSerial(io, node, 255);
                if (serial == null || serial.isEmpty()) continue;

                // 1️⃣ Persist discovery
                try {
                    discovered.upsert(serial, node, media, transportKey);
                } catch (Exception ignored) {
                }

                // 2️⃣ ✅ LOGIQUE OFFICIELLE — même chemin que connect-auto
                MultiRegisterApiFacadeImpl facade =
                        MultiRegisterApiFacadeImpl.getInstance();

                if (facade != null) {
                    facade.api_registerConnectAuto(serial, node);
                }
            }
        }

        return ApiResult.ok("SCAN_REGISTER_DONE", null);
    }

    /**
     * Lecture du numéro de série (#80)
     */
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
