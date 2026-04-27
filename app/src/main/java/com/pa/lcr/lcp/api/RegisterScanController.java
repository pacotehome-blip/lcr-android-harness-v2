
package com.pa.lcr.lcp.api;

public final class RegisterScanController {import com.pa.lcr.lcp.LcpLink;

    private final MediaTransportManager mediaMgr;
    private final DiscoveredRegisterStore discovered;

    public RegisterScanController(MediaTransportManager mediaMgr,
                                  DiscoveredRegisterStore discovered) {
        this.mediaMgr = mediaMgr;
        this.discovered = discovered;
    }

    /**
     * SCAN REGISTER
     * - Scan lcrnode 1..250
     * - Lire #80 (numéro de série)
     * - AUCUNE connexion
     */
    public ApiResult scan() {

        for (String transportKey : mediaMgr.listKeys()) {

            TransportIo io = mediaMgr.getByKey(transportKey);
            if (io == null || !io.isOpen()) continue;

            String media =
                    transportKey.startsWith("BT:") ? "bt" : "usb";

            for (int node = 1; node <= 250; node++) {

                String serial = probeSerial(io, node, 255);
                if (serial == null || serial.isEmpty()) continue;

                discovered.upsert(
                        serial,
                        node,
                        media,
                        transportKey
                );
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
``
import com.pa.lcr.lcp.ApiResult;
import com.pa.lcr.lcp.discovery.DiscoveredRegisterStore;
import com.pa.lcr.lcp.transport.MediaTransportManager;
import com.pa.lcr.lcp.transport.TransportIo;