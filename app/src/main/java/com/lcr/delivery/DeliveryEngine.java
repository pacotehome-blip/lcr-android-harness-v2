
package com.lcr.delivery;

import com.lcr.client.LcrClient;
import com.lcr.client.MachineStatus;

public class DeliveryEngine {

    private final LcrClient client;

    public DeliveryEngine(LcrClient client) {
        this.client = client;
    }

    /* ------------------------------------------------------------
     * PRESTART (version simplifiée alignée Python V2)
     * ------------------------------------------------------------ */
    public void prestart(
            Integer product,
            Double preset,
            boolean recoverActive,
            int ticketTimeoutSeconds,
            boolean verbose
    ) throws Exception {

        if (verbose) System.out.println("=== PRE-START ===");

        MachineStatus ms = getMachineStatusSafe(verbose);

        if ((ms.deliveryCode & 0x0008) != 0 || (ms.deliveryCode & 0x0004) != 0) {
            if (recoverActive) {
                if (verbose) System.out.println("[PRE] ACTIVE delivery → command #2");
                client.opIssueCommand(0x02);
            } else {
                throw new RuntimeException("Delivery/flow active, recoverActive=false");
            }
        }

        ms = getMachineStatusSafe(verbose);

        if ((ms.deliveryCode & 0x0001) != 0) {
            if (verbose) System.out.println("[PRE] Ticket pending → trying #6");
            long t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < ticketTimeoutSeconds * 1000) {
                client.opIssueCommand(0x06);
                ms = getMachineStatusSafe(verbose);
                if ((ms.deliveryCode & 0x0001) == 0) {
                    if (verbose) System.out.println("[PRE] Ticket cleared");
                    break;
                }
                Thread.sleep(200);
            }
        }

        if (product != null) {
            if (verbose) System.out.println("[PRE] Selecting product: " + product);
            client.opSetField(0, new byte[]{(byte)(product - 1)});
        }

        // Preset (simplifié)
        if (preset == null || preset == 0.0) {
            if (verbose) System.out.println("[PRE] Open mode (no preset)");
            client.opSetField(5, i32(0));
            client.opSetField(6, i32(0));
        } else {
            if (verbose) System.out.println("[PRE] Setting preset=" + preset);
            int raw = (int)Math.round(preset * 100); // assume 2 digits
            client.opSetField(5, i32(raw));
            client.opSetField(6, i32(0));
        }
    }

    /* ------------------------------------------------------------
     * START DELIVERY
     * ------------------------------------------------------------ */
    public void start(boolean useCommand01, double timeoutSeconds, boolean verbose) throws Exception {

        if (verbose) System.out.println("=== START DELIVERY ===");

        if (useCommand01) {
            if (verbose) System.out.println("[START] Command #1");
            client.opIssueCommand(1);
        } else {
            if (verbose) System.out.println("[START] Command #0");
            client.opIssueCommand(0);
        }

        long t0 = System.currentTimeMillis();

        MachineStatus ms;
        while (true) {
            if ((System.currentTimeMillis() - t0) > timeoutSeconds * 1000)
                throw new RuntimeException("START timeout");

            ms = getMachineStatusSafe(verbose);

            if (ms.deliveryActive() || ms.beginDelivery() || ms.flowActive()) {
                if (verbose) System.out.println("[START] delivery started");
                break;
            }

            Thread.sleep(200);
        }
    }

    /* ------------------------------------------------------------
     * LIVE LOOP (version simplifiée)
     * ------------------------------------------------------------ */
    public void live(double poll, boolean verbose) throws Exception {

        if (verbose) System.out.println("=== LIVE LOOP ===");

        MachineStatus ms;

        while (true) {
            ms = getMachineStatusSafe(false);

            if (!ms.flowActive() && !ms.deliveryActive()) {
                if (verbose) System.out.println("[LIVE] delivery ended");
                break;
            }

            Thread.sleep((long)(poll * 1000));
        }
    }

    /* ------------------------------------------------------------
     * END DELIVERY
     * ------------------------------------------------------------ */
    public void endDelivery(boolean verbose) throws Exception {

        if (verbose) System.out.println("=== END DELIVERY ===");

        client.opIssueCommand(2);

        long t0 = System.currentTimeMillis();

        while (true) {
            if (System.currentTimeMillis() - t0 > 15000)
                throw new RuntimeException("END timeout");

            MachineStatus ms = getMachineStatusSafe(false);

            if (!ms.flowActive() && !ms.deliveryActive()) {
                if (verbose) System.out.println("[END] OK");
                break;
            }

            Thread.sleep(200);
        }
    }

    /* ------------------------------------------------------------
     * HELPERS
     * ------------------------------------------------------------ */
    private MachineStatus getMachineStatusSafe(boolean verbose) {
        try {
            byte[] rsp = client.sendRecv(new byte[]{0x23});
            byte[] p = client.extractPayload(rsp);

            if (p.length >= 8 && p[0] == 0x00) {
                int dev = u16(p, 2);
                int ds  = u16(p, 4);
                int dc  = u16(p, 6);
                if (verbose) System.out.println("[MS] " + new MachineStatus(dev, ds, dc));
                return new MachineStatus(dev, ds, dc);
            }
        } catch (Exception ignored) {}

        try {
            byte[] rsp2 = client.sendRecv(new byte[]{0x28});
            byte[] p2 = client.extractPayload(rsp2);

            if (p2.length >= 6 && p2[0] == 0x00) {
                int ds = u16(p2, 2);
                int dc = u16(p2, 4);
                if (verbose) System.out.println("[MS:FALLBACK] " + new MachineStatus(0, ds, dc));
                return new MachineStatus(0, ds, dc);
            }
        } catch (Exception ignored) {}

        return new MachineStatus(0, 0, 0);
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static byte[] i32(int v) {
        return new byte[]{
                (byte)((v >> 24) & 0xFF),
                (byte)((v >> 16) & 0xFF),
                (byte)((v >> 8) & 0xFF),
                (byte)(v & 0xFF)
        };
    }
}
