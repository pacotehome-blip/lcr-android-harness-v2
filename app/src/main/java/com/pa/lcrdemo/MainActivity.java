
package com.pa.lcrdemo;

import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.*;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;

import com.google.android.material.tabs.TabLayout;
import com.hoho.android.usbserial.driver.*;
import com.pa.lcr.lcp.*;
import com.pa.lcr.lcp.storage.DeliveryDb;
import com.pa.lcr.lcp.storage.DeliveryLogStore;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends AppCompatActivity {

    public static final String ACTION_USB_PERMISSION = "com.pa.lcrdemo.USB_PERMISSION";

    private UsbManager usbManager;
    private final List<UsbDevice> usbDevices = new ArrayList<>();
    private UsbSerialPort usbPort;

    // ===== Tabs / Pages =====
    private TabLayout tabLayout;
    private View pageMain;
    private View pageApiFace;

    // ===== API-Face UI (sans trace UI) =====
    private TextView txtApiStatus;
    private TextView txtApiUrl;
    private Button btnApiStart;
    private Button btnApiStop;
    private Button btnDbBackup;

    // ===== API runtime =====
    private static final int API_PORT = 8765;
    private ApiServer apiServer;
    private DeliveryLogStore deliveryStore;

    // UI refs (pageMain)
    private Spinner spnUsbDevices;
    private Button btnScanUsb;
    private Button btnPingUsb;
    private EditText edtTo;
    private EditText edtFrom;
    private TextView txtActiveNode;
    private Button btnConnect;
    private Spinner spnProducts;
    private EditText edtProduct;
    private EditText edtPreset;

    private Button btnA, btnB, btnC;
    private Button btnContinue, btnFinish;

    private TextView txtLive;
    private View liveQtyPanel;
    private TextView txtQtyNet;
    private TextView txtQtyGross;

    private TextView txtTicketNumber;
    private TextView txtDeliveryUid; // affiché même si null -> "-"

    private TextView txtLog;
    private ScrollView logScroll;

    private Button btnClearLog;
    private Button btnCopyLog;
    private Button btnScrollDown;

    private CheckBox cbTxRx;
    private CheckBox cbLogTs;

    private boolean logTsEnabled = false;

    private DeliveryControllerPort controller;
    private LcpLink link = null;

    private boolean suppressProductSelection = false;
    private boolean userTouchedSpinner = false;

    private final StringBuilder logBuf = new StringBuilder(32768);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean liveTickRunning = false;

    private double lastNet = Double.NaN;
    private double lastGross = Double.NaN;

    private Runnable pendingInitRunnable = null;

    // ✅ Python parity: poll = 0.2s
    private static final int LIVE_POLL_MS = 200;

    // Backup (SAF folder picker)
    private static final int REQ_PICK_BACKUP_DIR = 9102;
    private static final String PREF_BACKUP_DIR_URI = "backup_dir_uri";

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

    // ===== USB auto-attach notification (via broadcast interne) =====
    private final BroadcastReceiver usbUiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
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

    private final Runnable liveTick = new Runnable() {
        @Override
        public void run() {
            if (controller == null) {
                liveTickRunning = false;
                return;
            }
            DeliveryState st = controller.getState();
            boolean shouldPoll =
                    (st == DeliveryState.RUNNING_FLOWING) ||
                    (st == DeliveryState.RUNNING_PAUSED);

            if (!shouldPoll) {
                liveTickRunning = false;
                return;
            }
            controller.requestLiveSample();
            ui.postDelayed(this, LIVE_POLL_MS);
        }
    };

    private void startLiveTickIfNeeded() {
        if (controller == null) return;
        if (liveTickRunning) return;
        DeliveryState st = controller.getState();
        boolean shouldPoll =
                (st == DeliveryState.RUNNING_FLOWING) ||
                (st == DeliveryState.RUNNING_PAUSED);
        if (!shouldPoll) return;
        liveTickRunning = true;
        ui.removeCallbacks(liveTick);
        ui.postDelayed(liveTick, LIVE_POLL_MS);
    }

    private void stopLiveTick() {
        liveTickRunning = false;
        ui.removeCallbacks(liveTick);
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
                    Intent.FLAG_GRANT_READ_URI_PERMISSION |
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            );
            try {
                getContentResolver().takePersistableUriPermission(dirUri, takeFlags);
            } catch (Exception ignored) {}

            saveBackupDirUri(dirUri);
            toast("Backup: dossier enregistré");
            backupDbToChosenDir(dirUri);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter();
        f.addAction(UsbReceiver.ACTION_USB_READY);
        f.addAction(UsbReceiver.ACTION_USB_DETACHED);
        registerReceiver(usbUiReceiver, f);

        UsbSerialPort p = UsbSession.getPort();
        if (p != null && usbPort == null) {
            onUsbPortReady(p);
        }
    }

    @Override
    protected void onStop() {
        try { unregisterReceiver(usbUiReceiver); } catch (Exception ignored) {}
        super.onStop();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        usbManager = (UsbManager) getSystemService(USB_SERVICE);

        bindUi();
        wireUi();
        initUiDefaults();
        setupTabs();

        deliveryStore = new DeliveryLogStore(this);
        deliveryStore.purgeOlderThanDaysAsync(7);

        refreshApiStatus();
        log("UI prête — Scan USB requis");
    }

    @Override
    protected void onDestroy() {
        stopApiServer("Activity destroyed");
        super.onDestroy();
    }

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

        btnConnect = findViewById(R.id.btnConnect);

        spnProducts = findViewById(R.id.spnProducts);
        edtProduct = findViewById(R.id.edtProduct);
        edtPreset = findViewById(R.id.edtPreset);

        btnA = findViewById(R.id.btnA);
        btnB = findViewById(R.id.btnB);
        btnC = findViewById(R.id.btnC);

        btnContinue = findViewById(R.id.btnContinue);
        btnFinish = findViewById(R.id.btnFinish);

        txtLive = findViewById(R.id.txtLive);
        liveQtyPanel = findViewById(R.id.liveQtyPanel);
        txtQtyNet = findViewById(R.id.txtQtyNet);
        txtQtyGross = findViewById(R.id.txtQtyGross);

        txtTicketNumber = findViewById(R.id.txtTicketNumber);
        try { txtDeliveryUid = findViewById(R.id.txtDeliveryUid); } catch (Exception ignored) {}

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

        if (txtApiUrl != null) {
            txtApiUrl.setText("http://127.0.0.1:" + API_PORT);
        }
    }

    private void initUiDefaults() {
        edtTo.setText("250");
        edtFrom.setText("255");

        txtActiveNode.setText("Node actif : —");
        txtLive.setText("LIVE: (en attente)");

        if (liveQtyPanel != null) liveQtyPanel.setVisibility(View.VISIBLE);
        if (txtQtyNet != null) txtQtyNet.setText("NET: 0.0");
        if (txtQtyGross != null) txtQtyGross.setText("GROSS: 0.0");

        if (txtTicketNumber != null) txtTicketNumber.setText("-");
        if (txtDeliveryUid != null) txtDeliveryUid.setText("-");

        List<ProductUiItem> products = new ArrayList<>();
        for (int i = 1; i <= 16; i++) products.add(new ProductUiItem(i, "Produit " + i));

        ArrayAdapter<ProductUiItem> adapter =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, products);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnProducts.setAdapter(adapter);
        spnProducts.setSelection(0);

        edtPreset.setText("50");

        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        boolean showTxRx = prefs.getBoolean("log_tx_rx", false);
        if (cbTxRx != null) cbTxRx.setChecked(showTxRx);

        boolean ts = prefs.getBoolean("log_ts", false);
        logTsEnabled = ts;
        if (cbLogTs != null) cbLogTs.setChecked(ts);
    }

    private void wireUi() {
        btnScanUsb.setOnClickListener(v -> scanUsb());
        btnPingUsb.setOnClickListener(v -> openSelectedUsb());
        btnConnect.setOnClickListener(v -> connectLcp());

        spnProducts.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) userTouchedSpinner = true;
            return false;
        });

        spnProducts.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (controller == null) return;
                if (suppressProductSelection) return;
                if (!userTouchedSpinner) return;
                userTouchedSpinner = false;

                ProductUiItem it = (ProductUiItem) spnProducts.getSelectedItem();
                controller.selectProduct(it.product1);
                edtProduct.setText(String.valueOf(it.product1));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnA.setOnClickListener(v -> { if (controller != null) controller.alignOrRecover(); });
        btnB.setOnClickListener(v -> { if (controller != null) controller.requestStatus(); });
        btnC.setOnClickListener(v -> { if (controller != null) controller.startDelivery(readProduct(), readPreset()); });

        btnContinue.setOnClickListener(v -> { if (controller != null) controller.resumeIfPaused(); });
        btnFinish.setOnClickListener(v -> { if (controller != null) controller.endDelivery(); });

        btnClearLog.setOnClickListener(v -> { logBuf.setLength(0); txtLog.setText(""); });

        btnCopyLog.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("log", txtLog.getText()));
            log("Log copié dans le presse-papiers");
        });

        if (btnScrollDown != null) {
            btnScrollDown.setOnClickListener(v -> logScroll.fullScroll(View.FOCUS_DOWN));
        }

        if (cbTxRx != null) {
            cbTxRx.setOnCheckedChangeListener((buttonView, checked) -> {
                SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
                prefs.edit().putBoolean("log_tx_rx", checked).apply();
                if (controller != null) controller.setTxRxLoggingEnabled(checked);
                log("Option TX/RX: " + (checked ? "ON" : "OFF"));
            });
        }

        if (cbLogTs != null) {
            cbLogTs.setOnCheckedChangeListener((buttonView, checked) -> {
                SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
                prefs.edit().putBoolean("log_ts", checked).apply();
                logTsEnabled = checked;
                if (controller != null) controller.setLogTimestampsEnabled(checked);
                if (link != null) link.setTraceTimestampsEnabled(checked);
                log("Option timestamps (UI+IO): " + (checked ? "ON" : "OFF"));
            });
        }

        if (btnApiStart != null) btnApiStart.setOnClickListener(v -> startApiServer());
        if (btnApiStop != null) btnApiStop.setOnClickListener(v -> stopApiServer("Stop button"));

        if (btnDbBackup != null) {
            btnDbBackup.setOnClickListener(v -> doBackupDb());
            btnDbBackup.setOnLongClickListener(v -> {
                requestBackupDir();
                return true;
            });
        }
    }

    // =========================
    // Tabs
    // =========================
    private void setupTabs() {
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
    // API Server
    // =========================
    private void startApiServer() {
        if (apiServer != null && apiServer.isRunning()) {
            log("[API " + uiTs() + "] déjà RUNNING");
            refreshApiStatus();
            return;
        }

        try {
            ApiFacade facade;
            if (controller instanceof DeliveryController) {
                DeliveryController dc = (DeliveryController) controller;
                facade = new DeliveryApiFacadeImpl(dc, this);
            } else {
                facade = new HeadlessApiFacade(this);
            }

            apiServer = new ApiServer(facade, this::onApiLine, API_PORT);
            apiServer.start();
            refreshApiStatus();
            toast("API démarrée (127.0.0.1:" + API_PORT + ")");
        } catch (Exception e) {
            log("[API " + uiTs() + "] START FAIL: " + safeMsg(e));
            refreshApiStatus();
            toast("API start error: " + safeMsg(e));
        }
    }

    private void stopApiServer(String reason) {
        try {
            if (apiServer != null && apiServer.isRunning()) {
                apiServer.stop();
                log("[API " + uiTs() + "] STOP (" + reason + ")");
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

            String jobId = extractJobIdFromPath(path);
            if (jobId != null) {
                if (apiJobSeen.contains(jobId)) return;
                apiJobSeen.add(jobId);
                apiFirstJobRid.add(rid);
                log(line);
                return;
            }
            log(line);
            return;
        }

        if (rid != null && isResp) {
            String path = apiRidToPath.remove(rid);
            String jobId = extractJobIdFromPath(path);
            if (jobId != null) {
                if (apiFirstJobRid.remove(rid)) {
                    log(line);
                    return;
                }
                if (isJobDoneRespLine(line)) {
                    log(line);
                }
                return;
            }
            log(line);
            return;
        }

        log(line);
    }

    // =========================
    // Headless API facade (no controller required)
    // =========================
    private static final class HeadlessApiFacade implements ApiFacade {
        private final Context appCtx;
        private final UsbManager usbManager;
        private final DeliveryLogStore store;

        HeadlessApiFacade(Context ctx) {
            this.appCtx = ctx.getApplicationContext();
            this.usbManager = (UsbManager) this.appCtx.getSystemService(Context.USB_SERVICE);
            this.store = new DeliveryLogStore(this.appCtx);
            this.store.purgeOlderThanDaysAsync(7);
        }

        @Override public ApiResult api_scanUsb() {
            try {
                int n = (usbManager != null) ? usbManager.getDeviceList().size() : 0;
                JSONObject d = new JSONObject();
                d.put("usb_devices", n);
                return (n > 0)
                        ? ApiResult.ok("Scan USB: 1 - Registre détecté (USB device présent)", d)
                        : ApiResult.fail("Scan USB: 0 - Aucun périphérique USB détecté.", "ERR_MEDIA_NOT_PRESENT", d);
            } catch (Exception e) {
                return ApiResult.fail("Scan USB: 0 - Failed", "ERR_MEDIA_NOT_PRESENT");
            }
        }

        @Override public ApiResult api_openPingUsb() {
            UsbSerialPort p = UsbSession.getPort();
            if (p == null) {
                return ApiResult.fail("Open/Ping USB: 0 - USB non prêt (port null).", "ERR_USB_PORT_NOT_READY");
            }
            return ApiResult.ok("Open/Ping USB: 1 - USB prêt (port ouvert)");
        }

        @Override public ApiResult api_connectLcp() {
            UsbSerialPort p = UsbSession.getPort();
            if (p == null) return ApiResult.fail("Connect LCP: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
            try {
                LcpLink tmp = new LcpLink(p, 250, 255, true);
                int[] ds = tmp.opDeliveryStatus();
                int delCode = ds[1];
                boolean ticketPending = (delCode & 0x0001) != 0;
                boolean flowActive = (delCode & 0x0004) != 0;
                boolean deliveryActive = (delCode & 0x0008) != 0;

                JSONObject data = new JSONObject();
                data.put("deliveryActive", deliveryActive ? 1 : 0);
                data.put("flowActive", flowActive ? 1 : 0);
                data.put("ticketPending", ticketPending ? 1 : 0);
                data.put("next", (!deliveryActive && !ticketPending) ? "C" : "A");

                return ApiResult.ok("Connect LCP: 1 - CONNECTED (headless)", data);
            } catch (Exception e) {
                JSONObject d = new JSONObject();
                try { d.put("detail", (e.getMessage() != null) ? e.getMessage() : ""); } catch (Exception ignored) {}
                return ApiResult.fail("Connect LCP: 0 - Failed", "ERR_LCP_CONNECT_FAILED", d);
            }
        }

        @Override public ApiResult api_deliveryAlignA() {
            return ApiResult.fail("Align A: 0 - Non disponible sans controller UI (headless minimal).", "NO_CONTROLLER");
        }

        @Override public ApiResult api_dbDump() {
            try {
                String name = "lcr_delivery_" + utcStamp() + ".json";
                boolean ok = store.dumpJsonToDownloads(appCtx, name);
                if (!ok) return ApiResult.fail("DB Dump: 0 - Failed", "DB_DUMP_FAIL");
                JSONObject d = new JSONObject();
                d.put("fileName", name);
                return ApiResult.ok("DB Dump: 1 - OK", d);
            } catch (Exception e) {
                JSONObject d = new JSONObject();
                try { d.put("detail", (e.getMessage() != null) ? e.getMessage() : ""); } catch (Exception ignored) {}
                return ApiResult.fail("DB Dump: 0 - Failed", "DB_DUMP_FAIL", d);
            }
        }

        @Override public ApiResult api_deliveryStartC(int product1to16, double presetNet) {
            return ApiResult.fail("Delivery C: 0 - Non disponible sans controller UI (headless minimal).", "NO_CONTROLLER");
        }

        @Override public ApiResult api_deliveryJobGet(String jobId) {
            return ApiResult.fail("Job: 0 - Non disponible sans controller UI (headless minimal).", "NO_CONTROLLER");
        }

        @Override public ApiResult api_deliveryOneShotStart(String numero_livraison, int product1to16, double presetNetL, String compartment) {
            return ApiResult.fail("OneShot: 0 - Non disponible sans controller UI (headless minimal).", "NO_CONTROLLER");
        }

        @Override public ApiResult api_deliveryContinue(String jobId) {
            return ApiResult.fail("Continue: 0 - Non disponible sans controller UI (headless minimal).", "NO_CONTROLLER");
        }

        @Override public ApiResult api_deliveryTerminate(String jobId) {
            return ApiResult.fail("Terminate: 0 - Non disponible sans controller UI (headless minimal).", "NO_CONTROLLER");
        }

        // =========================================================
        // ✅ COMMIT 3: headless validateRegister (utilise RegisterValidator)
        // =========================================================
        @Override
        public ApiResult api_registerValidate(String numero_livraison,
                                             Integer expected_lcrnode_dec,
                                             String expected_serial_id,
                                             Integer expected_product_number,
                                             String expected_compartment) {

            // 1) Média présent ?
            int n = 0;
            try { n = (usbManager != null) ? usbManager.getDeviceList().size() : 0; } catch (Exception ignored) {}
            if (n <= 0) {
                JSONObject d = new JSONObject();
                try { d.put("usb_devices", 0); } catch (Exception ignored) {}
                return ApiResult.fail("Validate: 0 - Média RS-232 absent (aucun USB device).",
                        RegisterValidator.Codes.ERR_LCP_CONNECT_FAILED, d);
            }

            // 2) Port prêt ?
            UsbSerialPort p = UsbSession.getPort();
            if (p == null) {
                return ApiResult.fail("Validate: 0 - USB non prêt (port null).",
                        RegisterValidator.Codes.ERR_USB_PORT_NOT_READY);
            }

            // 3) TO / FROM
            int to = (expected_lcrnode_dec != null && expected_lcrnode_dec >= 1 && expected_lcrnode_dec <= 250)
                    ? expected_lcrnode_dec
                    : 250;
            int from = 255;

            // 4) Validation LCP (0x28 + #23 + #80 + #0 + delivery_uid)
            return RegisterValidator.validateLcp(
                    p,
                    to,
                    from,
                    numero_livraison,
                    expected_serial_id,
                    expected_product_number,
                    expected_compartment
            );
        }

        private static String utcStamp() {
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss'Z'", Locale.ROOT);
            df.setTimeZone(TimeZone.getTimeZone("UTC"));
            return df.format(new Date());
        }
    }

    // =========================
    // Backup DB
    // =========================
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

            if (deliveryStore != null) {
                deliveryStore.checkpointWalBestEffort();
            }

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

    private void toast(String s) {
        ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }

    private static String safeMsg(Exception e) {
        if (e == null) return "";
        String m = e.getMessage();
        return (m == null) ? e.getClass().getSimpleName() : m;
    }

    // =========================
    // USB
    // =========================
    private void scanUsb() {
        usbDevices.clear();
        usbDevices.addAll(usbManager.getDeviceList().values());
        log("Scan USB: " + usbDevices.size() + " périphérique(s)");

        List<String> labels = new ArrayList<>();
        for (UsbDevice d : usbDevices) {
            String m = d.getManufacturerName();
            String p = d.getProductName();
            if (m == null) m = "Unknown";
            if (p == null) p = "Device";
            labels.add(m + " - " + p);
            log(String.format(" - %s - %s (VID=%04X PID=%04X)", m, p, d.getVendorId(), d.getProductId()));
        }
        spnUsbDevices.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels));
    }

    private void openSelectedUsb() {
        if (usbPort != null) {
            log("USB déjà prêt (port déjà ouvert)");
            return;
        }
        int idx = spnUsbDevices.getSelectedItemPosition();
        if (idx < 0 || idx >= usbDevices.size()) {
            log("Aucun périphérique USB sélectionné");
            return;
        }
        UsbDevice dev = usbDevices.get(idx);

        if (!usbManager.hasPermission(dev)) {
            PendingIntent pi = PendingIntent.getBroadcast(
                    this, 0, new Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
            );
            usbManager.requestPermission(dev, pi);
            return;
        }

        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(dev);
        if (driver == null) {
            log("Driver USB série introuvable");
            return;
        }

        try {
            UsbDeviceConnection conn = usbManager.openDevice(dev);
            UsbSerialPort port = driver.getPorts().get(0);
            port.open(conn);
            port.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            usbPort = port;
            log("USB prêt");
        } catch (Exception e) {
            log("Open USB ERR: " + e.getMessage());
        }
    }

    public void onUsbPortReady(UsbSerialPort port) {
        if (port == null) return;
        if (usbPort != null) {
            try { port.close(); } catch (Exception ignore) {}
            return;
        }
        usbPort = port;
        log("USB prêt (receiver)");
    }

    public void onUsbDetached() {
        log("USB détaché");
        try { UsbSession.clear(); } catch (Exception ignore) {}
        stopApiServer("USB detached");
        if (controller != null) {
            controller.shutdown(true);
            controller = null;
        }
        link = null;
        stopLiveTick();
        usbPort = null;
        txtActiveNode.setText("Node actif : —");
        txtLive.setText("LIVE: (en attente)");
        if (txtQtyNet != null) txtQtyNet.setText("NET: 0.0");
        if (txtQtyGross != null) txtQtyGross.setText("GROSS: 0.0");
        if (liveQtyPanel != null) liveQtyPanel.setVisibility(View.VISIBLE);
        if (txtTicketNumber != null) txtTicketNumber.setText("-");
        if (txtDeliveryUid != null) txtDeliveryUid.setText("-");
    }

    // =========================
    // LCP Connect
    // =========================
    private void connectLcp() {
        if (usbPort == null) {
            log("ERR: USB non connecté");
            return;
        }

        stopApiServer("Connect LCP (new session)");

        int to = parseInt(edtTo.getText().toString(), 250);
        int from = parseInt(edtFrom.getText().toString(), 255);

        edtTo.setText(String.valueOf(to));
        edtFrom.setText(String.valueOf(from));

        txtActiveNode.setText("Node actif : —");

        if (controller != null) {
            controller.shutdown(false);
            controller = null;
        }

        link = null;
        if (pendingInitRunnable != null) ui.removeCallbacks(pendingInitRunnable);

        link = new LcpLink(usbPort, to, from, true);

        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        boolean showTxRx = prefs.getBoolean("log_tx_rx", false);
        boolean ts = prefs.getBoolean("log_ts", false);

        link.setTraceTimestampsEnabled(ts);

        controller = new DeliveryController(link);

        if (deliveryStore != null) {
            ((DeliveryController) controller).setLogStore(deliveryStore);
        }

        controller.setTxRxLoggingEnabled(showTxRx);
        controller.setLogTimestampsEnabled(ts);

        logTsEnabled = ts;
        if (cbTxRx != null) cbTxRx.setChecked(showTxRx);
        if (cbLogTs != null) cbLogTs.setChecked(ts);

        controller.setListener(new DeliveryControllerPort.Listener() {
            @Override
            public void onStateChanged(DeliveryState state) {
                ui.post(() -> {
                    boolean stableOff = (controller != null) && controller.isFlowOffStable();
                    updateButtons(state, stableOff);

                    if (state == DeliveryState.RUNNING_FLOWING || state == DeliveryState.RUNNING_PAUSED) {
                        startLiveTickIfNeeded();
                    } else {
                        stopLiveTick();
                    }
                    if (liveQtyPanel != null) liveQtyPanel.setVisibility(View.VISIBLE);
                });
            }

            @Override public void onLiveStatus(String liveText) {
                ui.post(() -> txtLive.setText(liveText));
            }

            @Override public void onLiveQty(double net, double gross) {
                ui.post(() -> {
                    if (Double.compare(net, lastNet) != 0) {
                        if (txtQtyNet != null) txtQtyNet.setText(String.format("NET: %.3f", net));
                        lastNet = net;
                    }
                    if (Double.compare(gross, lastGross) != 0) {
                        if (txtQtyGross != null) txtQtyGross.setText(String.format("GROSS: %.3f", gross));
                        lastGross = gross;
                    }
                    if (liveQtyPanel != null) liveQtyPanel.setVisibility(View.VISIBLE);
                });
            }

            @Override public void onProductsUpdated(List<ProductUiItem> ignored, int idx0) {
                ui.post(() -> {
                    suppressProductSelection = true;
                    spnProducts.setSelection(idx0);
                    suppressProductSelection = false;
                });
            }

            @Override public void onLog(String msg) {
                log(msg);
                if (msg.startsWith("Node actif")) ui.post(() -> txtActiveNode.setText(msg));
            }

            @Override public void onError(String ctx, Throwable e) {
                log("ERR[" + ctx + "] " + (e != null ? e.getMessage() : ""));
            }

            @Override
            public void onTicketInfo(String ticketNo, String deliveryUid) {
                ui.post(() -> {
                    if (txtTicketNumber != null) txtTicketNumber.setText(ticketNo == null ? "-" : ticketNo);
                    if (txtDeliveryUid != null) txtDeliveryUid.setText(deliveryUid == null ? "-" : deliveryUid);
                });
            }
        });

        pendingInitRunnable = () -> { if (controller != null) controller.initialize(); };
        ui.postDelayed(pendingInitRunnable, 300);

        log("Connect LCP appliqué");
        stopLiveTick();
        if (liveQtyPanel != null) liveQtyPanel.setVisibility(View.VISIBLE);
        refreshApiStatus();
    }

    private void updateButtons(DeliveryState state, boolean stableOff) {
        boolean connected = (state == DeliveryState.CONNECTED);
        boolean paused = (state == DeliveryState.RUNNING_PAUSED);

        btnA.setEnabled(connected);
        btnC.setEnabled(connected);
        btnContinue.setEnabled(paused);
        btnFinish.setEnabled(paused && stableOff);

        btnB.setEnabled(true);
    }

    private int readProduct() {
        try {
            int v = Integer.parseInt(edtProduct.getText().toString());
            if (v >= 1 && v <= 16) return v;
        } catch (Exception ignore) {}

        ProductUiItem it = (ProductUiItem) spnProducts.getSelectedItem();
        return it.product1;
    }

    private double readPreset() {
        try { return Double.parseDouble(edtPreset.getText().toString()); }
        catch (Exception e) { return 0.0; }
    }

    private int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return def; }
    }

    private String uiTs() {
        SimpleDateFormat df = new SimpleDateFormat("HH:mm:ss.SSS", Locale.CANADA_FRENCH);
        return df.format(new Date(System.currentTimeMillis()));
    }

    private void log(String s) {
        ui.post(() -> {
            boolean isIoLine =
                    s.startsWith("[IO ") ||
                    s.startsWith("TX:") ||
                    s.startsWith("RX:") ||
                    s.startsWith("↳") ||
                    s.startsWith("[API ");

            String line = (logTsEnabled && !isIoLine) ? ("[UI " + uiTs() + "] " + s) : s;
            logBuf.append(line).append('\n');
            txtLog.setText(logBuf.toString());
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }
}
