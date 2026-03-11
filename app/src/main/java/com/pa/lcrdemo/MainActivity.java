
package com.pa.lcrdemo;

import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import android.app.PendingIntent;
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

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MAIN (clean UI) = infrastructure:
 *  - Scan USB + Ouvrir/Ping USB => UNE session UsbSession
 *  - TO/FROM + Ajouter/Focus TAB
 *  - Scan registres (autoritaire) => reset tabs + rebuild
 *  - Tabs registres (RegisterTabFragment) => Connect LCP et livraison dans les tabs
 *  - Log global MAIN (LogBus)
 *
 * API-Face (page) => Start/Stop + Backup DB
 */
public class MainActivity extends AppCompatActivity {

    public static final String ACTION_USB_PERMISSION = "com.pa.lcrdemo.USB_PERMISSION";

    private UsbManager usbManager;
    private final List<UsbDevice> usbDevices = new ArrayList<>();
    private UsbSerialPort usbPort; // cache local (la vérité = UsbSession.getPort())

    // ===== Tabs / Pages (TOP: MAIN / API-Face) =====
    private TabLayout tabLayout;
    private View pageMain;
    private View pageApiFace;

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

    private EditText edtTo;
    private EditText edtFrom;
    private TextView txtActiveNode;
    private Button btnAddRegisterTab;

    // ===================== Scan registres Option B (autoritaire) =====================
    private Button btnScanNodes;
    private Spinner spnNodesFound;
    private TextView txtNodesSummary;
    private boolean nodeUserTouchedSpinner = false;
    private ArrayAdapter<NodeScanItem> nodeAdapter;
    private final List<NodeScanItem> nodeItems = new ArrayList<>();
    private final ExecutorService scanExec = Executors.newSingleThreadExecutor();

    // ===================== Tabs registres =====================
    private TabLayout tabRegisters;
    private View registerContainer;

    // Unicité: 1 tab par lcrnode
    private final LinkedHashMap<Integer, Integer> regNodeToFrom = new LinkedHashMap<>(); // node -> from
    private int currentRegNode = -1;

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

    // =========================================================
    // ✅ API log filtering for polling: first + last only
    // =========================================================
    private final Map<Integer, String> apiRidToPath = new ConcurrentHashMap<>();
    private final Set<Integer> apiFirstJobRid = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<String> apiJobSeen = Collections.newSetFromMap(new ConcurrentHashMap<>());

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

    // ===== USB auto-attach notification (via broadcast interne) =====
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

    // ===== LogBus listener (rafraîchit la vue log global MAIN) =====
    private final LogBus.Listener mainLogListener = e -> refreshGlobalLogView();

    // =========================
    // Lifecycle
    // =========================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        usbManager = (UsbManager) getSystemService(USB_SERVICE);

        bindUi();
        wireUi();
        initUiDefaults();
        setupTabsTop();

        deliveryStore = new DeliveryLogStore(this);
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

        // ✅ Source de vérité = UsbSession
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

    // =========================
    // UI binding
    // =========================
    private void bindUi() {
        tabLayout = findViewById(R.id.tabLayout);
        pageMain = findViewById(R.id.pageMain);
        pageApiFace = findViewById(R.id.pageApiFace);

        btnScanUsb = findViewById(R.id.btnScanUsb);
        btnPingUsb = findViewById(R.id.btnPingUsb);
        spnUsbDevices = findViewById(R.id.spnUsbDevices);

        edtTo = findViewById(R.id.edtTo);
        edtFrom = findViewById(R.id.edtFrom);
        txtActiveNode = findViewById(R.id.txtActiveNode);
        btnAddRegisterTab = findViewById(R.id.btnAddRegisterTab);

        btnScanNodes = findViewById(R.id.btnScanNodes);
        spnNodesFound = findViewById(R.id.spnNodesFound);
        txtNodesSummary = findViewById(R.id.txtNodesSummary);

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

        // API-Face (fragment_api_face.xml est inclus dans pageApiFace)
        txtApiStatus = findViewById(R.id.txtApiStatus);
        txtApiUrl = findViewById(R.id.txtApiUrl);
        btnApiStart = findViewById(R.id.btnApiStart);
        btnApiStop = findViewById(R.id.btnApiStop);
        btnDbBackup = findViewById(R.id.btnDbBackup);

        if (txtApiUrl != null) {
            txtApiUrl.setText("http://127.0.0.1:" + API_PORT);
        }
    }

