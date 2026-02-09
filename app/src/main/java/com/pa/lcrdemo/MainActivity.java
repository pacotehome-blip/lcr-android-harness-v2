
package com.pa.lcrdemo;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import com.pa.lcr.lcp.LcpLink;
import com.pa.lcr.lcp.DeliveryController;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION = "com.pa.lcrdemo.USB_PERMISSION";

    // UI
    private TextView txtLog;
    private EditText edtTo, edtFrom, edtProduct, edtPreset;
    private Button btnConnect, btnStartOpen, btnStartPreset, btnEnd;
    private Button btnClear, btnCopy;
    private Button btnB, btnContinue, btnFinish;
    private CheckBox switchIoLog;

    // USB & LCP
    private UsbSerialPort serialPort;
    private LcpLink lcpLink;
    private DeliveryController controller;

    // Utils
    private final StringBuilder logBuf = new StringBuilder(16 * 1024);
    private final ExecutorService uiExec = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Détection "aucune progression" (prompt Continuer/Terminé)
    private volatile long lastProgressAtMs = 0L;
    private volatile int lastGross = -1, lastNet = -1;
    private static final long NO_PROGRESS_PROMPT_MS = 10_000; // 10s
    private volatile boolean promptShown = false;

    // Orchestration live loop / END / print
    private volatile boolean liveLoopActive = false;
    private volatile boolean endingRequested = false;
    private volatile boolean shouldPrintAtEnd = false;   // imprime seulement si Terminé

    // Flow=0 via DC (bitmask à ajuster avec ta doc)
    private static final int FLOW_ACTIVE_MASK = 0x0001; // TODO: mettre le bon bit du champ DC (0x23)
    private static final int NO_FLOW_DEBOUNCE_SAMPLES = 2;
    private int noFlowStreak = 0;

    // USB detach robuste
    private volatile Integer currentUsbDeviceId = null;
    private volatile long lastDetachHandledAt = 0L;
    private static final long DETACH_DEBOUNCE_MS = 1500L; // 1.5s
    private volatile boolean detachedDuringFlow = false;  // détaché pendant une session en cours
    private static final long DETACHED_FALLBACK_CLEANUP_MS = 3000L; // cleanup si rien ne se passe

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setDefaults();
        wireEvents();

        registerReceiver(usbPermissionReceiver, new IntentFilter(ACTION_USB_PERMISSION));

        IntentFilter usb = new IntentFilter();
        usb.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usb.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbAttachDetach, usb);

        LcpLink.setLogger(this::appendAndBuffer);

        append("I/O log activé\n");
        append("Prêt. Branchez le LCR puis cliquez 'Connexion USB'.\n");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(usbPermissionReceiver); } catch(Exception ignored){}
        try { unregisterReceiver(usbAttachDetach); } catch(Exception ignored){}
        try { if (serialPort != null) serialPort.close(); } catch(Exception ignored){}
        uiExec.shutdownNow();
    }

    /* ============================== Views ============================== */
    private void bindViews() {
        txtLog       = findViewById(R.id.txtLog);
        edtTo        = findViewById(R.id.edtTo);
        edtFrom      = findViewById(R.id.edtFrom);
        edtProduct   = findViewById(R.id.edtProduct);
        edtPreset    = findViewById(R.id.edtPreset);

        btnConnect     = findViewById(R.id.btnConnect);
        btnStartOpen   = findViewById(R.id.btnC);       // C – start delivery
        btnStartPreset = findViewById(R.id.btnStart);   // ancien Start (sera masqué)
        btnEnd         = findViewById(R.id.btnA);       // A reset (END)

        btnClear     = findViewById(R.id.btnClearLog);
        btnCopy      = findViewById(R.id.btnCopyLog);
        switchIoLog  = findViewById(R.id.switchIoLog);

        btnB        = findViewById(R.id.btnB);          // B ping
        btnContinue = findViewById(R.id.btnContinue);   // Continuer
        btnFinish   = findViewById(R.id.btnFinish);     // Terminé

        if (btnStartPreset != null) btnStartPreset.setVisibility(View.GONE);

        try { txtLog.setTextIsSelectable(true); } catch(Exception ignored) {}

        if (btnContinue != null) btnContinue.setEnabled(false);
        if (btnFinish   != null) btnFinish.setEnabled(false);
    }

    private void setDefaults() {
        if (edtTo != null && isEmpty(edtTo.getText()))   edtTo.setText("0xFA");
        if (edtFrom != null && isEmpty(edtFrom.getText())) edtFrom.setText("0xFF");
        if (edtProduct != null && isEmpty(edtProduct.getText())) edtProduct.setText("1");
        if (edtPreset  != null && isEmpty(edtPreset.getText()))  edtPreset.setText("50.0");

        if (switchIoLog != null) {
            switchIoLog.setChecked(true);
            LcpLink.DUMP_TX = true;
            LcpLink.DUMP_RX = true;
        }
    }

    private void wireEvents() {
        safeSetOnClick(btnConnect, v -> requestAndOpenFirstPort());
        safeSetOnClick(btnStartOpen, v -> startOpenMode());
        safeSetOnClick(btnEnd, v -> endGracefully());
        safeSetOnClick(btnClear, v -> { logBuf.setLength(0); runOnUiThread(() -> txtLog.setText("")); });
        safeSetOnClick(btnCopy, v -> copyLog());

        safeSetOnClick(btnB, v -> doBPing());
        safeSetOnClick(btnContinue, v -> continueDelivery());
        safeSetOnClick(btnFinish, v -> finishDelivery());

        if (switchIoLog != null) {
            switchIoLog.setOnCheckedChangeListener((b, checked) -> {
                LcpLink.DUMP_TX = checked;
                LcpLink.DUMP_RX = checked;
                append("I/O log " + (checked ? "activé" : "désactivé") + "\n");
            });
        }

        txtLog.setOnLongClickListener(v -> {
            try {
                txtLog.requestFocus();
                txtLog.setSelectAllOnFocus(true);
                txtLog.post(() -> {
                    txtLog.clearFocus();
                    txtLog.requestFocus();
                });
            } catch (Exception ignored) {}
            return false;
        });
    }

    private void safeSetOnClick(View v, View.OnClickListener l){
        if (v != null) v.setOnClickListener(l);
    }

    /* ============================== USB ============================== */

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (!granted) {
                append("Permission USB refusée\n");
                return;
            }
            append("Permission USB accordée, ouverture...\n");
            connectPort(device);
        }
    };

    private final BroadcastReceiver usbAttachDetach = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            final String action = i.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                append("USB attaché — cliquez 'Connexion USB'\n");
                return;
            }
            if (!UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) return;

            UsbDevice det = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            int detId = (det != null ? det.getDeviceId() : -1);
            append(String.format("USB DETACHED reçu (devId=%d)\n", detId));

            Integer curId = currentUsbDeviceId;
            if (curId == null) {
                append("DETACHED ignoré (aucun device actif)\n");
                return;
            }
            if (det == null || detId != curId) {
                append(String.format("DETACHED ignoré (devId=%d != actif=%d)\n", detId, curId));
                return;
            }

            long now = System.currentTimeMillis();
            if (now - lastDetachHandledAt < DETACH_DEBOUNCE_MS) {
                append("DETACHED ignoré (debounce)\n");
                return;
            }
            lastDetachHandledAt = now;

            // Session "en cours" = tout sauf IDLE/ENDED/ERROR
            DeliveryController.State s = (controller != null ? controller.getState() : DeliveryController.State.IDLE);
            boolean inProgress = (s != DeliveryController.State.IDLE &&
                                  s != DeliveryController.State.ENDED &&
                                  s != DeliveryController.State.ERROR);

            if (inProgress) {
                detachedDuringFlow = true;
                append("DETACHED pendant session (state=" + s + ") — I/O laissera remonter l'erreur; cleanup différé.\n");

                // Fallback: si rien ne se passe dans 3s (blocage improbable), on nettoie de force.
                mainHandler.postDelayed(() -> {
                    if (detachedDuringFlow) {
                        append("DETACHED timeout → cleanup forcé\n");
                        try { if (controller != null) controller.requestStop("usb detached (timeout)"); } catch(Exception ignored){}
                        cleanupUsb();
                        detachedDuringFlow = false;
                    }
                }, DETACHED_FALLBACK_CLEANUP_MS);
                return;
            }

            // Session non active → nettoyage immédiat.
            append("DETACHED : arrêt contrôleur + fermeture port (no active session)\n");
            try { if (controller != null) controller.requestStop("usb detached"); } catch(Exception ignored){}
            cleanupUsb();
        }
    };

    private void requestAndOpenFirstPort() {
        try {
            UsbManager mgr = (UsbManager)getSystemService(Context.USB_SERVICE);
            List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(mgr);
            if (drivers == null || drivers.isEmpty()) {
                append("Aucun convertisseur USB‑Série détecté\n");
                return;
            }
            UsbDevice dev = drivers.get(0).getDevice();
            if (!mgr.hasPermission(dev)) {
                append("Demande de permission USB…\n");
                PendingIntent pi = PendingIntent.getBroadcast(
                        this, 0,
                        new Intent(ACTION_USB_PERMISSION),
                        PendingIntent.FLAG_IMMUTABLE
                );
                mgr.requestPermission(dev, pi);
                return;
            }
            connectPort(dev);
        } catch (Exception e) {
            append("ERREUR: " + e.getMessage() + "\n");
        }
    }

    private void connectPort(UsbDevice dev) {
        try {
            UsbManager mgr = (UsbManager)getSystemService(Context.USB_SERVICE);
            UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(dev);
            if (driver == null) {
                append("Pas de driver USB-Série compatible\n");
                return;
            }

            UsbDeviceConnection conn = mgr.openDevice(dev);
            if (conn == null) {
                append("Impossible d’ouvrir le périphérique USB\n");
                return;
            }

            serialPort = driver.getPorts().get(0);
            serialPort.open(conn);
            serialPort.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            serialPort.setRTS(false);
            serialPort.setDTR(false);
            serialPort.purgeHwBuffers(true, true);

            // ID du device actif (pour filtrer DETACHED)
            if (driver.getDevice() != null) currentUsbDeviceId = driver.getDevice().getDeviceId();
            else currentUsbDeviceId = dev.getDeviceId();

            append("Port ouvert 19200 8N1\n");

            int to   = parseHexOrDefault(safe(edtTo),   0xFA);
            int from = parseHexOrDefault(safe(edtFrom), 0xFF);

            // LcpLink
            lcpLink = new LcpLink(serialPort, to, from, true);

            // Une seule instance de DeliveryController pour toute la session
            controller = new DeliveryController(
                    lcpLink,
                    new DeliveryController.DeliveryEvents() {
                        @Override public void onStateChanged(DeliveryController.State s) {
                            append("[SDK] State=" + s + "\n");
                            runOnUiThread(() -> {
                                switch (s) {
                                    case STARTING:
                                    case WAIT_FOR_FLOW:
                                        if (btnFinish != null) btnFinish.setEnabled(true);
                                        if (btnContinue != null) btnContinue.setEnabled(false);
                                        break;
                                    case FLOW_ACTIVE:
                                        if (btnFinish != null) btnFinish.setEnabled(true);
                                        break;
                                    case ENDED:
                                    case ERROR:
                                        if (btnFinish != null) btnFinish.setEnabled(false);
                                        if (btnContinue != null) btnContinue.setEnabled(false);

                                        // Impression après END si demandé via Terminé
                                        if (s == DeliveryController.State.ENDED && shouldPrintAtEnd) {
                                            shouldPrintAtEnd = false;
                                            printTicket();  // Impression LCP (0x22)
                                        }

                                        // Cleanup USB si détaché pendant le flow
                                        if (detachedDuringFlow) {
                                            append("Cleanup post-DETACHED (state=" + s + ")\n");
                                            cleanupUsb();
                                            detachedDuringFlow = false;
                                        }

                                        // Reset des flags
                                        endingRequested = false;
                                        liveLoopActive = false;
                                        noFlowStreak = 0;
                                        break;
                                    default: break;
                                }
                            });
                        }
                        @Override public void onFlowStarted() {
                            append("[SDK] FLOW détecté\n");
                            // reset détection "no progress"
                            lastProgressAtMs = System.currentTimeMillis();
                            lastGross = -1; lastNet = -1; promptShown = false;
                            noFlowStreak = 0;

                            // Ne pas relancer de live loop si une fin est déjà demandée
                            if (endingRequested) {
                                append("[SDK] Fin déjà demandée → pas de live loop\n");
                                return;
                            }

                            uiExec.execute(() -> {
                                liveLoopActive = true;
                                controller.runLiveLoop(250, false, 0.0);
                            });
                        }
                        @Override public void onFlowStopped() {
                            append("[SDK] FLOW stoppé\n");
                            liveLoopActive = false;

                            // Flow=0 : activer Continuer & Terminé
                            runOnUiThread(() -> {
                                if (btnContinue != null) btnContinue.setEnabled(true);
                                if (btnFinish   != null) btnFinish.setEnabled(true);
                            });

                            if (detachedDuringFlow) {
                                append("Cleanup post-DETACHED (onFlowStopped)\n");
                                cleanupUsb();
                                detachedDuringFlow = false;
                            }
                        }
                        @Override public void onLiveSample(int ds, int dc, double gL, double nL) {
                            append(String.format("[LIVE] DS=0x%04X DC=0x%04X G=%.1f N=%.1f\n",
                                    ds, dc, gL, nL));
                            // Détection "aucune progression" (alignée au prompt Python)
                            try {
                                int g = (int)Math.round(gL * 1000); // échelle indifférente aux digits
                                int n = (int)Math.round(nL * 1000);
                                boolean progressed;
                                if (lastGross < 0 && lastNet < 0) {
                                    progressed = true; // init
                                } else {
                                    progressed = (g != lastGross) || (n != lastNet);
                                }
                                long now = System.currentTimeMillis();
                                if (progressed) {
                                    lastGross = g; lastNet = n;
                                    lastProgressAtMs = now;
                                    if (promptShown) {
                                        promptShown = false;
                                        runOnUiThread(() -> { if (btnContinue != null) btnContinue.setEnabled(false); });
                                    }
                                } else {
                                    long base = (lastProgressAtMs == 0L) ? now : lastProgressAtMs;
                                    if (!promptShown && now - base >= NO_PROGRESS_PROMPT_MS) {
                                        promptShown = true;
                                        append("[LIVE] Aucune progression de volume… Continuer (C) / Terminer (T) ?\n");
                                        runOnUiThread(() -> {
                                            if (btnContinue != null) btnContinue.setEnabled(true);
                                            if (btnFinish   != null) btnFinish.setEnabled(true);
                                        });
                                    }
                                }
                            } catch(Exception ignored){}

                            // --- Déclenchement UI quand flow=0 (en plus de onFlowStopped) ---
                            boolean flowActive = (dc & FLOW_ACTIVE_MASK) != 0;
                            if (!flowActive) {
                                noFlowStreak++;
                                if (noFlowStreak >= NO_FLOW_DEBOUNCE_SAMPLES) {
                                    runOnUiThread(() -> {
                                        if (btnContinue != null) btnContinue.setEnabled(true);
                                        if (btnFinish   != null) btnFinish.setEnabled(true);
                                    });
                                }
                            } else {
                                noFlowStreak = 0;
                            }
                        }
                        @Override public void onGuardReached() {
                            append("[SDK] Guard atteint → END demandé\n");
                        }
                        @Override public void onError(String m, Throwable t) {
                            append("[SDK] ERREUR: " + m +
                                    (t != null ? (" / " + t.getMessage()) : "") + "\n");
                            runOnUiThread(() -> {
                                if (btnFinish != null) btnFinish.setEnabled(false);
                                if (btnContinue != null) btnContinue.setEnabled(false);
                            });
                            liveLoopActive = false;

                            if (detachedDuringFlow) {
                                append("Cleanup post-DETACHED (onError)\n");
                                cleanupUsb();
                                detachedDuringFlow = false;
                            }
                            // endingRequested réinitialisé sur ENDED/ERROR via onStateChanged
                        }
                    },
                    null
            );

            // RESYNC GET_PRODUCT_ID
            try {
                lcpLink.sendRecv(new byte[]{0x00}, 2000);
                append("[CONNECT] RESYNC GET_PRODUCT_ID OK\n");
            } catch (Exception e) {
                append("[CONNECT] RESYNC: " + e.getMessage() + "\n");
            }

        } catch (Exception e) {
            append("ERREUR ouverture USB: " + e.getMessage() + "\n");
            try { if (serialPort != null) serialPort.close(); } catch(Exception ignored){}
            serialPort = null; lcpLink = null; controller = null;
            currentUsbDeviceId = null;
        }
    }

    /* ============================== Nouveaux comportements UI ============================== */

    // B ping: lit un statut machine (0x23 via op*)
    private void doBPing() {
        try {
            if (!checkReady()) return;
            int[] triple = lcpLink.opMachineStatusFull();
            append(String.format("[PING] DS=0x%04X DC=0x%04X\n", triple[1], triple[2]));
        } catch (Exception e) {
            append("[PING] ERREUR: " + e.getMessage() + "\n");
        }
    }

    // Terminé: END propre + impression du ticket
    private void finishDelivery() {
        if (!checkReady()) return;
        append("[UI] Terminé demandé\n");

        endingRequested  = true;
        shouldPrintAtEnd = true;  // imprimera à ENDED

        // 1) Couper la live loop pour libérer la PollWindow/le thread
        try { controller.stopLiveLoop(); } catch (Exception ignored) {}
        liveLoopActive = false;

        // 2) Enchaîner l'END propre après un léger délai pour laisser la loop s'éteindre
        mainHandler.postDelayed(() -> {
            try {
                if (controller != null) {
                    append("[UI] END → envoi 0x24\n");
                    controller.endGracefully(15_000, 200);
                }
            } catch (Exception ignored) {}
        }, 150);

        // 3) UI : désactiver les boutons d'options
        if (btnContinue != null) btnContinue.setEnabled(false);
        if (btnFinish   != null) btnFinish.setEnabled(false);
    }

    // Continuer: pas d'impression, on ferme le prompt et on laisse la session se poursuivre
    private void continueDelivery() {
        append("[UI] Aucune progression… Continuer (pas d'impression)\n");
        shouldPrintAtEnd = false;
        promptShown = false;
        if (btnContinue != null) btnContinue.setEnabled(false);
        // rien d'autre : la boucle live reprend si le FLOW revient
    }

    /* ============================== Actions ============================== */

    private void startOpenMode() {
        if (!checkReady()) return;
        int product = parseIntOrDefault(safe(edtProduct), 1);
        append(String.format("[UI] Start OPEN product=%d\n", product));
        controller.startOpenMode(product, 20_000, 200);
    }

    // Ancien "start preset" (retiré du layout) – conservé pour usage futur
    @SuppressWarnings("unused")
    private void startPresetNet() {
        if (!checkReady()) return;
        int product = parseIntOrDefault(safe(edtProduct), 1);
        double presetL = parseDoubleOrDefault(safe(edtPreset), 50.0);
        append(String.format("[UI] Start PRESET NET %d → %.1f L\n", product, presetL));
        controller.startPresetNet(product, presetL, 20_000, 200);
    }

    // Bouton A: END propre (même séquence que "Terminé" mais sans forcer l'impression)
    private void endGracefully() {
        if (!checkReady()) return;
        append("[UI] END demandé (A)\n");

        endingRequested  = true;
        // shouldPrintAtEnd reste inchangé : seul "Terminé" décide d'imprimer

        try { controller.stopLiveLoop(); } catch (Exception ignored) {}
        liveLoopActive = false;

        mainHandler.postDelayed(() -> {
            try {
                if (controller != null) {
                    append("[UI] END (A) → envoi 0x24\n");
                    controller.endGracefully(15_000, 200);
                }
            } catch (Exception ignored) {}
        }, 150);
    }

    /* ============================== Impression (LCP 0x22) ============================== */

    /**
     * Impression du ticket par COMMANDE LCP 0x22 (PrintText).
     * Le LCR-II imprime en autonome; on pousse les lignes de texte côté hôte.
     */
    private void printTicket() {
        try {
            if (lcpLink == null) {
                append("[PRINT] LcpLink indisponible — impression annulée\n");
                return;
            }

            // --------- 1) Construire un ticket simple (exemple minimal) ----------
            StringBuilder sb = new StringBuilder();
            sb.append("Delivery Ticket").append('\n').append('\n');
            sb.append("Time: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX")
                    .format(new java.util.Date())).append('\n');
            // TODO: Ajouter tes totaux si tu veux refléter exactement le ticket Python.
            String ticket = sb.toString();
            byte[] full = ticket.getBytes(java.nio.charset.StandardCharsets.US_ASCII);

            // --------- 2) Envoi 0x22 par blocs (≈ 90 octets, comme Python ~0x5A) ----------
            final byte CMD_PRINT_TEXT = (byte)0x22;
            final int  CHUNK = 90;

            try { lcpLink.openPollWindow(); } catch (Throwable ignored) {}

            try {
                int off = 0;
                while (off < full.length) {
                    int len = Math.min(CHUNK, full.length - off);
                    byte[] msg = new byte[1 + len];
                    msg[0] = CMD_PRINT_TEXT;
                    System.arraycopy(full, off, msg, 1, len);

                    // Retry léger si timeout sporadique
                    boolean ok = false;
                    int tries = 0;
                    while (!ok && tries < 2) {
                        tries++;
                        try {
                            byte[] resp = lcpLink.sendRecv(msg, 5000); // 5s / bloc
                            append("[PRINT] 0x22 ack: " + bytesToHex(resp) + "\n");
                            ok = true;
                        } catch (Exception ex) {
                            if (tries >= 2) throw ex;
                            append("[PRINT] 0x22 retry: " + ex.getMessage() + "\n");
                            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                        }
                    }

                    off += len;
                }
                append("[PRINT] Host print OK\n");
            } finally {
                try { lcpLink.closePollWindow(); } catch (Throwable ignored) {}
            }
        } catch (Exception e) {
            append("[PRINT] Erreur impression LCP: " + e.getMessage() + "\n");
        }
    }

    // Utilitaire pour loguer un buffer en hex
    private static String bytesToHex(byte[] b) {
        if (b == null) return "(null)";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X ", x));
        return sb.toString().trim();
    }

    /* ============================== Utils ============================== */

    private boolean checkReady() {
        if (serialPort == null || lcpLink == null) {
            append("USB/LCP non prêt. Connectez d'abord.\n");
            return false;
        }
        if (controller == null) {
            append("SDK non prêt (reconnectez si besoin).\n");
            return false;
        }
        return true;
    }

    private void copyLog() {
        ClipboardManager cb = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb == null) {
            append("Clipboard indisponible\n");
            return;
        }
        cb.setPrimaryClip(ClipData.newPlainText("lcr_log", logBuf.toString()));
        append("Log copié\n");
    }

    private void append(String s) {
        runOnUiThread(() -> {
            boolean hasSelection = false;
            try {
                int selStart = txtLog.getSelectionStart();
                int selEnd   = txtLog.getSelectionEnd();
                hasSelection = (selStart != selEnd);
            } catch (Exception ignored) {}

            txtLog.append(s);

            if (!hasSelection) {
                ScrollView sv = findViewById(R.id.logScroll);
                if (sv != null) sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    private void appendAndBuffer(String s) {
        if (s == null) return;
        if (!s.endsWith("\n")) s = s + "\n";
        logBuf.append(s);
        append(s);
    }

    private void cleanupUsb() {
        try { if (serialPort != null) serialPort.close(); } catch(Exception ignored){}
        serialPort = null; lcpLink = null; controller = null;
        currentUsbDeviceId = null;
        append("USB nettoyé (port fermé)\n");
    }

    private static boolean isEmpty(CharSequence cs){ return cs == null || cs.toString().trim().isEmpty(); }
    private static String safe(EditText e){ return e == null ? "" : (e.getText()==null ? "" : e.getText().toString().trim()); }
    private static int parseHexOrDefault(String s, int def){
        try { return Integer.parseInt(s.replace("0x","").replace("0X",""), 16) & 0xFF; }
        catch(Exception e){ return def; }
    }
    private static int parseIntOrDefault(String s, int def){
        try { return Integer.parseInt(s); } catch(Exception e){ return def; }
    }
    private static double parseDoubleOrDefault(String s, double def){
        try { return Double.parseDouble(s); } catch(Exception e){ return def; }
    }
}
