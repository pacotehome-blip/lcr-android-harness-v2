package com.pa.lcr.lcp.api;

import android.content.Context;
import android.net.wifi.WifiManager;

import com.pa.lcr.lcp.ApiResult;
import com.pa.lcr.lcp.storage.KnownTcpDeviceStore;
import com.pa.lcr.lcp.transport.MediaTransportManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Connexion TCP raw passthrough (ex: Moxa N-Port) — deux chemins :
 *
 *  1) connectManual(ip, port) : le chauffeur/technicien saisit l'IP:port directement
 *     (le N-Port a une IP fixe connue, ou affichée sur un écran de config réseau).
 *
 *  2) scanSubnet(port) : balaie le sous-réseau Wi-Fi courant de la tablette (/24,
 *     déduit de son IP locale) à la recherche de ports ouverts sur le port raw
 *     du N-Port (par défaut 4001 — port raw standard Moxa NPort).
 *
 * Dans les deux cas, le socket ouvert est simplement enregistré comme un
 * transport de plus dans MediaTransportManager (clé "TCP:ip:port") — c'est
 * ensuite le bouton "Scan registres" existant (RegisterScanController.scan(),
 * déjà générique à tous les transports READY) qui identifie les vrais nodes
 * LCR-II/LC3 dessus. Ce fichier ne fait QUE l'ouverture réseau, jamais de LCP.
 *
 * ✅ Compatibilité Android 9-15 (API 28-35) :
 * Détection à l'exécution via Build.VERSION.SDK_INT — voir getLocalWifiIp() qui
 * bascule entre WifiManager (API<31) et ConnectivityManager (API>=31, chemin
 * recommandé sur Android 12+). Aucune autre méthode de ce fichier n'est
 * version-dépendante (java.net.Socket est stable sur toute la plage 26-35).
 */
public final class WifiRegisterScanController {

    /** Port raw TCP par défaut d'un Moxa N-Port en mode "TCP Server / raw data". */
    public static final int DEFAULT_RAW_PORT = 4001;

    // ✅ 250ms était trop agressif en conditions réelles (Wi-Fi camion, hôtes
    // qui ne répondent pas par un RST immédiat mais un silence — nécessite
    // d'attendre le plein timeout pour conclure "fermé"). Porté à 600ms/hôte.
    private static final int CONNECT_TIMEOUT_MS = 600;    // par hôte, pendant le scan subnet
    private static final int MANUAL_CONNECT_TIMEOUT_MS = 3000;
    private static final int SCAN_THREAD_POOL = 32;
    private static final int SCAN_TOTAL_BUDGET_SEC = 45;  // marge large (254 hôtes / 32 threads / 600ms ≈ 5-6s réel)

    private final Context appCtx;
    private final MediaTransportManager mediaMgr;
    private final KnownTcpDeviceStore knownStore;

    public WifiRegisterScanController(Context ctx, MediaTransportManager mediaMgr) {
        this.appCtx = ctx.getApplicationContext();
        this.mediaMgr = mediaMgr;
        this.knownStore = new KnownTcpDeviceStore(this.appCtx);
    }

    public KnownTcpDeviceStore getKnownStore() { return knownStore; }

    /** Sous-réseau Wi-Fi détecté, format "xxx.xxx.xxx.0" — ou null si indisponible. */
    public String detectSubnet() {
        String localIp = getLocalWifiIp();
        if (localIp == null) return null;
        String base = subnetBase24(localIp);
        return (base != null) ? (base + ".0") : null;
    }

    // =========================================================
    // 1) Connexion manuelle
    // =========================================================

    public ApiResult connectManual(String ip, int port) {
        if (ip == null || ip.trim().isEmpty()) {
            return ApiResult.fail("TCP_CONNECT: 0 - IP vide.", "ERR_TCP_IP_EMPTY");
        }
        ip = ip.trim();

        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(ip, port), MANUAL_CONNECT_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);

            mediaMgr.onTcpConnected(
                    socket,
                    socket.getInputStream(),
                    socket.getOutputStream(),
                    ip, port,
                    "TCP manuel " + ip + ":" + port
            );

            // ✅ Mémorisation — équivalent "appairage" BT pour un N-Port
            try { knownStore.upsertSeen(ip, port, "N-Port " + ip, null, null); } catch (Exception ignored) {}

