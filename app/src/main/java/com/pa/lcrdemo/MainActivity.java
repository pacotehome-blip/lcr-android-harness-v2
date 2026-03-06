
package com.pa.lcrdemo;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.*;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;
import com.google.android.material.tabs.TabLayout;
import com.hoho.android.usbserial.driver.*;
import com.pa.lcr.lcp.*;
import com.pa.lcr.lcp.storage.DeliveryLogStore;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
 public static final String ACTION_USB_PERMISSION = "com.pa.lcrdemo.USB_PERMISSION";
 private UsbManager usbManager;
 private final List<UsbDevice> usbDevices = new ArrayList<>();
 private UsbSerialPort usbPort;

 // ===== Tabs / Pages =====
 private TabLayout tabLayout;
 private View pageMain;
 private View pageApiFace;

 // ===== API-Face UI =====
 private TextView txtApiStatus;
 private TextView txtApiUrl;
 private TextView txtApiTrace;
 private ScrollView apiTraceScroll;
 private Button btnApiStart;
 private Button btnApiStop;
 private Button btnApiClearTrace;
 private Button btnApiCopyCurl;
 private Button btnDbBackup;

 // ===== API runtime =====
 private static final int API_PORT = 8765;
 private ApiTraceBuffer apiTrace;
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
   if (controller == null) { liveTickRunning = false; return; }
   // ✅ Poll en RUNNING_FLOWING ET RUNNING_PAUSED (pas seulement FLOWING)
   DeliveryState st = controller.getState();
   boolean shouldPoll =
           (st == DeliveryState.RUNNING_FLOWING)

                   || (st == DeliveryState.RUNNING_PAUSED);
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
          (st == DeliveryState.RUNNING_FLOWING)

                  || (st == DeliveryState.RUNNING_PAUSED);
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

   final int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
   try {
    getContentResolver().takePersistableUriPermission(dirUri, takeFlags);
   } catch (Exception ignored) {
   }

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

  // ✅ R2: rattrapage si USB READY est arrivé pendant que l'activité était stoppée
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
  apiTrace = new ApiTraceBuffer(500);
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
  txtLog = findViewById(R.id.txtLog);
  logScroll = findViewById(R.id.logScroll);
  btnClearLog = findViewById(R.id.btnClearLog);
  btnCopyLog = findViewById(R.id.btnCopyLog);
  btnScrollDown = findViewById(R.id.btnScrollDown);
  cbTxRx = findViewById(R.id.cbTxRx);
  cbLogTs = findViewById(R.id.cbLogTs);

  txtApiStatus = findViewById(R.id.txtApiStatus);
  txtApiUrl = findViewById(R.id.txtApiUrl);
  txtApiTrace = findViewById(R.id.txtApiTrace);
  apiTraceScroll = findViewById(R.id.apiTraceScroll);
  btnApiStart = findViewById(R.id.btnApiStart);
  btnApiStop = findViewById(R.id.btnApiStop);
  btnApiClearTrace = findViewById(R.id.btnApiClearTrace);
  btnApiCopyCurl = findViewById(R.id.btnApiCopyCurl);
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

  if (btnApiClearTrace != null) {
   btnApiClearTrace.setOnClickListener(v -> {
    if (apiTrace != null) apiTrace.clear();
    refreshApiTrace();
    toast("Trace API effacée");
   });
  }

  if (btnApiCopyCurl != null) {
   btnApiCopyCurl.setOnClickListener(v -> copyCurlExamples());
  }

  // Backup DB: tap = use saved dir if any; else auto Downloads (10+) or pick dir (<=9)
  // long-press = choose dir (SAF)
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
  if (index == 1) {
   refreshApiStatus();
   refreshApiTrace();
  }
 }

 // =========================
 // API Server
 // =========================
 private void startApiServer() {
  if (controller == null) {
   apiTraceAdd("[API] START REFUSED: controller==null (Connect LCP requis)");
   refreshApiStatus();
   refreshApiTrace();
   toast("Start API refusé: Connect LCP requis");
   return;
  }
  if (!(controller instanceof DeliveryController)) {
   apiTraceAdd("[API] START REFUSED: controller type incompatible");
   refreshApiStatus();
   refreshApiTrace();
   toast("Start API refusé: controller incompatible");
   return;
  }
  if (apiServer != null && apiServer.isRunning()) {
   apiTraceAdd("[API] déjà RUNNING");
   refreshApiStatus();
   refreshApiTrace();
   return;
  }
  try {
   DeliveryController dc = (DeliveryController) controller;
   ApiFacade facade = new DeliveryApiFacadeImpl(dc, this);
   apiServer = new ApiServer(facade, apiTrace, API_PORT);
   apiServer.start();
   apiTraceAdd("[API] START OK on http://127.0.0.1:" + API_PORT);
   refreshApiStatus();
   refreshApiTrace();
   toast("API démarrée (127.0.0.1:" + API_PORT + ")");
  } catch (Exception e) {
   apiTraceAdd("[API] START FAIL: " + safeMsg(e));
   refreshApiStatus();
   refreshApiTrace();
   toast("API start error: " + safeMsg(e));
  }
 }

 private void stopApiServer(String reason) {
  try {
   if (apiServer != null && apiServer.isRunning()) {
    apiServer.stop();
    apiTraceAdd("[API] STOP (" + reason + ")");
   }
  } catch (Exception ignored) {
  } finally {
   apiServer = null;
   refreshApiStatus();
   refreshApiTrace();
  }
 }

 private void refreshApiStatus() {
  if (txtApiStatus == null) return;
  boolean running = (apiServer != null && apiServer.isRunning());
  txtApiStatus.setText("Status: " + (running ? "RUNNING (loopback only)" : "STOPPED"));
  if (btnApiStart != null) btnApiStart.setEnabled(!running);
  if (btnApiStop != null) btnApiStop.setEnabled(running);
 }

 private void refreshApiTrace() {
  if (txtApiTrace == null ||

          apiTrace == null) return;
  List<String> lines = apiTrace.snapshot();
  if (lines.isEmpty()) {
   txtApiTrace.setText("(trace vide)");
   return;
  }
  StringBuilder sb = new StringBuilder(lines.size() * 64);
  for (String l : lines) sb.append(l).append('\n');
  txtApiTrace.setText(sb.toString());
  if (apiTraceScroll != null) {
   apiTraceScroll.post(() -> apiTraceScroll.fullScroll(View.FOCUS_DOWN));
  }
 }

 private void apiTraceAdd(String line) {
  if (apiTrace == null) return;
  apiTrace.add(line);
  ui.post(this::refreshApiTrace);
 }

 private void copyCurlExamples() {
  String base = "http://127.0.0.1:" + API_PORT;
  String examples =
          "adb reverse tcp:" + API_PORT + " tcp:" + API_PORT + "\n\n" +
                  "curl " + base + "/v1/ping\n" +
                  "curl " + base + "/v1/usb/scan\n" +
                  "curl -X POST " + base + "/v1/usb/open-ping\n" +
                  "curl -X POST " + base + "/v1/lcp/connect\n\n" +
                  "curl -X POST " + base + "/v1/delivery/C \\\n" +
                  " -H \"Content-Type: application/json\" \\\n" +
                  " -d '{\"product1to16\":1,\"presetNet\":50.0}'\n\n" +
                  "curl " + base + "/v1/delivery/job/<jobId>\n";
  ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
  cm.setPrimaryClip(ClipData.newPlainText("curl", examples));
  toast("Exemples curl copiés");
 }

 private void doBackupDb() {
  if (deliveryStore == null) {
   toast("Backup DB impossible: store absent");
   return;
  }

  // ✅ Choix 2: si un dossier SAF est déjà choisi -> écrire dedans
  Uri savedDir = getSavedBackupDirUri();
  if (savedDir != null) {
   backupDbToChosenDir(savedDir);
   return;
  }

  // Sinon, fallback:
  // - Android 10+ : auto Downloads (MediaStore.Downloads)
  // - Android <= 9 : demander dossier (MediaStore.Downloads non dispo)
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
      java.io.File dbFile = getDatabasePath(com.pa.lcr.lcp.storage.DeliveryDb.DB_NAME);
      if (dbFile == null || !dbFile.exists()) {
        toast("Backup FAIL: DB introuvable (" + com.pa.lcr.lcp.storage.DeliveryDb.DB_NAME + ")");
        return;
      }

      // IMPORTANT (SQLite journal/WAL):
      // Sur Android, le fichier principal .db peut ne pas contenir les dernieres donnees si un journal est present.
      // Pour un backup fiable (support/terrain), on copie aussi les fichiers sidecar si existants:
      //   - .db-journal (mode rollback)
      //   - .db-wal / .db-shm (mode WAL)
      // Ainsi, l'analyse sur PC retrouvera les donnees correctement.

      DocumentFile dir = DocumentFile.fromTreeUri(this, dirUri);
      if (dir == null || !dir.canWrite()) {
        toast("Backup FAIL: dossier non accessible en ecriture");
        return;
      }

      String baseName = "lcr_delivery_" + utcStamp() + ".db";

      // Copie fichier principal
      boolean okMain = copyLocalFileToDocumentFile(dbFile, dir, baseName, "application/x-sqlite3");
      if (!okMain) return;

      // Copie sidecars (si presents)
      java.io.File journal = new java.io.File(dbFile.getAbsolutePath() + "-journal");
      java.io.File wal     = new java.io.File(dbFile.getAbsolutePath() + "-wal");
      java.io.File shm     = new java.io.File(dbFile.getAbsolutePath() + "-shm");

      // On copie si le fichier existe ET a une taille > 0 (utile, evite un -wal vide)
      if (journal.exists() && journal.length() > 0) {
        copyLocalFileToDocumentFile(journal, dir, baseName + "-journal", "application/octet-stream");
      }
      if (shm.exists() && shm.length() > 0) {
        copyLocalFileToDocumentFile(shm, dir, baseName + "-shm", "application/octet-stream");
      }
      if (wal.exists() && wal.length() > 0) {
        copyLocalFileToDocumentFile(wal, dir, baseName + "-wal", "application/octet-stream");
      }

      toast("Backup OK (dossier choisi): " + baseName);

    } catch (Exception e) {
      toast("Backup FAIL: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
    }
  }

  /**
   * Copie un fichier local (sandbox app) vers un fichier SAF (DocumentFile) dans un dossier cible.
   * Ecrase un fichier existant de meme nom.
   * Retourne true si succes, false si echec (avec toast explicite).
   */
  private boolean copyLocalFileToDocumentFile(java.io.File srcFile, DocumentFile dir, String outName, String mime) {
    try {
      if (srcFile == null || !srcFile.exists()) return false;
      if (dir == null || !dir.canWrite()) return false;

      // Supprimer si deja present
      DocumentFile existing = dir.findFile(outName);
      if (existing != null) {
        try { existing.delete(); } catch (Exception ignore) {}
      }

      DocumentFile target = dir.createFile(mime, outName);
      if (target == null || target.getUri() == null) {
        toast("Backup FAIL: creation fichier impossible: " + outName);
        return false;
      }

      Uri outUri = target.getUri();
      try (java.io.InputStream in = new java.io.FileInputStream(srcFile);
           java.io.OutputStream out = getContentResolver().openOutputStream(outUri)) {
        if (out == null) {
          toast("Backup FAIL: output stream null: " + outName);
          return false;
        }
        byte[] buf = new byte[64 * 1024];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        out.flush();
      }
      return true;

    } catch (Exception e) {
      toast("Backup FAIL: " + outName + " - " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
      return false;
    }
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
  // ✅ R1: si un port est déjà ouvert, ne pas ré-ouvrir
  if (usbPort != null) {
   log("USB déjà prêt (port déjà ouvert)");
   return;
  }
  int idx = spnUsbDevices.getSelectedItemPosition();
  if (idx < 0 ||

          idx >= usbDevices.size()) {
   log("Aucun périphérique USB sélectionné");
   return;
  }
  UsbDevice dev = usbDevices.get(idx);
  if (!usbManager.hasPermission(dev)) {
   PendingIntent pi = PendingIntent.getBroadcast(
           this, 0, new Intent(ACTION_USB_PERMISSION),
           PendingIntent.FLAG_UPDATE_CURRENT |
                   PendingIntent.FLAG_MUTABLE
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
  // ✅ R1: éviter port orphelin si déjà prêt
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
     // ✅ Start LIVE tick en FLOWING ou PAUSED
     if (state == DeliveryState.RUNNING_FLOWING ||

             state == DeliveryState.RUNNING_PAUSED) startLiveTickIfNeeded();
     else stopLiveTick();
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
    log("ERR[" + ctx + "] " + e.getMessage());
   }
  });

  pendingInitRunnable = () -> {
   if (controller != null) controller.initialize();
  };
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
  java.text.SimpleDateFormat df =
          new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.CANADA_FRENCH);
  return df.format(new java.util.Date(System.currentTimeMillis()));
 }

 private void log(String s) {
  ui.post(() -> {
   boolean isIoLine =
           s.startsWith("[IO ")

                   || s.startsWith("TX:")

                   || s.startsWith("RX:")

                   || s.startsWith("↳");
   String line = (logTsEnabled && !isIoLine) ? ("[UI " + uiTs() + "] " + s) : s;
   logBuf.append(line).append('\n');
   txtLog.setText(logBuf.toString());
   logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
  });
 }
}
