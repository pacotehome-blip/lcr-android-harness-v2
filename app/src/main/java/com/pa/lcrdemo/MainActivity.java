
package com.pa.lcrdemo;

import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import android.app.PendingIntent;
import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.Uri;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import android.view.MotionEvent;
import android.view.View;
import android.widget.*;

import com.google.android.material.tabs.TabLayout;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import com.pa.lcr.lcp.ApiFacade;
import com.pa.lcr.lcp.ApiServer;
import com.pa.lcr.lcp.LcpLink;
import com.pa.lcr.lcp.MultiRegisterApiFacadeImpl;
import com.pa.lcr.lcp.RegisterSessionManager;
import com.pa.lcr.lcp.log.LogBus;
import com.pa.lcr.lcp.storage.DeliveryDb;
import com.pa.lcr.lcp.storage.DeliveryLogStore;

// ✅ Option A: runtime transport manager
import com.pa.lcr.lcp.transport.MediaTransportManager;


import com.pa.lcr.lcp.transport.TransportIo;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MAIN (clean UI) = infrastructure:
 * - Scan USB + Ouvrir/Ping USB => UNE session UsbSession
 * - TO/FROM + Ajouter/Focus TAB
 * - Scan registres (autoritaire) => reset tabs + rebuild
 * - Tabs registres (RegisterTabFragment)
 * - Log global MAIN (LogBus)
 *
 * API-Face => Start/Stop + Backup DB
 */
public class MainActivity extends AppCompatActivity {

    public static final String ACTION_USB_PERMISSION = "com.pa.lcrdemo.USB_PERMISSION";

    private UsbManager usbManager;
    private final List<UsbDevice> usbDevices = new ArrayList<>();
    private UsbSerialPort usbPort; // cache local (la vérité = UsbSession.getPort())

    // ===== Tabs / Pages (TOP: MAIN / API-Face / CONFIGURE) =====
    private TabLayout tabLayout;
    private View pageMain;
    private View pageApiFace;
    private View pageConfigure;

    // ===== CONFIGURE UI (status + BT) =====
    private TextView txtMediaActive;
    private TextView txtNodesActive;
    private Spinner spnBtBonded;
    private Button btnBtRefresh;
    private Button btnBtConnect;
    private Button btnBtDisconnect;
    private TextView txtBtStatus;

// ===== CONFIGURE: Scan registres (par média) =====
private Button btnScanUsbRegs;
private TextView txtUsbRegsFound;
private Button btnScanBtRegs;
private TextView txtBtRegsFound;
private Button btnScanWifiRegs;
private TextView txtWifiRegsFound;
    // ===== BT runtime (paired-only) =====
    private static final int REQ_ENABLE_BT = 9103;
    
 // ✅ Android 9 (API 28) : permission Storage legacy pour écrire dans /Download
 private static final int REQ_STORAGE_LEGACY = 9104;
private final ExecutorService btExec = Executors.newSingleThreadExecutor();
    private BluetoothAdapter btAdapter;
    private final List<BluetoothDevice> btBonded = new ArrayList<>();
    private ArrayAdapter<String> btAdapterUi;
    private BluetoothSocket btSocket;
    private InputStream btIn;
    private OutputStream btOut;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // ===== Media profile store =====
    private com.pa.lcr.lcp.storage.MediaProfileStore mediaProfileStore;

    // ✅ Option A: runtime transport manager + dernier MAC connecté
    private MediaTransportManager mediaTransportManager;
    private String lastBtMac = null;

    // ===== API-Face UI =====
    private TextView txtApiStatus;
    private TextView txtApiUrl;
    private Button btnApiStart;
    private Button btnApiStop;
    private Button btnDbBackup;

    // ===== API runtime =====
    private static final int API_PORT = 8765;
    private ApiServer apiServer;
    private DeliveryLogStore deliveryStore;

    // ===================== MAIN UI (USB + Scan) =====================
    private Spinner spnUsbDevices;
    private Button btnScanUsb;
    private Button btnPingUsb;
    private TextView txtActiveNode;
    // ===================== Scan registres (exec) =====================
    private final ExecutorService scanExec = Executors.newSingleThreadExecutor();
 private final List<NodeScanItem> nodeItems = new ArrayList<>();
    // ===================== Tabs registres =====================
    private TabLayout tabRegisters;
    private View registerContainer;

    // ✅ Multi-media tabs: unique par (media,node,serial)
    private static final class TabSpec {
        final String tabKey;       // ex: BT:250:1234
        final String mediaShort;   // BT / USB / —
        final String transportKey; // TransportIo.getKey() best-effort
        final int node;
        final int from;
        final String serialId;

        TabSpec(String tabKey, String mediaShort, String transportKey, int node, int from, String serialId) {
            this.tabKey = tabKey;
            this.mediaShort = mediaShort;
            this.transportKey = transportKey;
            this.node = node;
            this.from = from;
            this.serialId = serialId;
        }
    }

    // tabKey -> spec
    private final LinkedHashMap<String, TabSpec> tabsByKey = new LinkedHashMap<>();
    // regKey(node#serial) -> tabKey courant (clear ciblé si migre de média)
    private final LinkedHashMap<String, String> regKeyToTabKey = new LinkedHashMap<>();

    private String currentTabKey = null;
    private int currentRegNode = -1; // node actif (fallback pour logs API)

    // ===== LOG GLOBAL (MAIN) =====
    private CheckBox cbShowLog;
    private View logPanel;
    private TextView txtLog;
    private ScrollView logScroll;
    private Button btnClearLog;
    private Button btnCopyLog;
    private Button btnScrollDown;
    private CheckBox cbTxRx;
    private CheckBox cbLogTs;
    private boolean logTsEnabled = false;
    private long mainLogViewSinceMs = 0L;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private static final long MAIN_LOG_REFRESH_MIN_MS = 250;
    private long lastMainLogRefreshMs = 0L;
    private boolean mainLogRefreshPending = false;

    private final Map<Integer, String> apiRidToPath = new ConcurrentHashMap<>();
    private final Set<Integer> apiFirstJobRid = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<String> apiJobSeen = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // =========================
    // ✅ 1 ligne courte par action (log global)
    // =========================
    private void logMedia1(String msg) {
        logUi(null, "[MEDIA] " + msg);
    }

    private static Integer parseRid(String line) {
        try {
            int i = line.indexOf("[RID=");
            if (i < 0) return null;
            int j = line.indexOf("]", i);
            if (j < 0) return null;
            String n = line.substring(i + 5, j).trim();
            return Integer.parseInt(n);
        } catch (Exception e) {
            return null;
        }
    }

    private static String parseReqPath(String line) {
        try {
            int k = line.indexOf(" REQ ");
            if (k < 0) return null;
            String tail = line.substring(k + 5);
            String[] parts = tail.split(" ");
            if (parts.length < 2) return null;
            return parts[1].trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractJobIdFromPath(String path) {
        if (path == null) return null;
        String pfx = "/v1/delivery/job/";
        if (!path.startsWith(pfx)) return null;
        return path.substring(pfx.length()).trim();
    }

    private static boolean isJobDoneRespLine(String line) {
        return line != null && line.contains("\"msg\":\"Job: 1 - DONE\"");
    }

    private static Integer extractNodeFromPath(String path) {
        if (path == null) return null;
        int k = path.indexOf("lcrnode_dec=");
        if (k < 0) return null;
        int start = k + "lcrnode_dec=".length();
        int end = start;
        while (end < path.length()) {
            char c = path.charAt(end);
            if (c < '0' || c > '9') break;
            end++;
        }
        try {
            return Integer.parseInt(path.substring(start, end));
        } catch (Exception ignore) {
            return null;
        }
    }

    private final BroadcastReceiver usbUiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String a = intent.getAction();
            if (a == null) return;
            if (UsbReceiver.ACTION_USB_READY.equals(a)) {
                UsbSerialPort p = UsbSession.getPort();
                if (p != null) onUsbPortReady(p);
            } else if (UsbReceiver.ACTION_USB_DETACHED.equals(a)) {
                onUsbDetached();
            }
        }
    };

    private final LogBus.Listener mainLogListener = e -> scheduleMainLogRefresh();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        usbManager = (UsbManager) getSystemService(USB_SERVICE);