            JSONObject data = new JSONObject();
            data.put("ip", ip);
            data.put("port", port);
            data.put("key", MediaTransportManager.tcpKey(ip, port));
            return ApiResult.ok("TCP_CONNECT: 1 - connecté " + ip + ":" + port, data);

        } catch (Exception e) {
            try { mediaMgr.onTcpError(ip, port, e.getMessage()); } catch (Exception ignored) {}
            return ApiResult.fail(
                    "TCP_CONNECT: 0 - échec " + ip + ":" + port + " (" + e.getMessage() + ")",
                    "ERR_TCP_CONNECT_FAILED"
            );
        }
    }

    // =========================================================
    // 2) Scan du sous-réseau Wi-Fi courant
    // =========================================================

    public ApiResult scanSubnet(final int port) {
        String localIp = getLocalWifiIp();
        if (localIp == null) {
            return ApiResult.fail(
                    "TCP_SCAN: 0 - IP Wi-Fi locale introuvable (Wi-Fi désactivé ? ou tablette hors Wi-Fi).",
                    "ERR_TCP_NO_WIFI"
            );
        }

        String subnetBase = subnetBase24(localIp);
        if (subnetBase == null) {
            return ApiResult.fail("TCP_SCAN: 0 - sous-réseau introuvable (IP locale: " + localIp + ").", "ERR_TCP_NO_SUBNET");
        }

        final List<String> found = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(SCAN_THREAD_POOL);
        CountDownLatch latch = new CountDownLatch(254);

        for (int host = 1; host <= 254; host++) {
            final String ip = subnetBase + "." + host;
            pool.submit(() -> {
                try {
                    Socket probe = new Socket();
                    probe.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);
                    // Ouvert : on garde CE socket comme transport réel (pas de reconnexion double)
                    synchronized (found) { found.add(ip); }
                    mediaMgr.onTcpConnected(
                            probe,
                            probe.getInputStream(),
                            probe.getOutputStream(),
                            ip, port,
                            "TCP scan " + ip + ":" + port
                    );
                    // ✅ Mémorisation automatique de tout hôte trouvé par le scan
                    try { knownStore.upsertSeen(ip, port, "N-Port " + ip, null, null); } catch (Exception ignored) {}
                } catch (Exception ignored) {
                    // hôte absent / port fermé / timeout — normal pour la grande majorité des 254 IP
                } finally {
                    latch.countDown();
                }
            });
        }

        try { latch.await(SCAN_TOTAL_BUDGET_SEC, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        pool.shutdownNow();


        JSONArray arr = new JSONArray();
        for (String ip : found) arr.put(ip);

        JSONObject data = new JSONObject();
        try {
            data.put("localIp", localIp);
            data.put("subnet", subnetBase + ".0/24");
            data.put("port", port);
            data.put("found", arr);
            data.put("count", found.size());
        } catch (Exception ignored) {}

        return ApiResult.ok(
                "TCP_SCAN_DONE (tablette=" + localIp + "): " + found.size() + " hôte(s) avec le port " + port + " ouvert sur " + subnetBase + ".0/24",
                data
        );
    }

    // =========================================================
    // Helpers réseau
    // =========================================================

    /**
     * IP locale de la tablette sur le Wi-Fi courant (format "a.b.c.d"), ou null.
     *
     * ✅ Compatibilité API 28-35 (Android 9 à 15) :
     * - API < 31 (Android 9, SM-T397U) : WifiManager.getConnectionInfo().getIpAddress()
     *   est l'API standard et pleinement supportée sur ces versions.
     * - API >= 31 (Android 12+, ex: R52X508K2DR sous Android 15) : cette même méthode
     *   est dépréciée par Google (remplacée par ConnectivityManager.getLinkProperties()).
     *   Elle reste fonctionnelle jusqu'à Android 15 mais on bascule sur le chemin
     *   recommandé pour éviter une suppression future du SDK.
     */
    private String getLocalWifiIp() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                // Android 12+ (API 31+) : chemin recommandé via ConnectivityManager
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                        appCtx.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm == null) return null;
                android.net.Network net = cm.getActiveNetwork();
                if (net == null) return null;
                android.net.LinkProperties lp = cm.getLinkProperties(net);
                if (lp == null) return null;
                for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                    java.net.InetAddress addr = la.getAddress();
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
                return null;
            } else {
                // Android 9-11 (API 28-30) : WifiManager classique, pleinement supporté
                WifiManager wm = (WifiManager) appCtx.getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wm == null) return null;
                int ipInt = wm.getConnectionInfo().getIpAddress();
                if (ipInt == 0) return null;
                return String.format(java.util.Locale.ROOT, "%d.%d.%d.%d",
                        (ipInt & 0xff), (ipInt >> 8 & 0xff), (ipInt >> 16 & 0xff), (ipInt >> 24 & 0xff));
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** "192.168.1.42" -> "192.168.1" */
    private String subnetBase24(String ip) {
        if (ip == null) return null;
        int lastDot = ip.lastIndexOf('.');
        if (lastDot <= 0) return null;
        return ip.substring(0, lastDot);
    }
}