    private void initUiDefaults() {
        edtTo.setText("250");
        edtFrom.setText("255");

        if (txtNodesSummary != null) txtNodesSummary.setText("Nodes trouvés : —");
        if (txtActiveNode != null) txtActiveNode.setText("Node actif : —");

        // Log global caché par défaut
        if (cbShowLog != null) cbShowLog.setChecked(false);
        if (logPanel != null) logPanel.setVisibility(View.GONE);

        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        boolean showTxRx = prefs.getBoolean("log_tx_rx", false);
        if (cbTxRx != null) cbTxRx.setChecked(showTxRx);

        boolean ts = prefs.getBoolean("log_ts", false);
        logTsEnabled = ts;
        if (cbLogTs != null) cbLogTs.setChecked(ts);

        // Spinner nodes found (défaut 250 placeholder)
        nodeItems.clear();
        nodeItems.add(NodeScanItem.default250());
        nodeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, nodeItems);
        nodeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        if (spnNodesFound != null) spnNodesFound.setAdapter(nodeAdapter);

        // Tab par défaut 250 (placeholder)
        ensureRegisterTab(250, 255, true);

        mainLogViewSinceMs = 0L;
        refreshGlobalLogView();
    }

    private void wireUi() {
        btnScanUsb.setOnClickListener(v -> scanUsb());
        btnPingUsb.setOnClickListener(v -> openSelectedUsb());

        // Ajouter / Focus TAB depuis TO manuel
        if (btnAddRegisterTab != null) {
            btnAddRegisterTab.setOnClickListener(v -> {
                int to = parseInt(edtTo.getText().toString(), 250);
                int from = parseInt(edtFrom.getText().toString(), 255);
                ensureRegisterTab(to, from, true);
            });
        }

        // Scan registres (autoritaire)
        if (btnScanNodes != null) btnScanNodes.setOnClickListener(v -> scanRegistersOptionB());

        // Spinner nodes scan: sélection -> remplit edtTo (sans auto-connect)
        if (spnNodesFound != null) {
            spnNodesFound.setOnTouchListener((v, e) -> {
                if (e.getAction() == MotionEvent.ACTION_DOWN) nodeUserTouchedSpinner = true;
                return false;
            });
            spnNodesFound.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                    if (!nodeUserTouchedSpinner) return;
                    nodeUserTouchedSpinner = false;
                    NodeScanItem it = (NodeScanItem) spnNodesFound.getSelectedItem();
                    if (it == null) return;
                    edtTo.setText(String.valueOf(it.lcrnode));
                    logUi(null, "TO sélectionné via scan: " + it.lcrnode + " (cliquer Ajouter/Focus TAB)");
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        // Tabs registres: sélection -> afficher fragment + maj “Node actif”
        if (tabRegisters != null) {
            tabRegisters.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
                @Override public void onTabSelected(TabLayout.Tab tab) {
                    Object tag = tab.getTag();
                    if (tag instanceof Integer) showRegisterFragment((Integer) tag);
                }
                @Override public void onTabUnselected(TabLayout.Tab tab) {}
                @Override public void onTabReselected(TabLayout.Tab tab) {
                    Object tag = tab.getTag();
                    if (tag instanceof Integer) showRegisterFragment((Integer) tag);
                }
            });
        }

        // Log global toggle
        if (cbShowLog != null) {
            cbShowLog.setOnCheckedChangeListener((buttonView, checked) -> {
                if (logPanel != null) logPanel.setVisibility(checked ? View.VISIBLE : View.GONE);
                logUi(null, "Option Afficher log global: " + (checked ? "ON" : "OFF"));
                if (checked) refreshGlobalLogView();
            });
        }

        // Log global actions (clear = clear vue MAIN seulement)
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
                logUi(null, "Option TX/RX (vue MAIN): " + (checked ? "ON" : "OFF"));
                refreshGlobalLogView();
            });
        }

        if (cbLogTs != null) {
            cbLogTs.setOnCheckedChangeListener((buttonView, checked) -> {
                SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
                prefs.edit().putBoolean("log_ts", checked).apply();
                logTsEnabled = checked;
                logUi(null, "Option timestamps (UI+IO+API): " + (checked ? "ON" : "OFF"));
                refreshGlobalLogView();
            });
        }

        // API tab buttons
        if (btnApiStart != null) btnApiStart.setOnClickListener(v -> startApiServer());
        if (btnApiStop != null) btnApiStop.setOnClickListener(v -> stopApiServer("Stop button"));
        if (btnDbBackup != null) {
            btnDbBackup.setOnClickListener(v -> doBackupDb());
            btnDbBackup.setOnLongClickListener(v -> { requestBackupDir(); return true; });
        }
    }

    // =========================
    // Tabs TOP (MAIN / API-Face)
    // =========================
    private void setupTabsTop() {
        if (tabLayout == null) return;
        tabLayout.removeAllTabs();
        tabLayout.addTab(tabLayout.newTab().setText("MAIN"), true);
        tabLayout.addTab(tabLayout.newTab().setText("API-Face"), false);
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
        if (index == 1) refreshApiStatus();
    }

    // =========================
    // Register tabs helpers
    // =========================
    private void ensureRegisterTab(int node, int from, boolean focus) {
        if (node < 1 || node > 250) {
            logUi(null, "TAB registre: node invalide: " + node);
            return;
        }
        if (from < 0 || from > 255) from = 255;

        if (!regNodeToFrom.containsKey(node)) {
            regNodeToFrom.put(node, from);
            addRegisterTabUi(node);
            logUi(null, "TAB registre ajouté: " + node);
        } else {
            logUi(null, "TAB registre déjà présent: " + node + " (focus)");
        }

        if (focus) {
            selectRegisterTab(node);
            showRegisterFragment(node);
        } else if (currentRegNode < 0) {
            selectRegisterTab(node);
            showRegisterFragment(node);
        }
    }

    private void addRegisterTabUi(int node) {
        if (tabRegisters == null) return;
        TabLayout.Tab t = tabRegisters.newTab();
        t.setText(String.valueOf(node));
        t.setTag(node);
        tabRegisters.addTab(t, false);
    }

    private void selectRegisterTab(int node) {
        if (tabRegisters == null) return;
        for (int i = 0; i < tabRegisters.getTabCount(); i++) {
            TabLayout.Tab t = tabRegisters.getTabAt(i);
            if (t != null && t.getTag() instanceof Integer && ((Integer) t.getTag()) == node) {
                t.select();
                return;
            }
        }
    }

    private void showRegisterFragment(int node) {
        if (registerContainer == null) return;

        currentRegNode = node;
        int from = regNodeToFrom.containsKey(node) ? regNodeToFrom.get(node) : 255;

        if (txtActiveNode != null) txtActiveNode.setText("Node actif : " + node);

        FragmentManager fm = getSupportFragmentManager();
        String tag = "regtab_" + node;
        Fragment existing = fm.findFragmentByTag(tag);
        Fragment f = (existing != null) ? existing : RegisterTabFragment.newInstance(node, from);

        FragmentTransaction tx = fm.beginTransaction();
        tx.replace(R.id.registerContainer, f, tag);
        tx.setReorderingAllowed(true);
        tx.commitAllowingStateLoss();
    }

    /**
     * Scan toujours autoritaire: purge tabs + mapping + fragments regtab_*,
     * puis rebuild uniquement à partir des nodes détectés.
     */
    private void clearAllRegisterTabsAndFragments() {
        regNodeToFrom.clear();
        currentRegNode = -1;

        if (tabRegisters != null) tabRegisters.removeAllTabs();

        try {
            FragmentManager fm = getSupportFragmentManager();
            FragmentTransaction tx = fm.beginTransaction();
            for (Fragment f : fm.getFragments()) {
                if (f == null) continue;
                String tag = f.getTag();
                if (tag != null && tag.startsWith("regtab_")) {
                    tx.remove(f);
                }
            }
            tx.commitAllowingStateLoss();
        } catch (Exception ignored) {}
    }

    // =========================
    // Scan registres Option B (0x28 + #80 + #23) - AUTORITAIRE
    // =========================
    private void scanRegistersOptionB() {
        // ✅ Source de vérité = UsbSession (tabs utilisent UsbSession.getPort())
        final UsbSerialPort p = (UsbSession.getPort() != null) ? UsbSession.getPort() : usbPort;

        if (p == null) {
            logUi(null, "Scan registres: USB non prêt (port null). Utilise Ouvrir/Ping USB ou auto-attach.");
            toast("Scan registres: USB non prêt");
            return;
        }

        if (btnScanNodes != null) btnScanNodes.setEnabled(false);
        if (txtNodesSummary != null) txtNodesSummary.setText("Nodes trouvés : scan en cours...");

        scanExec.execute(() -> {
            LinkedHashMap<Integer, NodeScanItem> found = new LinkedHashMap<>();
            final int T28 = 300;
            final int TF = 300;

            for (int node = 1; node <= 250; node++) {
                try {
                    LcpLink tmp = new LcpLink(p, node, 255, true);
                    int[] ds = tmp.opDeliveryStatus(T28);
                    int delCode = ds[1];

                    boolean ticketPending = (delCode & 0x0001) != 0;
                    boolean flowActive = (delCode & 0x0004) != 0;
                    boolean deliveryActive = (delCode & 0x0008) != 0;

                    String serialId = decodeAz(tmp.opGetField(80, TF));
                    String ticketNo = u32beDec(tmp.opGetField(23, TF));

                    found.put(node, new NodeScanItem(node, serialId, ticketNo, ticketPending, deliveryActive, flowActive, false));
                } catch (Exception ignored) {}
            }

            ui.post(() -> {
                try {
                    nodeItems.clear();

                    // ✅ scan autoritaire = reset complet
                    clearAllRegisterTabsAndFragments();

                    if (found.isEmpty()) {
                        nodeItems.add(NodeScanItem.default250());
                        if (txtNodesSummary != null) txtNodesSummary.setText("Nodes trouvés : aucun (défaut 250)");
                        if (nodeAdapter != null) nodeAdapter.notifyDataSetChanged();

                        ensureRegisterTab(250, 255, true);
                        edtTo.setText("250");
                        if (txtActiveNode != null) txtActiveNode.setText("Node actif : 250");

                        logUi(null, "Scan registres: aucun trouvé -> reset + fallback tab 250");
                        return;
                    }

                    // default = 250 si présent, sinon premier trouvé
                    NodeScanItem defaultItem;
                    if (found.containsKey(250)) {
                        defaultItem = found.get(250).asDefault();
                        found.remove(250);
                    } else {
                        Map.Entry<Integer, NodeScanItem> first = found.entrySet().iterator().next();
                        defaultItem = first.getValue().asDefault();
                        found.remove(first.getKey());
                    }

                    nodeItems.add(defaultItem);
                    for (NodeScanItem it : found.values()) nodeItems.add(it);

                    if (txtNodesSummary != null) txtNodesSummary.setText("Nodes trouvés : " + nodeItems.size());
                    if (nodeAdapter != null) nodeAdapter.notifyDataSetChanged();

                    int defaultNode = defaultItem.lcrnode;

                    ensureRegisterTab(defaultNode, 255, true);
                    for (NodeScanItem it : nodeItems) {
                        if (it.lcrnode != defaultNode) ensureRegisterTab(it.lcrnode, 255, false);
                    }

                    edtTo.setText(String.valueOf(defaultNode));
                    if (txtActiveNode != null) txtActiveNode.setText("Node actif : " + defaultNode);

                    logUi(null, "Scan registres terminé: " + nodeItems.size()
                            + " node(s), default=" + defaultNode + " (scan autoritaire)");

                } finally {
                    if (btnScanNodes != null) btnScanNodes.setEnabled(true);
                }
            });
        });
    }

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

    /**
     * Ouvrir/Ping USB:
     *  - Ouvre le port série
     *  - ✅ Publie le port dans UsbSession (source de vérité globale)
     *  - Les tabs ne gèrent PAS le port: ils consomment UsbSession.getPort()
     */
    private void openSelectedUsb() {
        // Si UsbSession a déjà un port, on le considère prêt
        UsbSerialPort sessionPort = UsbSession.getPort();
        if (sessionPort != null) {
            if (usbPort == null) usbPort = sessionPort;
            logUi(null, "USB déjà prêt (UsbSession port déjà ouvert)");
            return;
        }
        if (usbPort != null) {
            logUi(null, "USB déjà prêt (port déjà ouvert)");
            // sécurité: publier si jamais UsbSession n'était pas set
            UsbDevice dev = (usbDevices.isEmpty() ? null : getSelectedUsbDeviceSafe());
            if (dev != null) UsbSession.set(dev, usbPort);
            return;
        }

        UsbDevice dev = getSelectedUsbDeviceSafe();
        if (dev == null) {
            logUi(null, "Aucun périphérique USB sélectionné");
            return;
        }

        if (!usbManager.hasPermission(dev)) {
            PendingIntent pi = PendingIntent.getBroadcast(
                    this, 0, new Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
            );
            usbManager.requestPermission(dev, pi);
            logUi(null, "Permission USB demandée");
            return;
        }

        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(dev);
        if (driver == null) {
            logUi(null, "Driver USB série introuvable");
            return;
        }

        try {
            UsbDeviceConnection conn = usbManager.openDevice(dev);
            UsbSerialPort port = driver.getPorts().get(0);
            port.open(conn);
            port.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

            usbPort = port;

            // ✅ PATCH IMPORTANT : publication globale pour les tabs
            UsbSession.set(dev, port);

            logUi(null, "USB prêt");
        } catch (Exception e) {
            logErr(null, "Open USB ERR: " + safeMsg(e));
            try { if (usbPort != null) usbPort.close(); } catch (Exception ignored) {}
            usbPort = null;
            try { UsbSession.clear(); } catch (Exception ignored) {}
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

        // Si on avait déjà un port local, on n’en remplace pas un autre
        if (usbPort != null) {
            // Si un port "receiver" arrive alors qu'on en a déjà un, on tente de le fermer
            try { port.close(); } catch (Exception ignore) {}
            return;
        }

        usbPort = port;
        logUi(null, "USB prêt (receiver)");
    }

    public void onUsbDetached() {
        logUi(null, "USB détaché");

        try { UsbSession.clear(); } catch (Exception ignore) {}
        stopApiServer("USB detached");

        // clear sessions multi-node (UI+API)
        try { RegisterSessionManager.get(this).clearAll(true); } catch (Exception ignored) {}

        usbPort = null;

        // Reset UI nodes list + tabs
        nodeItems.clear();
        nodeItems.add(NodeScanItem.default250());
        if (nodeAdapter != null) nodeAdapter.notifyDataSetChanged();
        if (txtNodesSummary != null) txtNodesSummary.setText("Nodes trouvés : —");

        clearAllRegisterTabsAndFragments();
        ensureRegisterTab(250, 255, true);
        if (txtActiveNode != null) txtActiveNode.setText("Node actif : 250");
    }

    // =========================
    // API Server (B2)
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
                if (isJobDoneRespLine(line)) {
                    logApi(node, line);
                }
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

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            String name = "lcr_delivery_" + utcStamp() + ".db";
            deliveryStore.backupDbToDownloadsAsync(this, name, (ok, fileName, detail) -> {
                if (ok) toast("Backup OK (Downloads): " + fileName);
                else toast("Backup FAIL: " + fileName + " " + detail);
            });
        } else {
            requestBackupDir();
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
        if (requestCode == REQ_PICK_BACKUP_DIR) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                toast("Backup: sélection de dossier annulée");
                return;
            }
            Uri dirUri = data.getData();
            final int takeFlags = data.getFlags() & (
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
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
    private void refreshGlobalLogView() {
        if (txtLog == null) return;
        if (cbShowLog != null && !cbShowLog.isChecked()) return;

        boolean includeIo = (cbTxRx != null && cbTxRx.isChecked());
        String text = LogBus.buildText(LogBus.filterGlobalUIIOAPI(includeIo, mainLogViewSinceMs), 1400);
        txtLog.setText(text);
    }

    private void logUi(Integer node, String msg) {
        if (msg == null) return;
        LogBus.ui(node, maybeUiTimestamp(msg));
    }

    private void logApi(Integer node, String msg) {
        if (msg == null) return;
        LogBus.api(node, msg);
    }

    private void logErr(Integer node, String msg) {
        if (msg == null) return;
        LogBus.err(node, msg);
    }

    private String maybeUiTimestamp(String line) {
        if (!logTsEnabled) return line;
        boolean isIoLine = line.startsWith("[IO ") || line.startsWith("TX:") || line.startsWith("RX:") || line.startsWith("↳");
        boolean isApiLine = line.startsWith("[API ") || line.startsWith("[API]");
        if (isIoLine || isApiLine) return line;
        return "[UI " + uiTs() + "] " + line;
    }

    private String uiTs() {
        SimpleDateFormat df = new SimpleDateFormat("HH:mm:ss.SSS", Locale.CANADA_FRENCH);
        return df.format(new Date(System.currentTimeMillis()));
    }

    // =========================
    // Utils
    // =========================
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
}
