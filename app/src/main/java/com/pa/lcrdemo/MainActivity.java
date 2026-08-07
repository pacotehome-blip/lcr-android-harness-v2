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
import com.pa.lcr.lcp.Lc3Link;
import com.pa.lcr.lcp.MultiRegisterApiFacadeImpl;
import com.pa.lcr.lcp.RegisterSessionManager;
import com.pa.lcr.lcp.log.LogBus;
import com.pa.lcr.lcp.storage.DeliveryDb;
import com.pa.lcr.lcp.storage.LcrDeliveryStatusDb;
import com.pa.lcr.lcp.storage.DeliveryLogStore;
import com.pa.lcrdemo.dataverse.DeliverySyncScheduler;
import com.pa.lcrdemo.auth.MsalTokenProvider;

// ✅ Option A: runtime transport manager
import com.pa.lcr.lcp.transport.MediaTransportManager;


import com.pa.lcr.lcp.transport.TransportIo;
import com.pa.lcr.lcp.transport.TransportSnapshot;
import com.pa.lcr.lcp.transport.TransportStatus;
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

    
    // ✅ Reçu de l\'API (MultiRegisterApiFacadeImpl.notifyNodeSeenFull)
    private static final String ACTION_NODE_SEEN = "com.pa.lcrdemo.ACTION_NODE_SEEN";
    private UsbManager usbManager;
    private final List<UsbDevice> usbDevices = new ArrayList<>();
    private UsbSerialPort usbPort; // cache local (la vérité = UsbSession.getPort())

    // ===== Tabs / Pages (TOP: MAIN / API-Face / CONFIGURE) =====
    private TabLayout tabLayout;
    private View pageMain;
    private View pageApiFace;
    private View pageConfigure;
    private View pageSupport;
    private EditText edtSupportTicketFilter;
    private EditText edtSupportSerialFilter;
    private EditText edtSupportNodeFilter;
    private TextView txtSupportCount;
    private CheckBox chkSupportErrorsOnly;
    // ✅ (ajouté 3 août 2026, demande Paul : "copier-coller l'ensemble des lignes") —
    // conserve la dernière liste chargée pour que le bouton Copier puisse la sérialiser
    // en texte, sans avoir à re-requêter la BD.
    private final java.util.List<String> lastSupportHeaders = new java.util.ArrayList<>();
    private final java.util.List<String> lastSupportDetails = new java.util.ArrayList<>();
    // ✅ (ajouté 3 août 2026, demande Paul : "je veux faire une sélection") — positions
    // cochées en mode sélection, réinitialisé à chaque nouveau chargement de la liste.
    private final java.util.Set<Integer> supportSelectedPositions = new java.util.HashSet<>();
    private boolean supportSelectionMode = false;
    private TextView txtSupportDiagnosis;
    private TextView txtSupportRestoreStatus;
    private ListView listSupportEvents;
    private EditText edtSupportValidatedBy;
    private View rowSupportIncident;
    private java.util.List<com.pa.lcr.lcp.diagnostic.DiagnosticMatch> lastSupportMatches
            = new java.util.ArrayList<>();

    // ===== CONFIGURE UI (status + BT) =====
    private TextView txtMediaActive;
    private TextView txtNodesActive;
    private Spinner spnBtBonded;
    private Button btnBtRefresh;
    private Button btnBtConnect;
    private Button btnBtDisconnect;
    private TextView txtBtStatus;

    // ✅ BT Signal UI
    private Button btnBtSignalScan;
    private TextView txtBtSignalResult;
    
    // ===== CONFIGURE: Scan registres (par média) =====
    private Button btnScanUsbRegs;
    private TextView txtUsbRegsFound;
    private Button btnScanBtRegs;
    private TextView txtBtRegsFound;
    private Button btnScanWifiRegs;
    private TextView txtWifiRegsFound;
    private android.widget.LinearLayout containerKnownTcp;
    private android.widget.EditText edtTcpNode;
    private android.widget.EditText edtTcpOctet1, edtTcpOctet2, edtTcpOctet3, edtTcpOctet4;
    private TextView txtTcpSubnetDetected;
    private android.widget.EditText edtTcpPort;
    private Button btnTcpConnect;
    private TextView txtTcpStatus;

    // ===== CONFIGURE: Ajout manuel (2 registres par média) =====
    private EditText edtUsbNode1, edtUsbNode2;
    private TextView txtUsbSerial1, txtUsbSerial2;
    private Button btnUsbConnect1, btnUsbConnect2;

    private EditText edtBtNode1, edtBtNode2;
    private TextView txtBtSerial1, txtBtSerial2;
    private Button btnBtConnect1, btnBtConnect2;
    // ===== BT runtime (paired-only) =====
    private static final int REQ_ENABLE_BT = 9103;
    
    // ✅ Android 9 (API 28) : permission Storage legacy pour écrire dans /Download
    //private static final int REQ_STORAGE_LEGACY = 9104;
    //private final ExecutorService btExec = Executors.newSingleThreadExecutor();
    private static final int REQ_STORAGE_LEGACY = 9104;
    private static final int REQ_LOCATION_BT_SIGNAL = 9105; // ✅ BT signal scan
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
    // ⚠️ LEGACY — non utilisé (aucun appelant de getApiServer()). Le vrai
    // ApiServer actif tourne dans LcrHttpService. Conservé le temps du
    // retrait complet du bouton API legacy.
    private ApiServer apiServer;
    public ApiServer getApiServer() { return apiServer; }
    private DeliveryLogStore deliveryStore;
    private DeepLinkHandler deepLinkHandler; // ✅ Gestion deep link Field Service

    // ✅ Getters publics pour DeepLinkHandler
    public BluetoothAdapter getBtAdapter() { return btAdapter; }

    /**
     * ✅ FIX (2026-07-29) : expose le DeepLinkHandler pour que le diagnostic de
     * reconnexion puisse relancer la livraison après succès.
     *
     * Sans cet accès, le chemin RegisterTabFragment.surErreurConnexion →
     * validerConnexion → lancerDiagnosticForce utilisait la surcharge à 4
     * arguments, qui passe deepLinkHandler=null. Le diagnostic réussissait, le
     * tab passait Connected-Ready, mais lancerLivraison() n'était jamais appelé
     * — donc aucune reprise de la livraison et aucun dialog Continuer/Annuler
     * (ce dialog vit dans DeepLinkHandler.lancerLivraison()).
     *
     * Peut retourner null si appelé avant onCreate/initialisation — les
     * appelants doivent le tolérer.
     */
    public DeepLinkHandler getDeepLinkHandler() { return deepLinkHandler; }

    // ✅ WebView Field Service — pour écrire dans localStorage avant finish()
    public android.webkit.WebView getFieldServiceWebView() {
        try {
            // Chercher le WebView actif dans les fragments visibles
            androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
            for (androidx.fragment.app.Fragment f : fm.getFragments()) {
                if (f != null && f.getView() != null) {
                    android.webkit.WebView wv = f.getView().findViewWithTag("lcr_webview");
                    if (wv != null) return wv;
                    // Chercher récursivement
                    wv = findWebView(f.getView());
                    if (wv != null) return wv;
                }
            }
            // Fallback — chercher dans la vue principale
            return findWebView(getWindow().getDecorView());
        } catch (Exception e) {
            android.util.Log.w("MainActivity", "getFieldServiceWebView ERR: " + e.getMessage());
            return null;
        }
    }

    private android.webkit.WebView findWebView(android.view.View root) {
        if (root instanceof android.webkit.WebView) return (android.webkit.WebView) root;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                android.webkit.WebView found = findWebView(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
    public String getLastBtMac() { return lastBtMac; }
    public MediaTransportManager getMediaTransportManager() { return mediaTransportManager; }
    public Handler getUiHandler() { return ui; }
    public DeliveryLogStore getDeliveryStore() { return deliveryStore; }
    public void updateBtStatusText(String text) {
        if (txtBtStatus != null) txtBtStatus.setText(text);
    }
    public void onBtConnectedFromDeepLink(BluetoothSocket socket,
                                           java.io.InputStream in,
                                           java.io.OutputStream out,
                                           String mac) {
        btSocket  = socket;
        btIn      = in;
        btOut     = out;
        lastBtMac = mac;
        if (mediaTransportManager != null) {
            android.bluetooth.BluetoothDevice dev = btAdapter.getRemoteDevice(mac);
            mediaTransportManager.onBtConnected(dev, socket, in, out, "DEEPLINK");
        }
    }

    // ===================== MAIN UI (USB + Scan) =====================
    private Spinner spnUsbDevices;
    private Button btnScanUsb;
    private Button btnPingUsb;
    private Button btnQuit;
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
        final boolean isLc3;
        String qtySuffix; // " | N=.. G=.."

        TabSpec(String tabKey, String mediaShort, String transportKey, int node, int from, String serialId) {
            this(tabKey, mediaShort, transportKey, node, from, serialId, false);
        }

        TabSpec(String tabKey, String mediaShort, String transportKey, int node, int from, String serialId, boolean isLc3) {
            this.tabKey = tabKey;
            this.mediaShort = mediaShort;
            this.transportKey = transportKey;
            this.node = node;
            this.from = from;
            this.serialId = serialId;
            this.isLc3 = isLc3;
        }
    }



    // tabKey -> spec
    // ✅ FIX (6 août 2026, demande Paul — balayage systématique, classe de
    // bug "concurrence") — LinkedHashMap n'est PAS thread-safe, alors que
    // cette map est touchée depuis plusieurs threads (scan USB, deep link,
    // BT, reconnexion auto, tous en arrière-plan) en plus du thread UI.
    // Comparer à apiRidToPath/apiFirstJobRid/apiJobSeen un peu plus bas, qui
    // utilisent déjà ConcurrentHashMap — le code sait gérer ça correctement
    // ailleurs, juste pas ici, malgré que ce soit l'état central des tabs.
    // Collections.synchronizedMap() protège chaque opération individuelle
    // (put/get/remove) tout en conservant l'ordre d'insertion de
    // LinkedHashMap (important pour l'ordre d'affichage des tabs). Reste un
    // point d'attention : itérer avec "for (... : tabsByKey.values())"
    // nécessite toujours un bloc synchronized(tabsByKey) autour de
    // l'itération elle-même pour être complètement sûr — non fait
    // systématiquement partout, à surveiller si un ConcurrentModification
    // apparaît en test.
    private final Map<String, TabSpec> tabsByKey = Collections.synchronizedMap(new LinkedHashMap<>());

    // ✅ (4 août 2026) — accesseur read-only pour DeepLinkHandler : permet de
    // savoir, AVANT upsertRegisterTabFromScan(), si un tab existait déjà pour
    // cette clé (utilisé pour décider s'il faut attendre le scan auto produits).
    public boolean tabExists(String tabKey) {
        return tabsByKey.containsKey(tabKey);
    }
    // regKey(node#serial) -> tabKey courant (clear ciblé si migre de média)
    // ✅ FIX (6 août 2026) — même raison que tabsByKey ci-dessus.
    private final Map<String, String> regKeyToTabKey = Collections.synchronizedMap(new LinkedHashMap<>());

    private String currentTabKey = null;
    private String visibleRegFragmentTag = null; // fragment visible dans registerContainer

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
    // ✅ FIX : garde-fou anti-concurrence — empêche finalizeTcpRegisterTab()
    // de tourner deux fois EN MÊME TEMPS pour le même transport. Sans ça, un
    // 2e appel (ex: connexion manuelle + auto-connect presque simultanés)
    // pouvait interférer avec la sonde LC3 du 1er, la faire échouer à tort,
    // et tomber dans la boucle LCR-II 1..250 (lente, ~plusieurs secondes,
    // pour rien) — voire créer un onglet erroné selon ce que la boucle lit.
    private final java.util.Set<String> tcpFinalizeInProgress =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final long MAIN_LOG_REFRESH_MIN_MS = 250;
    private long lastMainLogRefreshMs = 0L;
    private boolean mainLogRefreshPending = false;

    private final Map<Integer, String> apiRidToPath = new ConcurrentHashMap<>();
    private final Set<Integer> apiFirstJobRid = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<String> apiJobSeen = Collections.newSetFromMap(new ConcurrentHashMap<>());

    
    // ✅ Guard switch média (CONFIGURE): bloque probes/IO pendant ~800ms
    private volatile long mediaSwitchGuardUntilMs = 0L;
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

            } else if (ACTION_USB_PERMISSION.equals(a)) {
                // ✅ Permission USB accordée/refusée
                try {
                    boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    if (!granted) {
                        logUi(null, "Permission USB refusée");
                        return;
                    }
                    logUi(null, "Permission USB accordée");
                    // IMPORTANT: le replug crée souvent un port stale -> reset puis rescan + open
                    resetUsbState("PERMISSION_GRANTED");
                    scanUsb();
                    openSelectedUsb();
                } catch (Exception ignored) {}

            } else if (ACTION_NODE_SEEN.equals(a)) {
                // ✅ Reçu de /register/connect-auto (BT ou USB) -> upsert tab (anti-doublon + migration média)
                try {
                    int node = intent.getIntExtra("node", 0);
                    int from = intent.getIntExtra("from", 255);
                    String serial = intent.getStringExtra("serial");
                    String transportKey = intent.getStringExtra("transport");

                    if (node < 1 || node > 250) return;

                    serial = (serial != null) ? serial.trim() : "";
                    if (!isPlausibleSerial(serial)) {
                        logUi(null, "NODE_SEEN ignoré: serial invalide: " + serial + " (node=" + node + ")");
                        return;
                    }

                    if (transportKey == null) transportKey = "";
                    transportKey = transportKey.trim();
                    if (transportKey.isEmpty()) {
                        // fallback best-effort
                        transportKey = MediaTransportManager.getActiveKeyStatic();
                        if (transportKey == null) transportKey = "";
                    }

                    // Focus intelligent (ne vole pas le focus inutilement)
                    String regKey = regKeyOf(node, serial);
                    String oldTabKey = regKeyToTabKey.get(regKey);
                    String mediaShort = mediaShortFromTransportKey(transportKey);
                    String newTabKey = tabKeyOf(mediaShort, node, serial);

                    boolean focus = (currentTabKey == null)
                            || (oldTabKey != null && oldTabKey.equals(currentTabKey))
                            || newTabKey.equals(currentTabKey);

                    // ✅ Utilise la mécanique existante (migration média incluse)
                    upsertRegisterTabFromScan(transportKey, node, from, serial, focus);

                    // Rafraîchir label (OFF/READY)
                    final String k = tabKeyOf(mediaShortFromTransportKey(transportKey), node, serial);
                    ui.postDelayed(() -> refreshOneTabMediaStatus(k), 80);

                } catch (Exception ignored) {}

            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                onUsbDetached();
                resetUsbState("SYS_DETACHED");

            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                // rescan seulement, l'ouverture se fait via Open/Ping
                try { scanUsb(); } catch (Exception ignored) {}
            }
        }
    };

    private final LogBus.Listener mainLogListener = e -> scheduleMainLogRefresh();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // ✅ Reset guard diagnostic au démarrage
        com.pa.lcrdemo.RegisterConnectionHelper.resetDiagnostic();

        usbManager = (UsbManager) getSystemService(USB_SERVICE);

        bindUi();
        wireUi();
        refreshKnownTcpList();
        refreshDetectedSubnet();
        initUiDefaults();
        setupTabsTop();

        // ✅ Démarrage automatique Field Service
        if (getSharedPreferences("filgo_prefs", MODE_PRIVATE)
                .getBoolean("auto_launch_fs", false)) {
            startActivity(new Intent(this, FieldServiceActivity.class));
        }

        // CONFIGURE: media + bluetooth


        // CONFIGURE: media + bluetooth
        mediaProfileStore = new com.pa.lcr.lcp.storage.MediaProfileStore(this);
        btAdapter = BluetoothAdapter.getDefaultAdapter();

        // ✅ Option A: manager runtime multi-transport
        mediaTransportManager = MediaTransportManager.get(this);

        deliveryStore = new DeliveryLogStore(this);
 // ✅ Android 9: demander la permission storage (une seule fois) pour /Download
 ensureLegacyStoragePermissionForDownloads(true);
        deliveryStore.purgeOlderThanDaysAsync(7);
        deepLinkHandler = new DeepLinkHandler(this, deliveryStore, btExec);

        // ✅ WorkManager — vide la queue offline Dataverse quand réseau disponible
        DeliverySyncScheduler.schedulePeriodic(this);

        // ✅ (demandé 31 juillet 2026, suite à la perte du ticket 10898) : le worker
        // périodique attend jusqu'à 15 minutes (minimum imposé par Android pour
        // PeriodicWorkRequest — pas un choix arbitraire du code). Ce callback réagit
        // IMMÉDIATEMENT dès que le réseau redevient disponible, sans attendre le prochain
        // cycle du minuteur — réduit la fenêtre de risque entre une livraison PENDING et
        // sa synchronisation réelle.
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                android.net.NetworkRequest req = new android.net.NetworkRequest.Builder()
                        .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();
                cm.registerNetworkCallback(req, new android.net.ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(android.net.Network network) {
                        android.util.Log.i("NetworkSync", "Réseau disponible — déclenchement sync immédiat");
                        DeliverySyncScheduler.triggerNow(getApplicationContext());
                    }
                });
            }
        } catch (Exception e) {
            android.util.Log.w("NetworkSync", "registerNetworkCallback ERR (non-bloquant): " + e.getMessage());
        }

        // ✅ (demande Paul 31 juillet 2026 : "tout persister") — LogBus était jusqu'ici un
        // buffer 100% en mémoire, jamais persisté, invisible pour le RCA après coup. Ce
        // listener écrit chaque événement (UI/API/IO_TX/IO_RX) dans log_bus_event de façon
        // async, sans jamais ralentir l'émission LogBus elle-même.
        final com.pa.lcr.lcp.storage.LogBusStore logBusStore =
                new com.pa.lcr.lcp.storage.LogBusStore(getApplicationContext());
        LogBus.addListener(event -> logBusStore.addEventAsync(
                event.node, event.src != null ? event.src.name() : "", event.msg));

        // ✅ MSAL — init + login au premier démarrage de l'APK
        // Après ce premier login le token est en cache → silent pour toutes les livraisons
        MsalTokenProvider msal = new MsalTokenProvider(this);
        msal.init(new MsalTokenProvider.InitCallback() {
            @Override
            public void onReady() {
                msal.acquireToken(MainActivity.this, new MsalTokenProvider.TokenCallback() {
                    @Override
                    public void onSuccess(String token) {
                        android.util.Log.i("MSAL", "Token OK — Dataverse prêt");
                        // Déclencher sync immédiat si items en attente
                        DeliverySyncScheduler.triggerNow(MainActivity.this);
                        // ✅ Sync filgo_delivery_status + filgo_note_template au lancement
                        new Thread(() ->
                            com.pa.lcrdemo.dataverse.LcrDeliverySync.syncAll(
                                MainActivity.this, token)
                        ).start();
                    }
                    @Override
                    public void onError(Exception e) {
                        android.util.Log.w("MSAL", "Token ERR: " + e.getMessage());
                    }
                });
            }
            @Override
            public void onError(Exception e) {
                android.util.Log.e("MSAL", "Init ERR: " + e.getMessage());
            }
        });

        refreshApiStatus();
        logUi(null, "UI prête — Scan USB requis");
        // ✅ Démarrage API via LcrHttpService (foreground service permanent)
        android.content.Intent svcIntent = new android.content.Intent(this, LcrHttpService.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(svcIntent);
        } else {
            startService(svcIntent);
        }
        
        // ✅ Deep Link au lancement (APK fermé)
        deepLinkHandler.handleDeepLink(getIntent());
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter();
        f.addAction(UsbReceiver.ACTION_USB_READY);
        f.addAction(UsbReceiver.ACTION_USB_DETACHED);
        f.addAction(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        // ✅ AJOUT: signal API -> UI (BT/USB)
        f.addAction(ACTION_NODE_SEEN);
        // Android 9-13 : registerReceiver(receiver, filter) — sans flag
        // Android 14+  : registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(usbUiReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbUiReceiver, f);
        }

        LogBus.addListener(mainLogListener);

        // ✅ FIX CRITIQUE (5 août 2026, demande Paul — retracé ligne par
        // ligne : "je fais retour au bon de travail, ensuite lancer
        // livraison, aussitôt de retour dans l'apk par deeplink, je vois
        // USB(OFF)... avant on avait pas ce trouble nulle part") — trouvé :
        // ce code voyait que UsbSession gardait encore une référence au port
        // et la réutilisait AVEUGLÉMENT, sans jamais vérifier qu'elle était
        // encore vivante. Entre le clic "retour au bon de travail" et le
        // retour dans l'app via deep link, l'app passe par onStop() (qui
        // désenregistre le receiver USB) pendant que tu es dans FieldService
        // — Android peut légitimement suspendre l'accès USB d'une app en
        // arrière-plan. Au retour, la référence en mémoire existe encore,
        // mais la connexion sous-jacente peut être morte — exactement comme
        // le port périmé qu'on a dû corriger côté api_openPingUsb(). Un
        // débranchement/rebranchement physique force TOUJOURS une vraie
        // réouverture (resetUsbState + scan + open) — c'est pour ça que ça
        // marche systématiquement. Ici : le retour au premier plan fait
        // maintenant la MÊME chose — on ne fait plus confiance à la
        // référence existante, on force une vraie réouverture, à chaque
        // retour, comme un "débranchement/rebranchement logiciel".
        UsbSerialPort p = UsbSession.getPort();
        if (p != null && usbPort == null) {
            android.util.Log.i("MainActivity", "onStart: référence USB existante trouvée — "
                + "vérification/réouverture forcée avant réutilisation (comme un rebranchement)");
            resetUsbState("ONSTART_FORCE_REFRESH");
            ui.post(() -> { scanUsb(); openSelectedUsb(); });
        }
        refreshGlobalLogView();
        // ✅ Rattrapage — sync tabs avec sessions connues (cas arrière-plan)
        ui.postDelayed(this::syncTabsFromActiveSessions, 400);
    }

    /**
     * ✅ Rattrapage UI — synchronise les tabs avec les sessions LCP connues.
     * Appelé au retour au premier plan pour rattraper les NODE_SEEN manqués en arrière-plan.
     */
    private void syncTabsFromActiveSessions() {
        try {
            RegisterSessionManager sessions = RegisterSessionManager.get(this);
            List<String[]> known = sessions.listKnownRegisters();
            if (known == null || known.isEmpty()) {
                logUi(null, "syncTabs: aucune session connue");
                return;
            }
            logUi(null, "syncTabs: " + known.size() + " session(s) connue(s)");
            boolean focused = false;
            for (String[] reg : known) {
                if (reg == null || reg.length < 3) continue;
                int node;
                try { node = Integer.parseInt(reg[0]); } catch (Exception e) { continue; }
                String serial      = reg[1];
                String transportKey = reg[2];

                if (!isPlausibleSerial(serial)) continue;

                // ✅ FIX (la vraie source du tab TCP fantôme) : avant, si aucun
                // transport n'était encore CONFIRMÉ (pinné) pour ce couple précis
                // (node, serial), le code se repliait sur "le transport actif du
                // moment" — SANS AUCUNE VÉRIFICATION que ce transport ait
                // réellement CE registre. Ça associait à tort un registre
                // "attendu" (ex: le LCR-II du deep link, lié tôt via
                // bindExpectedSerial) au transport actif du moment (ex: le TCP-LC3
                // déjà connecté), créant un onglet fantôme visible ~400ms après
                // chaque retour au premier plan (ex: retour de Field Service).
                // Sans transport CONFIRMÉ, on ne devine plus — on saute cette
                // entrée, la vraie détection (deep link / scan) s'en chargera.
                if (transportKey == null || transportKey.trim().isEmpty()) {
                    logUi(null, "syncTabs: node=" + node + " serial=" + serial
                            + " — aucun transport confirmé, ignoré (pas de devinette)");
                    continue;
                }

                String mediaShort = mediaShortFromTransportKey(transportKey);
                String tabKey = tabKeyOf(mediaShort, node, serial);
                boolean exists = tabsByKey.containsKey(tabKey);

                logUi(null, "syncTabs: node=" + node + " serial=" + serial
                        + " media=" + mediaShort + (exists ? " (existant)" : " (nouveau)"));

                // focus sur le premier tab seulement si aucun tab actif
                boolean focus = (!focused && currentTabKey == null);
                upsertRegisterTabFromScan(transportKey, node, 255, serial, focus);
                if (focus) focused = true;

                // Rafraîchir le statut média
                final String fTabKey = tabKeyOf(mediaShortFromTransportKey(transportKey), node, serial);
                ui.postDelayed(() -> refreshOneTabMediaStatus(fTabKey), 100);
            }
            refreshAllTabsMediaStatus();
        } catch (Exception e) {
            logUi(null, "syncTabs: erreur: " + e.getMessage());
        }
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
        // ✅ FIX (6 août 2026, demande Paul — balayage systématique des
        // autres classes de bugs, gestion mémoire/cycle de vie) — btExec
        // n'était jamais arrêté ici, contrairement à scanExec juste
        // au-dessus. Si l'Activity est recréée dans le même processus
        // (ex. après finish() suivi d'un relancement rapide), chaque
        // nouvelle instance créait son propre btExec sans jamais nettoyer
        // l'ancien — accumulation de threads au fil des cycles.
        try { btExec.shutdownNow(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(@androidx.annotation.NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // ✅ Rotation tablette — ne pas recréer les fragments/tabs
        // Le manifest a configChanges="orientation|screenSize|keyboardHidden|screenLayout"
        // donc Android appelle ici au lieu de recréer l'activité
        // Rien à faire — les fragments gèrent leur propre état
        android.util.Log.i("MainActivity",
            "onConfigurationChanged: orientation=" + newConfig.orientation + " — tabs conservés");
    }

    // ✅ Deep Link lcrdemo:// — appelé quand Field Service Mobile lance l'APK
    // Exemples:
    //   lcrdemo://livraison?idWorkOrder=123&serialId=16466294&lcrnode=250
    //   lcrdemo://ping
    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (deepLinkHandler != null) deepLinkHandler.handleDeepLink(intent);
    }



    // ✅ Délégation à DeepLinkHandler
    public void onDeliveryEnded(String woNum, String extraJson) {
        if (deepLinkHandler != null) deepLinkHandler.onDeliveryEnded(woNum, extraJson);
    }

    public void onDeliveryEnded(String woNum, String woIdGuid, String extraJson) {
        if (deepLinkHandler != null) deepLinkHandler.onDeliveryEnded(woNum, woIdGuid, extraJson);
    }

    /**
     * ✅ Délégation à DeepLinkHandler.lancerLivraison() — permet au bouton C
     * (relance manuelle dans l'APK) d'utiliser EXACTEMENT le même chemin que
     * le deep link FieldService: stabilisation BT, oneshot/start, poll de fin,
     * patchDataverse automatique. Seul le ticket number du registre change
     * à chaque appel — woNum/woIdGuid/produit/preset restent ceux du WO en cours.
     */
    /** Retourne le transportKey du tab actif pour un node donné */
    public String getTransportKeyForNode(int node) {
        // ✅ FIX (6 août 2026, concurrence) — itération protégée, voir
        // commentaire sur la déclaration de tabsByKey.
        synchronized (tabsByKey) {
            for (TabSpec t : tabsByKey.values()) {
                if (t.node == node && t.transportKey != null && !t.transportKey.isEmpty())
                    return t.transportKey;
            }
        }
        return null;
    }

    /** Lance la livraison directement via le fragment du tab existant — même chemin que bouton C
     *  Utilisé par DeepLinkHandler quand resolveOrCreateForNode() échoue mais tab existe */
    public boolean lancerLivraisonViaTabExistant(int node, String woNum, String woIdGuid,
            String produit, String presetStr) {
        java.util.List<TabSpec> snapshot;
        synchronized (tabsByKey) { snapshot = new ArrayList<>(tabsByKey.values()); }
        for (TabSpec t : snapshot) {
            if (t.node != node) continue;
            String tabKey = t.tabKey;
            Fragment f = getSupportFragmentManager().findFragmentByTag("regtab_" + tabKey);
            if (!(f instanceof RegisterTabFragment)) continue;
            RegisterTabFragment tab = (RegisterTabFragment) f;
            // Préfiller le WO et lancer comme le bouton C
            tab.prefillFromDeepLink(woNum, woIdGuid, produit, presetStr);
            runOnUiThread(() -> tab.startNewDeliveryCFromDeepLink(woNum, woIdGuid, produit, presetStr));
            android.util.Log.i("MainActivity", "lancerLivraisonViaTabExistant — node=" + node + " wo=" + woNum);
            return true;
        }
        return false;
    }

    public void lancerLivraisonDepuisTab(String transportKey, int node, String serialId,
                                          String woNum, String woIdGuid,
                                          String produit, String presetStr, String mac) {
        if (deepLinkHandler != null) {
            // ✅ FIX : cet appel se faisait DIRECTEMENT sur le thread appelant (le
            // thread UI, puisque déclenché depuis le clic du bouton C). Or
            // lancerLivraison() contient le dialogue "Bon déjà complété", qui affiche
            // via activity.runOnUiThread() PUIS bloque le thread appelant avec
            // latch.await() en attendant le clic. Si le thread appelant EST le thread
            // UI, le dialogue ne peut jamais s'afficher (sa propre file de messages
            // est bloquée) et le clic ne peut jamais arriver — auto-blocage garanti.
            // Partout ailleurs dans le code, cet appel est fait via new Thread(...) —
            // ce point d'entrée était le seul oublié.
            new Thread(() -> deepLinkHandler.lancerLivraison(transportKey, node, serialId,
                woNum, woIdGuid, produit, presetStr, mac)).start();
        }
    }
    /**
     * Retourner à Field Service Mobile après la livraison.
     * Construit l'URL ms-dynamicsxrm:// avec le statut et les données
     * que le PCF interceptera pour mettre à jour le SQLite Dataverse local.
     *
     * @param idWorkOrder  GUID de l'ordre de travail (ex: {A4251FF8-...})
     * @param status       "ok", "en_cours", "termine", "erreur"
     * @param extra        données supplémentaires JSON (optionnel, ex: ticketNo, litres)
     */

    private void bindUi() {
        tabLayout = findViewById(R.id.tabLayout);
        pageMain = findViewById(R.id.pageMain);
        pageApiFace = findViewById(R.id.pageApiFace);
        pageConfigure = findViewById(R.id.pageConfigure);
        pageSupport = findViewById(R.id.pageSupport);
        edtSupportTicketFilter = findViewById(R.id.edtSupportTicketFilter);
        edtSupportSerialFilter = findViewById(R.id.edtSupportSerialFilter);
        edtSupportNodeFilter = findViewById(R.id.edtSupportNodeFilter);
        // ✅ FIX (6 août 2026, demande Paul — "permettre une recherche de
        // type google") — les résultats se rafraîchissent maintenant
        // automatiquement pendant la frappe (avec un court délai pour ne pas
        // relancer une requête à chaque lettre), plus besoin de cliquer
        // "Rafraîchir" après chaque changement.
        android.text.TextWatcher supportLiveSearchWatcher = new android.text.TextWatcher() {
            private final Runnable debounced = () -> refreshSupportEvents();
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                ui.removeCallbacks(debounced);
                ui.postDelayed(debounced, 400);
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        };
        if (edtSupportTicketFilter != null) edtSupportTicketFilter.addTextChangedListener(supportLiveSearchWatcher);
        if (edtSupportSerialFilter != null) edtSupportSerialFilter.addTextChangedListener(supportLiveSearchWatcher);
        if (edtSupportNodeFilter != null) edtSupportNodeFilter.addTextChangedListener(supportLiveSearchWatcher);
        txtSupportCount = findViewById(R.id.txtSupportCount);
        chkSupportErrorsOnly = findViewById(R.id.chkSupportErrorsOnly);
        if (chkSupportErrorsOnly != null) {
            chkSupportErrorsOnly.setOnCheckedChangeListener((btn, checked) -> refreshSupportEvents());
        }
        CheckBox chkSupportSelectionMode = findViewById(R.id.chkSupportSelectionMode);
        if (chkSupportSelectionMode != null) {
            chkSupportSelectionMode.setOnCheckedChangeListener((btn, checked) -> {
                supportSelectionMode = checked;
                if (!checked) supportSelectedPositions.clear();
                if (listSupportEvents != null && listSupportEvents.getAdapter() != null) {
                    ((SupportEventAdapter) listSupportEvents.getAdapter()).notifyDataSetChanged();
                }
            });
        }
        txtSupportDiagnosis = findViewById(R.id.txtSupportDiagnosis);
        listSupportEvents = findViewById(R.id.listSupportEvents);
        Button btnSupportRefresh = findViewById(R.id.btnSupportRefresh);
        if (btnSupportRefresh != null) {
            btnSupportRefresh.setOnClickListener(v -> refreshSupportEvents());
        }
        Button btnSupportDiagnose = findViewById(R.id.btnSupportDiagnose);
        if (btnSupportDiagnose != null) {
            btnSupportDiagnose.setOnClickListener(v -> runSupportDiagnosis());
        }
        Button btnSupportCoherence = findViewById(R.id.btnSupportCoherence);
        if (btnSupportCoherence != null) {
            btnSupportCoherence.setOnClickListener(v -> runCoherenceCheck());
        }
        Button btnSupportLexique = findViewById(R.id.btnSupportLexique);
        if (btnSupportLexique != null) {
            btnSupportLexique.setOnClickListener(v -> showSupportLexiqueDialog());
        }
        txtSupportRestoreStatus = findViewById(R.id.txtSupportRestoreStatus);
        Button btnSupportRestoreBackup = findViewById(R.id.btnSupportRestoreBackup);
        if (btnSupportRestoreBackup != null) {
            btnSupportRestoreBackup.setOnClickListener(v -> confirmAndRunRestoreBackup());
        }

        Button btnSupportCopyAll = findViewById(R.id.btnSupportCopyAll);
        if (btnSupportCopyAll != null) {
            btnSupportCopyAll.setOnClickListener(v -> copySupportListToClipboard());
        }
        // ✅ AJOUTÉ (7 août 2026, demande Paul — "un bouton pour vider l'écran
        // support et repartir en neuf, mais avant il doit faire un backup") —
        // confirmation explicite avant l'action (destructive, même avec backup).
        Button btnSupportClearAll = findViewById(R.id.btnSupportClearAll);
        if (btnSupportClearAll != null) {
            btnSupportClearAll.setOnClickListener(v -> {
                new android.app.AlertDialog.Builder(this)
                    .setTitle("Vider l'écran Support")
                    .setMessage("Une copie complète de la base de données sera d'abord sauvegardée "
                        + "(Téléchargements, fichier .db restaurable, conservé 7 jours), puis les "
                        + "événements de l'écran Support seront vidés. Continuer?")
                    .setPositiveButton("Vider", (dlg, which) -> {
                        // ✅ FIX (7 août 2026, demande Paul — "qu'est-ce que tu
                        // prends en backup, il y a deux BD") — trouvé : le
                        // backup ne prenait QUE lcr_delivery.db, jamais
                        // filgo_delivery_status.db (les vraies données de
                        // livraison — ticket_no, net_l, gross_l, sync_status).
                        // Corrigé : les DEUX sont maintenant sauvegardées,
                        // même timestamp partagé pour les regrouper facilement,
                        // même mécanisme déjà éprouvé que le bouton "Backup DB
                        // (Downloads)" existant (backupRawDbToDownloadsQ/
                        // Legacy). Le vidage de log_bus_event n'a lieu
                        // qu'après le succès des DEUX sauvegardes.
                        final String stamp = String.valueOf(System.currentTimeMillis());
                        final String lcrBackupName = "filgo_support_backup_lcr_" + stamp + ".db";
                        final String statusBackupName = "filgo_support_backup_status_" + stamp + ".db";
                        final boolean[] lcrOk = {false};
                        final boolean[] statusOk = {false};
                        final String[] statusDetail = {""};

                        Runnable proceedIfBothDone = () -> {
                            if (!lcrOk[0] || !statusOk[0]) return; // attend les deux
                            com.pa.lcr.lcp.storage.DeliveryLogStore store =
                                new com.pa.lcr.lcp.storage.DeliveryLogStore(getApplicationContext());
                            store.clearLogBusEventOnlyAsync((success, rowsCleared, errorMessage) -> runOnUiThread(() -> {
                                if (success) {
                                    Toast.makeText(this, "Backup complet (2 BD) effectué, "
                                        + rowsCleared + " événements vidés — écran Support réinitialisé", Toast.LENGTH_LONG).show();
                                    refreshSupportEvents();
                                } else {
                                    Toast.makeText(this, "Backup OK mais échec du vidage : " + errorMessage, Toast.LENGTH_LONG).show();
                                }
                            }));
                        };

                        com.pa.lcr.lcp.storage.DeliveryLogStore storeForBackup =
                            new com.pa.lcr.lcp.storage.DeliveryLogStore(getApplicationContext());
                        storeForBackup.backupDbToDownloadsAsync(getApplicationContext(), lcrBackupName, (ok1, name1, detail1) -> {
                            lcrOk[0] = ok1;
                            if (!ok1) {
                                runOnUiThread(() -> Toast.makeText(this,
                                    "Échec backup lcr_delivery.db — écran NON vidé : " + detail1, Toast.LENGTH_LONG).show());
                                return;
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                backupRawDbToDownloadsQ(LcrDeliveryStatusDb.DB_NAME, statusBackupName, (ok2, name2, detail2) -> {
                                    statusOk[0] = ok2;
                                    statusDetail[0] = detail2;
                                    if (!ok2) {
                                        runOnUiThread(() -> Toast.makeText(this,
                                            "Échec backup filgo_delivery_status.db — écran NON vidé : " + detail2, Toast.LENGTH_LONG).show());
                                        return;
                                    }
                                    proceedIfBothDone.run();
                                });
                            } else {
                                backupRawDbToDownloadsLegacy(LcrDeliveryStatusDb.DB_NAME, statusBackupName, (ok2, name2, detail2) -> {
                                    statusOk[0] = ok2;
                                    statusDetail[0] = detail2;
                                    if (!ok2) {
                                        runOnUiThread(() -> Toast.makeText(this,
                                            "Échec backup filgo_delivery_status.db — écran NON vidé : " + detail2, Toast.LENGTH_LONG).show());
                                        return;
                                    }
                                    proceedIfBothDone.run();
                                });
                            }
                        });
                    })
                    .setNegativeButton("Annuler", null)
                    .show();
            });
        }
        // ✅ AJOUTÉ (7 août 2026, demande Paul — "oui" à l'ajout d'une vraie
        // restauration) — liste les backups disponibles (les plus récents en
        // premier), choix explicite, confirmation, puis restauration +
        // redémarrage forcé (nécessaire pour repartir sur une connexion
        // SQLite propre après avoir remplacé le fichier .db vivant).
        Button btnSupportRestore = findViewById(R.id.btnSupportRestore);
        if (btnSupportRestore != null) {
            btnSupportRestore.setOnClickListener(v -> {
                com.pa.lcr.lcp.storage.DeliveryLogStore.listSupportBackupsAsync(getApplicationContext(), backups -> runOnUiThread(() -> {
                    if (backups.isEmpty()) {
                        Toast.makeText(this, "Aucun backup disponible dans Téléchargements", Toast.LENGTH_LONG).show();
                        return;
                    }
                    String[] labels = new String[backups.size()];
                    for (int i = 0; i < backups.size(); i++) {
                        com.pa.lcr.lcp.storage.DeliveryLogStore.BackupInfo b = backups.get(i);
                        String dateStr = android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", b.dateAddedMs).toString();
                        labels[i] = dateStr + "  —  " + b.displayLabel;
                    }
                    new android.app.AlertDialog.Builder(this)
                        .setTitle("Restaurer depuis un backup")
                        .setItems(labels, (dlg, which) -> {
                            com.pa.lcr.lcp.storage.DeliveryLogStore.BackupInfo chosen = backups.get(which);
                            new android.app.AlertDialog.Builder(this)
                                .setTitle("Confirmer la restauration")
                                .setMessage("Ceci va REMPLACER complètement les données Support actuelles "
                                    + "par le contenu de :\n\n" + chosen.displayLabel
                                    + "\n\nL'app redémarrera automatiquement juste après. Continuer?")
                                .setPositiveButton("Restaurer", (d2, w2) -> {
                                    // Fermer toute connexion SQLite ouverte avant d'écraser le fichier.
                                    try { if (deliveryStore != null) deliveryStore.close(); } catch (Exception ignored) {}
                                    com.pa.lcr.lcp.storage.DeliveryLogStore.restoreFromBackupAsync(
                                        getApplicationContext(), chosen, (success, errorMessage) -> runOnUiThread(() -> {
                                        if (success) {
                                            Toast.makeText(this, "Restauration réussie — redémarrage...", Toast.LENGTH_LONG).show();
                                            ui.postDelayed(() -> {
                                                try {
                                                    android.content.Intent intent = getPackageManager()
                                                        .getLaunchIntentForPackage(getPackageName());
                                                    if (intent != null) {
                                                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                                                            | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                                                        startActivity(intent);
                                                    }
                                                    android.os.Process.killProcess(android.os.Process.myPid());
                                                } catch (Exception e) {
                                                    android.util.Log.e("MainActivity", "Restart après restauration ERR: " + e.getMessage());
                                                }
                                            }, 1200);
                                        } else {
                                            Toast.makeText(this, "Échec de la restauration : " + errorMessage, Toast.LENGTH_LONG).show();
                                        }
                                    }));
                                })
                                .setNegativeButton("Annuler", null)
                                .show();
                        })
                        .setNegativeButton("Annuler", null)
                        .show();
                }));
            });
        }
        edtSupportValidatedBy = findViewById(R.id.edtSupportValidatedBy);
        rowSupportIncident = findViewById(R.id.rowSupportIncident);
        Button btnSupportRecordIncident = findViewById(R.id.btnSupportRecordIncident);
        if (btnSupportRecordIncident != null) {
            btnSupportRecordIncident.setOnClickListener(v -> recordSupportIncidents());
        }

        btnScanUsb = findViewById(R.id.btnScanUsb);
        btnPingUsb = findViewById(R.id.btnPingUsb);
        btnQuit = findViewById(R.id.btnQuit);
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
       
        // ✅ BT Signal
        btnBtSignalScan = findViewById(R.id.btnBtSignalScan);
        txtBtSignalResult = findViewById(R.id.txtBtSignalResult);   

        // CONFIGURE: scan registres (par média)
        btnScanUsbRegs = findViewById(R.id.btnScanUsbRegs);
        txtUsbRegsFound = findViewById(R.id.txtUsbRegsFound);
        btnScanBtRegs = findViewById(R.id.btnScanBtRegs);
        txtBtRegsFound = findViewById(R.id.txtBtRegsFound);
        btnScanWifiRegs = findViewById(R.id.btnScanWifiRegs);
        txtWifiRegsFound = findViewById(R.id.txtWifiRegsFound);
        containerKnownTcp = findViewById(R.id.containerKnownTcp);
        edtTcpNode = findViewById(R.id.edtTcpNode);
        edtTcpOctet1 = findViewById(R.id.edtTcpOctet1);
        edtTcpOctet2 = findViewById(R.id.edtTcpOctet2);
        edtTcpOctet3 = findViewById(R.id.edtTcpOctet3);
        edtTcpOctet4 = findViewById(R.id.edtTcpOctet4);
        txtTcpSubnetDetected = findViewById(R.id.txtTcpSubnetDetected);
        edtTcpPort = findViewById(R.id.edtTcpPort);
        btnTcpConnect = findViewById(R.id.btnTcpConnect);
        txtTcpStatus = findViewById(R.id.txtTcpStatus);

        // CONFIGURE: Ajout manuel (2 registres / média)
        edtUsbNode1 = findViewById(R.id.edtUsbNode1);
        edtUsbNode2 = findViewById(R.id.edtUsbNode2);
        txtUsbSerial1 = findViewById(R.id.txtUsbSerial1);
        txtUsbSerial2 = findViewById(R.id.txtUsbSerial2);
        btnUsbConnect1 = findViewById(R.id.btnUsbConnect1);
        btnUsbConnect2 = findViewById(R.id.btnUsbConnect2);

        edtBtNode1 = findViewById(R.id.edtBtNode1);
        edtBtNode2 = findViewById(R.id.edtBtNode2);
        txtBtSerial1 = findViewById(R.id.txtBtSerial1);
        txtBtSerial2 = findViewById(R.id.txtBtSerial2);
        btnBtConnect1 = findViewById(R.id.btnBtConnect1);
        btnBtConnect2 = findViewById(R.id.btnBtConnect2);
        // ✅ Valeur initiale posée ici ; refreshApiStatus() (appelée dans
        // onCreate()) écrase ensuite avec l'état réel de LcrHttpService.
        if (txtApiUrl != null) {
            txtApiUrl.setText("https://127.0.0.1:" + LcrHttpService.getApiPort());
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
        // ⚠️ RETOUR EN ARRIÈRE : le retrait de cet appel a cassé la création
        // de TOUS les onglets (pas seulement le placeholder). Remis en place
        // le temps de comprendre la vraie dépendance avant de retoucher.
        ensureRegisterTab(250, 255, true);

        mainLogViewSinceMs = 0L;
        refreshGlobalLogView();
    }

    private void wireUi() {
        if (btnScanUsb != null) btnScanUsb.setOnClickListener(v -> scanUsb());
        if (btnPingUsb != null) btnPingUsb.setOnClickListener(v -> openSelectedUsb());
        if (btnQuit != null) btnQuit.setOnClickListener(v -> confirmQuit());

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

            // Long press → dialogue Reconnect / Supprimer
            tabRegisters.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                attachTabLongPressListeners());
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

        // ✅ (retiré 3 août 2026, demande Paul : "la partie Field Service Mobile je
        // supprimerais cette notion là") — carte et wiring Field Service Mobile
        // entièrement retirés de l'onglet API (anciennement API-Face).

        // BT
        if (btnBtRefresh != null) btnBtRefresh.setOnClickListener(v -> refreshBondedBtList());
        if (btnBtConnect != null) btnBtConnect.setOnClickListener(v -> btConnectSelected());
        if (btnBtDisconnect != null) btnBtDisconnect.setOnClickListener(v -> btDisconnect());
        // ✅ BT Signal scan
        if (btnBtSignalScan != null) {
            btnBtSignalScan.setOnClickListener(v -> {
                if (!ensureLocationPermissionForBtScan(true)) {
                    if (txtBtSignalResult != null)
                        txtBtSignalResult.setText("Permission localisation requise pour le scan RSSI");
                    return;
                }
                if (txtBtSignalResult != null) txtBtSignalResult.setText("Scan RSSI en cours...");
                btnBtSignalScan.setEnabled(false);
                scanExec.execute(() -> {
                    try {
                        MultiRegisterApiFacadeImpl facade = new MultiRegisterApiFacadeImpl(MainActivity.this);
                        com.pa.lcr.lcp.ApiResult r = facade.api_btSignalScan(lastBtMac);
                        String txt;
                        if (r != null && r.code == 1) {
                            JSONObject d = r.data;
                            JSONArray scanned = (d != null) ? d.optJSONArray("scanned") : null;
                            StringBuilder sb = new StringBuilder();
                            sb.append("Scan RSSI — ").append(r.msg).append("\n");
                            if (scanned != null) {
                                for (int i = 0; i < scanned.length(); i++) {
                                    JSONObject row = scanned.optJSONObject(i);
                                    if (row == null) continue;
                                    sb.append("• ").append(row.optString("name", "?"))
                                      .append("  MAC=").append(row.optString("mac", "?"))
                                      .append("  RSSI=").append(row.optInt("rssi", 0)).append(" dBm")
                                      .append("  (").append(row.optString("rssi_quality", "?")).append(")\n");
                                }
                            }
                            txt = sb.toString().trim();
                        } else {
                            txt = "Scan RSSI: " + (r != null ? r.msg : "null");
                        }
                        final String fTxt = txt;
                        ui.post(() -> {
                            if (txtBtSignalResult != null) txtBtSignalResult.setText(fTxt);
                            btnBtSignalScan.setEnabled(true);
                        });
                    } catch (Exception e) {
                        ui.post(() -> {
                            if (txtBtSignalResult != null) txtBtSignalResult.setText("Scan RSSI ERR: " + safeMsg(e));
                            btnBtSignalScan.setEnabled(true);
                        });
                    }
                });
            });
        }
        

        // CONFIGURE: scan registres par média
        if (btnScanUsbRegs != null) btnScanUsbRegs.setOnClickListener(v -> scanRegistersUsbOnly());
        if (btnScanBtRegs != null) btnScanBtRegs.setOnClickListener(v -> scanRegistersBtOnly());
        if (btnScanWifiRegs != null) btnScanWifiRegs.setOnClickListener(v -> scanWifiRegisters());
        if (btnTcpConnect != null) btnTcpConnect.setOnClickListener(v -> connectTcpManual());

        // CONFIGURE: ajout manuel (2 slots / média)
        if (btnUsbConnect1 != null) btnUsbConnect1.setOnClickListener(v -> connectManualUsbSlot(1));
        if (btnUsbConnect2 != null) btnUsbConnect2.setOnClickListener(v -> connectManualUsbSlot(2));
        if (btnBtConnect1 != null) btnBtConnect1.setOnClickListener(v -> connectManualBtSlot(1));
        if (btnBtConnect2 != null) btnBtConnect2.setOnClickListener(v -> connectManualBtSlot(2));

        loadManualSlotsFromPrefs();
    }

private void setupTabsTop() {
        if (tabLayout == null) return;
        tabLayout.removeAllTabs();
        tabLayout.addTab(tabLayout.newTab().setText("MAIN"), true);
        tabLayout.addTab(tabLayout.newTab().setText("API"), false);
        tabLayout.addTab(tabLayout.newTab().setText("CONFIGURE"), false);
        tabLayout.addTab(tabLayout.newTab().setText("Support"), false);
        showPage(0);
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { showPage(tab.getPosition()); }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    public void showPage(int index) {
        if (pageMain != null) pageMain.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        if (pageApiFace != null) pageApiFace.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        if (pageConfigure != null) pageConfigure.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        if (pageSupport != null) pageSupport.setVisibility(index == 3 ? View.VISIBLE : View.GONE);
        if (index == 1) refreshApiStatus();
        if (index == 2) {
            updateMediaStatusUi();
            updateNodesStatusUi();
            refreshBondedBtList();
        }
        if (index == 3) refreshSupportEvents();
    }

    // =========================================================
    // Support tab: lecture de v_diagnostic_events (Phase 1b — 27 juillet 2026)
    // Lecture seule, thread background, curseur/DB toujours fermés en finally.
    // =========================================================
    /**
     * (Demandé 31 juillet 2026) Écran diagnostic (Support) : si le ticket filtré n'a pas
     * de détail de livraison local (BD vide pour ce ticket, ou #delivery-uid/#wo manquant),
     * avertit l'utilisateur ET tente automatiquement un pull Dataverse. Le lcrnode n'est pas
     * connu depuis cet écran (pas de contexte registre) — filtre Dataverse sur serial_id +
     * ticket_no seulement. Doit être appelée depuis un thread d'arrière-plan.
     */
    private void checkAndPullSupportMissingDetail(String ticketFilter, String serialFilter, int eventCount) {
        com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow row = null;
        try {
            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb lcrDb =
                    new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(getApplicationContext());
            try {
                row = lcrDb.getByTicketNo(ticketFilter);
            } finally {
                try { lcrDb.close(); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        boolean missing = (row == null)
                || row.woNum == null || row.woNum.isEmpty()
                || row.woIdGuid == null || row.woIdGuid.isEmpty();
        if (!missing) return; // détail déjà présent localement — rien à faire

        if (serialFilter.isEmpty()) {
            // Sans serial_id, impossible d'interroger Dataverse de façon fiable — informer seulement.
            runOnUiThread(() -> {
                if (txtSupportDiagnosis != null) {
                    txtSupportDiagnosis.setVisibility(View.VISIBLE);
                    txtSupportDiagnosis.setText((eventCount == 0
                            ? "Aucune donnée locale pour ce ticket. "
                            : "Ticket sans #delivery-uid/#wo local. ")
                            + "Entrez un serial_id pour tenter une recherche Dataverse.");
                }
            });
            return;
        }

        runOnUiThread(() -> {
            if (txtSupportDiagnosis != null) {
                txtSupportDiagnosis.setVisibility(View.VISIBLE);
                txtSupportDiagnosis.setText((eventCount == 0
                        ? "Aucune donnée locale pour ce ticket. "
                        : "Ticket sans #delivery-uid/#wo local. ") + "Tentative Dataverse...");
            }
        });

        try {
            // ✅ (fix 31 juillet 2026, demande Paul : "il ne doit JAMAIS concurrencer
            // aucun processus") — même verrou global que le push et le pull côté registre.
            MsalTokenProvider.MSAL_SERIAL_LOCK.acquire();
            try {
            MsalTokenProvider msal = new MsalTokenProvider(getApplicationContext());
            final String[] tokenHolder = {null};
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            msal.init(new MsalTokenProvider.InitCallback() {
                @Override public void onReady() {
                    msal.acquireTokenSilentFromWorker(new MsalTokenProvider.TokenCallback() {
                        @Override public void onSuccess(String token) { tokenHolder[0] = token; latch.countDown(); }
                        @Override public void onError(Exception e) { latch.countDown(); }
                    });
                }
                @Override public void onError(Exception e) { latch.countDown(); }
            });
            latch.await(8, java.util.concurrent.TimeUnit.SECONDS);

            if (tokenHolder[0] == null) {
                runOnUiThread(() -> {
                    if (txtSupportDiagnosis != null) {
                        txtSupportDiagnosis.setText("Pas de token Dataverse disponible (offline ou non connecté).");
                    }
                });
                return;
            }

            boolean found = com.pa.lcrdemo.dataverse.LcrDeliverySync.pullDeliveryByTicket(
                    getApplicationContext(), tokenHolder[0], serialFilter, null, ticketFilter);

            if (!found) {
                runOnUiThread(() -> {
                    if (txtSupportDiagnosis != null) {
                        txtSupportDiagnosis.setText("Aucune donnée trouvée (local + Dataverse) pour ce ticket.");
                    }
                });
                return;
            }

            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb lcrDb2 =
                    new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(getApplicationContext());
            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow pulled;
            try {
                pulled = lcrDb2.getByTicketNo(ticketFilter);
            } finally {
                try { lcrDb2.close(); } catch (Exception ignored) {}
            }

            final com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow fPulled = pulled;

            // ✅ (demandé 31 juillet 2026) : rapatrie aussi toutes les autres transactions
            // de ce même #wo (pas seulement celle du ticket demandé).
            int nTransactions = 1;
            if (fPulled != null && fPulled.woNum != null && !fPulled.woNum.isEmpty()) {
                try {
                    nTransactions = com.pa.lcrdemo.dataverse.LcrDeliverySync.pullAllDeliveriesForWorkOrder(
                            getApplicationContext(), tokenHolder[0], fPulled.woNum, serialFilter);
                    if (nTransactions <= 0) nTransactions = 1;
                } catch (Exception ignored) {}
            }
            final int fNTransactions = nTransactions;

            // ✅ (demandé 31 juillet 2026) : rapatrie aussi les autres arrêts de la même
            // journée pour ce registre (#série), au-delà du seul #wo. Ancre de date =
            // start_utc de la livraison résolue (jamais devinée/aujourd'hui par défaut).
            int nDay = 0;
            if (fPulled != null && fPulled.startUtc != null && !fPulled.startUtc.isEmpty()) {
                try {
                    nDay = com.pa.lcrdemo.dataverse.LcrDeliverySync.pullAllDeliveriesForDay(
                            getApplicationContext(), tokenHolder[0], serialFilter, fPulled.startUtc);
                } catch (Exception ignored) {}
            }
            final int fNDay = nDay;

            runOnUiThread(() -> {
                if (txtSupportDiagnosis != null && fPulled != null) {
                    txtSupportDiagnosis.setText("Récupéré depuis Dataverse — WO=" + fPulled.woNum
                            + "  delivery-uid=" + fPulled.woNum + "-" + ticketFilter
                            + "  produit=" + fPulled.produitNo
                            + "  preset=" + fPulled.presetL
                            + "  (" + fNTransactions + " transaction(s) de ce WO, "
                            + fNDay + " livraison(s) de la journée rapatriée(s))");
                }
            });
            // Note : pas de rappel à refreshSupportEvents() ici — v_diagnostic_events
            // (delivery_event/api_trace) n'est pas alimentée par ce pull Dataverse, qui
            // écrit uniquement dans LcrDeliveryStatusDb. Un second appel n'ajouterait
            // rien et risquerait une boucle si le mapping revenait incomplet.
            } finally {
                MsalTokenProvider.MSAL_SERIAL_LOCK.release();
            } // fin verrou MSAL_SERIAL_LOCK
        } catch (Exception e) {
            runOnUiThread(() -> {
                if (txtSupportDiagnosis != null) {
                    txtSupportDiagnosis.setText("Erreur pull Dataverse: " + e.getMessage());
                }
            });
        }
    }

    // =========================================================
    // "Voir le processus lié" (demandé 3 août 2026, suite écran ticket 10905) — un attempt_id
    // regroupe déjà TOUS les événements d'une même tentative de livraison dans delivery_event
    // (voir schéma delivery_attempt/delivery_event). Cette méthode affiche cette chronologie
    // complète pour l'attempt_id de la ligne tapée, plutôt que l'événement isolé seul.
    // =========================================================
    private void showRelatedProcessDialog(long attemptId) {
        new Thread(() -> {
            // ✅ (ajouté 3 août 2026, demande Paul : "forer dans l'information jusqu'à
            // trouver les traces rx/tx") — merge de deux sources distinctes en une seule
            // chronologie: delivery_event (par attempt_id) ET log_bus_event IO_TX/IO_RX
            // (qui n'a pas de notion d'attempt_id, seulement node+temps). On dérive le
            // node à partir du ticket_no/serial_id de la tentative, puis on fenêtre la
            // recherche RX/TX sur la plage de temps réelle de cette tentative (+/- 2s de
            // marge), pour éviter de ramener le trafic BT d'AUTRES tickets sur ce node.
            java.util.List<long[]> tsOrder = new java.util.ArrayList<>(); // [0]=ts, index parallèle à lines
            java.util.List<String> lines = new java.util.ArrayList<>();
            com.pa.lcr.lcp.storage.DeliveryDb dbHelper = null;
            android.database.sqlite.SQLiteDatabase db = null;
            String ticketNoForNode = null;
            long minTs = Long.MAX_VALUE, maxTs = Long.MIN_VALUE;
            try {
                dbHelper = new com.pa.lcr.lcp.storage.DeliveryDb(getApplicationContext());
                db = dbHelper.getReadableDatabase();
                try (android.database.Cursor c = db.rawQuery(
                        "SELECT ts, event_type, event_code, event_where, detail_short, ticket_no " +
                        "FROM v_diagnostic_events WHERE attempt_id = ? ORDER BY ts ASC",
                        new String[]{String.valueOf(attemptId)})) {
                    while (c.moveToNext()) {
                        long ts = c.getLong(0);
                        String type = c.getString(1);
                        String code = c.getString(2);
                        String where = c.getString(3);
                        String detail = c.getString(4);
                        if (ticketNoForNode == null) ticketNoForNode = c.getString(5);
                        if (ts < minTs) minTs = ts;
                        if (ts > maxTs) maxTs = ts;
                        String tsFmt = android.text.format.DateFormat.format("HH:mm:ss", ts).toString();
                        StringBuilder line = new StringBuilder();
                        line.append(tsFmt).append("  ").append(code != null ? code : type).append('\n');
                        if (where != null && !where.isEmpty()) line.append("    où=").append(where).append('\n');
                        if (detail != null && !detail.isEmpty()) line.append("    ").append(detail).append('\n');
                        lines.add(line.toString());
                        tsOrder.add(new long[]{ts});
                    }
                }

                // Dérivation du node pour retrouver les traces RX/TX (log_bus_event n'a pas
                // de ticket_no, seulement node) — même logique que le point 5 (auto-derive).
                if (ticketNoForNode != null && minTs != Long.MAX_VALUE) {
                    com.pa.lcr.lcp.storage.LcrDeliveryStatusDb lcrDbNode =
                        new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(getApplicationContext());
                    try {
                        com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow rowNode =
                            lcrDbNode.getByTicketNo(ticketNoForNode);
                        if (rowNode != null && rowNode.lcrnode > 0) {
                            long windowStart = minTs - 2000, windowEnd = maxTs + 2000;
                            try (android.database.Cursor c2 = db.rawQuery(
                                    "SELECT ts, src, msg FROM log_bus_event " +
                                    "WHERE node = ? AND src IN ('IO_TX','IO_RX') AND ts BETWEEN ? AND ? " +
                                    "ORDER BY ts ASC",
                                    new String[]{String.valueOf(rowNode.lcrnode),
                                                 String.valueOf(windowStart), String.valueOf(windowEnd)})) {
                                while (c2.moveToNext()) {
                                    long ts2 = c2.getLong(0);
                                    String src2 = c2.getString(1);
                                    String msg2 = c2.getString(2);
                                    String tsFmt2 = android.text.format.DateFormat.format("HH:mm:ss", ts2).toString();
                                    lines.add(tsFmt2 + "  [" + src2 + "]\n    " + (msg2 != null ? msg2 : "") + "\n");
                                    tsOrder.add(new long[]{ts2});
                                }
                            }
                        }
                    } finally {
                        try { lcrDbNode.close(); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                lines.add("Erreur lecture processus lié: " + e.getMessage());
                tsOrder.add(new long[]{0});
            } finally {
                if (db != null) try { db.close(); } catch (Exception ignored) {}
                if (dbHelper != null) try { dbHelper.close(); } catch (Exception ignored) {}
            }

            // Fusion chronologique des deux sources
            Integer[] idx = new Integer[lines.size()];
            for (int i = 0; i < idx.length; i++) idx[i] = i;
            java.util.Arrays.sort(idx, (a, b) -> Long.compare(tsOrder.get(a)[0], tsOrder.get(b)[0]));
            StringBuilder sb = new StringBuilder();
            for (int i : idx) sb.append(lines.get(i)).append('\n');

            final String text = sb.length() > 0 ? sb.toString() : "Aucun autre événement pour ce processus.";
            runOnUiThread(() -> {
                android.widget.TextView tv = new android.widget.TextView(this);
                tv.setText(text);
                tv.setTextIsSelectable(true);
                int pad = (int) (16 * getResources().getDisplayMetrics().density);
                tv.setPadding(pad, pad, pad, pad);
                android.widget.ScrollView scroll = new android.widget.ScrollView(this);
                scroll.addView(tv);
                new android.app.AlertDialog.Builder(this)
                        .setTitle("Processus lié + RX/TX (attempt_id=" + attemptId + ")")
                        .setView(scroll)
                        .setPositiveButton("Fermer", null)
                        .show();
            });
        }, "SupportRelatedProcess").start();
    }

    // ✅ (ajouté 3 août 2026, demande Paul : "faire un copier coller de l'ensemble des
    // lignes... plus facile de te montrer ce que j'ai") — sérialise la liste actuellement
    // affichée (après filtres/erreurs-seulement déjà appliqués) en texte brut, une ligne
    // par événement (en-tête + détail), et la met sur le presse-papier système.
    private void copySupportListToClipboard() {
        if (lastSupportHeaders.isEmpty()) {
            Toast.makeText(this, "Rien à copier — rafraîchis d'abord la liste", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        String ticketFilterNow = (edtSupportTicketFilter != null) ? edtSupportTicketFilter.getText().toString().trim() : "";
        String nodeFilterNow = (edtSupportNodeFilter != null) ? edtSupportNodeFilter.getText().toString().trim() : "";
        // ✅ (ajouté 3 août 2026, demande Paul : "je veux faire une sélection") — si des
        // lignes sont cochées en mode sélection, ne copier QUE celles-là; sinon, tout copier
        // comme avant (comportement inchangé quand la sélection n'est pas utilisée).
        boolean useSelection = !supportSelectedPositions.isEmpty();
        int copiedCount = useSelection ? supportSelectedPositions.size() : lastSupportHeaders.size();
        sb.append("=== Support LCR — ticket=").append(ticketFilterNow.isEmpty() ? "—" : ticketFilterNow)
          .append(" node=").append(nodeFilterNow.isEmpty() ? "—" : nodeFilterNow)
          .append(useSelection ? " (sélection : " : " (")
          .append(copiedCount).append(" événements) ===\n\n");
        for (int i = 0; i < lastSupportHeaders.size(); i++) {
            if (useSelection && !supportSelectedPositions.contains(i)) continue;
            sb.append(lastSupportHeaders.get(i)).append('\n');
            String d = (i < lastSupportDetails.size()) ? lastSupportDetails.get(i) : "";
            if (d != null && !d.isEmpty()) sb.append("    ").append(d).append('\n');
        }
        android.content.ClipboardManager clipboard =
            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText("Support LCR", sb.toString());
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, copiedCount + " événement(s) copié(s)", Toast.LENGTH_SHORT).show();
    }

    // =========================================================
    // ✅ (ajouté 3 août 2026, demande Paul : "vérifier automatiquement que les 3 sources
    // concordent — détecter les incohérences") — compare local (LcrDeliveryStatusDb),
    // backup JSON (LocalDeliveryBackup) et Dataverse (peekDeliveryByTicket, LECTURE SEULE,
    // aucune écriture) pour un même ticket_no. Rapporte chaque source trouvée/absente et
    // toute divergence sur net_l/gross_l/wo_num.
    // =========================================================
    private void runCoherenceCheck() {
        String ticketNo = (edtSupportTicketFilter != null) ? edtSupportTicketFilter.getText().toString().trim() : "";
        String serialId = (edtSupportSerialFilter != null) ? edtSupportSerialFilter.getText().toString().trim() : "";
        if (ticketNo.isEmpty()) {
            Toast.makeText(this, "Entrez un ticket_no avant de vérifier la cohérence", Toast.LENGTH_SHORT).show();
            return;
        }
        if (txtSupportDiagnosis != null) {
            txtSupportDiagnosis.setVisibility(View.VISIBLE);
            txtSupportDiagnosis.setText("Vérification cohérence en cours (local + backup + Dataverse)...");
        }

        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Cohérence ticket=").append(ticketNo).append(" ===\n\n");
            final String[] localSerialHolder = {null};

            // 1) Local
            Double localNet = null, localGross = null;
            String localWo = null;
            try {
                com.pa.lcr.lcp.storage.LcrDeliveryStatusDb lcrDb =
                    new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(getApplicationContext());
                try {
                    com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow row = lcrDb.getByTicketNo(ticketNo);
                    if (row != null) {
                        localNet = row.netL; localGross = row.grossL; localWo = row.woNum;
                        sb.append("LOCAL (BD) : trouvé — net=").append(row.netL)
                          .append(" gross=").append(row.grossL)
                          .append(" wo=").append(row.woNum)
                          .append(" sync=").append(row.syncStatus).append('\n');
                        if (row.serialId != null) localSerialHolder[0] = row.serialId;
                    } else {
                        sb.append("LOCAL (BD) : ABSENT\n");
                    }
                } finally {
                    try { lcrDb.close(); } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                sb.append("LOCAL (BD) : erreur lecture — ").append(e.getMessage()).append('\n');
            }

            // 2) Backup JSON
            Double backupNet = null, backupGross = null;
            String backupWo = null;
            try {
                com.pa.lcr.lcp.storage.LocalDeliveryBackup.BackupMatch match =
                    com.pa.lcr.lcp.storage.LocalDeliveryBackup.findLatestByTicketNo(getApplicationContext(), ticketNo);
                if (match != null) {
                    backupNet = match.json.optDouble("net_l", Double.NaN);
                    backupGross = match.json.optDouble("gross_l", Double.NaN);
                    backupWo = match.json.optString("wo_num", "");
                    sb.append("BACKUP (JSON) : trouvé — net=").append(backupNet)
                      .append(" gross=").append(backupGross)
                      .append(" wo=").append(backupWo)
                      .append(" (backup_ts=").append(match.backupTs).append(")\n");
                } else {
                    sb.append("BACKUP (JSON) : ABSENT\n");
                }
            } catch (Exception e) {
                sb.append("BACKUP (JSON) : erreur lecture — ").append(e.getMessage()).append('\n');
            }

            // 3) Dataverse (lecture seule)
            Double dvNet = null, dvGross = null;
            String dvWo = null;
            String effectiveSerial = serialId.isEmpty() ? localSerialHolder[0] : serialId;
            if (effectiveSerial == null || effectiveSerial.isEmpty()) {
                sb.append("DATAVERSE : impossible de vérifier — aucun serial_id disponible " +
                        "(remplis le champ serial_id, ou assure-toi que la ligne locale en a un)\n");
            } else {
                try {
                    MsalTokenProvider.MSAL_SERIAL_LOCK.acquire();
                    try {
                        MsalTokenProvider msal = new MsalTokenProvider(getApplicationContext());
                        final String[] tokenHolder = {null};
                        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                        msal.init(new MsalTokenProvider.InitCallback() {
                            @Override public void onReady() {
                                msal.acquireTokenSilentFromWorker(new MsalTokenProvider.TokenCallback() {
                                    @Override public void onSuccess(String token) { tokenHolder[0] = token; latch.countDown(); }
                                    @Override public void onError(Exception e) { latch.countDown(); }
                                });
                            }
                            @Override public void onError(Exception e) { latch.countDown(); }
                        });
                        latch.await(8, java.util.concurrent.TimeUnit.SECONDS);

                        if (tokenHolder[0] == null) {
                            sb.append("DATAVERSE : pas de token disponible (offline ou non connecté)\n");
                        } else {
                            org.json.JSONObject d = com.pa.lcrdemo.dataverse.LcrDeliverySync.peekDeliveryByTicket(
                                getApplicationContext(), tokenHolder[0], effectiveSerial, null, ticketNo);
                            if (d != null) {
                                dvNet = d.optDouble("filgo_net_l", Double.NaN);
                                dvGross = d.optDouble("filgo_gross_l", Double.NaN);
                                dvWo = d.optString("filgo_wo_num", "");
                                sb.append("DATAVERSE : trouvé — net=").append(dvNet)
                                  .append(" gross=").append(dvGross)
                                  .append(" wo=").append(dvWo).append('\n');
                            } else {
                                sb.append("DATAVERSE : ABSENT (jamais poussé, ou push échoué)\n");
                            }
                        }
                    } finally {
                        MsalTokenProvider.MSAL_SERIAL_LOCK.release();
                    }
                } catch (Exception e) {
                    sb.append("DATAVERSE : erreur — ").append(e.getMessage()).append('\n');
                }
            }

            // 4) Comparaison
            sb.append("\n--- Comparaison ---\n");
            java.util.List<Double> nets = new java.util.ArrayList<>();
            java.util.List<Double> grosses = new java.util.ArrayList<>();
            if (localNet != null) nets.add(localNet);
            if (backupNet != null && !backupNet.isNaN()) nets.add(backupNet);
            if (dvNet != null && !dvNet.isNaN()) nets.add(dvNet);
            if (localGross != null) grosses.add(localGross);
            if (backupGross != null && !backupGross.isNaN()) grosses.add(backupGross);
            if (dvGross != null && !dvGross.isNaN()) grosses.add(dvGross);

            boolean netOk = nets.size() <= 1 || allClose(nets);
            boolean grossOk = grosses.size() <= 1 || allClose(grosses);
            int sourcesFound = (localNet != null ? 1 : 0) + (backupNet != null ? 1 : 0) + (dvNet != null ? 1 : 0);

            if (sourcesFound == 0) {
                sb.append("⚠ AUCUNE source n'a de données pour ce ticket — rien à comparer.\n");
            } else if (sourcesFound == 1) {
                sb.append("ℹ Une seule source a des données — pas de comparaison possible, " +
                        "mais pas forcément un problème (ex: livraison très récente, sync en attente).\n");
            } else if (netOk && grossOk) {
                sb.append("✅ COHÉRENT — les ").append(sourcesFound)
                  .append(" sources trouvées concordent (net/gross identiques).\n");
            } else {
                sb.append("❌ INCOHÉRENCE DÉTECTÉE — les valeurs net/gross diffèrent entre les sources.\n");
            }

            final String text = sb.toString();
            runOnUiThread(() -> {
                if (txtSupportDiagnosis != null) {
                    txtSupportDiagnosis.setVisibility(View.VISIBLE);
                    txtSupportDiagnosis.setText(text);
                }
            });
        }, "SupportCoherenceCheck").start();
    }

    private static boolean allClose(java.util.List<Double> values) {
        double first = values.get(0);
        for (double v : values) {
            if (Math.abs(v - first) > 0.05) return false; // tolérance 0.05L (arrondi)
        }
        return true;
    }

    private void refreshSupportEvents() {
        final String ticketFilter = (edtSupportTicketFilter != null)
                ? edtSupportTicketFilter.getText().toString().trim() : "";
        final String serialFilter = (edtSupportSerialFilter != null)
                ? edtSupportSerialFilter.getText().toString().trim() : "";
        final String nodeFilter = (edtSupportNodeFilter != null)
                ? edtSupportNodeFilter.getText().toString().trim() : "";

        new Thread(() -> {
            // Chaque entrée : {ts, header, detail} — fusionné ensuite avec log_bus_event et trié
            final java.util.List<Object[]> rows = new java.util.ArrayList<>();
            com.pa.lcr.lcp.storage.DeliveryDb dbHelper = null;
            android.database.sqlite.SQLiteDatabase db = null;
            android.database.Cursor c = null;
            try {
                dbHelper = new com.pa.lcr.lcp.storage.DeliveryDb(getApplicationContext());
                db = dbHelper.getReadableDatabase();

                StringBuilder sql = new StringBuilder(
                        "SELECT ts, serial_id, ticket_no, event_type, event_code, event_where, detail_short, attempt_id, level " +
                        "FROM v_diagnostic_events WHERE 1=1 ");
                java.util.List<String> args = new java.util.ArrayList<>();
                // ✅ FIX (4 août 2026, demande Paul — "ceci touche directement la couche
                // support") — v_diagnostic_events inclut maintenant log_bus_event en 3e
                // branche (voir DeliveryDb.createDiagnosticEventsView, v21). Ces lignes
                // n'ont ni ticket_no ni serial_id (scopées par node uniquement) — le filtre
                // ticket doit donc les laisser passer plutôt que les exclure, sinon elles
                // redeviennent invisibles dès qu'un filtre ticket est actif. Dérivation
                // node→ticket conservée (ancienne fusion manuelle) mais appliquée ICI comme
                // filtre unique, pour ne plus dupliquer les lignes log_bus_event.
                String effectiveNodeFilter0 = nodeFilter;
                if (effectiveNodeFilter0.isEmpty() && !ticketFilter.isEmpty()) {
                    try {
                        com.pa.lcr.lcp.storage.LcrDeliveryStatusDb lcrDbNode0 =
                            new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(getApplicationContext());
                        try {
                            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow rowNode0 =
                                lcrDbNode0.getByTicketNo(ticketFilter);
                            if (rowNode0 != null && rowNode0.lcrnode > 0) {
                                effectiveNodeFilter0 = String.valueOf(rowNode0.lcrnode);
                            }
                        } finally {
                            try { lcrDbNode0.close(); } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {}
                }
                boolean hasTicket = !ticketFilter.isEmpty();
                boolean hasNode = !effectiveNodeFilter0.isEmpty();
                if (hasTicket && hasNode) {
                    sql.append("AND (ticket_no = ? OR (event_type = 'LOG_BUS' AND event_where = ?)) ");
                    args.add(ticketFilter);
                    args.add("LogBus(node=" + effectiveNodeFilter0 + ")");
                } else if (hasTicket) {
                    sql.append("AND ticket_no = ? ");
                    args.add(ticketFilter);
                } else if (hasNode) {
                    // Filtre node seul (sans ticket) : ne restreint QUE les lignes LOG_BUS —
                    // les événements ticket-scopés (delivery_event/api_trace) n'ont pas de
                    // notion de node dans cette vue, donc ils restent visibles peu importe
                    // le node filtré (comportement identique à avant ce fix : le filtre node
                    // seul n'a jamais restreint les événements par ticket).
                    sql.append("AND (event_type != 'LOG_BUS' OR event_where = ?) ");
                    args.add("LogBus(node=" + effectiveNodeFilter0 + ")");
                }
                if (!serialFilter.isEmpty()) {
                    sql.append("AND serial_id = ? ");
                    args.add(serialFilter);
                }
                sql.append("ORDER BY ts DESC LIMIT 300");

                c = db.rawQuery(sql.toString(), args.toArray(new String[0]));
                while (c.moveToNext()) {
                    long ts = c.getLong(0);
                    String serialId = c.getString(1);
                    String ticketNo = c.getString(2);
                    String eventType = c.getString(3);
                    String eventCode = c.getString(4);
                    String eventWhere = c.getString(5);
                    String detailShort = c.getString(6);
                    Long attemptId = c.isNull(7) ? null : c.getLong(7);
                    // ✅ FIX (6 août 2026, demande Paul — "je veux vraiment
                    // voir de manière évidente que s'il y a une erreur je
                    // veux un affichage couleur... le niveau de support à
                    // faire interagir") — level était calculé dans la vue
                    // v_diagnostic_events depuis le tout début, mais jamais
                    // sélectionné ici — donc jamais transporté jusqu'à
                    // l'écran. Ajouté à la ligne pour permettre le code
                    // couleur ci-dessous (voir SupportEventAdapter).
                    String level = c.isNull(8) ? "INFO" : c.getString(8);

                    String tsFmt = android.text.format.DateFormat.format("MM-dd HH:mm:ss", ts).toString();
                    // ✅ FIX (7 août 2026, demande Paul — "une colonne après
                    // le timestamp avec le ticket_number associé") — avant ce
                    // fix, le ticket_no était relégué à la toute fin de la
                    // ligne entre crochets, facile à manquer. Déplacé juste
                    // après le timestamp, avec une largeur fixe (padStart)
                    // pour qu'il s'aligne comme une vraie colonne d'une ligne
                    // à l'autre — txtRowHeader utilise déjà une police à
                    // espacement fixe (monospace), donc l'alignement visuel
                    // fonctionne directement sans changer le layout XML.
                    String ticketCol = ticketNo != null && !ticketNo.trim().isEmpty() ? ticketNo.trim() : "—";
                    if (ticketCol.length() > 10) ticketCol = ticketCol.substring(0, 10);
                    String header = tsFmt + "  [" + String.format(java.util.Locale.ROOT, "%-10s", ticketCol) + "]  "
                            + (eventCode != null ? eventCode : eventType);
                    String detail = (serialId != null ? "serial=" + serialId + "  " : "")
                            + (eventWhere != null ? "où=" + eventWhere + "  " : "")
                            + (detailShort != null ? detailShort : "");
                    // ✅ (ajouté 3 août 2026, demande Paul : "voir tout le processus lié") —
                    // attemptId nullable transporté jusqu'à la ligne, pour permettre au clic
                    // sur une ligne de retrouver tous les événements de la MÊME tentative
                    // (delivery_attempt), triés chronologiquement — pas juste cette ligne isolée.
                    rows.add(new Object[]{ts, header, detail, attemptId, level});
                }
            } catch (Exception e) {
                rows.add(new Object[]{System.currentTimeMillis(), "Erreur lecture v_diagnostic_events",
                        String.valueOf(e.getMessage()), null});
            } finally {
                if (c != null) try { c.close(); } catch (Exception ignored) {}
                if (db != null) try { db.close(); } catch (Exception ignored) {}
                if (dbHelper != null) try { dbHelper.close(); } catch (Exception ignored) {}
            }

            // Tri global décroissant par ts (le plus récent en premier), toutes sources confondues
            rows.sort((a, b) -> Long.compare((Long) b[0], (Long) a[0]));

            // ✅ (ajouté 3 août 2026, demande Paul : "afficher les enregistrements qui
            // sont en erreur pour visualiser facilement les erreurs") — filtre client
            // simple sur le texte déjà assemblé (header+détail), plutôt que sur la
            // colonne "level" seule : la branche api_trace de v_diagnostic_events fixe
            // TOUJOURS level='INFO' (même pour un push Dataverse échoué, voir
            // DATAVERSE_PUSH_FAILED), donc filtrer sur level manquerait ces cas. Un
            // filtre texte large (ERR/FAIL) couvre ERR_IO, JOBGET_READ_FAIL,
            // CONTINUE_RUN_FAIL, "push ERR", etc. — peu importe la source.
            final boolean errorsOnly = (chkSupportErrorsOnly != null) && chkSupportErrorsOnly.isChecked();
            final java.util.List<Object[]> filteredRows;
            if (errorsOnly) {
                filteredRows = new java.util.ArrayList<>();
                for (Object[] r : rows) {
                    String h = String.valueOf(r[1]).toUpperCase(java.util.Locale.ROOT);
                    String d = String.valueOf(r[2]).toUpperCase(java.util.Locale.ROOT);
                    String lvl = String.valueOf(r[4]).toUpperCase(java.util.Locale.ROOT);
                    // ✅ FIX (6 août 2026) — la colonne level (corrigée en DB v22) est
                    // maintenant fiable — utilisée en priorité, avec le filtre texte
                    // large en filet de sécurité pour les cas non couverts par level
                    // (ex. api_trace, toujours 'INFO' même en échec — voir commentaire
                    // plus haut).
                    if ("ERROR".equals(lvl) || "WARN".equals(lvl)
                            || h.contains("ERR") || h.contains("FAIL") || d.contains("ERR") || d.contains("FAIL")) {
                        filteredRows.add(r);
                    }
                }
            } else {
                filteredRows = rows;
            }

            final java.util.List<String> headers = new java.util.ArrayList<>();
            final java.util.List<String> details = new java.util.ArrayList<>();
            final java.util.List<Long> attemptIds = new java.util.ArrayList<>();
            final java.util.List<String> levels = new java.util.ArrayList<>();
            for (Object[] r : filteredRows) {
                headers.add((String) r[1]);
                details.add((String) r[2]);
                attemptIds.add(r.length > 3 ? (Long) r[3] : null);
                levels.add(r.length > 4 ? (String) r[4] : "INFO");
            }
            int count = filteredRows.size();

            // ✅ (demandé 31 juillet 2026) : si un ticket est filtré et que la BD locale
            // est vide pour lui, OU qu'il n'a pas de #delivery-uid/#wo, avertir et tenter
            // automatiquement un pull Dataverse.
            if (!ticketFilter.isEmpty()) {
                checkAndPullSupportMissingDetail(ticketFilter, serialFilter, count);
            }

            final int finalCount = count;
            runOnUiThread(() -> {
                if (txtSupportCount != null) {
                    txtSupportCount.setText(finalCount + " événement" + (finalCount > 1 ? "s" : ""));
                }
                if (listSupportEvents != null) {
                    lastSupportHeaders.clear();
                    lastSupportHeaders.addAll(headers);
                    lastSupportDetails.clear();
                    lastSupportDetails.addAll(details);
                    supportSelectedPositions.clear(); // ✅ nouvelle liste = sélection réinitialisée
                    SupportEventAdapter adapter = new SupportEventAdapter(this, headers, details, levels, supportSelectedPositions);
                    listSupportEvents.setAdapter(adapter);
                    // ✅ (ajouté 3 août 2026, demande Paul : "voir tout le processus lié" /
                    // "je veux faire une sélection") — en mode sélection, taper une ligne
                    // la coche/décoche au lieu d'ouvrir le dialogue "processus lié".
                    listSupportEvents.setOnItemClickListener((parent, view, position, id) -> {
                        if (supportSelectionMode) {
                            if (!supportSelectedPositions.remove(position)) {
                                supportSelectedPositions.add(position);
                            }
                            adapter.notifyDataSetChanged();
                            return;
                        }
                        Long attemptId = (position < attemptIds.size()) ? attemptIds.get(position) : null;
                        if (attemptId == null) {
                            Toast.makeText(this,
                                "Aucun processus lié pour cet événement (source LogBus ou API_TRACE)",
                                Toast.LENGTH_SHORT).show();
                            return;
                        }
                        showRelatedProcessDialog(attemptId);
                    });
                }
            });
        }, "SupportEventsLoader").start();
    }

    // =========================================================
    // Diagnostic rules engine (Phase 2 — 27 juillet 2026)
    // Exige un ticket_no dans le filtre : les règles corrèlent une chronologie par ticket.
    // =========================================================
    /**
     * Lexique des niveaux support et couches de diagnostic (demandé 31 juillet 2026) —
     * explique ce que veulent dire les deux valeurs affichées par "Diagnostiquer", pour
     * que support/utilisateur sache comment les interpréter sans avoir à demander.
     */
    /**
     * (Demandé 31 juillet 2026, suite à la perte du ticket 10898) — Restaure les livraisons
     * depuis les backups JSON dans Téléchargements (écrits par LocalDeliveryBackup à chaque
     * livraison, survivent à une désinstallation). Confirmation demandée avant d'agir —
     * ça modifie la BD locale (insertions).
     */
    private void confirmAndRunRestoreBackup() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Restaurer depuis backup")
                .setMessage("Ceci va chercher les livraisons sauvegardées dans Téléchargements " +
                        "et réinsérer localement celles qui manquent (en attente de synchronisation " +
                        "vers Dataverse). Les livraisons déjà présentes localement ne seront jamais " +
                        "écrasées.\n\nContinuer ?")
                .setPositiveButton("Restaurer", (d, w) -> runRestoreBackup())
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void runRestoreBackup() {
        if (txtSupportRestoreStatus != null) {
            txtSupportRestoreStatus.setText("Restauration en cours...");
        }
        com.pa.lcr.lcp.storage.LocalDeliveryBackup.restoreAllAsync(getApplicationContext(),
                (restored, skipped, failed, messages) -> runOnUiThread(() -> {
                    if (txtSupportRestoreStatus != null) {
                        txtSupportRestoreStatus.setText(restored + " restaurée(s), "
                                + skipped + " déjà présente(s), " + failed + " erreur(s)");
                    }
                    if (restored > 0) {
                        // Les lignes restaurées sont en PENDING — déclenche une tentative
                        // de push immédiate plutôt que d'attendre le prochain cycle.
                        try {
                            com.pa.lcrdemo.dataverse.DeliverySyncScheduler.triggerNow(getApplicationContext());
                        } catch (Exception ignored) {}
                        Toast.makeText(this, restored + " livraison(s) restaurée(s) — synchronisation lancée",
                                Toast.LENGTH_LONG).show();
                    } else if (failed > 0) {
                        Toast.makeText(this, "Restauration terminée avec des erreurs — voir détails",
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "Aucune livraison à restaurer trouvée", Toast.LENGTH_SHORT).show();
                    }
                }));
    }

    // ✅ FIX (6 août 2026, demande Paul — "peux-tu m'ajouter les couleurs
    // dans ?, au niveau de l'onglet Support") — le dialogue Lexique était en
    // texte brut, sans aucune indication visuelle de gravité. Coloré
    // maintenant avec la même logique que la liste Support (vert = simple,
    // rouge = critique), pour que le niveau saute aux yeux avant même de
    // lire le texte.
    private void showSupportLexiqueDialog() {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();

        appendColored(sb, "NIVEAUX SUPPORT\n\n", 0xFF212121, true);

        appendColored(sb, "N1 — Chauffeur\n", 0xFF2E7D32, true);
        sb.append("  Résolution simple sur le terrain : rebrancher/reconnecter le BT ou USB, "
                + "redémarrer l'app, vérifier l'appairage. Pas besoin d'appeler le support.\n\n");

        appendColored(sb, "N2 — Support technique\n", 0xFFF9A825, true);
        sb.append("  Nécessite une vérification par le support (état du registre, config "
                + "Field Service, synchronisation Dataverse).\n\n");

        appendColored(sb, "N3 — Escalade développeur\n", 0xFFE65100, true);
        sb.append("  Comportement anormal qui dépasse le dépannage standard — probable "
                + "défaillance logicielle ou matérielle à investiguer.\n\n");

        appendColored(sb, "N4 — Critique / urgent développeur\n", 0xFFB71C1C, true);
        sb.append("  Cas grave (perte de données, blocage complet) nécessitant une "
                + "intervention immédiate.\n\n");

        appendColored(sb, "N/A", 0xFF757575, true);
        sb.append(" — Aucune règle de diagnostic ne matche pour ce ticket. Ne veut pas "
                + "dire qu'il n'y a pas de problème, juste qu'aucune des règles connues ne "
                + "l'a détecté automatiquement.\n\n");

        appendColored(sb, "N1/N2 (tendance)", 0xFF757575, true);
        sb.append(" — Aucune règle exacte ne matche, mais la couche "
                + "(Transport/API/UI) est clairement dominante dans les événements de ce "
                + "ticket. Suggestion basée sur cette tendance, PAS un diagnostic confirmé — "
                + "à valider manuellement. N/A reste affiché seulement si même la couche est "
                + "indéterminée (aucun signal exploitable).\n\n");

        sb.append("─────────────────────────────\n\n");
        appendColored(sb, "COUCHES PAR COMPLEXITÉ\n\n", 0xFF212121, true);

        appendColored(sb, "TRANSPORT (BT/USB/TCP/registre)\n", 0xFF1565C0, true);
        sb.append("  Le problème semble venir de la communication physique avec le registre "
                + "(Bluetooth, USB, TCP) ou du protocole LCP lui-même — pas de la logique "
                + "applicative. Signal : erreurs classées \"level\":\"TRANSPORT\", événements "
                + "event_where=LCP, ou trafic IO_TX/IO_RX du log du registre.\n\n");

        appendColored(sb, "API\n", 0xFF6A1B9A, true);
        sb.append("  Le problème semble venir des échanges API (Field Service Mobile ↔ APK, "
                + "ou appels REST internes) — pas du transport ni de l'interface.\n\n");

        appendColored(sb, "UI\n", 0xFF00838F, true);
        sb.append("  Le problème semble venir d'une action ou d'un affichage côté interface "
                + "utilisateur (boutons A/B/C, Continuer, Terminer, etc.).\n\n");

        appendColored(sb, "INDÉTERMINÉ\n", 0xFF757575, true);
        sb.append("  Pas assez de signal dans les logs disponibles pour trancher entre les "
                + "trois couches ci-dessus.\n\n");

        sb.append("⚠️ La couche est déterminée par vote majoritaire sur les logs présents — "
                + "c'est un point de départ pour aiguiller, pas un diagnostic définitif. Le "
                + "détail des comptes (Transport=X API=Y UI=Z) est toujours affiché pour "
                + "vérifier si le verdict est solide ou serré.");

        new android.app.AlertDialog.Builder(this)
                .setTitle("Lexique — Niveaux et couches de diagnostic")
                .setMessage(sb)
                .setPositiveButton("Fermer", null)
                .show();
    }

    /** Ajoute un segment de texte coloré (et optionnellement en gras) à un SpannableStringBuilder. */
    private void appendColored(android.text.SpannableStringBuilder sb, String text, int color, boolean bold) {
        int start = sb.length();
        sb.append(text);
        int end = sb.length();
        sb.setSpan(new android.text.style.ForegroundColorSpan(color), start, end,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) {
            sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, end,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void runSupportDiagnosis() {
        final String ticketFilter = (edtSupportTicketFilter != null)
                ? edtSupportTicketFilter.getText().toString().trim() : "";
        final String nodeFilter = (edtSupportNodeFilter != null)
                ? edtSupportNodeFilter.getText().toString().trim() : "";

        if (ticketFilter.isEmpty()) {
            if (txtSupportDiagnosis != null) {
                txtSupportDiagnosis.setVisibility(View.VISIBLE);
                txtSupportDiagnosis.setText("Entrez un ticket_no dans le filtre avant de diagnostiquer.");
            }
            return;
        }

        new Thread(() -> {
            java.util.List<com.pa.lcr.lcp.diagnostic.DiagnosticMatch> matches;
            try {
                com.pa.lcr.lcp.diagnostic.DiagnosticRuleEngine engine =
                        new com.pa.lcr.lcp.diagnostic.DiagnosticRuleEngine(getApplicationContext());
                matches = engine.evaluateForTicket(ticketFilter);
            } catch (Exception e) {
                matches = new java.util.ArrayList<>();
            }
            final java.util.List<com.pa.lcr.lcp.diagnostic.DiagnosticMatch> finalMatches = matches;

            // ✅ (demandé 31 juillet 2026) : triage sur TOUS les logs présents (v_diagnostic_events
            // + log_bus_event, pas seulement les règles qui matchent) — deux valeurs pour
            // aiguiller support/utilisateur :
            //   1. Niveau support : le pire (le plus élevé) parmi les règles qui matchent
            //   2. Couche par complexité : où le problème semble se situer (Transport/API/UI)
            String[] triage = computeSupportTriage(ticketFilter, nodeFilter, finalMatches);
            final String triageSupportLevel = triage[0];
            final String triageLayer = triage[1];
            final String triageDetail = triage[2];

            final StringBuilder sb = new StringBuilder();
            sb.append("▶ Niveau support suggéré : ").append(triageSupportLevel).append("\n");
            sb.append("▶ Couche probable : ").append(triageLayer).append("  (").append(triageDetail).append(")\n\n");

            if (finalMatches.isEmpty()) {
                sb.append("Aucune règle de diagnostic ne matche pour ce ticket.");
            } else {
                for (com.pa.lcr.lcp.diagnostic.DiagnosticMatch m : finalMatches) {
                    sb.append("• [").append(m.supportLevel).append(" — ").append(m.confidence).append("%] ")
                      .append(m.diagnostic);
                    if (m.recommendedAction != null && !m.recommendedAction.isEmpty()) {
                        sb.append("\n  → ").append(m.recommendedAction);
                    }
                    sb.append("\n");
                }
            }

            runOnUiThread(() -> {
                lastSupportMatches = finalMatches;
                if (txtSupportDiagnosis != null) {
                    txtSupportDiagnosis.setVisibility(View.VISIBLE);
                    txtSupportDiagnosis.setText(sb.toString().trim());
                }
                if (rowSupportIncident != null) {
                    rowSupportIncident.setVisibility(finalMatches.isEmpty() ? View.GONE : View.VISIBLE);
                }
                // ✅ FIX (7 août 2026, demande Paul — "je veux que tu ajoutes
                // une approche de résolution lorsqu'il y a une erreur selon
                // le niveau support apporté, affiché dans une fenêtre
                // dialogue") — recommendedAction existait déjà par règle,
                // mais restait noyé dans le texte en ligne de l'écran
                // Support. Affiché maintenant dans une vraie fenêtre
                // dialogue, colorée par niveau (même palette que le Lexique),
                // pour que l'approche de résolution soit le point central,
                // pas une ligne perdue parmi le reste.
                showDiagnosisResolutionDialog(triageSupportLevel, triageLayer, triageDetail, finalMatches);
            });
        }, "SupportDiagnosisLoader").start();
    }

    /** Couleur associée à un niveau support (N1=vert...N4=rouge), même palette que le Lexique. */
    private int colorForSupportLevel(String level) {
        if (level == null) return 0xFF757575;
        switch (level.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "N1": return 0xFF2E7D32; // vert
            case "N2": return 0xFFF9A825; // jaune/orange
            case "N3": return 0xFFE65100; // orange foncé
            case "N4": return 0xFFB71C1C; // rouge
            default:   return 0xFF757575; // gris — N/A ou inconnu
        }
    }

    /**
     * Fenêtre dialogue affichant l'approche de résolution suggérée pour le ticket
     * diagnostiqué, colorée par niveau support — voir demande Paul du 7 août 2026.
     */
    private void showDiagnosisResolutionDialog(String triageSupportLevel, String triageLayer,
            String triageDetail, java.util.List<com.pa.lcr.lcp.diagnostic.DiagnosticMatch> matches) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();

        int levelColor = colorForSupportLevel(triageSupportLevel);
        appendColored(sb, "Niveau support : " + triageSupportLevel + "\n", levelColor, true);
        sb.append("Couche probable : ").append(triageLayer).append("  (").append(triageDetail).append(")\n\n");

        if (matches == null || matches.isEmpty()) {
            appendColored(sb, "Aucune règle de diagnostic ne matche pour ce ticket.\n\n", 0xFF757575, false);
            sb.append("Ça ne veut pas dire qu'il n'y a pas de problème — juste qu'aucune règle "
                    + "connue ne l'a détecté automatiquement. Vérifie l'onglet Support pour le détail "
                    + "brut des événements.");
        } else {
            for (com.pa.lcr.lcp.diagnostic.DiagnosticMatch m : matches) {
                int mColor = colorForSupportLevel(m.supportLevel);
                appendColored(sb, "● [" + m.supportLevel + " — " + m.confidence + "%] ", mColor, true);
                sb.append(m.diagnostic).append("\n");
                if (m.recommendedAction != null && !m.recommendedAction.trim().isEmpty()) {
                    appendColored(sb, "  → Approche de résolution :\n", 0xFF212121, true);
                    sb.append("  ").append(m.recommendedAction.trim()).append("\n");
                } else {
                    appendColored(sb, "  → Aucune approche de résolution enregistrée pour cette règle.\n", 0xFF757575, false);
                }
                sb.append("\n");
            }
        }

        new android.app.AlertDialog.Builder(this)
                .setTitle("Diagnostic — Approche de résolution")
                .setMessage(sb)
                .setPositiveButton("Fermer", null)
                .show();
    }

    /**
     * Triage global (demandé 31 juillet 2026) : agrège TOUS les logs présents pour ce
     * ticket/node — v_diagnostic_events (event_where, level, data_json) et log_bus_event
     * (src) — et produit deux valeurs pour aiguiller support/utilisateur :
     *   [0] niveau support suggéré : le pire (le plus élevé) parmi les règles qui matchent
     *       (N1=chauffeur, N2=support, N3/N4=escalade dev). "N/A" si aucune règle ne matche.
     *   [1] couche probable : TRANSPORT / API / UI / INDÉTERMINÉ — dérivée par vote majoritaire
     *       sur les signaux disponibles (pas une science exacte, un point de départ pour aiguiller).
     *   [2] détail des comptes par couche, pour transparence (pas juste un verdict opaque).
     * Doit être appelée depuis un thread d'arrière-plan.
     */
    private String[] computeSupportTriage(String ticketFilter, String nodeFilter,
                                           java.util.List<com.pa.lcr.lcp.diagnostic.DiagnosticMatch> matches) {
        // ✅ (refactor 31 juillet 2026) — délègue au moteur partagé SupportTriageEngine, pour
        // que l'onglet Support (ici) et l'API HTTP exposent EXACTEMENT la même logique de
        // triage, plutôt que deux implémentations qui pourraient diverger avec le temps.
        com.pa.lcr.lcp.diagnostic.SupportTriageEngine.TriageResult r =
                com.pa.lcr.lcp.diagnostic.SupportTriageEngine.computeTriage(
                        getApplicationContext(), ticketFilter, nodeFilter, matches);
        String detail = "Transport=" + r.transportCount + " API=" + r.apiCount
                + " UI=" + r.uiCount + " Indéterminé=" + r.indetermineCount;
        return new String[]{r.supportLevel, r.layer, detail};
    }

    // (severityOfSupportLevel supprimée — logique maintenant dans SupportTriageEngine, partagée avec l'API)

    // =========================================================
    // Boucle de rétroaction (Phase 3 — 27 juillet 2026)
    // Enregistre chaque diagnostic actuellement affiché dans incident_history.
    // Upsert géré par IncidentHistoryStore (incrémente occurrence_count si déjà vu).
    // =========================================================
    private void recordSupportIncidents() {
        final java.util.List<com.pa.lcr.lcp.diagnostic.DiagnosticMatch> matches = lastSupportMatches;
        if (matches == null || matches.isEmpty()) return;

        final String ticketFilter = (edtSupportTicketFilter != null)
                ? edtSupportTicketFilter.getText().toString().trim() : "";
        final String serialFilter = (edtSupportSerialFilter != null)
                ? edtSupportSerialFilter.getText().toString().trim() : "";
        final String validatedBy = (edtSupportValidatedBy != null)
                ? edtSupportValidatedBy.getText().toString().trim() : "";

        new Thread(() -> {
            try {
                com.pa.lcr.lcp.storage.IncidentHistoryStore store =
                        new com.pa.lcr.lcp.storage.IncidentHistoryStore(getApplicationContext());
                for (com.pa.lcr.lcp.diagnostic.DiagnosticMatch m : matches) {
                    store.recordIncident(m.ruleId, serialFilter.isEmpty() ? null : serialFilter,
                            ticketFilter.isEmpty() ? null : ticketFilter, m.diagnostic, null, null, null,
                            validatedBy.isEmpty() ? null : validatedBy);
                }
            } catch (Exception ignored) {
                // Best-effort — un incident non enregistré ne doit jamais bloquer l'utilisateur.
            }
            runOnUiThread(() -> toast("Incident(s) enregistré(s) dans l'historique."));
        }, "IncidentHistoryWriter").start();
    }

    /**
     * Adapter simple 2 lignes (header + detail) pour la liste Support.
     */
    private static final class SupportEventAdapter extends ArrayAdapter<String> {
        private final java.util.List<String> details;
        private final java.util.List<String> levels;
        private final java.util.Set<Integer> selectedPositions;

        SupportEventAdapter(android.content.Context ctx, java.util.List<String> headers,
                             java.util.List<String> details, java.util.List<String> levels,
                             java.util.Set<Integer> selectedPositions) {
            super(ctx, R.layout.row_support_event, R.id.txtRowHeader, headers);
            this.details = details;
            this.levels = levels;
            this.selectedPositions = selectedPositions;
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            View row = super.getView(position, convertView, parent);
            TextView txtDetail = row.findViewById(R.id.txtRowDetail);
            if (txtDetail != null && position < details.size()) {
                txtDetail.setText(details.get(position));
            }
            // ✅ FIX (6 août 2026, demande Paul — "je veux vraiment voir de
            // manière évidente que s'il y a une erreur je veux un affichage
            // couleur... le niveau de support à faire interagir") — code
            // couleur par niveau, sur toute la ligne (fond) ET le texte de
            // l'en-tête, pour que ce soit visible d'un coup d'œil, pas juste
            // lisible en cherchant le mot "ERR" dans le texte.
            String level = (position < levels.size()) ? levels.get(position) : "INFO";
            TextView txtHeader = row.findViewById(R.id.txtRowHeader);
            int bgColor;
            int headerColor;
            switch (level == null ? "INFO" : level) {
                case "ERROR":
                    bgColor = 0x33F44336;      // rouge translucide
                    headerColor = 0xFFB71C1C;  // rouge foncé
                    break;
                case "WARN":
                    bgColor = 0x33FF9800;      // orange translucide
                    headerColor = 0xFFE65100;  // orange foncé
                    break;
                // ✅ FIX (7 août 2026, demande Paul — "voir de manière
                // évidente les connexions et déconnexions") — couleurs
                // dédiées, distinctes des ERROR/WARN, pour repérer ces
                // transitions d'un coup d'œil dans la liste.
                case "CONNECT":
                    bgColor = 0x334CAF50;      // vert translucide
                    headerColor = 0xFF2E7D32;  // vert foncé
                    break;
                case "DISCONNECT":
                    bgColor = 0x33607D8B;      // bleu-gris translucide
                    headerColor = 0xFF37474F;  // bleu-gris foncé
                    break;
                default:
                    bgColor = 0x00000000;
                    headerColor = 0xFF212121;  // gris foncé standard
                    break;
            }
            if (selectedPositions.contains(position)) {
                // ✅ (ajouté 3 août 2026) — surlignage de sélection garde priorité visuelle
                row.setBackgroundColor(0x334CAF50);
            } else {
                row.setBackgroundColor(bgColor);
            }
            if (txtHeader != null) txtHeader.setTextColor(headerColor);
            return row;
        }
    }

    // =========================
// Register tabs helpers (multi-media)
// =========================

    public static String mediaShortFromTransportKey(String transportKey) {
        if (transportKey == null) return "—";
        String k = transportKey.trim().toUpperCase(java.util.Locale.ROOT);
        if (k.startsWith("BT:")) return "BT";
        if (k.startsWith("USB")) return "USB";
        if (k.startsWith("TCP:")) return "TCP";
        if (k.contains("BT")) return "BT";
        if (k.contains("USB")) return "USB";
        if (k.contains("TCP")) return "TCP";
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

    public static String tabKeyOf(String mediaShort, int node, String serialId) {
        String m = (mediaShort == null || mediaShort.trim().isEmpty()) ? "—" : mediaShort.trim();
                return m + ":" + node + ":" + safeSerial(serialId);
    }

    private static String regKeyOf(int node, String serialId) {
        return (node & 0xFF) + "#" + safeSerial(serialId);
    }

    private String tabLabelOf(String mediaShort, int node, String serialId) {
        return tabLabelOf(mediaShort, node, serialId, false);
    }

    private String tabLabelOf(String mediaShort, int node, String serialId, boolean isLc3) {
        String m = (mediaShort == null || mediaShort.trim().isEmpty()) ? "—" : mediaShort.trim();
        String badge = isLc3 ? "[LC3] " : "[LCR-II] ";
        return m + " - " + badge + serialShort(serialId) + " - " + node;
    }

    private boolean isTransportReady(String transportKey) {
        try {
            if (mediaTransportManager == null) return false;
            if (transportKey == null || transportKey.trim().isEmpty()) return false;
            // getByKey retourne null si DISCONNECTED ou ERROR
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

        // ✅ Format: BT(OFF) - [LC3] 123456 - 250
        updateRegisterTabLabel(tabKey, tabLabelOf(mediaLabel, spec.node, spec.serialId, spec.isLc3) + (spec.qtySuffix != null ? spec.qtySuffix : ""));

        try {
            Fragment f = getSupportFragmentManager().findFragmentByTag("regtab_" + tabKey);
            if (f instanceof RegisterTabFragment) {
                ((RegisterTabFragment) f).onTabMediaStatusChanged(ready, media);
            }
        } catch (Exception ignored) {}

        persistTabMediaStatusForApi(spec, ready, media);
    }

    public void refreshAllTabsMediaStatus() {
        try {
            ArrayList<String> keys;
            synchronized (tabsByKey) { keys = new ArrayList<>(tabsByKey.keySet()); }
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
    public void upsertRegisterTabFromScan(String transportKey, int node, int from, String serialId, boolean focus) {
        upsertRegisterTabFromScan(transportKey, node, from, serialId, focus, resolveIsLc3(transportKey, node));
    }

    // ✅ Point d'entrée UNIQUE pour déterminer si un registre est LC3, avant
    // de créer/mettre à jour un onglet. Remplace les vérifications dupliquées
    // qui existaient séparément dans DeepLinkHandler, RegisterConnectionHelper
    // et l'ancienne version de cette méthode (qui devinait via un onglet
    // existant — toujours faux pour un premier onglet créé sur un LC3).
    //
    // Ordre de résolution :
    //   1) DeliveryController.getLink() — source de vérité si une session
    //      est déjà active pour ce (transportKey, node).
    //   2) Onglet existant (repli) — utile si un onglet a déjà été créé
    //      correctement avant qu'une session ne soit (re)créée.
    public boolean resolveIsLc3(String transportKey, int node) {
        try {
            com.pa.lcr.lcp.DeliveryController dc =
                    com.pa.lcr.lcp.RegisterSessionManager.get(this).getController(transportKey, node);
            if (dc != null && dc.getLink() instanceof com.pa.lcr.lcp.Lc3Link) return true;
            if (dc != null) return false; // session existe et n'est PAS Lc3Link → LCR-II confirmé
        } catch (Exception ignored) {}

        try {
            String mediaShort = mediaShortFromTransportKey(transportKey);
            // on ne connaît pas encore le serial ici pour un tabKey exact —
            // on cherche par (média, node) parmi les onglets existants.
            synchronized (tabsByKey) {
                for (TabSpec spec : tabsByKey.values()) {
                    if (spec != null && spec.node == node
                            && transportKey.equalsIgnoreCase(spec.transportKey)) {
                        return spec.isLc3;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ✅ Nouvelle étape de résolution (demandée) : AVANT de sonder quoi que ce
    // soit (transport/onglet/registre), valider si le node+#série demandés
    // sont déjà ce qui tourne sur le MÉDIA ACTUELLEMENT ACTIF. Si oui →
    // réutilisation immédiate, aucun scan/sondage d'un autre média nécessaire.
    // Si non → retourne null, laissant le flux normal (resolveOrCreateForNode
    // / auto-connect) chercher ailleurs.
    //
    // C'est ce qui évite de sonder un TCP-LC3 déjà connecté quand la
    // livraison en cours cible en réalité un tout autre registre BT.
    public String resolveIfActiveMatches(int node, String serialId) {
        if (serialId == null || serialId.trim().isEmpty()) return null;
        try {
            // ✅ FIX (4 août 2026, demande Paul — "on est supposé trouver le
            // média pour un registre, on trouve des médias, mais le média
            // réel est mis à OFF, ça n'a pas de sens") — RÉPONSE : ce check
            // ne validait QUE mediaTransportManager.getActiveKey(), un état
            // "actif" purement EN MÉMOIRE (sert seulement à l'exclusivité
            // entre transports), jamais persisté — remis à zéro à CHAQUE
            // redémarrage du processus (Android tue l'app en arrière-plan
            // très régulièrement, confirmé dans tous les logs analysés
            // aujourd'hui). Le port USB, lui, physiquement, n'a pas bougé.
            // Corrigé : on cherche maintenant le tab par node+#série d'abord,
            // puis on vérifie l'état RÉEL de SON transport pinné
            // (io.isOpen()) — peu importe ce que "actif" dit en mémoire — et
            // on l'active nous-mêmes s'il est physiquement prêt mais pas
            // encore marqué actif.
            // ✅ FIX (6 août 2026, concurrence) — copie défensive plutôt que
            // synchronized() autour de toute la boucle : le corps fait des
            // appels potentiellement lents (activateExclusive, getOrCreate)
            // qu'on ne veut pas exécuter en tenant le verrou de tabsByKey.
            java.util.List<TabSpec> specsSnapshot;
            synchronized (tabsByKey) { specsSnapshot = new ArrayList<>(tabsByKey.values()); }
            for (TabSpec spec : specsSnapshot) {
                if (spec == null) continue;
                if (spec.node != node) continue;
                if (spec.serialId == null) continue;
                if (!serialId.trim().equalsIgnoreCase(spec.serialId.trim())) continue;
                if (spec.transportKey == null || spec.transportKey.trim().isEmpty()) continue;

                String candidateKey = spec.transportKey.trim();
                TransportIo io = (mediaTransportManager != null) ? mediaTransportManager.getByKey(candidateKey) : null;
                boolean reallyOpen = false;
                try { reallyOpen = (io != null && io.isOpen()); } catch (Exception ignored2) {}
                if (!reallyOpen) {
                    android.util.Log.i("MainActivity", "resolveIfActiveMatches: tab trouvé pour node=" + node
                        + " serial=" + serialId + " sur " + candidateKey + " mais transport réellement fermé — laisser chercher ailleurs");
                    continue;
                }

                // Le port est réellement ouvert — s'assurer qu'il est bien marqué actif
                // (pas de vol d'exclusivité si une livraison tourne ailleurs — même garde
                // que partout dans l'app).
                String activeKeyNow = (mediaTransportManager != null) ? mediaTransportManager.getActiveKey() : null;
                if (activeKeyNow == null || !activeKeyNow.equalsIgnoreCase(candidateKey)) {
                    if (!isTransportSwitchSafe(candidateKey, "RESOLVE_IF_ACTIVE_MATCHES")) {
                        android.util.Log.i("MainActivity", "resolveIfActiveMatches: " + candidateKey
                            + " physiquement ouvert mais livraison active ailleurs — laisser chercher ailleurs");
                        continue;
                    }
                    try { mediaTransportManager.activateExclusive(candidateKey, "RESOLVE_IF_ACTIVE_MATCHES"); } catch (Exception ignored2) {}
                }

                // Confirmer qu'une session vivante existe bien pour ce couple
                com.pa.lcr.lcp.DeliveryController dc =
                        com.pa.lcr.lcp.RegisterSessionManager.get(this).getController(candidateKey, node);
                if (dc == null) {
                    // ✅ Transport physiquement ouvert mais pas encore de session — la
                    // créer maintenant plutôt que d'abandonner : c'est exactement le
                    // cas "même série, même node, même USB, juste le processus a
                    // redémarré" que ce fix vise.
                    try { dc = com.pa.lcr.lcp.RegisterSessionManager.get(this).getOrCreate(candidateKey, node, 255, io); } catch (Exception ignored2) {}
                    if (dc == null) {
                        android.util.Log.i("MainActivity", "resolveIfActiveMatches: " + candidateKey
                            + " ouvert mais session non créable — abandon, laisser chercher un autre média");
                        continue;
                    }
                }
                // ✅ FIX : ne pas se contenter que la session EXISTE — vérifier
                // qu'elle est réellement CONNECTED. Sinon, laisser le flux normal
                // (resolveOrCreateForNode / auto-connect) chercher un autre média,
                // au lieu de lancer la livraison sur un registre pas prêt.
                if (dc.getState() != com.pa.lcr.lcp.DeliveryState.CONNECTED) {
                    android.util.Log.i("MainActivity", "resolveIfActiveMatches: média " + candidateKey
                            + " trouvé mais état=" + dc.getState() + " (pas CONNECTED) — chercher un autre média");
                    continue;
                }
                android.util.Log.i("MainActivity", "resolveIfActiveMatches: " + candidateKey
                        + " réellement ouvert ET CONNECTED (node=" + node + " serial=" + serialId + ") — réutilisation directe");
                return candidateKey;
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ✅ Rendu public : DeepLinkHandler et RegisterConnectionHelper doivent
    // pouvoir passer isLc3 explicitement (vrai type de Link connu via
    // DeliveryController.getLink()), plutôt que de passer par la version à
    // 5 arguments qui devine isLc3 depuis un onglet existant — toujours
    // faux pour un premier onglet créé sur un registre LC3.
    public void upsertRegisterTabFromScan(String transportKey, int node, int from, String serialId, boolean focus, boolean isLc3) {
        // ✅ Log d'entrée AVEC pile d'appel — pour identifier, une fois pour
        // toutes, QUI appelle cette méthode avec quelles données. Le log de
        // migration seul ne suffisait pas : il ne se déclenche que la 2e fois
        // (quand un ancien tab existe déjà) — jamais lors de la toute première
        // création, qui est justement le moment qu'on n'arrivait pas à tracer.
        android.util.Log.i("MainActivity", "upsertRegisterTabFromScan: APPEL transportKey=" + transportKey
                + " node=" + node + " serial=" + serialId + " isLc3=" + isLc3 + " focus=" + focus
                + "\n" + android.util.Log.getStackTraceString(new Exception("stacktrace-only")));
        if (node < 1 || (!isLc3 && node > 250)) return;
        if (from < 0 || from > 255) from = 255;
        String mediaShort = mediaShortFromTransportKey(transportKey);
        String serial = safeSerial(serialId);
        if (serial.isEmpty()) return;

        // 1) retirer les tabs legacy (serial vide) dès qu'on trouve au moins un registre
        removeAllUnknownSerialTabsBestEffort();

        // 2) clear ciblé si migration (même node+serial+type, média différent)
        // ✅ FIX : migration réelle UNIQUEMENT si l'ancien transport n'est plus
        // joignable. Avant ce correctif, deux registres INDÉPENDANTS partageant
        // par coïncidence le même (node, serial, type) sur deux médias
        // différents pouvaient s'écraser l'un l'autre — alors qu'ils doivent
        // pouvoir coexister connectés simultanément (voir onConfigureMediaActivated).
        // ✅ FIX : plus de suffixe ":lc3"/":lcr" dans regKey — même #série + même
        // node = MÊME registre physique, peu importe le type détecté (correct
        // ou mal détecté) lors d'une tentative précédente. Avant ce correctif,
        // un ancien tab mal typé (ex: isLc3=true par erreur) utilisait un regKey
        // différent ("...:lc3" vs "...:lcr"), donc n'était JAMAIS trouvé lors de
        // la recherche du "vieux tab à migrer" — les deux persistaient.
        String regKey = regKeyOf(node, serial);
        String newTabKey = tabKeyOf(mediaShort, node, serial);
        String oldTabKey = regKeyToTabKey.get(regKey);
        if (oldTabKey != null && !oldTabKey.equals(newTabKey)) {
            TabSpec oldSpec = tabsByKey.get(oldTabKey);
            android.util.Log.i("MainActivity", "upsertRegisterTabFromScan: regKey=" + regKey
                    + " oldTabKey=" + oldTabKey + " newTabKey=" + newTabKey
                    + " oldSpec.isLc3=" + (oldSpec != null ? oldSpec.isLc3 : "null") + " isLc3=" + isLc3);
            if (oldSpec != null) {
                // ✅ RÈGLE SIMPLE (demandée) : même #série + même node = on
                // reprend, on supprime l'ancien onglet et on applique le nouveau
                // média — sans AUCUNE condition, ni sur le transport ni sur
                // isLc3. Avant ce correctif, si un ancien tab avait été créé
                // avec un isLc3 incorrect (ex: faux-positif LC3 sur un LCR-II
                // réel), la migration ne se déclenchait JAMAIS puisqu'elle
                // exigeait oldSpec.isLc3 == isLc3 — les deux onglets
                // persistaient indéfiniment côte à côte pour LE MÊME registre.
                android.util.Log.i("MainActivity", "upsertRegisterTabFromScan: MIGRATION — suppression de " + oldTabKey);
                removeTabAndFragment(oldTabKey, "migrated to " + newTabKey);
            }
        }
        regKeyToTabKey.put(regKey, newTabKey);
          // Pré-populer le serial pour LC3 avant création du fragment
        if (isLc3 && !serial.isEmpty()) {
            try {
                RegisterSessionManager sm = RegisterSessionManager.get(getApplicationContext());
                sm.bindExpectedSerial(node, serial);
            } catch (Exception ignored) {}
        }      

        // 3) upsert tab
        TabSpec existing = tabsByKey.get(newTabKey);
        if (existing == null) {
            TabSpec spec = new TabSpec(newTabKey, mediaShort, transportKey, node, from, serial, isLc3);
            tabsByKey.put(newTabKey, spec);
            addRegisterTabUi(spec);
            logUi(null, "TAB registre ajouté: " + tabLabelOf(mediaShort, node, serial, isLc3));
        } else {
            TabSpec spec = new TabSpec(newTabKey, mediaShort, transportKey, node, from, serial, isLc3);
            tabsByKey.put(newTabKey, spec);
            updateRegisterTabLabel(newTabKey, tabLabelOf(mediaShort, node, serial, isLc3));
        }

        if (focus) {
            selectRegisterTabByKey(newTabKey);
            showRegisterFragmentByKey(newTabKey);
        }
    }

    private void removeAllUnknownSerialTabsBestEffort() {
        try {
            java.util.ArrayList<String> toRemove = new java.util.ArrayList<>();
            // ✅ FIX (6 août 2026, concurrence)
            synchronized (tabsByKey) {
                for (java.util.Map.Entry<String, TabSpec> e : tabsByKey.entrySet()) {
                    if (e == null) continue;
                    TabSpec s = e.getValue();
                    if (s == null) continue;
                    if (s.serialId == null || s.serialId.trim().isEmpty()) {
                        toRemove.add(e.getKey());
                    }
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
        t.setTag(spec.tabKey);
        tabRegisters.addTab(t, false);
        // ✅ FIX (7 août 2026, demande Paul — "ajouter une icône à gauche du
        // tab pour faire rafraîchir le tab") — vue personnalisée avec icône
        // cliquable + texte, remplace le setText() simple d'avant.
        View custom = getLayoutInflater().inflate(R.layout.tab_register_custom, null);
        TextView txtLabel = custom.findViewById(R.id.txtTabLabel);
        if (txtLabel != null) txtLabel.setText(tabLabelOf(spec.mediaShort, spec.node, spec.serialId, spec.isLc3));
        ImageView imgRefresh = custom.findViewById(R.id.imgTabRefresh);
        if (imgRefresh != null) {
            imgRefresh.setOnClickListener(v -> onTabRefreshClicked(spec.tabKey));
        }
        t.setCustomView(custom);
    }

    /** ✅ AJOUTÉ (7 août 2026, demande Paul) — clic sur l'icône de
     *  rafraîchissement à gauche d'un tab : relance une vérification de
     *  connexion pour CE tab précis (même chemin que le bouton Status),
     *  sans avoir à ouvrir le tab d'abord. Chaque clic tracé dans Support. */
    private void onTabRefreshClicked(String tabKey) {
        try {
            logUi(null, "[TAB-REFRESH] Clic icône rafraîchir — tabKey=" + tabKey);
            Fragment f = getSupportFragmentManager().findFragmentByTag("regtab_" + tabKey);
            if (f instanceof RegisterTabFragment) {
                ((RegisterTabFragment) f).triggerManualRefreshFromTabIcon();
            } else {
                refreshOneTabMediaStatus(tabKey);
            }
        } catch (Exception ignored) {}
    }

    private void updateRegisterTabLabel(String tabKey, String label) {
        try {
            if (tabRegisters == null) return;
            for (int i = 0; i < tabRegisters.getTabCount(); i++) {
                TabLayout.Tab t = tabRegisters.getTabAt(i);
                if (t == null) continue;
                Object tag = t.getTag();
                if (tag instanceof String && tabKey.equals(tag)) {
                    View custom = t.getCustomView();
                    if (custom != null) {
                        TextView txtLabel = custom.findViewById(R.id.txtTabLabel);
                        if (txtLabel != null) { txtLabel.setText(label); return; }
                    }
                    t.setText(label); // repli si la vue personnalisée n'existe pas encore
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

        // ✅ B1 FSM: activer le transport du tab (évite boutons morts après switch)
        ensureActiveTransport(spec.transportKey, "TAB_SWITCH");

        currentTabKey = tabKey;
        currentRegNode = spec.node;

        if (txtActiveNode != null) {
            txtActiveNode.setText("Node actif : " + tabLabelOf(spec.mediaShort, spec.node, spec.serialId, spec.isLc3));
        }

        FragmentManager fm = getSupportFragmentManager();
        String tag = "regtab_" + tabKey;
        Fragment existing = fm.findFragmentByTag(tag);
        // Recréer si serial manquant dans le fragment existant

        boolean needsRebuild = false;
            if (existing instanceof RegisterTabFragment) {
                String existingSerial = ((RegisterTabFragment) existing).getSerialFromArgs();
                int existingNode = ((RegisterTabFragment) existing).getNodeFromArgs(); // ← ajouter
                needsRebuild = (spec.serialId != null && !spec.serialId.trim().isEmpty()
                        && !spec.serialId.trim().equals(existingSerial != null ? existingSerial.trim() : ""))
                    || (spec.node != existingNode); // ← ajouter
            }

        Fragment f = (existing != null && !needsRebuild) ? existing
                : RegisterTabFragment.newInstance(spec.node, spec.from, spec.serialId, spec.transportKey);
        FragmentTransaction tx = fm.beginTransaction();
        tx.replace(R.id.registerContainer, f, tag);
        tx.setReorderingAllowed(true);
        tx.commitAllowingStateLoss();
        ui.postDelayed(() -> refreshOneTabMediaStatus(tabKey), 50);
        ui.postDelayed(() -> {
            try {
             Fragment ff = getSupportFragmentManager().findFragmentByTag("regtab_" + tabKey);
             if (ff instanceof RegisterTabFragment) {
                ((RegisterTabFragment) ff).onTabActivated();
             }
            } catch (Exception ignored) {}
        }, 100);        
    }

    /**
     * ✅ Clear ciblé A1: retire TAB + Fragment explicitement.
     */
    private void attachTabLongPressListeners() {
        if (tabRegisters == null) return;
        android.view.ViewGroup strip = (android.view.ViewGroup) tabRegisters.getChildAt(0);
        if (strip == null) return;
        for (int i = 0; i < strip.getChildCount(); i++) {
            android.view.View tabView = strip.getChildAt(i);
            final int idx = i;
            tabView.setOnLongClickListener(v -> {
                TabLayout.Tab tab = tabRegisters.getTabAt(idx);
                if (tab == null) return true;
                Object tag = tab.getTag();
                if (!(tag instanceof String)) return true;
                showTabContextDialog((String) tag, tab.getText() != null ? tab.getText().toString() : "");
                return true;
            });
        }
    }

    private void showTabContextDialog(String tabKey, String tabLabel) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(tabLabel)
            .setItems(new String[]{"Reconnect", "Supprimer"}, (dialog, which) -> {
                if (which == 0) {
                    // Reconnect
                    FragmentManager fm = getSupportFragmentManager();
                    Fragment f = fm.findFragmentByTag("regtab_" + tabKey);
                    if (f instanceof RegisterTabFragment) {
                        selectRegisterTabByKey(tabKey);
                        showRegisterFragmentByKey(tabKey);
                        ((RegisterTabFragment) f).reconnectFromDialog();
                    }
                } else {
                    // Supprimer
                    new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Supprimer le tab ?")
                        .setMessage("Supprimer " + tabLabel + " ?")
                        .setPositiveButton("Supprimer", (d, w) -> {
                            removeTabAndFragment(tabKey, "user deleted");
                            // Si plus aucun tab registre → afficher tab par défaut
                            if (tabRegisters != null && tabRegisters.getTabCount() == 0) {
                                showPage(0); // retour à MAIN
                                logUi(null, "Tous les tabs supprimés — retour MAIN");
                            }
                        })
                        .setNegativeButton("Annuler", null)
                        .show();
                }
            })
            .show();
    }

    private void removeTabAndFragment(String tabKey, String reason) {
        if (tabKey == null) return;

        tabsByKey.remove(tabKey);

        // remove regKey mapping entries pointing to this tabKey
        // ✅ FIX (6 août 2026, concurrence)
        try {
            java.util.ArrayList<String> toRemove = new java.util.ArrayList<>();
            synchronized (regKeyToTabKey) {
                for (java.util.Map.Entry<String, String> e : regKeyToTabKey.entrySet()) {
                    if (e == null) continue;
                    if (tabKey.equals(e.getValue())) toRemove.add(e.getKey());
                }
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

        // ✅ FIX (5 août 2026, demande Paul — "en toute circonstance il faut
        // remettre un tab vide ou un bouton reconnect s'il n'y a plus de
        // tab, on peut pas faire reconnect ou supprimer dans le tab car il
        // n'y en a plus") — quand le DERNIER tab disparaît, il n'y a plus
        // aucun moyen dans l'UI de déclencher une reconnexion (pas de tab,
        // donc pas de bouton Status/Reconnect à cliquer). Plutôt que
        // d'ajouter un nouvel élément UI (risqué à modifier en aveugle dans
        // le layout XML), on déclenche automatiquement une tentative de
        // reconnexion via le même point d'entrée unifié que Diagnostic
        // utilise — le résultat est le même que si l'utilisateur avait pu
        // cliquer "Reconnecter", sans dépendre d'un bouton qui n'existe pas
        // dans cet état.
        try {
            if (tabRegisters == null || tabRegisters.getTabCount() == 0) {
                logUi(null, "Plus aucun tab — tentative de reconnexion automatique déclenchée");
                // ✅ FIX (6 août 2026, demande Paul — "on devrait toujours
                // avoir un tab si pas de registre, sinon un par défaut") —
                // déterminer un node de repli AVANT le thread d'arrière-plan
                // (tabKey du tab qu'on vient de retirer est le meilleur indice
                // disponible ici).
                int fallbackNode = -1;
                try {
                    String[] parts = tabKey.split(":");
                    if (parts.length >= 2) fallbackNode = Integer.parseInt(parts[1].trim());
                } catch (Exception ignored) {}
                final int fFallbackNode = fallbackNode;
                new Thread(() -> {
                    try {
                        com.pa.lcr.lcp.MultiRegisterApiFacadeImpl facadeAuto =
                            new com.pa.lcr.lcp.MultiRegisterApiFacadeImpl(this);
                        com.pa.lcr.lcp.ApiResult r = facadeAuto.api_registerConnectAuto(null, null);
                        android.util.Log.i("MainActivity", "Reconnexion auto (plus de tab) — code="
                            + (r != null ? r.code : "null") + " msg=" + (r != null ? r.msg : "null"));
                        // ✅ Repli : si la reconnexion auto n'a rien trouvé, afficher quand
                        // même un tab "inconnu" (mécanisme déjà existant, voir
                        // ensureRegisterTab()) — jamais laisser l'écran complètement vide,
                        // sans aucun moyen d'interagir (Status/Reconnecter/Supprimer).
                        if (r == null || r.code != 1) {
                            int nodeToUse = fFallbackNode;
                            if (nodeToUse <= 0) {
                                try {
                                    java.util.List<String[]> known = com.pa.lcr.lcp.RegisterSessionManager
                                        .get(this).listKnownRegisters();
                                    if (known != null && !known.isEmpty()) {
                                        nodeToUse = Integer.parseInt(known.get(0)[0]);
                                    }
                                } catch (Exception ignored2) {}
                            }
                            if (nodeToUse <= 0) nodeToUse = 250; // dernier repli — node par défaut du camion
                            final int fNodeToUse = nodeToUse;
                            runOnUiThread(() -> {
                                if (tabRegisters == null || tabRegisters.getTabCount() == 0) {
                                    ensureRegisterTab(fNodeToUse, 255, true);
                                    logUi(null, "Reconnexion auto sans résultat — tab par défaut affiché (node="
                                        + fNodeToUse + ") pour permettre une action manuelle");
                                }
                            });
                        }
                    } catch (Exception ignored) {}
                }).start();
            }
        } catch (Exception ignored) {}
    }

    // =========================
    // ✅ Serial plausibility (évite les serial garbage: "��")
    // =========================
    private static boolean isPlausibleSerial(String serial) {
        if (serial == null) return false;
        String s = serial.trim();
        if (s.isEmpty()) return false;
        if (s.indexOf('�') >= 0) return false; // unicode replacement char
        if (s.length() < 4 || s.length() > 32) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F) return false; // control
            boolean ok = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || c == '-' || c == '_' || c == '.';
            if (!ok) return false;
        }
        return true;
    }

    private static final class ProbeResult {
        final boolean ok;
        final String serial;
        final String reason;
        ProbeResult(boolean ok, String serial, String reason) {
            this.ok = ok;
            this.serial = serial;
            this.reason = reason;
        }
        static ProbeResult ok(String serial) { return new ProbeResult(true, serial, null); }
        static ProbeResult fail(String reason) { return new ProbeResult(false, null, reason); }
    }

    /**
     * Probe best-effort équivalent à un Status(B) minimal:
     * - MachineStatus (best-effort)
     * - 0x28 delivery status (timeout court)
     * - #80 serial (timeout court)
     */
    private ProbeResult probeRegisterReadable(TransportIo io, int node, int from, String expectedSerial) {
        if (io == null || !io.isOpen()) return ProbeResult.fail("transport_not_ready");
        if (node < 1 || node > 250) return ProbeResult.fail("node_invalid");
        if (from < 0 || from > 255) from = 255;
        try {
            LcpLink tmp = new LcpLink(io, node, from, true);
            try { tmp.opGetMachineStatus(); } catch (Exception ignored) {}
            try { tmp.opDeliveryStatus(450); } catch (Exception ignored) {}
            String serial = decodeAz(tmp.opGetField(80, 750));
            if (!isPlausibleSerial(serial)) return ProbeResult.fail("serial_invalid");
            if (expectedSerial != null && !expectedSerial.trim().isEmpty()) {
                String exp = expectedSerial.trim();
                if (!serial.equalsIgnoreCase(exp)) {
                    return ProbeResult.fail("serial_mismatch(" + exp + " != " + serial + ")");
                }
            }
            return ProbeResult.ok(serial);
        } catch (Exception e) {
            return ProbeResult.fail("probe_err:" + safeMsg(e));
        }
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
        ensureActiveTransport(io.getKey(), "SCAN_USB");
 scanRegistersWithIo(io, io.getKey(), txtUsbRegsFound);
    }

    private void scanRegistersBtOnly() {
        if (mediaTransportManager == null) return;
        // Scanner tous les transports BT connectés
        try {
            for (TransportSnapshot s : mediaTransportManager.listSnapshots()) {
                if (s == null || s.key == null) continue;
                if (!s.key.toUpperCase().startsWith("BT:")) continue;
                if (s.status == TransportStatus.DISCONNECTED || s.status == TransportStatus.ERROR) continue;
                TransportIo io = mediaTransportManager.getByKey(s.key);
                if (io == null || !io.isOpen()) continue;
                final TransportIo ioFinal = io;
                final String keyFinal = s.key;
                scanExec.execute(() -> scanRegistersWithIo(ioFinal, keyFinal, txtBtRegsFound));
            }
        } catch (Exception e) {
            logUi(null, "Scan BT registres: erreur: " + e.getMessage());
        }
    }

    // ✅ TCP (N-Port raw passthrough) — connexion manuelle via 4 cases d'octets
    // IP (xxx.xxx.xxx.xxx), même style visuel vert que BT/USB. Node optionnel :
    // si rempli, connexion directe à ce node (comme BT), sinon détection auto.
    private void connectTcpManual() {
        String o1 = readOctet(edtTcpOctet1);
        String o2 = readOctet(edtTcpOctet2);
        String o3 = readOctet(edtTcpOctet3);
        String o4 = readOctet(edtTcpOctet4);
        if (o1 == null || o2 == null || o3 == null || o4 == null) {
            toast("TCP: adresse IP incomplète (4 cases requises)");
            return;
        }
        String ip = o1 + "." + o2 + "." + o3 + "." + o4;

        String portStr = (edtTcpPort != null && edtTcpPort.getText() != null)
                ? edtTcpPort.getText().toString().trim() : "";
        int port;
        try {
            port = portStr.isEmpty()
                    ? com.pa.lcr.lcp.api.WifiRegisterScanController.DEFAULT_RAW_PORT
                    : Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            toast("TCP: port invalide");
            return;
        }

        int expectedNode = -1;
        String nodeStr = (edtTcpNode != null && edtTcpNode.getText() != null)
                ? edtTcpNode.getText().toString().trim() : "";
        if (!nodeStr.isEmpty()) {
            try {
                int n = Integer.parseInt(nodeStr);
                if (n < 1 || n > 250) { toast("TCP: node invalide (1..250)"); return; }
                expectedNode = n;
            } catch (NumberFormatException e) {
                toast("TCP: node invalide (1..250)");
                return;
            }
        }
        connectTcpTo(ip, port, expectedNode);
    }

    // ✅ Lit un octet IP (0-255) depuis une case, ou null si vide/invalide.
    private String readOctet(android.widget.EditText edt) {
        if (edt == null || edt.getText() == null) return null;
        String s = edt.getText().toString().trim();
        if (s.isEmpty()) return null;
        try {
            int v = Integer.parseInt(s);
            if (v < 0 || v > 255) return null;
            return String.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ✅ Connexion TCP réutilisable — appelée par la saisie manuelle ET par
    // le bouton Connect de chaque ligne de la liste des N-Port connus.
    // expectedNode : -1 = détection auto (LC3 puis boucle LCR-II), sinon
    // connexion directe à ce node précis (comme connectManualWithIo pour BT).
    private void connectTcpTo(final String ip, final int port) {
        connectTcpTo(ip, port, -1);
    }

    private void connectTcpTo(final String ip, final int port, final int expectedNode) {
        if (txtTcpStatus != null) txtTcpStatus.setText("Statut : connexion en cours vers " + ip + ":" + port + "...");

        scanExec.execute(() -> {
            com.pa.lcr.lcp.api.WifiRegisterScanController ctl =
                    new com.pa.lcr.lcp.api.WifiRegisterScanController(this, mediaTransportManager);
            com.pa.lcr.lcp.ApiResult r = ctl.connectManual(ip, port);
            runOnUiThread(() -> {
                if (txtTcpStatus != null) txtTcpStatus.setText("Statut : " + r.msg);
                toast(r.msg);
                refreshKnownTcpList();
            });
            if (r.code == 1) {
                try {
                    String key = MediaTransportManager.tcpKey(ip, port);
                    TransportIo io = mediaTransportManager.getByKey(key);
                    if (io != null && io.isOpen()) {
                        // ✅ FIX : un seul sondage LCP (plus d'appel séparé à
                        // scanRegistersWithIo en plus) — les deux faisaient chacun
                        // leur propre probeAndIdentify sur le MÊME socket, mis en
                        // file sur scanExec (single-thread) : le 1er sondage
                        // (finalize, synchrone dans cette tâche) s'exécutait avant
                        // le 2e (scanRegistersWithIo, tâche mise en file séparément)
                        // — redondant, et le 1er semblait s'interrompre avant la
                        // lecture du serial. Un seul sondage suffit et finalise.
                        finalizeTcpRegisterTab(io, key, txtWifiRegsFound, expectedNode);
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    // ✅ FIX (remplace l'ancien appel à onConfigureMediaActivated, qui devinait
    // le node à 250 par défaut — faux pour un LC3 réel trouvé au node 245).
    //
    // scanRegistersWithIo() détecte volontairement SANS créer de session
    // complète (voir son commentaire "sans créer de session complète") — pour
    // BT/USB, c'est le chauffeur qui confirme ensuite manuellement via le
    // bouton "Node X + Connect" (connectManualWithIo, qui lit le VRAI node,
    // jamais deviné). Pour TCP, la connexion elle-même EST déjà l'action
    // déliberée équivalente — donc on refait ici la même détection
    // (LC3 d'abord, LCR-II en fallback, exactement comme scanRegistersWithIo)
    // puis on finalise directement avec le node réel trouvé, sans deviner.
    //
    // ✅ UN SEUL sondage LCP par appel (plus de scanRegistersWithIo() en double
    // sur le même socket) — logs explicites à chaque étape pour diagnostic.
    //
    // expectedNode : -1 = détection auto (LC3 puis boucle LCR-II, comme avant).
    // Si > 0 (saisi dans le champ "Node :"), connexion DIRECTE à ce node exact —
    // aucune détection, aucune boucle — même principe que connectManualWithIo
    // pour BT/USB (le node est fourni, jamais deviné).
    private void finalizeTcpRegisterTab(TransportIo io, String transportKey, TextView consoleTarget, int expectedNode) {
        final String TAG = "MainActivity";

        // ✅ FIX : si un appel est déjà en cours pour CE transport, on l'ignore
        // plutôt que de laisser deux sondes LC3 se dérouler en même temps
        // sur le même registre (interférence → faux négatif → boucle LCR-II
        // 1..250 inutile, voire onglet erroné).
        if (!tcpFinalizeInProgress.add(transportKey)) {
            android.util.Log.w(TAG, "finalizeTcpRegisterTab: déjà en cours pour " + transportKey + " — appel ignoré");
            return;
        }
        try {
            finalizeTcpRegisterTabLocked(io, transportKey, consoleTarget, expectedNode);
        } finally {
            tcpFinalizeInProgress.remove(transportKey);
        }
    }

    private void finalizeTcpRegisterTabLocked(TransportIo io, String transportKey, TextView consoleTarget, int expectedNode) {
        final String TAG = "MainActivity";
        android.util.Log.i(TAG, "finalizeTcpRegisterTab: début, transportKey=" + transportKey + " expectedNode=" + expectedNode);

        if (io == null || !io.isOpen()) {
            android.util.Log.w(TAG, "finalizeTcpRegisterTab: transport fermé/null, abandon");
            return;
        }

        // ✅ Node explicite fourni : connexion directe, pas de détection.
        if (expectedNode > 0) {
            try {
                LcpLink tmp = new LcpLink(io, expectedNode, 255, true);
                String serial = decodeAz(tmp.opGetField(80, 600));
                if (serial != null && !serial.trim().isEmpty()) {
                    final String serialFinal = serial.trim();
                    android.util.Log.i(TAG, "finalizeTcpRegisterTab: node explicite " + expectedNode + " -> serial=" + serialFinal);
                    ui.post(() -> {
                        if (consoleTarget != null) {
                            consoleTarget.setText("Node " + expectedNode + " — serial=" + serialFinal + " (onglet créé)");
                        }
                        upsertRegisterTabFromScan(transportKey, expectedNode, 255, serialFinal, true);
                        refreshAllTabsMediaStatus();
                    });
                } else {
                    android.util.Log.w(TAG, "finalizeTcpRegisterTab: node explicite " + expectedNode + " -> aucun serial (registre absent à ce node ?)");
                    if (consoleTarget != null) {
                        final String msg = "Node " + expectedNode + " : aucun registre trouvé";
                        ui.post(() -> consoleTarget.setText(msg));
                    }
                }
            } catch (Exception e) {
                android.util.Log.e(TAG, "finalizeTcpRegisterTab: node explicite " + expectedNode + " -> exception: " + safeMsg(e));
                if (consoleTarget != null) {
                    final String msg = "Node " + expectedNode + " : erreur (" + safeMsg(e) + ")";
                    ui.post(() -> consoleTarget.setText(msg));
                }
            }
            return;
        }

        try {
            Lc3Link.RegisterIdentity identity = null;
            try { identity = Lc3Link.probeAndIdentify(io); } catch (Exception e) {
                android.util.Log.w(TAG, "finalizeTcpRegisterTab: probeAndIdentify (LC3) exception: " + safeMsg(e));
            }

            if (identity != null && identity.isLc3) {
                final int node = identity.nodeId > 0 ? identity.nodeId : 250;
                final String serialFixed = identity.serialId;

                // ✅ Le retry sur le placeholder "LC3" vit maintenant DANS
                // Lc3Link.probeAndIdentify() lui-même (mécanisme unique,
                // partagé par tous les appelants) — plus besoin de le refaire
                // ici. Si serialFixed vaut encore "LC3" à ce stade, les deux
                // tentatives internes ont échoué — on rejette proprement.
                android.util.Log.i(TAG, "finalizeTcpRegisterTab: LC3 détecté node=" + node + " serial=" + serialFixed);

                boolean serialValid = serialFixed != null && !serialFixed.trim().isEmpty()
                        && !serialFixed.trim().equals("LC3");

                if (serialValid) {
                    final String serialFinal = serialFixed.trim();
                    // ✅ FIX : sans cet appel, RegisterSessionManager.getOrCreate()
                    // (appelé depuis le thread UI par RegisterTabFragment) ne sait
                    // JAMAIS que ce transport TCP est un LC3 — il retombe sur un
                    // LcpLink générique, et le NET/GROSS live ne se lit pas
                    // correctement (mauvaise sous-classe de protocole).
                    try {
                        RegisterSessionManager.get(getApplicationContext())
                                .markAsLc3Transport(transportKey, serialFinal, node);
                        android.util.Log.i(TAG, "finalizeTcpRegisterTab: markAsLc3Transport(" + transportKey + ") appelé");
                    } catch (Exception e) {
                        android.util.Log.w(TAG, "finalizeTcpRegisterTab: markAsLc3Transport exception: " + safeMsg(e));
                    }
                    ui.post(() -> {
                        if (consoleTarget != null) {
                            consoleTarget.setText("LC3 trouvé — node=" + node + " serial=" + serialFinal + " (onglet créé)");
                        }
                        android.util.Log.i(TAG, "finalizeTcpRegisterTab: appel upsertRegisterTabFromScan(node=" + node + ", serial=" + serialFinal + ")");
                        // ✅ FIX : la version à 5 arguments devine isLc3 en regardant
                        // si un onglet existe déjà — pour un NOUVEL onglet (cas normal
                        // ici), elle suppose systématiquement false, affichant à tort
                        // "[LCR-II]" même quand le registre est un LC3 confirmé.
                        // On passe maintenant isLc3=true explicitement (6 arguments).
                        upsertRegisterTabFromScan(transportKey, node, 255, serialFinal, true, true);
                        refreshAllTabsMediaStatus();
                        android.util.Log.i(TAG, "finalizeTcpRegisterTab: upsertRegisterTabFromScan terminé");
                    });
                } else {
                    android.util.Log.w(TAG, "finalizeTcpRegisterTab: LC3 identifié mais #série illisible (placeholder LC3 persistant après retry), aucun onglet créé");
                    if (consoleTarget != null) ui.post(() -> consoleTarget.setText("LC3 détecté mais #série illisible — relance le scan"));
                }
                return;
            }

            android.util.Log.i(TAG, "finalizeTcpRegisterTab: pas de LC3, boucle LCR-II (node 1..250)");
            // LCR-II — même boucle que scanRegistersWithIo, mais on s'arrête
            // au premier node valide trouvé pour finaliser l'onglet.
            for (int node = 1; node <= 250; node++) {
                try {
                    LcpLink tmp = new LcpLink(io, node, 255, true);
                    String serial = decodeAz(tmp.opGetField(80, 300));
                    if (serial != null && !serial.trim().isEmpty()) {
                        final int nodeFinal = node;
                        final String serialFinal = serial.trim();
                        android.util.Log.i(TAG, "finalizeTcpRegisterTab: LCR-II trouvé node=" + nodeFinal + " serial=" + serialFinal);
                        ui.post(() -> {
                            if (consoleTarget != null) {
                                consoleTarget.setText("LCR-II trouvé — node=" + nodeFinal + " serial=" + serialFinal + " (onglet créé)");
                            }
                            upsertRegisterTabFromScan(transportKey, nodeFinal, 255, serialFinal, true, false);
                            refreshAllTabsMediaStatus();
                        });
                        return;
                    }
                } catch (Exception ignored) {}
            }
            android.util.Log.w(TAG, "finalizeTcpRegisterTab: aucun registre LCR-II trouvé (1..250), aucun onglet créé");
            if (consoleTarget != null) ui.post(() -> consoleTarget.setText("Aucun registre trouvé sur ce transport (1..250)"));
        } catch (Exception e) {
            android.util.Log.e(TAG, "finalizeTcpRegisterTab: exception globale: " + safeMsg(e));
        }
    }

    // ✅ Scan réseau (subnet /24) à la recherche du port raw N-Port (4001 défaut).
    // Chaque socket ouvert est enregistré comme transport puis identifié
    // avec la même mécanique que le scan USB/BT.
    private void scanWifiRegisters() {
        // ✅ Le port n'est plus figé à 4001 — on lit le champ port déjà présent
        // dans l'UI (edtTcpPort), qui sert maintenant à la fois à la connexion
        // manuelle ET au scan réseau. Un N-Port peut être configuré sur
        // n'importe quel port TCP (ex: 5002) selon l'installation.
        String portStr = (edtTcpPort != null && edtTcpPort.getText() != null)
                ? edtTcpPort.getText().toString().trim() : "";
        final int scanPort;
        if (portStr.isEmpty()) {
            scanPort = com.pa.lcr.lcp.api.WifiRegisterScanController.DEFAULT_RAW_PORT;
        } else {
            int parsed;
            try { parsed = Integer.parseInt(portStr); }
            catch (NumberFormatException e) { toast("TCP: port invalide"); return; }
            scanPort = parsed;
        }

        if (btnScanWifiRegs != null) btnScanWifiRegs.setEnabled(false);
        if (txtWifiRegsFound != null) txtWifiRegsFound.setText("Scan réseau en cours (1..254, port " + scanPort + ")...");

        scanExec.execute(() -> {
            com.pa.lcr.lcp.api.WifiRegisterScanController ctl =
                    new com.pa.lcr.lcp.api.WifiRegisterScanController(this, mediaTransportManager);
            com.pa.lcr.lcp.ApiResult r = ctl.scanSubnet(scanPort);

            runOnUiThread(() -> {
                if (txtWifiRegsFound != null) txtWifiRegsFound.setText(r.msg);
                if (btnScanWifiRegs != null) btnScanWifiRegs.setEnabled(true);
                toast(r.msg);
                refreshKnownTcpList();
                refreshDetectedSubnet();
            });

            // Identification des nodes sur chaque transport TCP découvert
            // — un même socket (un même port) peut révéler PLUSIEURS registres
            // (bus RS-485 multi-point). Un seul sondage par transport
            // (finalizeTcpRegisterTab), plus de scanRegistersWithIo() en double.
            if (mediaTransportManager != null) {
                try {
                    for (TransportSnapshot s : mediaTransportManager.listSnapshots()) {
                        if (s == null || s.key == null) continue;
                        if (!s.key.toUpperCase().startsWith("TCP:")) continue;
                        if (s.status == TransportStatus.DISCONNECTED || s.status == TransportStatus.ERROR) continue;
                        TransportIo io = mediaTransportManager.getByKey(s.key);
                        if (io == null || !io.isOpen()) continue;
                        finalizeTcpRegisterTab(io, s.key, txtWifiRegsFound, -1);
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    // ✅ Reconstruit la liste des N-Port connus (containerKnownTcp) — même
    // principe que les "appareils appairés" BT : une ligne par appareil déjà
    // vu, avec son propre bouton Connect, pour éviter de retaper l'IP.
    private void refreshKnownTcpList() {
        if (containerKnownTcp == null) return;
        scanExec.execute(() -> {
            org.json.JSONArray known;
            try {
                com.pa.lcr.lcp.storage.KnownTcpDeviceStore store =
                        new com.pa.lcr.lcp.storage.KnownTcpDeviceStore(this);
                known = store.listKnown();
            } catch (Exception e) {
                known = new org.json.JSONArray();
            }
            final org.json.JSONArray knownFinal = known;
            runOnUiThread(() -> buildKnownTcpRows(knownFinal));
        });
    }

    // ✅ Peuple txtTcpSubnetDetected avec le sous-réseau Wi-Fi réel de la
    // tablette (format xxx.xxx.xxx.xxx), demandé pour la case verte TCP.
    private void refreshDetectedSubnet() {
        if (txtTcpSubnetDetected == null) return;
        scanExec.execute(() -> {
            String subnet;
            try {
                com.pa.lcr.lcp.api.WifiRegisterScanController ctl =
                        new com.pa.lcr.lcp.api.WifiRegisterScanController(this, mediaTransportManager);
                subnet = ctl.detectSubnet();
            } catch (Exception e) {
                subnet = null;
            }
            final String subnetFinal = subnet;
            runOnUiThread(() -> {
                if (txtTcpSubnetDetected != null) {
                    txtTcpSubnetDetected.setText("Sous-réseau : " + (subnetFinal != null ? subnetFinal : "indisponible (Wi-Fi désactivé ?)"));
                }
            });
        });
    }

    private void buildKnownTcpRows(org.json.JSONArray known) {
        if (containerKnownTcp == null) return;
        containerKnownTcp.removeAllViews();

        if (known == null || known.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("(aucun N-Port mémorisé pour l'instant)");
            empty.setTextSize(11f);
            empty.setTextColor(0xFF888888);
            containerKnownTcp.addView(empty);
            return;
        }

        for (int i = 0; i < known.length(); i++) {
            try {
                org.json.JSONObject o = known.getJSONObject(i);
                final String ip = o.getString("ip");
                final int port = o.getInt("port");
                String serialId = o.isNull("serial_id") ? null : o.optString("serial_id", null);

                android.widget.LinearLayout row = new android.widget.LinearLayout(this);
                row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                int padPx = (int) (6 * getResources().getDisplayMetrics().density);
                row.setPadding(padPx, padPx, padPx, padPx);
                row.setBackgroundColor(0xFF0B6623); // même vert que les cases manuelles BT/USB

                TextView label = new TextView(this);
                String txt = ip + ":" + port + (serialId != null ? "  (#" + serialId + ")" : "");
                label.setText(txt);
                label.setTextColor(0xFFFFFFFF);
                label.setTextSize(12f);
                android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                row.addView(label, lp);

                Button btn = new Button(this);
                btn.setText("Connect");
                btn.setTextColor(0xFFFFFFFF);
                btn.setOnClickListener(v -> connectTcpTo(ip, port));
                row.addView(btn);

                android.widget.LinearLayout.LayoutParams rowLp = new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                rowLp.setMargins(0, 0, 0, (int) (4 * getResources().getDisplayMetrics().density));
                containerKnownTcp.addView(row, rowLp);
            } catch (Exception ignored) {}
        }
    }

    private void scanRegistersBtOnlyLegacy() {
        if (lastBtMac == null || lastBtMac.trim().isEmpty()) {
            logUi(null, "Scan BT registres: aucun BT connecté.");
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
        ensureActiveTransport(io.getKey(), "SCAN_BT");
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

       if (target != null) ui.post(() -> target.setText("Scan en cours... (" + mediaShort + ")"));

        final TransportIo ioFinal = io;
        scanExec.execute(() -> {
            final long scanStartedMs = System.currentTimeMillis();
            LinkedHashMap<Integer, NodeScanItem> found = new LinkedHashMap<>();
            final int T28 = 300;
            final int TF = 300;

            // APRÈS — LC3 d'abord, LCR-II en fallback
            Lc3Link.RegisterIdentity identity = null;
            try { identity = Lc3Link.probeAndIdentify(ioFinal); } catch (Exception ignored) {}

            if (identity != null && identity.isLc3) {
                // LC3 détecté — pas de boucle node
                int    lc3Node  = identity.nodeId > 0 ? identity.nodeId : 250;
                String serialId = identity.serialId;
                // ✅ Le retry sur le placeholder "LC3" vit maintenant DANS
                // Lc3Link.probeAndIdentify() lui-même — mécanisme unique
                // partagé par tous les appelants (RegisterSessionManager,
                // finalizeTcpRegisterTab, ici). Plus besoin de le refaire.
                String ticketNo = "";
                try {
                    Lc3Link lc3tmp = new Lc3Link(ioFinal);
                    ticketNo = u32beDec(lc3tmp.opGetField(23, 3000));
                } catch (Exception ignored) {}
                found.put(lc3Node, new NodeScanItem(
                    lc3Node, serialId, ticketNo,
                    false, false, false, false, true
                ));
                android.util.Log.i("MainActivity", "Scan LC3: node=" + lc3Node
                        + " serial=" + serialId);
                // Pré-populer knownLc3TransportKeys sans créer de session complète

                try {
                    RegisterSessionManager sm = RegisterSessionManager.get(getApplicationContext());
                    sm.bindExpectedSerial(lc3Node, serialId);
                    sm.markAsLc3Transport(tk, serialId, lc3Node);
                } catch (Exception ignored) {}

            } else {
                // LCR-II — boucle classique
                for (int node = 1; node <= 250; node++) {
                    try {
                        LcpLink tmp = new LcpLink(ioFinal, node, 255, true);
                        int[] ds = tmp.opDeliveryStatus(T28);
                        int delCode = ds[1];
                        boolean ticketPending  = (delCode & 0x0001) != 0;
                        boolean flowActive     = (delCode & 0x0004) != 0;
                        boolean deliveryActive = (delCode & 0x0008) != 0;
                        String serialId = decodeAz(tmp.opGetField(80, TF));
                        String ticketNo = u32beDec(tmp.opGetField(23, TF));
                        if (serialId != null && !serialId.trim().isEmpty()) {
                            found.put(node, new NodeScanItem(
                                node, serialId, ticketNo,
                                ticketPending, deliveryActive, flowActive, false
                            ));
                        }
                    } catch (Exception ignored) {}
                }
            }

            final long scanFinishedMs = System.currentTimeMillis();
            persistScanEvents(scanStartedMs, scanFinishedMs, found);

            ui.post(() -> {
                try {
                    nodeItems.clear();
                    nodeItems.addAll(found.values());

                    if (target != null) {
                        if (found.isEmpty()) {
                         ui.post(() -> target.setText("(aucun registre trouvé)\n" + mediaShort));
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

                           final String result = sb.toString().trim();
                           ui.post(() -> target.setText(result));

                        }
                    }

                    if (!found.isEmpty()) {
                        boolean focused = false;
                        for (NodeScanItem it : found.values()) {
                            if (it == null) continue;
                            boolean focus = false;
                            if (!focused && it.lcrnode == 250) focus = true;
                            upsertRegisterTabFromScan(tk, it.lcrnode, 255, it.serialId, focus, it.isLc3);
                            if (focus) focused = true;
                        }
                        if (!focused) {
                            NodeScanItem first = found.values().iterator().next();
                            if (first != null) upsertRegisterTabFromScan(tk, first.lcrnode, 255, first.serialId, true, first.isLc3);
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
        String qtySuffix; // " | N=.. G=.."
        final String ticketNo;
        final boolean ticketPending;
        final boolean deliveryActive;
        final boolean flowActive;
        final boolean isDefault;
        final boolean isLc3;

        NodeScanItem(int lcrnode, String serialId, String ticketNo,
                     boolean ticketPending, boolean deliveryActive, boolean flowActive, boolean isDefault) {
            this(lcrnode, serialId, ticketNo, ticketPending, deliveryActive, flowActive, isDefault, false);
        }

        NodeScanItem(int lcrnode, String serialId, String ticketNo,
                     boolean ticketPending, boolean deliveryActive, boolean flowActive, boolean isDefault, boolean isLc3) {
            this.lcrnode = lcrnode;
            this.serialId = serialId;
            this.ticketNo = ticketNo;
            this.ticketPending = ticketPending;
            this.deliveryActive = deliveryActive;
            this.flowActive = flowActive;
            this.isDefault = isDefault;
            this.isLc3 = isLc3;
        }

        static NodeScanItem default250() { return new NodeScanItem(250, "", "", false, false, false, true); }

        NodeScanItem asDefault() {
            return new NodeScanItem(lcrnode, serialId, ticketNo, ticketPending, deliveryActive, flowActive, true, isLc3);
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
    
    // =========================
    // ✅ USB reset (detach/replug)
    // =========================
    private void resetUsbState(String reason) {
        try { logUi(null, "USB reset: " + (reason != null ? reason : "-")); } catch (Exception ignored) {}
        try { if (usbPort != null) usbPort.close(); } catch (Exception ignored) {}
        usbPort = null;
        try { UsbSession.clear(); } catch (Exception ignored) {}
    }

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

        // ✅ Replug: reset du port stale avant toute logique
        try {
            if (UsbSession.getPort() == null && usbPort != null) {
                try { usbPort.close(); } catch (Exception ignored) {}
                usbPort = null;
            }
        } catch (Exception ignored) {}

        // ✅ Si liste USB vide, rescanner
        try { if (usbDevices != null && usbDevices.isEmpty()) scanUsb(); } catch (Exception ignored) {}


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
                // ✅ CONFIGURE: média activé -> rebind tab sur USB
                onConfigureMediaActivated(MediaTransportManager.KEY_USB, "USB_READY");
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

        // ✅ Si usbPort stale (fermé ou différent du nouveau) → remplacer
        if (usbPort != null && usbPort != port) {
            boolean stale = false;
            try { stale = !usbPort.isOpen(); } catch (Exception e) { stale = true; }
            if (stale) {
                try { usbPort.close(); } catch (Exception ignore) {}
                usbPort = null;
                logUi(null, "USB: port stale remplacé");
            }
        }

        if (usbPort != null) {
            // Port valide déjà ouvert — fermer le doublon
            try { port.close(); } catch (Exception ignore) {}
            return;
        }

        usbPort = port;
        logUi(null, "USB prêt (receiver)");

        // ✅ Option A: publish USB ready
        try {
            if (mediaTransportManager != null) {
                mediaTransportManager.onUsbReady(null, usbPort, "USB prêt (MainActivity)");
                // ✅ CONFIGURE: média activé -> rebind tab sur USB
                onConfigureMediaActivated(MediaTransportManager.KEY_USB, "USB_READY_RX");
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
        //stopApiServer("USB detached");

        // ✅ Multi-média: ne pas détruire les tabs BT.
        // Retirer uniquement les tabs USB (et leurs fragments) de manière explicite (A1).
        // ✅ FIX (6 août 2026, concurrence)
        try {
            ArrayList<String> toRemove = new ArrayList<>();
            synchronized (tabsByKey) {
                for (Map.Entry<String, TabSpec> e : tabsByKey.entrySet()) {
                    if (e == null) continue;
                    TabSpec s = e.getValue();
                    if (s == null) continue;
                    String mShort = (s.mediaShort != null) ? s.mediaShort : mediaShortFromTransportKey(s.transportKey);
                    if ("USB".equalsIgnoreCase(mShort)) toRemove.add(e.getKey());
                }
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
    // ⚠️ LEGACY — 3 août 2026 : cette instance locale d'ApiServer, créée par
    // ce bouton, était SÉPARÉE du vrai service permanent LcrHttpService
    // (démarré au onCreate() / au boot). Le statut/URL affiché dans l'onglet
    // API reflétait ce doublon (souvent en conflit de port 8765) et non le
    // service réellement actif en continu sur le camion.
    // Neutralisé volontairement (no-op) — à retirer complètement plus tard.
    // Le statut réel est maintenant lu directement depuis LcrHttpService
    // (voir refreshApiStatus()).
    private void startApiServer() {
        logApi(null, "[API] Bouton legacy désactivé — le service réel LcrHttpService tourne déjà en continu");
        refreshApiStatus();
    }

    // ✅ Quit — bouton header (haut droit). Confirme avant de fermer car
    // l'APK tourne normalement en continu sur les tablettes camion
    // (LcrBootReceiver la relance au boot) : une fermeture accidentelle
    // interromprait le service HTTP local utilisé par Field Service Mobile.
    // ✅ Compatibilité Android 9-15 (API 28-35) : finish() et
    // Process.killProcess() sont des API stables depuis l'API 16 — aucune
    // branche SDK_INT nécessaire ici.
    private void confirmQuit() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Quitter l'application")
            .setMessage("Fermer Filgo Registre ? Le service local (API/Field Service) sera arrêté.")
            .setPositiveButton("Quitter", (dialog, which) -> quitApp())
            .setNegativeButton("Annuler", null)
            .show();
    }

    private void quitApp() {
        try { stopApiServer("Quit button"); } catch (Exception ignored) {}
        try {
            android.content.Intent svcIntent = new android.content.Intent(this, LcrHttpService.class);
            stopService(svcIntent);
        } catch (Exception ignored) {}
        try { UsbSession.clear(); } catch (Exception ignored) {}
        try {
            if (mediaTransportManager != null) mediaTransportManager.clearActiveIfMatches(
                mediaTransportManager.getActiveKey());
        } catch (Exception ignored) {}
        // ✅ FIX : finishAffinity() fermait TOUTE la tâche Android — y compris
        // Field Service Mobile, puisque MainActivity partage délibérément sa
        // tâche via android:taskAffinity="com.microsoft.crm.crmphone.fieldServices"
        // (transition fluide voulue pour le deep link). finish() ne ferme QUE
        // notre propre Activity — Field Service Mobile reste ouvert en dessous.
        finish();
        // ✅ Léger délai avant killProcess() pour laisser la transition visuelle
        // de finish() se terminer proprement (évite un flash noir à l'écran).
        ui.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 300);
    }

    // ⚠️ LEGACY — voir startApiServer(). Ne touche plus à l'instance locale
    // apiServer (conservée nulle). quitApp() continue d'appeler cette méthode
    // par sécurité mais elle est désormais un no-op sur le plan fonctionnel ;
    // l'arrêt réel du service se fait via stopService(LcrHttpService) ailleurs
    // dans quitApp().
    private void stopApiServer(String reason) {
        logApi(null, "[API] Bouton/appel legacy STOP (" + reason + ") — no-op, voir LcrHttpService");
        apiServer = null;
        refreshApiStatus();
    }

    // ✅ TEST SSL — appel HttpsURLConnection interne vers notre propre serveur
    private void testSslPing() {
        new Thread(() -> {
            String result;
            try {
                // Utiliser le même SSLSocketFactory que LcrBridge (lcr_local.crt depuis res/raw)
                java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
                java.io.InputStream caInput = getResources().openRawResource(R.raw.lcr_local);
                java.security.cert.Certificate ca = cf.generateCertificate(caInput);
                caInput.close();

                java.security.KeyStore ks = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
                ks.load(null, null);
                ks.setCertificateEntry("lcr_local", ca);

                javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ks);

                javax.net.ssl.SSLContext sslCtx = javax.net.ssl.SSLContext.getInstance("TLS");
                sslCtx.init(null, tmf.getTrustManagers(), null);

                java.net.URL url = new java.net.URL("https://127.0.0.1:" + API_PORT + "/v1/ping");
                javax.net.ssl.HttpsURLConnection conn = (javax.net.ssl.HttpsURLConnection) url.openConnection();
                conn.setSSLSocketFactory(sslCtx.getSocketFactory());
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                java.io.InputStream is = conn.getInputStream();
                byte[] buf = new byte[1024];
                int n = is.read(buf);
                String body = new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8);
                conn.disconnect();
                result = "✅ SSL OK — HTTP " + code + " — " + body.substring(0, Math.min(80, body.length()));
            } catch (javax.net.ssl.SSLHandshakeException e) {
                result = "❌ SSL HANDSHAKE FAIL: " + e.getMessage();
            } catch (javax.net.ssl.SSLException e) {
                result = "❌ SSL ERROR: " + e.getMessage();
            } catch (Exception e) {
                result = "❌ ERREUR: " + e.getClass().getSimpleName() + " — " + e.getMessage();
            }
            final String finalResult = result;
            android.util.Log.e("LCRDEMO_SSL", finalResult);
            runOnUiThread(() -> toast(finalResult));
        }).start();
    }

    // ✅ FIX 3 août 2026 : reflète le VRAI service HTTPS permanent
    // (LcrHttpService), pas l'ancienne instance locale apiServer (legacy).
    private void refreshApiStatus() {
        if (txtApiStatus == null) return;
        boolean httpsRunning = LcrHttpService.isApiRunning();
        txtApiStatus.setText("Status: " + (httpsRunning ? "RUNNING (service permanent)" : "STOPPED"));
        if (txtApiUrl != null) {
            txtApiUrl.setText("https://127.0.0.1:" + LcrHttpService.getApiPort());
        }
        // Boutons legacy — désactivés en permanence, le service tourne déjà
        // seul en continu. Conservés visuellement pour l'instant (à retirer).
        if (btnApiStart != null) btnApiStart.setEnabled(false);
        if (btnApiStop != null) btnApiStop.setEnabled(false);
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
            String statusName = "filgo_delivery_status_" + utcStamp() + ".db";
            backupRawDbToDownloadsQ(LcrDeliveryStatusDb.DB_NAME, statusName, (ok, fileName, detail) -> {
                if (ok) toast("Backup OK (Downloads): " + fileName);
                else android.util.Log.w("MainActivity", "Backup filgo_delivery_status.db FAIL: " + detail);
            });
        } else {
		// Android 9 et - : tenter Downloads si permission accordée, sinon demander permission puis fallback dossier
		if (ensureLegacyStoragePermissionForDownloads(true)) {
			String name = "lcr_delivery_" + utcStamp() + ".db";
			deliveryStore.backupDbToDownloadsAsync(this, name, (ok, fileName, detail) -> {
				if (ok) toast("Backup OK (Downloads): " + fileName);
				else toast("Backup FAIL: " + fileName + " " + detail);
			});
			String statusName = "filgo_delivery_status_" + utcStamp() + ".db";
			backupRawDbToDownloadsLegacy(LcrDeliveryStatusDb.DB_NAME, statusName, (ok, fileName, detail) -> {
				if (ok) toast("Backup OK (Downloads): " + fileName);
				else android.util.Log.w("MainActivity", "Backup filgo_delivery_status.db FAIL: " + detail);
			});
		} else {
			requestBackupDir();
		}
	}
    }

    private interface RawBackupCallback {
        void onDone(boolean ok, String fileName, String detail);
    }

    /**
     * Copie brute d'un fichier SQLite quelconque vers Downloads via MediaStore (Android Q+).
     * Ajouté pour que filgo_delivery_status.db (LcrDeliveryStatusDb) soit exporté au même
     * titre que la base de DeliveryLogStore — jusqu'ici seule cette dernière l'était.
     */
    private void backupRawDbToDownloadsQ(String dbName, String destName, RawBackupCallback cb) {
        new Thread(() -> {
            try {
                java.io.File src = getDatabasePath(dbName);
                if (src == null || !src.exists()) {
                    cb.onDone(false, destName, "DB introuvable (" + dbName + ")");
                    return;
                }
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, destName);
                cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/x-sqlite3");
                cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS);

                Uri outUri = getContentResolver().insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (outUri == null) {
                    cb.onDone(false, destName, "insert MediaStore a échoué");
                    return;
                }
                try (java.io.InputStream in = new java.io.FileInputStream(src);
                     java.io.OutputStream out = getContentResolver().openOutputStream(outUri)) {
                    if (out == null) {
                        cb.onDone(false, destName, "output stream null");
                        return;
                    }
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                    out.flush();
                }
                cb.onDone(true, destName, null);
            } catch (Exception e) {
                cb.onDone(false, destName, e.getMessage());
            }
        }).start();
    }

    /**
     * Copie brute vers Downloads pour Android 9-10 (permission legacy déjà accordée
     * par ensureLegacyStoragePermissionForDownloads avant cet appel).
     */
    private void backupRawDbToDownloadsLegacy(String dbName, String destName, RawBackupCallback cb) {
        new Thread(() -> {
            try {
                java.io.File src = getDatabasePath(dbName);
                if (src == null || !src.exists()) {
                    cb.onDone(false, destName, "DB introuvable (" + dbName + ")");
                    return;
                }
                java.io.File downloads = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS);
                java.io.File dest = new java.io.File(downloads, destName);
                try (java.io.InputStream in = new java.io.FileInputStream(src);
                     java.io.OutputStream out = new java.io.FileOutputStream(dest)) {
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                    out.flush();
                }
                cb.onDone(true, destName, null);
            } catch (Exception e) {
                cb.onDone(false, destName, e.getMessage());
            }
        }).start();
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

            // ✅ Étendre le backup pour ramasser aussi filgo_delivery_status.db
            // (LcrDeliveryStatusDb) — jusqu'ici seul DeliveryDb était exporté ici,
            // donc les tables de LcrDeliveryStatusDb (totaux NET/GROSS par WO)
            // n'apparaissaient jamais dans le backup.
            String statusName = "filgo_delivery_status_" + utcStamp() + ".db";
            boolean statusOk = copyRawDbToDir(dir, LcrDeliveryStatusDb.DB_NAME, statusName);

            toast("Backup OK (dossier choisi): " + name
                + (statusOk ? " + " + statusName : " (filgo_delivery_status.db absent)"));

        } catch (Exception e) {
            toast("Backup FAIL: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    /**
     * Copie brute d'un fichier SQLite quelconque vers un dossier SAF déjà ouvert.
     * Utilisé pour ajouter filgo_delivery_status.db au backup existant sans dupliquer
     * toute la logique de copie déjà en place pour DeliveryDb.
     */
    private boolean copyRawDbToDir(DocumentFile dir, String dbName, String destName) {
        try {
            java.io.File src = getDatabasePath(dbName);
            if (src == null || !src.exists()) return false;

            DocumentFile existing = dir.findFile(destName);
            if (existing != null) { try { existing.delete(); } catch (Exception ignore) {} }

            DocumentFile target = dir.createFile("application/x-sqlite3", destName);
            if (target == null || target.getUri() == null) return false;

            try (java.io.InputStream in = new java.io.FileInputStream(src);
                 java.io.OutputStream out = getContentResolver().openOutputStream(target.getUri())) {
                if (out == null) return false;
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                out.flush();
            }
            return true;
        } catch (Exception e) {
            android.util.Log.w("MainActivity", "copyRawDbToDir(" + dbName + ") ERR: " + e.getMessage());
            return false;
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

    public void toast(String s) {
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

    // =========================
    // ✅ TAB label Net/Gross
    // =========================
    private static String formatQtyLabel(double net, double gross) {
        return String.format(java.util.Locale.ROOT, " | N=%.2f G=%.2f", net, gross);
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
                    // ✅ CONFIGURE: média activé -> rebind tab sur BT
                    try { onConfigureMediaActivated(MediaTransportManager.btKey(lastBtMac), "BT_READY"); } catch (Exception ignored) {}
                } catch (Exception ignored) {}
                ui.postDelayed(this::refreshAllTabsMediaStatus, 200);
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

    public synchronized void btDisconnect() {
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

        final String disconnectedMac = lastBtMac;
        lastBtMac = null;

        ui.post(() -> {
            if (txtBtStatus != null) txtBtStatus.setText("BT : DISCONNECTED");
            updateMediaStatusUi();
            // Forcer à (OFF) seulement les tabs du BT déconnecté
            // ✅ FIX (6 août 2026, concurrence) — même si ce bloc tourne sur
            // le thread UI, d'autres threads peuvent muter tabsByKey en
            // parallèle pendant cette itération.
            try {
                String disconnectedKey = disconnectedMac != null
                    ? MediaTransportManager.btKey(disconnectedMac) : null;
                synchronized (tabsByKey) {
                    for (TabSpec s : tabsByKey.values()) {
                        if (s == null) continue;
                        if (disconnectedKey != null
                                && !disconnectedKey.equalsIgnoreCase(s.transportKey)) continue;
                        String ms = mediaShortFromTransportKey(s.transportKey);
                        if ("BT".equalsIgnoreCase(ms)) {
                            updateRegisterTabLabel(s.tabKey,
                                tabLabelOf(ms + "(OFF)", s.node, s.serialId, s.isLc3)
                                + (s.qtySuffix != null ? s.qtySuffix : ""));
                        }
                    }
                }

            } catch (Exception ignored) {}
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
        if (requestCode == REQ_LOCATION_BT_SIGNAL) {
            boolean ok = (grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED);
            if (ok) logMedia1("BT Signal: permission localisation accordée");
            else { logMedia1("BT Signal: permission localisation refusée"); toast("Permission localisation requise pour le scan RSSI BT"); }
            return;
        }

if (requestCode == 9101) {
            boolean ok = (grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED);
            if (ok) refreshBondedBtList();
            else if (txtBtStatus != null) txtBtStatus.setText("BT : permission refusée");
            if (!ok) logMedia1("BT Permission: refusée");
        }
    }

    /**
     * ✅ Permission localisation requise pour BluetoothAdapter.startDiscovery() (scan RSSI).
     * Android 6-11 : ACCESS_FINE_LOCATION
     * Android 12+  : BLUETOOTH_SCAN
     */
    private boolean ensureLocationPermissionForBtScan(boolean prompt) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                int p = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN);
                if (p == PackageManager.PERMISSION_GRANTED) return true;
                if (!prompt) return false;
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_SCAN}, REQ_LOCATION_BT_SIGNAL);
                return false;
            }
            int p = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION);
            if (p == PackageManager.PERMISSION_GRANTED) return true;
            if (!prompt) return false;
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION_BT_SIGNAL);
            return false;
        } catch (Exception ignored) { return false; }
    }

// =========================
// CONFIGURE: Ajout manuel (2 registres par média)
// - Node saisi -> Connect -> lecture #80 (serial) -> mise à jour slot + upsert tab
// - Persisté en SharedPreferences (simple)
// =========================
private static final String PREF_USB_NODE1 = "manual_usb_node1";
private static final String PREF_USB_NODE2 = "manual_usb_node2";
private static final String PREF_USB_SER1  = "manual_usb_ser1";
private static final String PREF_USB_SER2  = "manual_usb_ser2";
private static final String PREF_BT_NODE1  = "manual_bt_node1";
private static final String PREF_BT_NODE2  = "manual_bt_node2";
private static final String PREF_BT_SER1   = "manual_bt_ser1";
private static final String PREF_BT_SER2   = "manual_bt_ser2";

private void loadManualSlotsFromPrefs() {
    try {
        SharedPreferences p = getSharedPreferences("prefs", MODE_PRIVATE);

        int un1 = p.getInt(PREF_USB_NODE1, 0);
        int un2 = p.getInt(PREF_USB_NODE2, 0);
        String us1 = p.getString(PREF_USB_SER1, "");
        String us2 = p.getString(PREF_USB_SER2, "");

        int bn1 = p.getInt(PREF_BT_NODE1, 0);
        int bn2 = p.getInt(PREF_BT_NODE2, 0);
        String bs1 = p.getString(PREF_BT_SER1, "");
        String bs2 = p.getString(PREF_BT_SER2, "");

        if (edtUsbNode1 != null && un1 > 0) edtUsbNode1.setText(String.valueOf(un1));
        if (edtUsbNode2 != null && un2 > 0) edtUsbNode2.setText(String.valueOf(un2));
        if (txtUsbSerial1 != null) txtUsbSerial1.setText("#Série : " + ((us1 == null || us1.trim().isEmpty()) ? "—" : us1));
        if (txtUsbSerial2 != null) txtUsbSerial2.setText("#Série : " + ((us2 == null || us2.trim().isEmpty()) ? "—" : us2));

        if (edtBtNode1 != null && bn1 > 0) edtBtNode1.setText(String.valueOf(bn1));
        if (edtBtNode2 != null && bn2 > 0) edtBtNode2.setText(String.valueOf(bn2));
        if (txtBtSerial1 != null) txtBtSerial1.setText("#Série : " + ((bs1 == null || bs1.trim().isEmpty()) ? "—" : bs1));
        if (txtBtSerial2 != null) txtBtSerial2.setText("#Série : " + ((bs2 == null || bs2.trim().isEmpty()) ? "—" : bs2));

    } catch (Exception ignored) {}
}

private void saveManualSlotToPrefs(boolean usb, int slot, int node, String serial) {
    try {
        SharedPreferences p = getSharedPreferences("prefs", MODE_PRIVATE);
        SharedPreferences.Editor e = p.edit();
        if (usb) {
            if (slot == 1) { e.putInt(PREF_USB_NODE1, node); e.putString(PREF_USB_SER1, serial != null ? serial : ""); }
            if (slot == 2) { e.putInt(PREF_USB_NODE2, node); e.putString(PREF_USB_SER2, serial != null ? serial : ""); }
        } else {
            if (slot == 1) { e.putInt(PREF_BT_NODE1, node); e.putString(PREF_BT_SER1, serial != null ? serial : ""); }
            if (slot == 2) { e.putInt(PREF_BT_NODE2, node); e.putString(PREF_BT_SER2, serial != null ? serial : ""); }
        }
        e.apply();
    } catch (Exception ignored) {}
}

private int readNode(EditText edt) {
    try {
        if (edt == null) return -1;
        String s = String.valueOf(edt.getText());
        if (s == null) return -1;
        s = s.trim();
        if (s.isEmpty()) return -1;
        int n = Integer.parseInt(s);
        if (n < 1 || n > 250) return -1;
        return n;
    } catch (Exception ignored) { return -1; }
}

private void connectManualUsbSlot(int slot) {
    int node = (slot == 1) ? readNode(edtUsbNode1) : readNode(edtUsbNode2);
    if (node < 0) { toast("USB: node invalide (1..250)"); return; }

    TransportIo io = null;
    try {
        if (mediaTransportManager != null) io = mediaTransportManager.getByKey(MediaTransportManager.KEY_USB);
    } catch (Exception ignored) {}

    if (io == null || !io.isOpen()) {
        toast("USB(OFF): média non prêt");
        if (slot == 1 && txtUsbSerial1 != null) txtUsbSerial1.setText("#Série : USB(OFF)");
        if (slot == 2 && txtUsbSerial2 != null) txtUsbSerial2.setText("#Série : USB(OFF)");
        return;
    }

    TextView out = (slot == 1) ? txtUsbSerial1 : txtUsbSerial2;
    ensureActiveTransport(io.getKey(), "MANUAL_USB");
 connectManualWithIo(io, io.getKey(), "USB", slot, node, out, true);
}

private void connectManualBtSlot(int slot) {
    int node = (slot == 1) ? readNode(edtBtNode1) : readNode(edtBtNode2);
    if (node < 0) { toast("BT: node invalide (1..250)"); return; }

    // ✅ BT manuel: ne dépend pas de lastBtMac. Cherche un BT READY.
    TransportIo io = null;
    String transportKey = null;
    try {
        if (mediaTransportManager != null) {
            java.util.List<TransportSnapshot> snaps = mediaTransportManager.listSnapshots();
            if (snaps != null) {
                for (TransportSnapshot s : snaps) {
                    if (s == null || s.key == null) continue;
                    if (!s.key.toUpperCase(java.util.Locale.ROOT).startsWith("BT:")) continue;
                    if (s.status != TransportStatus.READY) continue;
                    transportKey = s.key;
                    io = mediaTransportManager.getByKey(s.key);
                    if (io != null && io.isOpen()) break;
                }
            }
        }
    } catch (Exception ignored) {}

    // fallback: si lastBtMac présent
    if ((io == null || !io.isOpen()) && lastBtMac != null && !lastBtMac.trim().isEmpty()) {
        try {
            transportKey = MediaTransportManager.btKey(lastBtMac);
            if (mediaTransportManager != null) io = mediaTransportManager.getByKey(transportKey);
        } catch (Exception ignored) {}
    }

    if (io == null || !io.isOpen()) {
        toast("BT(OFF): média non prêt");
        if (slot == 1 && txtBtSerial1 != null) txtBtSerial1.setText("#Série : BT(OFF)");
        if (slot == 2 && txtBtSerial2 != null) txtBtSerial2.setText("#Série : BT(OFF)");
        return;
    }

    TextView out = (slot == 1) ? txtBtSerial1 : txtBtSerial2;
    ensureActiveTransport((transportKey != null ? transportKey : io.getKey()), "MANUAL_BT");
 connectManualWithIo(io, (transportKey != null ? transportKey : io.getKey()), "BT", slot, node, out, false);
}

private void connectManualWithIo(TransportIo io, String transportKey, String mediaShort, int slot, int node, TextView out, boolean usb) {
    if (io == null || !io.isOpen()) return;
    final int from = 255;

    scanExec.execute(() -> {
        try {
            LcpLink tmp = new LcpLink(io, node, from, true);
            byte[] b80 = tmp.opGetField(80, 600);
            String serial = decodeAz(b80);
            if (serial == null) serial = "";
            serial = serial.trim();

            final String fSerial = serial;
            ui.post(() -> {
                if (out != null) out.setText("#Série : " + (fSerial.isEmpty() ? "—" : fSerial));
            });

            if (fSerial.isEmpty()) {
                ui.post(() -> toast(mediaShort + ": lecture #80 (serial) échouée"));
                saveManualSlotToPrefs(usb, slot, node, "");
                return;
            }

            final String tk = transportKey;
            ui.post(() -> {
                upsertRegisterTabFromScan(tk, node, from, fSerial, true);
                refreshAllTabsMediaStatus();
            });

            saveManualSlotToPrefs(usb, slot, node, fSerial);

        } catch (Exception e) {
            ui.post(() -> toast(mediaShort + ": connect manuel ERR: " + safeMsg(e)));
        }
    });
}



 // =========================
 // ✅ Reçu des tabs: média OFF/not-ready (pour que l'API/FS sache avant/pendant)
 // =========================
 public void reportMediaNotReadyFromTab(int node, String serialId, String transportKey, String origin, String detail) {
     try {
         String media = mediaShortFromTransportKey(transportKey);
         LogBus.api(node, "TAB_MEDIA_STATUS OFF (from TAB) media=" + media + " origin=" + origin + " detail=" + detail);

         if (deliveryStore == null) return;
         String serial = safeSerial(serialId);
         if (serial.isEmpty()) serial = "__TAB__";
         String ticketKey = "TAB-" + (node & 0xFF);

         JSONObject data = new JSONObject();
         data.put("event_type", "TAB_MEDIA_STATUS");
         data.put("state", "OFF");
         data.put("media", media);
         data.put("transport_key", transportKey);
         data.put("node", (node & 0xFF));
         data.put("serial_id", serial);
         data.put("origin", origin != null ? origin : "-");
         data.put("detail", detail != null ? detail : "-");
         data.put("ts_ms", System.currentTimeMillis());

         deliveryStore.upsertSummaryAsync(serial, ticketKey, null, "TAB_OFF", DeliveryLogStore.SOURCE_UI, null, null, null);
         deliveryStore.openAttemptAsync(serial, ticketKey, DeliveryLogStore.SOURCE_UI, null, attemptId -> {
             deliveryStore.addEventAsync(attemptId, DeliveryLogStore.LEVEL_INFO,
                     "TAB_MEDIA_STATUS",
                     "Tab media OFF (reported)",
                     data.toString());
             deliveryStore.closeAttemptAsync(attemptId, "DONE", data.toString(), null);
         });
     } catch (Exception ignored) {}
 }



    // =========================
    // ✅ B1 FSM: rendre un transport ACTIVE avant toute opération IO (USB/BT)
    // =========================
    

    // =========================
    // ✅ Guard switch média (CONFIGURE)
    // - Empêche les probes/validations pendant un switch (BT↔USB)
    // - Rebind le tab courant sur le nouveau transport (évite "Transport not open")
    // =========================
    private void beginMediaSwitchGuard(String reason) {
        try {
            long now = System.currentTimeMillis();
            mediaSwitchGuardUntilMs = now + 800; // 0.8s
            logMedia1("MEDIA SWITCH (guard) " + (reason != null ? reason : ""));
        } catch (Exception ignored) {}
    }

    private boolean isMediaSwitchGuardActive() {
        try { return System.currentTimeMillis() < mediaSwitchGuardUntilMs; }
        catch (Exception e) { return false; }
    }

    /**
     * Appelé quand un média devient READY via CONFIGURE (USB Open/Ping / BT Connect).
     * But: forcer le transport actif + rebind sur un tab du même registre.
     */
    public void onConfigureMediaActivated(String transportKey, String reason) {
        if (transportKey == null || transportKey.trim().isEmpty()) return;
        beginMediaSwitchGuard(reason);
        final String tk = transportKey.trim();

        // 1) activer exclusif immédiatement
        ensureActiveTransport(tk, "CONFIGURE_MEDIA_SWITCH");

        // ✅ FIX (perf) : si un onglet valide existe DÉJÀ pour ce transport
        // précis, on réutilise directement son node/serial/isLc3 connus —
        // aucune sonde réseau nécessaire. Avant ce correctif, CHAQUE arrivée
        // de deep link relançait une sonde réelle (jusqu'à ~600ms+ sur TCP)
        // au node=250 codé en dur, alors que le vrai node peut être différent
        // (ex: LC3 au node 245) — la sonde échouait donc systématiquement
        // pour rien, ajoutant un délai perceptible sans aucun bénéfice.
        //
        // ✅ FIX (6 août 2026, demande Paul — "je ne veux en aucun cas
        // courcircuiter la recherche d'un registre... en arrivant de
        // deeplink ou en branchant usb ou bt") — ce chemin sautait la sonde
        // ENTIÈREMENT sur la seule base qu'un tab existait déjà, sans
        // jamais vérifier que le transport était réellement encore ouvert —
        // un vrai court-circuit de la recherche, exactement ce qui est
        // interdit maintenant. Corrigé : on vérifie toujours l'état RÉEL du
        // transport (io.isOpen()) avant de réutiliser le node/serial en
        // cache — seule la sonde LENTE (identification node/serial) est
        // évitée quand elle est déjà connue, jamais la vérification de base
        // que la connexion existe vraiment. Si le transport n'est pas
        // réellement ouvert, on tombe dans la recherche complète ci-dessous
        // — jamais de court-circuit silencieux.
        try {
            java.util.List<TabSpec> tabSnapshot;
            synchronized (tabsByKey) { tabSnapshot = new ArrayList<>(tabsByKey.values()); }
            for (TabSpec spec : tabSnapshot) {
                if (spec != null && tk.equalsIgnoreCase(spec.transportKey)
                        && spec.serialId != null && !spec.serialId.trim().isEmpty()) {
                    com.pa.lcr.lcp.transport.TransportIo ioCheck =
                        (mediaTransportManager != null) ? mediaTransportManager.getByKey(tk) : null;
                    boolean reallyOpen = false;
                    try { reallyOpen = (ioCheck != null && ioCheck.isOpen()); } catch (Exception ignored2) {}
                    if (!reallyOpen) {
                        android.util.Log.i("MainActivity", "onConfigureMediaActivated: onglet connu pour " + tk
                            + " mais transport réellement fermé — pas de court-circuit, recherche complète");
                        break; // sort de la boucle, continue vers la recherche complète plus bas
                    }
                    final int knownNode = spec.node;
                    final String knownSerial = spec.serialId;
                    final boolean knownIsLc3 = spec.isLc3;
                    android.util.Log.i("MainActivity", "onConfigureMediaActivated: onglet déjà connu pour " + tk
                            + " (node=" + knownNode + " serial=" + knownSerial + "), transport réellement ouvert — sonde d'identification évitée");
                    ui.post(() -> {
                        try {
                            upsertRegisterTabFromScan(tk, knownNode, 255, knownSerial, true, knownIsLc3);
                            refreshAllTabsMediaStatus();
                        } catch (Exception ignored) {}
                    });
                    return;
                }
            }
        } catch (Exception ignored) {}

        // ✅ FIX : ne JAMAIS présumer que l'onglet actuellement affiché
        // (currentTabKey) appartient au transport qu'on vient d'activer.
        // Avant ce correctif, le node+serial de l'onglet courant étaient
        // réutilisés aveuglément pour N'IMPORTE QUEL nouveau transport
        // activé (ex: scan BT alors qu'un LC3 était affiché en TCP) — la
        // logique de "migration" d'upsertRegisterTabFromScan supprimait
        // alors l'onglet existant en le croyant "déplacé" vers le nouveau
        // média, alors qu'il s'agit de deux registres réellement
        // indépendants pouvant coexister connectés simultanément.
        //
        // Seule une VRAIE sonde sur CE transport peut justifier de créer
        // ou migrer un onglet — jamais une simple supposition basée sur
        // "quel onglet est affiché à l'écran". Ce chemin ne s'exécute
        // maintenant que pour un transport VRAIMENT nouveau (aucun onglet
        // existant trouvé ci-dessus).
        int node = 250; // valeur neutre, uniquement pour tenter la sonde ci-dessous
        String serial = null;
        try {
            TransportIo ioT = (mediaTransportManager != null) ? mediaTransportManager.getByKey(tk) : null;
            if (ioT != null && ioT.isOpen()) {
                ProbeResult pr = probeRegisterReadable(ioT, node, 255, null);
                if (pr != null && pr.ok && pr.serial != null && isPlausibleSerial(pr.serial)) {
                    serial = safeSerial(pr.serial);
                }
            }
        } catch (Exception ignored) {}

        if (serial == null || serial.trim().isEmpty() || !isPlausibleSerial(serial)) {
            // Rien de réel trouvé sur ce transport — ne touche à AUCUN onglet existant.
            ui.post(this::refreshAllTabsMediaStatus);
            return;
        }

        final int fNode = node;
        final String fSerial = serial;

        // 2) créer/activer un tab pour ce transport (registre réellement sondé) + focus
        ui.post(() -> {
            try {
                upsertRegisterTabFromScan(tk, fNode, 255, fSerial, true);
                refreshAllTabsMediaStatus();
            } catch (Exception ignored) {}
        });
    }


    // =========================
    // ✅ Status(B) -> TAB label Net/Gross
    // - SUCCÈS: afficher N/G sur le tab
    // - ÉCHEC : effacer N/G du tab
    // =========================
    public void reportTabQuantitiesFromStatusB(int node, String serialId, String transportKey, double net, double gross) {
        try {
            String serial = safeSerial(serialId);
            TabSpec spec = null;

            // 1) match exact (media,node,serial)
            if (!serial.isEmpty()) {
                String media = mediaShortFromTransportKey(transportKey);
                String key = tabKeyOf(media, node, serial);
                spec = tabsByKey.get(key);
            }

            // ✅ FIX (6 août 2026, concurrence) — les 3 boucles ci-dessous
            // protégées.
            // 2) fallback (node,serial)
            if (spec == null && !serial.isEmpty()) {
                synchronized (tabsByKey) {
                    for (TabSpec s : tabsByKey.values()) {
                        if (s == null) continue;
                        if ((s.node & 0xFF) != (node & 0xFF)) continue;
                        if (!serial.equalsIgnoreCase(safeSerial(s.serialId))) continue;
                        spec = s;
                        break;
                    }
                }
            }

            // 3) fallback (node,transportKey)
            if (spec == null) {
                String tk = (transportKey != null ? transportKey.trim() : "");
                if (!tk.isEmpty()) {
                    synchronized (tabsByKey) {
                        for (TabSpec s : tabsByKey.values()) {
                            if (s == null) continue;
                            if ((s.node & 0xFF) != (node & 0xFF)) continue;
                            String stk = (s.transportKey != null ? s.transportKey.trim() : "");
                            if (tk.equalsIgnoreCase(stk)) {
                                spec = s;
                                break;
                            }
                        }
                    }
                }
            }

            if (spec == null) return;
            spec.qtySuffix = formatQtyLabel(net, gross);
            String base = tabLabelOf(spec.mediaShort, spec.node, spec.serialId, spec.isLc3);
            updateRegisterTabLabel(spec.tabKey, base + spec.qtySuffix);
        } catch (Exception ignored) {}
    }

    public void clearTabQuantitiesFromStatusB(int node, String serialId, String transportKey) {
        try {
            String serial = safeSerial(serialId);
            TabSpec spec = null;

            if (!serial.isEmpty()) {
                String media = mediaShortFromTransportKey(transportKey);
                String key = tabKeyOf(media, node, serial);
                spec = tabsByKey.get(key);
            }

            // ✅ FIX (6 août 2026, concurrence)
            if (spec == null && !serial.isEmpty()) {
                synchronized (tabsByKey) {
                    for (TabSpec s : tabsByKey.values()) {
                        if (s == null) continue;
                        if ((s.node & 0xFF) != (node & 0xFF)) continue;
                        if (!serial.equalsIgnoreCase(safeSerial(s.serialId))) continue;
                        spec = s;
                        break;
                    }
                }
            }

            if (spec == null) {
                String tk = (transportKey != null ? transportKey.trim() : "");
                if (!tk.isEmpty()) {
                    synchronized (tabsByKey) {
                        for (TabSpec s : tabsByKey.values()) {
                            if (s == null) continue;
                            if ((s.node & 0xFF) != (node & 0xFF)) continue;
                            String stk = (s.transportKey != null ? s.transportKey.trim() : "");
                            if (tk.equalsIgnoreCase(stk)) { spec = s; break; }
                        }
                    }
                }
            }

            if (spec == null) return;
            spec.qtySuffix = null;
            String base = tabLabelOf(spec.mediaShort, spec.node, spec.serialId, spec.isLc3);
            updateRegisterTabLabel(spec.tabKey, base);
        } catch (Exception ignored) {}
    }

private void ensureActiveTransport(String transportKey, String reason) {
        try {
            if (transportKey == null || transportKey.trim().isEmpty()) return;
            if (mediaTransportManager == null) mediaTransportManager = MediaTransportManager.get(this);
            if (mediaTransportManager != null) {
                // TAB_SWITCH entre deux BT différents — pas d'activateExclusive
                // chaque registre BT garde son transport actif
                if ("TAB_SWITCH".equals(reason)) {
                    String tk = transportKey.trim();
                    boolean isBt = tk.toUpperCase().startsWith("BT:");
                    if (isBt) {
                        // Vérifier si l'autre transport actif est aussi BT
                        String activeKey = mediaTransportManager.getActiveKey();
                        boolean otherIsBt = activeKey != null && activeKey.toUpperCase().startsWith("BT:");
                        if (otherIsBt && !activeKey.equalsIgnoreCase(tk)) {
                            // Deux BT différents — pas d'exclusion
                            return;
                        }
                    }
                }

                if (!"TAB_SWITCH".equals(reason) && !isTransportSwitchSafe(transportKey.trim(), reason)) {
                    return;
                }

                mediaTransportManager.activateExclusive(transportKey.trim(), (reason != null ? reason : "UI"));
            }
        } catch (Exception ignored) {}
    }

    // ✅ FIX (4 août 2026, demande Paul) — "on ne doit jamais oublier l'arrivée
    // du deeplink peu importe le transport trouvé". Cette garde vivait
    // uniquement dans ensureActiveTransport() — mais DeepLinkHandler appelle
    // AUSSI mediaTransportManager.activateExclusive() DIRECTEMENT plus loin
    // dans son flux (juste avant le oneshot start, raison
    // "DEEPLINK_ONESHOT"), contournant complètement ce garde. Extrait ici en
    // méthode publique réutilisable par MainActivity (ensureActiveTransport)
    // ET par DeepLinkHandler, pour qu'AUCUN chemin d'activation de transport
    // ne puisse contourner la règle — peu importe le média (BT/USB/TCP) et
    // peu importe le point d'entrée (USB branché, BT connecté, deep link,
    // oneshot start).
    //
    // Retourne false si une livraison est active sur le transport actuel ET
    // que le nouveau transport ne correspond pas au même registre (node+
    // #série) — dans ce cas, l'appelant ne doit PAS voler l'exclusivité.
    public boolean isTransportSwitchSafe(String newTransportKey, String reason) {
        try {
            if (newTransportKey == null || newTransportKey.trim().isEmpty()) return true;
            if (mediaTransportManager == null) mediaTransportManager = MediaTransportManager.get(this);
            if (mediaTransportManager == null) return true;

            String activeKeyBefore = mediaTransportManager.getActiveKey();
            String tkNew = newTransportKey.trim();
            if (activeKeyBefore == null || activeKeyBefore.equalsIgnoreCase(tkNew)) return true;

            // ✅ FIX (6 août 2026, concurrence) — une seule copie défensive
            // réutilisée pour les deux boucles (elles itèrent la même map).
            java.util.List<TabSpec> tabSnapshotSafe;
            synchronized (tabsByKey) { tabSnapshotSafe = new ArrayList<>(tabsByKey.values()); }

            for (TabSpec spec : tabSnapshotSafe) {
                if (spec == null || spec.transportKey == null) continue;
                if (!activeKeyBefore.equalsIgnoreCase(spec.transportKey)) continue;
                com.pa.lcr.lcp.DeliveryController dcActive =
                    com.pa.lcr.lcp.RegisterSessionManager.get(this)
                        .getController(spec.transportKey, spec.node);
                if (dcActive == null) continue;
                com.pa.lcr.lcp.DeliveryState st = dcActive.getState();
                boolean deliveryRunning = st == com.pa.lcr.lcp.DeliveryState.RUNNING_FLOWING
                        || st == com.pa.lcr.lcp.DeliveryState.RUNNING_PAUSED;
                if (!deliveryRunning) continue;

                // Livraison active trouvée sur l'ancien transport actif.
                // Le nouveau transport a-t-il le même node+#série ?
                boolean sameRegister = false;
                for (TabSpec newSpec : tabSnapshotSafe) {
                    if (newSpec == null || newSpec.transportKey == null) continue;
                    if (!tkNew.equalsIgnoreCase(newSpec.transportKey)) continue;
                    if (newSpec.node == spec.node
                            && newSpec.serialId != null && spec.serialId != null
                            && newSpec.serialId.trim().equalsIgnoreCase(spec.serialId.trim())) {
                        sameRegister = true;
                        break;
                    }
                }
                if (!sameRegister) {
                    android.util.Log.w("MainActivity", "isTransportSwitchSafe: BLOQUÉ — livraison "
                        + "active sur " + activeKeyBefore + " (node=" + spec.node + " serial="
                        + spec.serialId + "), nouveau transport " + tkNew + " n'est pas le même"
                        + " registre — exclusivité NON transférée (reason=" + reason + ")");
                    return false;
                }
                android.util.Log.i("MainActivity", "isTransportSwitchSafe: même registre "
                    + "(node=" + spec.node + " serial=" + spec.serialId + ") détecté sur "
                    + tkNew + " — bascule autorisée malgré livraison active");
            }
            return true;
        } catch (Exception e) {
            return true; // best-effort — ne jamais bloquer sur une erreur du garde lui-même
        }
    }

}