        bindUi();
        wireUi();
        initUiDefaults();
        setupTabsTop();

        // CONFIGURE: media + bluetooth
        mediaProfileStore = new com.pa.lcr.lcp.storage.MediaProfileStore(this);
        btAdapter = BluetoothAdapter.getDefaultAdapter();

        // ✅ Option A: manager runtime multi-transport
        mediaTransportManager = MediaTransportManager.get(this);

        deliveryStore = new DeliveryLogStore(this);
 // ✅ Android 9: demander la permission storage (une seule fois) pour /Download
 ensureLegacyStoragePermissionForDownloads(true);
        deliveryStore.purgeOlderThanDaysAsync(7);

        refreshApiStatus();
        logUi(null, "UI prête — Scan USB requis");
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter();
        f.addAction(UsbReceiver.ACTION_USB_READY);
        f.addAction(UsbReceiver.ACTION_USB_DETACHED);
        registerReceiver(usbUiReceiver, f);

        LogBus.addListener(mainLogListener);

        UsbSerialPort p = UsbSession.getPort();
        if (p != null && usbPort == null) {
            onUsbPortReady(p);
        }
        refreshGlobalLogView();
    }

    @Override
    protected void onStop() {
        try { unregisterReceiver(usbUiReceiver); } catch (Exception ignored) {}
        LogBus.removeListener(mainLogListener);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopApiServer("Activity destroyed");
        try { scanExec.shutdownNow(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private void bindUi() {
        tabLayout = findViewById(R.id.tabLayout);
        pageMain = findViewById(R.id.pageMain);
        pageApiFace = findViewById(R.id.pageApiFace);
        pageConfigure = findViewById(R.id.pageConfigure);

        btnScanUsb = findViewById(R.id.btnScanUsb);
        btnPingUsb = findViewById(R.id.btnPingUsb);
        spnUsbDevices = findViewById(R.id.spnUsbDevices);
txtActiveNode = findViewById(R.id.txtActiveNode);
tabRegisters = findViewById(R.id.tabRegisters);
        registerContainer = findViewById(R.id.registerContainer);

        cbShowLog = findViewById(R.id.cbShowLog);
        logPanel = findViewById(R.id.logPanel);
        txtLog = findViewById(R.id.txtLog);
        logScroll = findViewById(R.id.logScroll);
        btnClearLog = findViewById(R.id.btnClearLog);
        btnCopyLog = findViewById(R.id.btnCopyLog);
        btnScrollDown = findViewById(R.id.btnScrollDown);
        cbTxRx = findViewById(R.id.cbTxRx);
        cbLogTs = findViewById(R.id.cbLogTs);

        txtApiStatus = findViewById(R.id.txtApiStatus);
        txtApiUrl = findViewById(R.id.txtApiUrl);
        btnApiStart = findViewById(R.id.btnApiStart);
        btnApiStop = findViewById(R.id.btnApiStop);
        btnDbBackup = findViewById(R.id.btnDbBackup);

        // CONFIGURE status + BT
        txtMediaActive = findViewById(R.id.txtMediaActive);
        txtNodesActive = findViewById(R.id.txtNodesActive);
        spnBtBonded = findViewById(R.id.spnBtBonded);
        btnBtRefresh = findViewById(R.id.btnBtRefresh);
        btnBtConnect = findViewById(R.id.btnBtConnect);
        btnBtDisconnect = findViewById(R.id.btnBtDisconnect);
        txtBtStatus = findViewById(R.id.txtBtStatus);

        // CONFIGURE: scan registres (par média)
        btnScanUsbRegs = findViewById(R.id.btnScanUsbRegs);
        txtUsbRegsFound = findViewById(R.id.txtUsbRegsFound);
        btnScanBtRegs = findViewById(R.id.btnScanBtRegs);
        txtBtRegsFound = findViewById(R.id.txtBtRegsFound);
        btnScanWifiRegs = findViewById(R.id.btnScanWifiRegs);
        txtWifiRegsFound = findViewById(R.id.txtWifiRegsFound);
        if (txtApiUrl != null) {
            txtApiUrl.setText("http://127.0.0.1:" + API_PORT);
        }
    }

    private void initUiDefaults() {
        if (txtActiveNode != null) txtActiveNode.setText("Node actif : —");
        if (cbShowLog != null) cbShowLog.setChecked(false);
        if (logPanel != null) logPanel.setVisibility(View.GONE);

        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        boolean showTxRx = prefs.getBoolean("log_tx_rx", false);
        if (cbTxRx != null) cbTxRx.setChecked(showTxRx);
        LogBus.SHOW_IO = showTxRx;

        boolean ts = prefs.getBoolean("log_ts", false);
        logTsEnabled = ts;
        if (cbLogTs != null) cbLogTs.setChecked(ts);
        LogBus.SHOW_TS = ts;
    nodeItems.clear();
ensureRegisterTab(250, 255, true);

        mainLogViewSinceMs = 0L;
        refreshGlobalLogView();
    }

    private void wireUi() {
        if (btnScanUsb != null) btnScanUsb.setOnClickListener(v -> scanUsb());
        if (btnPingUsb != null) btnPingUsb.setOnClickListener(v -> openSelectedUsb());

        // Tabs registres
        if (tabRegisters != null) {
            tabRegisters.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
                @Override public void onTabSelected(TabLayout.Tab tab) {
                    Object tag = (tab != null) ? tab.getTag() : null;
                    if (tag instanceof String) showRegisterFragmentByKey((String) tag);
                }
                @Override public void onTabUnselected(TabLayout.Tab tab) {}
                @Override public void onTabReselected(TabLayout.Tab tab) {
                    Object tag = (tab != null) ? tab.getTag() : null;
                    if (tag instanceof String) showRegisterFragmentByKey((String) tag);
                }
            });
        }

        // Log global
        if (cbShowLog != null) {
            cbShowLog.setOnCheckedChangeListener((buttonView, checked) -> {
                if (logPanel != null) logPanel.setVisibility(checked ? View.VISIBLE : View.GONE);
                logUi(null, "Option Afficher log global: " + (checked ? "ON" : "OFF"));
                if (checked) refreshGlobalLogView();
            });
        }
        if (btnClearLog != null) {
            btnClearLog.setOnClickListener(v -> {
                mainLogViewSinceMs = System.currentTimeMillis();
                if (txtLog != null) txtLog.setText("");
                logUi(null, "Clear log (vue MAIN)");
            });
        }
        if (btnCopyLog != null) {
            btnCopyLog.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("log", txtLog != null ? txtLog.getText() : ""));
                logUi(null, "Log copié dans le presse-papiers");
            });
        }
        if (btnScrollDown != null) {
            btnScrollDown.setOnClickListener(v -> {
                if (logScroll != null) logScroll.fullScroll(View.FOCUS_DOWN);
            });
        }
        if (cbTxRx != null) {
            cbTxRx.setOnCheckedChangeListener((buttonView, checked) -> {
                SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
                prefs.edit().putBoolean("log_tx_rx", checked).apply();
                LogBus.SHOW_IO = checked;
                logUi(null, "Option TX/RX (vue MAIN): " + (checked ? "ON" : "OFF"));
                refreshGlobalLogView();
            });
        }
        if (cbLogTs != null) {
            cbLogTs.setOnCheckedChangeListener((buttonView, checked) -> {
                SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
                prefs.edit().putBoolean("log_ts", checked).apply();
                logTsEnabled = checked;
                LogBus.SHOW_TS = checked;
                logUi(null, "Option timestamps (UI+IO+API): " + (checked ? "ON" : "OFF"));
                refreshGlobalLogView();
            });
        }

        // API-Face
        if (btnApiStart != null) btnApiStart.setOnClickListener(v -> startApiServer());
        if (btnApiStop != null) btnApiStop.setOnClickListener(v -> stopApiServer("Stop button"));
        if (btnDbBackup != null) {
            btnDbBackup.setOnClickListener(v -> doBackupDb());
            btnDbBackup.setOnLongClickListener(v -> { requestBackupDir(); return true; });
        }

        // BT
        if (btnBtRefresh != null) btnBtRefresh.setOnClickListener(v -> refreshBondedBtList());
        if (btnBtConnect != null) btnBtConnect.setOnClickListener(v -> btConnectSelected());
        if (btnBtDisconnect != null) btnBtDisconnect.setOnClickListener(v -> btDisconnect());

        // CONFIGURE: scan registres par média
        if (btnScanUsbRegs != null) btnScanUsbRegs.setOnClickListener(v -> scanRegistersUsbOnly());
        if (btnScanBtRegs != null) btnScanBtRegs.setOnClickListener(v -> scanRegistersBtOnly());
        if (btnScanWifiRegs != null) btnScanWifiRegs.setOnClickListener(v -> toast("Wi‑Fi: bientôt"));
    }

private void setupTabsTop() {
        if (tabLayout == null) return;
        tabLayout.removeAllTabs();
        tabLayout.addTab(tabLayout.newTab().setText("MAIN"), true);
        tabLayout.addTab(tabLayout.newTab().setText("API-Face"), false);
        tabLayout.addTab(tabLayout.newTab().setText("CONFIGURE"), false);
        showPage(0);
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { showPage(tab.getPosition()); }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    private void showPage(int index) {
        if (pageMain != null) pageMain.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        if (pageApiFace != null) pageApiFace.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        if (pageConfigure != null) pageConfigure.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        if (index == 1) refreshApiStatus();
        if (index == 2) {
            updateMediaStatusUi();
            updateNodesStatusUi();
            refreshBondedBtList();
        }
    }

    // =========================
// Register tabs helpers (multi-media)
// =========================

    private static String mediaShortFromTransportKey(String transportKey) {
        if (transportKey == null) return "—";
        String k = transportKey.trim().toUpperCase(java.util.Locale.ROOT);
        if (k.startsWith("BT:")) return "BT";
        if (k.startsWith("USB")) return "USB";
        if (k.contains("BT")) return "BT";
        if (k.contains("USB")) return "USB";
        return "—";
    }

    private static String safeSerial(String serialId) {
        if (serialId == null) return "";
        return serialId.trim();
    }

    private static String serialShort(String serialId) {
        String s = safeSerial(serialId);
        if (s.isEmpty()) return "—";
        if (s.length() <= 6) return s;
        return s.substring(Math.max(0, s.length() - 6));
    }

    private static String tabKeyOf(String mediaShort, int node, String serialId) {
        String m = (mediaShort == null || mediaShort.trim().isEmpty()) ? "—" : mediaShort.trim();
        return m + ":" + (node & 0xFF) + ":" + safeSerial(serialId);
    }

    private static String regKeyOf(int node, String serialId) {
        return (node & 0xFF) + "#" + safeSerial(serialId);
    }

    private String tabLabelOf(String mediaShort, int node, String serialId) {
        String m = (mediaShort == null || mediaShort.trim().isEmpty()) ? "—" : mediaShort.trim();
        return m + " - " + serialShort(serialId) + " - " + (node & 0xFF);
    }

    private boolean isTransportReady(String transportKey) {
        try {
            if (mediaTransportManager == null) return false;
            if (transportKey == null || transportKey.trim().isEmpty()) return false;
            TransportIo io = mediaTransportManager.getByKey(transportKey.trim());
            return io != null && io.isOpen();
        } catch (Exception e) {
            return false;
        }
    }

    private void persistTabMediaStatusForApi(TabSpec spec, boolean ready, String media) {
        try {
            if (deliveryStore == null || spec == null) return;
            String serial = safeSerial(spec.serialId);
            if (serial.isEmpty()) return;

            String ticketKey = "TAB-" + (spec.node & 0xFF);
            JSONObject data = new JSONObject();
            data.put("event_type", "TAB_MEDIA_STATUS");
            data.put("state", ready ? "READY" : "OFF");
            data.put("media", media);
            data.put("transport_key", spec.transportKey);
            data.put("node", (spec.node & 0xFF));
            data.put("serial_id", serial);
            data.put("ts_ms", System.currentTimeMillis());

            deliveryStore.upsertSummaryAsync(serial, ticketKey, null,
                    ready ? "TAB_READY" : "TAB_OFF",
                    DeliveryLogStore.SOURCE_UI, null, null, null);

            final String tk = ticketKey;
            deliveryStore.openAttemptAsync(serial, tk, DeliveryLogStore.SOURCE_UI, null, attemptId -> {
                deliveryStore.addEventAsync(attemptId, DeliveryLogStore.LEVEL_INFO,
                        "TAB_MEDIA_STATUS",
                        "Tab media status",
                        data.toString());
                deliveryStore.closeAttemptAsync(attemptId, "DONE", data.toString(), null);
            });
        } catch (Exception ignored) {}
    }

    private void refreshOneTabMediaStatus(String tabKey) {
        if (tabKey == null) return;
        TabSpec spec = tabsByKey.get(tabKey);
        if (spec == null) return;

        boolean ready = isTransportReady(spec.transportKey);
        String media = mediaShortFromTransportKey(spec.transportKey);
        String mediaLabel = ready ? media : (media + "(OFF)");

        // ✅ Format: BT(OFF) - 123456 - 250
        updateRegisterTabLabel(tabKey, tabLabelOf(mediaLabel, spec.node, spec.serialId));

        try {
            Fragment f = getSupportFragmentManager().findFragmentByTag("regtab_" + tabKey);
            if (f instanceof RegisterTabFragment) {
                ((RegisterTabFragment) f).onTabMediaStatusChanged(ready, media);
            }
        } catch (Exception ignored) {}

        persistTabMediaStatusForApi(spec, ready, media);
    }

    private void refreshAllTabsMediaStatus() {
        try {
            ArrayList<String> keys = new ArrayList<>(tabsByKey.keySet());
            for (String k : keys) refreshOneTabMediaStatus(k);
        } catch (Exception ignored) {}
    }


    /**
     * Legacy: créer un TAB "unknown serial" (avant scan) — pas de regKey mapping.
     */
    private void ensureRegisterTab(int node, int from, boolean focus) {
        if (node < 1 || node > 250) {
            logUi(null, "TAB registre: node invalide: " + node);
            return;
        }
        if (from < 0 || from > 255) from = 255;

        String tabKey = tabKeyOf("—", node, "");
        if (!tabsByKey.containsKey(tabKey)) {
            TabSpec spec = new TabSpec(tabKey, "—", null, node, from, "");
            tabsByKey.put(tabKey, spec);
            addRegisterTabUi(spec);
            logUi(null, "TAB registre ajouté (unknown): " + node);
        } else {
            logUi(null, "TAB registre déjà présent (unknown): " + node + " (focus)");
        }

        if (focus) {
            selectRegisterTabByKey(tabKey);
            showRegisterFragmentByKey(tabKey);
        } else if (currentTabKey == null) {
            selectRegisterTabByKey(tabKey);
            showRegisterFragmentByKey(tabKey);
        }
    }

    /**
     * Upsert issu d'un scan (serial connu).
     * Règle: clear ciblé A1 si même (node,serial) apparaît sur un autre média.
     */
    private void upsertRegisterTabFromScan(String transportKey, int node, int from, String serialId, boolean focus) {
        if (node < 1 || node > 250) return;
        if (from < 0 || from > 255) from = 255;
        String mediaShort = mediaShortFromTransportKey(transportKey);
        String serial = safeSerial(serialId);
        if (serial.isEmpty()) return;

        // 1) retirer les tabs legacy (serial vide) dès qu'on trouve au moins un registre
        removeAllUnknownSerialTabsBestEffort();

        // 2) clear ciblé si migration (même node+serial, média différent)
        String regKey = regKeyOf(node, serial);
        String newTabKey = tabKeyOf(mediaShort, node, serial);
        String oldTabKey = regKeyToTabKey.get(regKey);
        if (oldTabKey != null && !oldTabKey.equals(newTabKey)) {
            removeTabAndFragment(oldTabKey, "migrated to " + newTabKey);
        }
        regKeyToTabKey.put(regKey, newTabKey);

        // 3) upsert tab
        TabSpec existing = tabsByKey.get(newTabKey);
        if (existing == null) {
            TabSpec spec = new TabSpec(newTabKey, mediaShort, transportKey, node, from, serial);
            tabsByKey.put(newTabKey, spec);
            addRegisterTabUi(spec);
            logUi(null, "TAB registre ajouté: " + tabLabelOf(mediaShort, node, serial));
        } else {
            TabSpec spec = new TabSpec(newTabKey, mediaShort, transportKey, node, from, serial);
            tabsByKey.put(newTabKey, spec);
            updateRegisterTabLabel(newTabKey, tabLabelOf(mediaShort, node, serial));
        }

        if (focus) {
            selectRegisterTabByKey(newTabKey);
            showRegisterFragmentByKey(newTabKey);
        }
    }

    private void removeAllUnknownSerialTabsBestEffort() {
        try {
            java.util.ArrayList<String> toRemove = new java.util.ArrayList<>();
            for (java.util.Map.Entry<String, TabSpec> e : tabsByKey.entrySet()) {
                if (e == null) continue;
                TabSpec s = e.getValue();
                if (s == null) continue;
                if (s.serialId == null || s.serialId.trim().isEmpty()) {
                    toRemove.add(e.getKey());
                }
            }
            for (String k : toRemove) {
                removeTabAndFragment(k, "remove legacy unknown tab");
            }
        } catch (Exception ignored) {}
    }

    private void addRegisterTabUi(TabSpec spec) {
        if (tabRegisters == null || spec == null) return;
        TabLayout.Tab t = tabRegisters.newTab();
        t.setText(tabLabelOf(spec.mediaShort, spec.node, spec.serialId));
        t.setTag(spec.tabKey);
        tabRegisters.addTab(t, false);
    }

    private void updateRegisterTabLabel(String tabKey, String label) {
        try {
            if (tabRegisters == null) return;
            for (int i = 0; i < tabRegisters.getTabCount(); i++) {
                TabLayout.Tab t = tabRegisters.getTabAt(i);
                if (t == null) continue;
                Object tag = t.getTag();
                if (tag instanceof String && tabKey.equals(tag)) {
                    t.setText(label);
                    return;
                }
            }
        } catch (Exception ignored) {}
    }

    private void selectRegisterTabByKey(String tabKey) {
        if (tabRegisters == null || tabKey == null) return;
        for (int i = 0; i < tabRegisters.getTabCount(); i++) {
            TabLayout.Tab t = tabRegisters.getTabAt(i);
            if (t != null && t.getTag() instanceof String && tabKey.equals((String) t.getTag())) {
                t.select();
                return;
            }
        }
    }

    private void showRegisterFragmentByKey(String tabKey) {
        if (registerContainer == null || tabKey == null) return;
        TabSpec spec = tabsByKey.get(tabKey);
        if (spec == null) return;
        currentTabKey = tabKey;
        currentRegNode = spec.node;

        if (txtActiveNode != null) {
            txtActiveNode.setText("Node actif : " + tabLabelOf(spec.mediaShort, spec.node, spec.serialId));
        }

        FragmentManager fm = getSupportFragmentManager();
        String tag = "regtab_" + tabKey;
        Fragment existing = fm.findFragmentByTag(tag);
        Fragment f = (existing != null) ? existing : RegisterTabFragment.newInstance(spec.node, spec.from, spec.serialId, spec.transportKey);
        FragmentTransaction tx = fm.beginTransaction();
        tx.replace(R.id.registerContainer, f, tag);
        tx.setReorderingAllowed(true);
        tx.commitAllowingStateLoss();
        ui.postDelayed(() -> refreshOneTabMediaStatus(tabKey), 50);
    }

    /**
     * ✅ Clear ciblé A1: retire TAB + Fragment explicitement.
     */
    private void removeTabAndFragment(String tabKey, String reason) {
        if (tabKey == null) return;

        tabsByKey.remove(tabKey);

        // remove regKey mapping entries pointing to this tabKey
        try {
            java.util.ArrayList<String> toRemove = new java.util.ArrayList<>();
            for (java.util.Map.Entry<String, String> e : regKeyToTabKey.entrySet()) {
                if (e == null) continue;
                if (tabKey.equals(e.getValue())) toRemove.add(e.getKey());
            }
            for (String k : toRemove) regKeyToTabKey.remove(k);
        } catch (Exception ignored) {}

        // remove tab UI
        try {
            if (tabRegisters != null) {
                for (int i = 0; i < tabRegisters.getTabCount(); i++) {
                    TabLayout.Tab t = tabRegisters.getTabAt(i);
                    if (t == null) continue;
                    Object tag = t.getTag();
                    if (tag instanceof String && tabKey.equals(tag)) {
                        tabRegisters.removeTabAt(i);
                        break;
                    }
                }
            }
        } catch (Exception ignored) {}

        // remove fragment explicitly
        try {
            FragmentManager fm = getSupportFragmentManager();
            String ftag = "regtab_" + tabKey;
            Fragment f = fm.findFragmentByTag(ftag);
            if (f != null) {
                FragmentTransaction tx = fm.beginTransaction();
                tx.remove(f);
                tx.commitAllowingStateLoss();
        ui.postDelayed(() -> refreshOneTabMediaStatus(tabKey), 50);
            }
        } catch (Exception ignored) {}

        if (tabKey.equals(currentTabKey)) {
            currentTabKey = null;
            currentRegNode = -1;
            try {
                if (tabRegisters != null && tabRegisters.getTabCount() > 0) {
                    TabLayout.Tab t0 = tabRegisters.getTabAt(0);
                    if (t0 != null) t0.select();
                    Object tag = (t0 != null) ? t0.getTag() : null;
                    if (tag instanceof String) showRegisterFragmentByKey((String) tag);
                }
            } catch (Exception ignored) {}
        }

        logUi(null, "TAB registre supprimé: " + tabKey + (reason != null ? (" (" + reason + ")") : ""));
    }
// =========================
    // Scan registres Option B (0x28 + #80 + #23) - AUTORITAIRE
    // =========================
// =========================
// Scan registres Option B (0x28 + #80 + #23) - AUTORITAIRE
// ✅ Option B strict: tout passe par TransportIo (USB/BT)
// =========================

    // =========================
    // CONFIGURE: Scan registres par média (USB / BT / Wi‑Fi)
    // - Un scan = un seul média.
    // - Résultats affichés sous la section correspondante (serial complet + node + TO/FROM).
    // - Tabs MAIN: libellé abrégé (6 derniers) : MEDIA - <last6> - <node>.
    // =========================

    private void scanRegistersUsbOnly() {
        TransportIo io = null;
        try {
            if (mediaTransportManager != null) {
                io = mediaTransportManager.getByKey(MediaTransportManager.KEY_USB);
            }
        } catch (Exception ignored) {}
        if (io == null || !io.isOpen()) {
            logUi(null, "Scan USB registres: USB non prêt. Faire Scan USB (devices) + Ouvrir/Ping.");
            toast("Scan USB registres: USB non prêt");
            return;
        }
        scanRegistersWithIo(io, io.getKey(), txtUsbRegsFound);
    }

    private void scanRegistersBtOnly() {
        if (lastBtMac == null || lastBtMac.trim().isEmpty()) {
            logUi(null, "Scan BT registres: aucun BT connecté. Faire Refresh + Connect.");
            toast("Scan BT registres: BT non connecté");
            return;
        }
        TransportIo io = null;
        try {
            String key = MediaTransportManager.btKey(lastBtMac);
            if (mediaTransportManager != null) {
                io = mediaTransportManager.getByKey(key);
            }
        } catch (Exception ignored) {}
        if (io == null || !io.isOpen()) {
            logUi(null, "Scan BT registres: BT non prêt. Faire Connect BT.");
            toast("Scan BT registres: BT non prêt");
            return;
        }
        scanRegistersWithIo(io, io.getKey(), txtBtRegsFound);
    }

    private void scanRegistersWithIo(TransportIo io, String transportKey, TextView target) {
        if (io == null || !io.isOpen()) return;
        final String tk = (transportKey != null ? transportKey : io.getKey());
        final String mediaShort = mediaShortFromTransportKey(tk);

        logUi(null, "Scan registres (" + mediaShort + ") demandé");

        // disable buttons during scan (best-effort)
        try { if ("USB".equalsIgnoreCase(mediaShort) && btnScanUsbRegs != null) btnScanUsbRegs.setEnabled(false); } catch (Exception ignored) {}
        try { if ("BT".equalsIgnoreCase(mediaShort) && btnScanBtRegs != null) btnScanBtRegs.setEnabled(false); } catch (Exception ignored) {}

        if (target != null) target.setText("Scan en cours... (" + mediaShort + ")");

        final TransportIo ioFinal = io;
        scanExec.execute(() -> {
            final long scanStartedMs = System.currentTimeMillis();
            LinkedHashMap<Integer, NodeScanItem> found = new LinkedHashMap<>();
            final int T28 = 300;
            final int TF = 300;

            for (int node = 1; node <= 250; node++) {
                try {
                    LcpLink tmp = new LcpLink(ioFinal, node, 255, true);
                    int[] ds = tmp.opDeliveryStatus(T28);
                    int delCode = ds[1];
                    boolean ticketPending = (delCode & 0x0001) != 0;
                    boolean flowActive = (delCode & 0x0004) != 0;
                    boolean deliveryActive = (delCode & 0x0008) != 0;

                    String serialId = decodeAz(tmp.opGetField(80, TF));
                    String ticketNo = u32beDec(tmp.opGetField(23, TF)); // optionnel

                    if (serialId != null && !serialId.trim().isEmpty()) {
                        found.put(node, new NodeScanItem(node, serialId, ticketNo, ticketPending, deliveryActive, flowActive, false));
                    }
                } catch (Exception ignored) {}
            }

            final long scanFinishedMs = System.currentTimeMillis();
            persistScanEvents(scanStartedMs, scanFinishedMs, found);

            ui.post(() -> {
                try {
                    nodeItems.clear();
                    nodeItems.addAll(found.values());

                    if (target != null) {
                        if (found.isEmpty()) {
                            target.setText("(aucun registre trouvé)\n" + mediaShort);
                        } else {
                            StringBuilder sb = new StringBuilder();
                            sb.append(mediaShort).append(" — ").append(found.size()).append(" registre(s)\n");
                            for (NodeScanItem it : found.values()) {
                                if (it == null) continue;
                                sb.append("Serial=").append(safeSerial(it.serialId))
                                  .append("  Node=").append(it.lcrnode)
                                  .append("  TO=").append(it.lcrnode)
                                  .append("  From=255\n");
                            }
                            target.setText(sb.toString().trim());
                        }
                    }

                    if (!found.isEmpty()) {
                        boolean focused = false;
                        for (NodeScanItem it : found.values()) {
                            if (it == null) continue;
                            boolean focus = false;
                            if (!focused && it.lcrnode == 250) focus = true;
                            upsertRegisterTabFromScan(tk, it.lcrnode, 255, it.serialId, focus);
                            if (focus) focused = true;
                        }
                        if (!focused) {
                            NodeScanItem first = found.values().iterator().next();
                            if (first != null) upsertRegisterTabFromScan(tk, first.lcrnode, 255, first.serialId, true);
                        }
                    }
                } finally {
                    try { if ("USB".equalsIgnoreCase(mediaShort) && btnScanUsbRegs != null) btnScanUsbRegs.setEnabled(true); } catch (Exception ignored) {}
                    try { if ("BT".equalsIgnoreCase(mediaShort) && btnScanBtRegs != null) btnScanBtRegs.setEnabled(true); } catch (Exception ignored) {}
                    updateNodesStatusUi();
                }
            });
        });
    }







    /**
     * ✅ Option 2 + Option 5:
     * - SCAN_NODE_DETECTED: only for nodes truly detected
     * - SCAN_COMPLETED: always once at end (even if found_count==0)
     */
    private void persistScanEvents(long scanStartedMs, long scanFinishedMs, Map<Integer, NodeScanItem> found) {
        if (deliveryStore == null) return;
        final long durationMs = Math.max(0L, scanFinishedMs - scanStartedMs);
        final int foundCount = (found != null) ? found.size() : 0;

        // 1) Per-node detected events (ONLY if found)
        if (found != null && !found.isEmpty()) {
            for (NodeScanItem it : found.values()) {
                if (it == null) continue;
                if (it.serialId == null || it.serialId.trim().isEmpty()) continue;

                long detectedMs = System.currentTimeMillis();
            String tno = (it.ticketNo != null && !it.ticketNo.trim().isEmpty()) ? it.ticketNo.trim() : ("SCAN-" + scanStartedMs + "-N" + it.lcrnode);
            JSONObject data = new JSONObject();
                try {
                    data.put("event_type", "SCAN_NODE_DETECTED");
                    data.put("node", it.lcrnode);
                    data.put("from", 255);
                    data.put("scan_started_ms", scanStartedMs);
                    data.put("detected_ms", detectedMs);
                    data.put("scan_finished_ms", scanFinishedMs);
                    data.put("duration_ms", durationMs);
                    data.put("serial_id", it.serialId);
                    data.put("ticket_no", it.ticketNo);
                data.put("ticket_key", tno);
                    data.put("ticketPending", it.ticketPending ? 1 : 0);
                    data.put("deliveryActive", it.deliveryActive ? 1 : 0);
                    data.put("flowActive", it.flowActive ? 1 : 0);
                } catch (Exception ignored) {}

                deliveryStore.upsertSummaryAsync(
                        it.serialId,
                        tno,
                        null,
                        "NODE_DETECTED_SCAN",
                        DeliveryLogStore.SOURCE_UI,
                        null,
                        null,
                        null
        );

        deliveryStore.openAttemptAsync(it.serialId, tno, DeliveryLogStore.SOURCE_UI, null, attemptId -> {
                    deliveryStore.addEventAsync(attemptId, DeliveryLogStore.LEVEL_INFO,
                            "SCAN_NODE_DETECTED",
"Scan registres: node détecté",
                            data.toString());
                    deliveryStore.closeAttemptAsync(attemptId, "SEEN", data.toString(), null);
                });
            }
        }

        // 2) Completion event (ALWAYS)
        final String scanSerial = "__SCAN__";
        final String scanTicket = "SCAN-" + scanStartedMs;
        final String scanState = "SCAN_COMPLETED";

        JSONObject summary = new JSONObject();
        try {
            summary.put("event_type", "SCAN_COMPLETED");
            summary.put("scan_started_ms", scanStartedMs);
            summary.put("scan_finished_ms", scanFinishedMs);
            summary.put("duration_ms", durationMs);
            summary.put("found_count", foundCount);

            JSONArray nodes = new JSONArray();
            if (found != null) {
                int k = 0;
                for (Integer n : found.keySet()) {
                    if (n == null) continue;
                    nodes.put(n.intValue());
                    if (++k >= 50) break;
                }
            }
            summary.put("nodes", nodes);
            summary.put("nodes_truncated", (foundCount > 50) ? 1 : 0);
        } catch (Exception ignored) {}

        deliveryStore.upsertSummaryAsync(
                scanSerial,
                scanTicket,
                null,
                scanState,
                DeliveryLogStore.SOURCE_UI,
                null,
                null,
                null
        );

        deliveryStore.openAttemptAsync(scanSerial, scanTicket, DeliveryLogStore.SOURCE_UI, null, attemptId -> {
            deliveryStore.addEventAsync(attemptId, DeliveryLogStore.LEVEL_INFO,
                    "SCAN_COMPLETED",
                    (foundCount == 0)
                            ? "Scan registres terminé: aucun node détecté"
                            : ("Scan registres terminé: " + foundCount + " node(s) détecté(s)"),
                    summary.toString());
            deliveryStore.closeAttemptAsync(attemptId, "DONE", summary.toString(), null);
        });
    }

    // -------------------------
    // Helpers decode
    // -------------------------
    private static String decodeAz(byte[] b) {
        if (b == null || b.length == 0) return "";
        String s = new String(b, StandardCharsets.UTF_8);
        int nul = s.indexOf('\0');
        if (nul >= 0) s = s.substring(0, nul);
        return s.trim();
    }

    private static String u32beDec(byte[] be4) {
        if (be4 == null || be4.length < 4) return "";
        long u = ((be4[0] & 0xFFL) << 24)
                | ((be4[1] & 0xFFL) << 16)
                | ((be4[2] & 0xFFL) << 8)
                | (be4[3] & 0xFFL);
        return String.valueOf(u & 0xFFFFFFFFL);
    }

    private static final class NodeScanItem {
        final int lcrnode;
        final String serialId;
        final String ticketNo;
        final boolean ticketPending;
        final boolean deliveryActive;
        final boolean flowActive;
        final boolean isDefault;

        NodeScanItem(int lcrnode, String serialId, String ticketNo,
                     boolean ticketPending, boolean deliveryActive, boolean flowActive, boolean isDefault) {
            this.lcrnode = lcrnode;
            this.serialId = serialId;
            this.ticketNo = ticketNo;
            this.ticketPending = ticketPending;
            this.deliveryActive = deliveryActive;
            this.flowActive = flowActive;
            this.isDefault = isDefault;
        }

        static NodeScanItem default250() { return new NodeScanItem(250, "", "", false, false, false, true); }

        NodeScanItem asDefault() {
            return new NodeScanItem(lcrnode, serialId, ticketNo, ticketPending, deliveryActive, flowActive, true);
        }

        @Override public String toString() {
            String base = (isDefault ? "Défaut " : "") + lcrnode;
            String sid = (serialId == null || serialId.isEmpty()) ? "serial=—" : ("serial=" + serialId);
            String tno = (ticketNo == null || ticketNo.isEmpty()) ? "ticket=—" : ("ticket=" + ticketNo);
            String pend = "pending=" + (ticketPending ? "1" : "0");
            String act = "active=" + (deliveryActive ? "1" : "0");
            return base + " — " + sid + " — " + tno + " — " + pend + " — " + act;
        }
    }

    // =========================
    // USB
    // =========================
    private void scanUsb() {
        usbDevices.clear();
        usbDevices.addAll(usbManager.getDeviceList().values());
        logUi(null, "Scan USB: " + usbDevices.size() + " périphérique(s)");

        List<String> labels = new ArrayList<>();
        for (UsbDevice d : usbDevices) {
            String m = d.getManufacturerName();
            String p = d.getProductName();
            if (m == null) m = "Unknown";
            if (p == null) p = "Device";
            labels.add(m + " - " + p);
            logUi(null, String.format(Locale.ROOT, " - %s - %s (VID=%04X PID=%04X)", m, p, d.getVendorId(), d.getProductId()));
        }

        spnUsbDevices.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels));
    }

    private void openSelectedUsb() {
        logMedia1("USB Open/Ping");

        UsbSerialPort sessionPort = UsbSession.getPort();
        if (sessionPort != null) {
            if (usbPort == null) usbPort = sessionPort;
            logUi(null, "USB déjà prêt (UsbSession port déjà ouvert)");
            logMedia1("USB Open/Ping: déjà prêt");
            return;
        }

        if (usbPort != null) {
            logUi(null, "USB déjà prêt (port déjà ouvert)");
            UsbDevice dev = (usbDevices.isEmpty() ? null : getSelectedUsbDeviceSafe());
            if (dev != null) UsbSession.set(dev, usbPort);
            logMedia1("USB Open/Ping: déjà prêt");
            return;
        }

        UsbDevice dev = getSelectedUsbDeviceSafe();
        if (dev == null) {
            logUi(null, "Aucun périphérique USB sélectionné");
            logMedia1("USB Open/Ping: ÉCHEC");
            return;
        }

        if (!usbManager.hasPermission(dev)) {
            PendingIntent pi = PendingIntent.getBroadcast(
                    this, 0, new Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
            );
                        usbManager.requestPermission(dev, pi);
            logUi(null, "Permission USB demandée");
            logMedia1("USB Open/Ping: permission requise");
            return;
        }

        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(dev);
        if (driver == null) {
            logUi(null, "Driver USB série introuvable");
            logMedia1("USB Open/Ping: ÉCHEC");
            return;
        }

        try {
            UsbDeviceConnection conn = usbManager.openDevice(dev);
            UsbSerialPort port = driver.getPorts().get(0);
            port.open(conn);
            port.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            usbPort = port;
            UsbSession.set(dev, port);
            // ✅ Publish USB ready (requis pour Scan registres USB via MediaTransportManager)
            try {
                if (mediaTransportManager != null) {
                    mediaTransportManager.onUsbReady(dev, usbPort, "USB prêt (OpenSelectedUsb)");
                }
            } catch (Exception ignored) {}
            logUi(null, "USB prêt");
            logMedia1("USB Open/Ping: OK");
        } catch (Exception e) {
            logErr(null, "Open USB ERR: " + safeMsg(e));
            try { if (usbPort != null) usbPort.close(); } catch (Exception ignored) {}
            usbPort = null;
            try { UsbSession.clear(); } catch (Exception ignored) {}
            logMedia1("USB Open/Ping: ÉCHEC");
        }
    }

    private UsbDevice getSelectedUsbDeviceSafe() {
        try {
            int idx = spnUsbDevices.getSelectedItemPosition();
            if (idx < 0 || idx >= usbDevices.size()) return null;
            return usbDevices.get(idx);
        } catch (Exception e) {
            return null;
        }
    }

    public void onUsbPortReady(UsbSerialPort port) {
        if (port == null) return;
        if (usbPort != null) {
            try { port.close(); } catch (Exception ignore) {}
            return;
        }

        usbPort = port;
        logUi(null, "USB prêt (receiver)");

        // ✅ Option A: publish USB ready
        try {
            if (mediaTransportManager != null) {
                mediaTransportManager.onUsbReady(null, usbPort, "USB prêt (MainActivity)");
            }
        } catch (Exception ignored) {}

        logMedia1("USB Ready");
    }

    public void onUsbDetached() {
        logUi(null, "USB détaché");
        // ✅ Option A: publish USB detached
        try {
            if (mediaTransportManager != null) {
                mediaTransportManager.onUsbDetached("USB detached");
            }
        } catch (Exception ignored) {}
        logMedia1("USB Detached");

        try { UsbSession.clear(); } catch (Exception ignore) {}
        stopApiServer("USB detached");

        // ✅ Multi-média: ne pas détruire les tabs BT.
        // Retirer uniquement les tabs USB (et leurs fragments) de manière explicite (A1).
        try {
            ArrayList<String> toRemove = new ArrayList<>();
            for (Map.Entry<String, TabSpec> e : tabsByKey.entrySet()) {
                if (e == null) continue;
                TabSpec s = e.getValue();
                if (s == null) continue;
                String mShort = (s.mediaShort != null) ? s.mediaShort : mediaShortFromTransportKey(s.transportKey);
                if ("USB".equalsIgnoreCase(mShort)) toRemove.add(e.getKey());
            }
            for (String k : toRemove) removeTabAndFragment(k, "USB detached");
        } catch (Exception ignored) {}

        usbPort = null;
        updateMediaStatusUi();
        updateNodesStatusUi();
    }


    // =========================
    // API Server
    // =========================
    private void startApiServer() {
        if (apiServer != null && apiServer.isRunning()) {
            logApi(null, "[API] déjà RUNNING");
            refreshApiStatus();
            return;
        }
        try {
            ApiFacade facade = new MultiRegisterApiFacadeImpl(this);
            apiServer = new ApiServer(facade, this::onApiLine, API_PORT);
            apiServer.start();
            refreshApiStatus();
            toast("API démarrée (127.0.0.1:" + API_PORT + ")");
        } catch (Exception e) {
            logApi(null, "[API] START FAIL: " + safeMsg(e));
            refreshApiStatus();
            toast("API start error: " + safeMsg(e));
        }
    }

    private void stopApiServer(String reason) {
        try {
            if (apiServer != null && apiServer.isRunning()) {
                apiServer.stop();
                logApi(null, "[API] STOP (" + reason + ")");
            }
        } catch (Exception ignored) {
        } finally {
            apiServer = null;
            refreshApiStatus();
        }
    }

    private void refreshApiStatus() {
        if (txtApiStatus == null) return;
        boolean running = (apiServer != null && apiServer.isRunning());
        txtApiStatus.setText("Status: " + (running ? "RUNNING (loopback only)" : "STOPPED"));
        if (btnApiStart != null) btnApiStart.setEnabled(!running);
        if (btnApiStop != null) btnApiStop.setEnabled(running);
    }

    private void onApiLine(String line) {
        if (line == null) return;

        Integer rid = parseRid(line);
        boolean isReq = line.contains("] REQ ");
        boolean isResp = line.contains("] RESP ");

        if (rid != null && isReq) {
            String path = parseReqPath(line);
            if (path != null) apiRidToPath.put(rid, path);

            Integer node = extractNodeFromPath(path);
            if (node == null && currentRegNode > 0) node = currentRegNode;

            String jobId = extractJobIdFromPath(path);
            if (jobId != null) {
                if (apiJobSeen.contains(jobId)) return;
                apiJobSeen.add(jobId);
                apiFirstJobRid.add(rid);
                logApi(node, line);
                return;
            }

            logApi(node, line);
            return;
        }

        if (rid != null && isResp) {
            String path = apiRidToPath.remove(rid);
            Integer node = extractNodeFromPath(path);
            if (node == null && currentRegNode > 0) node = currentRegNode;

            String jobId = extractJobIdFromPath(path);
            if (jobId != null) {
                if (apiFirstJobRid.remove(rid)) {
                    logApi(node, line);
                    return;
                }
                if (isJobDoneRespLine(line)) logApi(node, line);
                return;
            }

            logApi(node, line);
            return;
        }

        logApi((currentRegNode > 0 ? currentRegNode : null), line);
    }

    // =========================
    // Backup DB
    // =========================
    private static final int REQ_PICK_BACKUP_DIR = 9102;
    private static final String PREF_BACKUP_DIR_URI = "backup_dir_uri";

    private void doBackupDb() {
        if (deliveryStore == null) {
            toast("Backup DB impossible: store absent");
            return;
        }

        Uri savedDir = getSavedBackupDirUri();
        if (savedDir != null) {
            backupDbToChosenDir(savedDir);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String name = "lcr_delivery_" + utcStamp() + ".db";
            deliveryStore.backupDbToDownloadsAsync(this, name, (ok, fileName, detail) -> {
                if (ok) toast("Backup OK (Downloads): " + fileName);
                else toast("Backup FAIL: " + fileName + " " + detail);
            });
        } else {
		// Android 9 et - : tenter Downloads si permission accordée, sinon demander permission puis fallback dossier
		if (ensureLegacyStoragePermissionForDownloads(true)) {
			String name = "lcr_delivery_" + utcStamp() + ".db";
			deliveryStore.backupDbToDownloadsAsync(this, name, (ok, fileName, detail) -> {
				if (ok) toast("Backup OK (Downloads): " + fileName);
				else toast("Backup FAIL: " + fileName + " " + detail);
			});
		} else {
			requestBackupDir();
		}
	}
    }

    private void requestBackupDir() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        i.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        i.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, REQ_PICK_BACKUP_DIR);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_ENABLE_BT) {
            refreshBondedBtList();
            return;
        }

        if (requestCode == REQ_PICK_BACKUP_DIR) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                toast("Backup: sélection de dossier annulée");
                return;
            }
            Uri dirUri = data.getData();
            final int takeFlags = data.getFlags() & (
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            );
                        try { getContentResolver().takePersistableUriPermission(dirUri, takeFlags); } catch (Exception ignored) {}
            saveBackupDirUri(dirUri);
            toast("Backup: dossier enregistré");
            backupDbToChosenDir(dirUri);
        }
    }

    private Uri getSavedBackupDirUri() {
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        String s = prefs.getString(PREF_BACKUP_DIR_URI, null);
        if (s == null || s.trim().isEmpty()) return null;
        try { return Uri.parse(s); } catch (Exception e) { return null; }
    }

    private void saveBackupDirUri(Uri uri) {
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        prefs.edit().putString(PREF_BACKUP_DIR_URI, uri.toString()).apply();
    }

    private void backupDbToChosenDir(Uri dirUri) {
        try {
            java.io.File dbFile = getDatabasePath(DeliveryDb.DB_NAME);
            if (dbFile == null || !dbFile.exists()) {
                toast("Backup FAIL: DB introuvable (" + DeliveryDb.DB_NAME + ")");
                return;
            }

            if (deliveryStore != null) deliveryStore.checkpointWalBestEffort();

            String name = "lcr_delivery_" + utcStamp() + ".db";
            DocumentFile dir = DocumentFile.fromTreeUri(this, dirUri);
            if (dir == null || !dir.canWrite()) {
                toast("Backup FAIL: dossier non accessible en écriture");
                return;
            }

            DocumentFile existing = dir.findFile(name);
            if (existing != null) { try { existing.delete(); } catch (Exception ignore) {} }

            DocumentFile target = dir.createFile("application/x-sqlite3", name);
            if (target == null || target.getUri() == null) {
                toast("Backup FAIL: création du fichier impossible");
                return;
            }

            Uri outUri = target.getUri();
            try (java.io.InputStream in = new java.io.FileInputStream(dbFile);
                 java.io.OutputStream out = getContentResolver().openOutputStream(outUri)) {

                if (out == null) {
                    toast("Backup FAIL: output stream null");
                    return;
                }

                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                out.flush();
            }

            toast("Backup OK (dossier choisi): " + name);

        } catch (Exception e) {
            toast("Backup FAIL: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    private static String utcStamp() {
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss'Z'", Locale.ROOT);
        df.setTimeZone(TimeZone.getTimeZone("UTC"));
        return df.format(new Date());
    }

    // =========================
    // Log principal via LogBus
    // =========================
    private void scheduleMainLogRefresh() {
        if (txtLog == null) return;
        if (cbShowLog != null && !cbShowLog.isChecked()) return;

        long now = System.currentTimeMillis();
        long dt = now - lastMainLogRefreshMs;

        if (dt >= MAIN_LOG_REFRESH_MIN_MS && !mainLogRefreshPending) {
            lastMainLogRefreshMs = now;
            refreshGlobalLogView();
            return;
        }

        if (mainLogRefreshPending) return;
        mainLogRefreshPending = true;

        long delay = Math.max(0L, MAIN_LOG_REFRESH_MIN_MS - dt);
        ui.postDelayed(() -> {
            mainLogRefreshPending = false;
            lastMainLogRefreshMs = System.currentTimeMillis();
            refreshGlobalLogView();
        }, delay);
    }

    private void refreshGlobalLogView() {
        if (txtLog == null) return;
        if (cbShowLog != null && !cbShowLog.isChecked()) return;

        List<LogBus.LogEvent> events = LogBus.snapshotGlobal(1400, mainLogViewSinceMs);
        txtLog.setText(LogBus.buildText(events));
    }

    private void logUi(Integer node, String msg) {
        if (msg == null) return;
        int n = (node != null ? node : 0);
        LogBus.ui(n, maybeUiTimestamp(msg));
    }

    private void logApi(Integer node, String msg) {
        if (msg == null) return;
        int n = (node != null ? node : 0);
        LogBus.api(n, msg);
    }

    private void logErr(Integer node, String msg) {
        if (msg == null) return;
        int n = (node != null ? node : 0);
        LogBus.api(n, "[ERR] " + msg);
    }

    private String maybeUiTimestamp(String line) {
        if (!logTsEnabled) return line;
        return uiTs() + " " + line;
    }

    private String uiTs() {
        SimpleDateFormat df = new SimpleDateFormat("HH:mm:ss.SSS", Locale.CANADA_FRENCH);
        return df.format(new Date(System.currentTimeMillis()));
    }

    private int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private void toast(String s) {
        ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }

    private static String safeMsg(Exception e) {
        if (e == null) return "";
        String m = e.getMessage();
        return (m == null) ? e.getClass().getSimpleName() : m;
    }

    // =========================
    // CONFIGURE: Status UI (media + nodes)
    // =========================
    private void updateMediaStatusUi() {
        try {
            String s;
            boolean usbReady = (UsbSession.getPort() != null);
            boolean btReady = (btSocket != null && btSocket.isConnected());
            if (usbReady) s = "Média : USB (prêt)";
            else if (btReady) s = "Média : BT (connecté)";
            else s = "Média : —";
            if (txtMediaActive != null) txtMediaActive.setText(s);
        } catch (Exception ignored) {}
    }

    private void updateNodesStatusUi() {
        try {
            if (txtNodesActive == null) return;
            if (nodeItems == null || nodeItems.isEmpty()) {
                txtNodesActive.setText("Nodes : —");
                return;
            }
            int count = nodeItems.size();
            StringBuilder sb = new StringBuilder();
            sb.append("Nodes : ").append(count).append(" (");
            int shown = 0;
            for (NodeScanItem it : nodeItems) {
                if (it == null) continue;
                if (shown > 0) sb.append(", ");
                sb.append(it.lcrnode);
                shown++;
                if (shown >= 3) break;
            }
            if (count > 3) sb.append(", …");
            sb.append(")");
            txtNodesActive.setText(sb.toString());
        } catch (Exception ignored) {}
    }

    // =========================
    // CONFIGURE: Bluetooth (paired only)
    // =========================
    
 // =========================
 // ✅ Storage legacy (Android 9 / API 28) : permission runtime
 // - Requis pour écrire dans /storage/emulated/0/Download
 // - Sur Android 10+ : non requis (MediaStore / scoped storage)
 // =========================
 private boolean ensureLegacyStoragePermissionForDownloads(boolean prompt) {
     try {
         // Android 10+ : pas besoin
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true;
         // Android 9 et - : WRITE_EXTERNAL_STORAGE runtime (API 23+)
         if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
         int p = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
         if (p == PackageManager.PERMISSION_GRANTED) return true;
         if (!prompt) return false;
         ActivityCompat.requestPermissions(this,
                 new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE},
                 REQ_STORAGE_LEGACY);
         return false;
     } catch (Exception ignored) {
         return false;
     }
 }

private boolean ensureBtConnectPermission() {
        // Android 9: pas de permission runtime; Android 12+: BLUETOOTH_CONNECT.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }

        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 9101);
        return false;
    }

    private void refreshBondedBtList() {
        if (!ensureBtConnectPermission()) {
            if (txtBtStatus != null) txtBtStatus.setText("BT : permission requise (BLUETOOTH_CONNECT)");
            logMedia1("BT Refresh: permission");
            return;
        }

        btBonded.clear();

        if (btAdapter == null) {
            if (txtBtStatus != null) txtBtStatus.setText("BT : non disponible");
            logMedia1("BT Refresh: non dispo");
            return;
        }

        try {
            if (!btAdapter.isEnabled()) {
                if (txtBtStatus != null) txtBtStatus.setText("BT : désactivé — activation requise");
                try {
                    Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    startActivityForResult(i, REQ_ENABLE_BT);
                } catch (Exception ignored) {}
                logMedia1("BT Refresh: BT OFF");
                return;
            }
        } catch (Exception ignored) {}

        try {
            Set<BluetoothDevice> bonded = btAdapter.getBondedDevices();
            if (bonded != null) btBonded.addAll(bonded);
        } catch (Exception e) {
            if (txtBtStatus != null) txtBtStatus.setText("BT : erreur liste appairés");
            logMedia1("BT Refresh: ÉCHEC");
            return;
        }

        List<String> labels = new ArrayList<>();
        for (BluetoothDevice d : btBonded) {
            String name = (d.getName() != null) ? d.getName() : "(no-name)";
            String mac = (d.getAddress() != null) ? d.getAddress() : "";
            labels.add(name + " — " + mac);
        }

        if (btAdapterUi == null) {
            btAdapterUi = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
            btAdapterUi.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            if (spnBtBonded != null) spnBtBonded.setAdapter(btAdapterUi);
        } else {
            btAdapterUi.clear();
            btAdapterUi.addAll(labels);
            btAdapterUi.notifyDataSetChanged();
        }

        if (txtBtStatus != null) txtBtStatus.setText("BT : " + labels.size() + " appareil(s) appairé(s)");
        logMedia1("BT Refresh: appairés=" + btBonded.size());
    }

    private BluetoothDevice getSelectedBonded() {
        if (spnBtBonded == null) return null;
        int idx = spnBtBonded.getSelectedItemPosition();
        if (idx < 0 || idx >= btBonded.size()) return null;
        return btBonded.get(idx);
    }

    private void btConnectSelected() {
        if (!ensureBtConnectPermission()) {
            if (txtBtStatus != null) txtBtStatus.setText("BT : permission requise (BLUETOOTH_CONNECT)");
            logMedia1("BT Connect: permission");
            return;
        }

        final BluetoothDevice dev = getSelectedBonded();
        if (dev == null) {
            if (txtBtStatus != null) txtBtStatus.setText("BT : aucun device sélectionné");
            logMedia1("BT Connect: aucun device");
            return;
        }

        logMedia1("BT Connect: demandé " + dev.getAddress());

        if (txtBtStatus != null) txtBtStatus.setText("BT : connecting…");

        btExec.execute(() -> {
            BluetoothSocket s = null;
            try {
                btDisconnect();

                // ✅ FIX: éviter que la discovery casse le RFCOMM (ret=-1)
                try { if (btAdapter != null) btAdapter.cancelDiscovery(); } catch (Exception ignored) {}

                // ✅ FIX: insecure RFCOMM d'abord (souvent requis sur adaptateurs série),
                // puis fallback secure si nécessaire.
                try {
                    s = dev.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                } catch (Exception insecureNotSupported) {
                    s = dev.createRfcommSocketToServiceRecord(SPP_UUID);
                }

                s.connect();

                btSocket = s;
                btIn = s.getInputStream();
                btOut = s.getOutputStream();

                // ✅ Option A: publish BT connected
                try {
                    lastBtMac = (dev != null ? dev.getAddress() : null);
                    if (mediaTransportManager != null) {
                        mediaTransportManager.onBtConnected(dev, btSocket, btIn, btOut, "BT SPP CONNECTED");
                    }
                } catch (Exception ignored) {}

                if (mediaProfileStore != null) {
                    mediaProfileStore.setActiveBt(dev.getName(), dev.getAddress(), SPP_UUID.toString());
                    mediaProfileStore.setActiveStatus("CONNECTED", null);
                }

                ui.post(() -> {
                    if (txtBtStatus != null) txtBtStatus.setText("BT : CONNECTED — " + dev.getName());
                    updateMediaStatusUi();
                });

                logMedia1("BT Connect: OK " + dev.getAddress());

            } catch (Exception e) {

                try { if (s != null) s.close(); } catch (Exception ignored) {}

                // ✅ Option A: publish BT error
                try {
                    String mac = (dev != null ? dev.getAddress() : lastBtMac);
                    if (mediaTransportManager != null) {
                        mediaTransportManager.onBtError(mac,
                                (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName());
                    }
                } catch (Exception ignored) {}

                if (mediaProfileStore != null) {
                    mediaProfileStore.setActiveBt(dev.getName(), dev.getAddress(), SPP_UUID.toString());
                    mediaProfileStore.setActiveStatus("ERROR",
                            (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName());
                }

                ui.post(() -> {
                    if (txtBtStatus != null) txtBtStatus.setText("BT : FAIL — " +
                            (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                    updateMediaStatusUi();
                });

                logMedia1("BT Connect: ÉCHEC " + dev.getAddress());
            }
        });
    }

    private synchronized void btDisconnect() {
        logMedia1("BT Disconnect: " + (lastBtMac != null ? lastBtMac : "-"));

        // ✅ Option A: publish BT disconnected
        try {
            if (mediaTransportManager != null) {
                mediaTransportManager.onBtDisconnected(lastBtMac, "BT disconnected");
            }
        } catch (Exception ignored) {}

        try { if (btIn != null) btIn.close(); } catch (Exception ignored) {}
        try { if (btOut != null) btOut.close(); } catch (Exception ignored) {}
        try { if (btSocket != null) btSocket.close(); } catch (Exception ignored) {}

        btIn = null;
        btOut = null;
        btSocket = null;

        if (mediaProfileStore != null) {
            try { mediaProfileStore.setActiveStatus("DISCONNECTED", null); } catch (Exception ignored) {}
        }

        lastBtMac = null;

        ui.post(() -> {
            if (txtBtStatus != null) txtBtStatus.setText("BT : DISCONNECTED");
            updateMediaStatusUi();
        });

        logMedia1("BT Disconnect: OK");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
	if (requestCode == REQ_STORAGE_LEGACY) {
		boolean ok = (grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED);
		if (ok) {
			toast("Storage OK (Android 9): accès Downloads accordé");
			logUi(null, "Storage permission granted (Downloads)");
		} else {
			toast("Storage refusé: /Download indisponible (Android 9)");
			logUi(null, "Storage permission denied (Downloads)");
		}
		return;
	}

if (requestCode == 9101) {
            boolean ok = (grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED);
            if (ok) refreshBondedBtList();
            else if (txtBtStatus != null) txtBtStatus.setText("BT : permission refusée");
            if (!ok) logMedia1("BT Permission: refusée");
        }
    }
}
