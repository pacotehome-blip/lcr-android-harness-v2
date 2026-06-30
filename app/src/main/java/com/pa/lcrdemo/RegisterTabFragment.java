package com.pa.lcrdemo;

// ═══════════════════════════════════════════════════════════════════════
// COMPATIBILITÉ ANDROID : API 28 (Android 9) → API 35 (Android 15)
// ───────────────────────────────────────────────────────────────────────
// Toute modification de ce fichier doit être testée sur :
//   · Android 9  (API 28) — Samsung SM-T397U  · ADB 192.168.134.105:5555
//   · Android 15 (API 35) — Samsung R52X508K2DR · ADB 192.168.134.126:5555
//
// Règles obligatoires :
//   1. Détecter la version à l'exécution via Build.VERSION.SDK_INT
//   2. Appliquer le comportement EXPLICITEMENT par version — pas de spéculation
//   3. Ne jamais utiliser d'API introduite après API 28 sans guard de version
//   4. registerReceiver() : RECEIVER_NOT_EXPORTED ou RECEIVER_EXPORTED sur API 34+
//   5. PendingIntent     : FLAG_IMMUTABLE sur API 31+ · FLAG_MUTABLE + guard sur API 34+
//   6. startForeground() : type obligatoire sur API 34+ — doit matcher le manifest
//
// Constantes utiles :
//   Build.VERSION_CODES.P                = 28  (Android 9)
//   Build.VERSION_CODES.Q                = 29  (Android 10)
//   Build.VERSION_CODES.S                = 31  (Android 12)
//   Build.VERSION_CODES.TIRAMISU         = 33  (Android 13)
//   Build.VERSION_CODES.UPSIDE_DOWN_CAKE = 34  (Android 14)
//   Build.VERSION_CODES.VANILLA_ICE_CREAM= 35  (Android 15)
// ═══════════════════════════════════════════════════════════════════════

import com.pa.lcr.lcp.transport.TransportIo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;

import com.pa.lcr.lcp.*;
import com.pa.lcr.lcp.log.LogBus;
import com.pa.lcr.lcp.transport.MediaTransportManager;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RegisterTabFragment extends Fragment {

    private static final String ARG_NODE = "node";
    private static final String ARG_FROM = "from";
    private static final String ARG_SERIAL = "serial";
    private static final String ARG_TRANSPORT = "transport";

    public static RegisterTabFragment newInstance(int node, int from) {
        RegisterTabFragment f = new RegisterTabFragment();
        Bundle b = new Bundle();
        b.putInt(ARG_NODE, node);
        b.putInt(ARG_FROM, from);
        f.setArguments(b);
        return f;
    }

    public void onTabActivated() {
        ui.postDelayed(() -> {
            try { runStatusBLikeButton("TAB_ACTIVATED"); } catch (Exception ignored) {}
        }, 300);

        // ✅ Vérifier si une livraison PENDING attend ce registre
        // Si oui → pré-remplir le tab et proposer de reprendre
        ui.postDelayed(() -> checkPendingDeliveryForThisRegister(), 600);
    }

    /**
     * Vérifie si ActiveDeliveryStore a une livraison PENDING pour ce registre.
     * Si serial + node correspondent → valider + afficher infos + bouton Lancer.
     */
    private void checkPendingDeliveryForThisRegister() {
        try {
            android.content.Context ctx = getContext();
            if (ctx == null) return;

            // ✅ Si le controller est déjà en livraison ou connecté et actif — ne pas interférer
            if (controller != null) {
                com.pa.lcr.lcp.DeliveryState st = controller.getState();
                if (st == com.pa.lcr.lcp.DeliveryState.RUNNING_FLOWING
                        || st == com.pa.lcr.lcp.DeliveryState.RUNNING_PAUSED
                        || st == com.pa.lcr.lcp.DeliveryState.ENDING
                        || st == com.pa.lcr.lcp.DeliveryState.CONNECTED) return;
            }

            com.pa.lcr.lcp.storage.ActiveDeliveryStore ads =
                new com.pa.lcr.lcp.storage.ActiveDeliveryStore(ctx);
            com.pa.lcr.lcp.storage.ActiveDeliveryStore.ActiveDelivery ad = ads.load();

            // ✅ Filet de sécurité — capturer le contexte WO stable dès qu'on le voit
            if (ad != null && ad.woNum != null && !ad.woNum.isEmpty()) {
                currentWoNum = ad.woNum;
                if (ad.woIdGuid != null && !ad.woIdGuid.isEmpty()) currentWoIdGuid = ad.woIdGuid;
            }

            if (ad == null || (!"PENDING".equals(ad.status) && !"CANCELLED".equals(ad.status) && !"STARTED".equals(ad.status))) return;

            // ✅ Si CANCELLED — remettre net/gross à zéro (nouvelle livraison à venir)
            if ("CANCELLED".equals(ad.status)) {
                ui.post(() -> {
                    if (txtQtyNet   != null) txtQtyNet.setText("NET: 0.0");
                    if (txtQtyGross != null) txtQtyGross.setText("GROSS: 0.0");
                });
                return;
            }

            if (ad.woNum == null || ad.woNum.isEmpty()) return;

            // ✅ Bon registre — effacer lastResultJson stale
            com.pa.lcrdemo.DeepLinkHandler.lastResultJson = null;

            // Forcer attachement listener UI
            ui.post(() -> connectThisRegister(false));

            // Attendre que le controller soit prêt puis afficher
            final com.pa.lcr.lcp.storage.ActiveDeliveryStore.ActiveDelivery fAd = ad;
            ui.postDelayed(() -> showDeliveryReadyPanel(fAd), 800);

        } catch (Exception e) {
            android.util.Log.w("RegisterTabFragment",
                "checkPendingDeliveryForThisRegister ERR: " + e.getMessage());
        }
    }

    /**
     * Affiche les infos de la livraison en attente et le bouton Lancer.
     * Appelé après validation serial/node.
     */
    private void showDeliveryReadyPanel(
            com.pa.lcr.lcp.storage.ActiveDeliveryStore.ActiveDelivery ad) {
        try {
            // ✅ Garde: ne pas afficher si le status est DONE (livraison déjà complétée)
            // On ne bloque plus sur le ticket affiché — trop agressif pour les nouvelles livraisons
            if ("DONE".equals(ad.status)) {
                android.util.Log.i("RegisterTabFragment",
                    "showDeliveryReadyPanel annulé — status DONE");
                return;
            }

            // ✅ Afficher infos livraison dans txtDeliveryUid
            if (txtDeliveryUid != null) {
                txtDeliveryUid.setText(
                    "WO: " + ad.woNum
                    + "  |  Produit: " + ad.produit
                    + "  |  Preset: " + (int) ad.preset + " L");
                txtDeliveryUid.setTextColor(
                    android.graphics.Color.parseColor("#15803d"));
            }

            // ✅ Remplacer le bouton Retour WO par "Lancer la livraison"
            if (btnRetourWO != null) {
                btnRetourWO.setVisibility(android.view.View.VISIBLE);
                btnRetourWO.setText("🚀 Lancer la livraison");
                btnRetourWO.setBackgroundColor(
                    android.graphics.Color.parseColor("#15803d"));

                // ✅ Lancer directement sans repasser par FSM
                final com.pa.lcr.lcp.storage.ActiveDeliveryStore.ActiveDelivery fAd = ad;
                btnRetourWO.setOnClickListener(v -> lancerDepuisStore(fAd));
            }

            android.widget.Toast.makeText(getContext(),
                "✅ Registre validé — prêt pour " + ad.woNum,
                android.widget.Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            android.util.Log.w("RegisterTabFragment",
                "showDeliveryReadyPanel ERR: " + e.getMessage());
        }
    }

    /**
     * Lance la livraison directement depuis ActiveDeliveryStore — sans repasser par FSM.
     */
    private void lancerDepuisStore(
            com.pa.lcr.lcp.storage.ActiveDeliveryStore.ActiveDelivery ad) {
        try {
            // Remettre le bouton à son état normal
            if (btnRetourWO != null) {
                btnRetourWO.setText("Retour au Bon de travail");
                btnRetourWO.setBackgroundColor(
                    android.graphics.Color.parseColor("#185FA5"));
                btnRetourWO.setOnClickListener(v -> retournerAuWorkOrder());
            }

            // ✅ MAC pour deep link de retour
            String macToUse = ad.mac != null ? ad.mac : "";
            if (macToUse.isEmpty() && tabTransportKey != null && tabTransportKey.startsWith("BT:")) {
                macToUse = tabTransportKey.substring(3);
            }
            final String fMac = macToUse;
            final String fWoNum = ad.woNum;
            final String fWoIdGuid = ad.woIdGuid != null ? ad.woIdGuid : "";
            final String fSerialId = ad.serialId != null ? ad.serialId : "";
            final int fNode = ad.node;
            final int fProduit = ad.produit;
            final double fPreset = ad.preset;

            android.util.Log.i("RegisterTabFragment",
                "lancerDepuisStore: wo=" + fWoNum + " node=" + fNode + " preset=" + fPreset);

            // ✅ Vérifier que le controller du tab est prêt — pas créer nouveau facade
            if (controller == null) {
                android.util.Log.w("RegisterTabFragment", "lancerDepuisStore: controller null");
                android.widget.Toast.makeText(requireContext(),
                    "Registre non connecté", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            if (!verifierIoAvantAction("LANCER_LIVRAISON")) return;

            // ✅ Activer le transport du tab AVANT oneshot
            try {
                if (tabTransportKey != null)
                    MediaTransportManager.get(requireContext())
                        .activateExclusive(tabTransportKey, "LANCER_LIVRAISON");
            } catch (Exception ignored) {}

            // ✅ Appel direct sur le controller du tab — un seul socket
            new Thread(() -> {
                try {
                    com.pa.lcr.lcp.ApiResult r = controller.api_deliveryOneShotStart(
                        fWoNum, fProduit, fPreset, null);

                    android.util.Log.i("RegisterTabFragment",
                        "lancerDepuisStore oneshot: code=" + r.code + " msg=" + r.msg);

                    final com.pa.lcr.lcp.ApiResult fR = r;
                    if (r.code == 1) {
                        // ✅ Succès — relancer deep link pour poll FSM
                        ui.post(() -> {
                            try {
                                android.net.Uri uri = android.net.Uri.parse(
                                    "lcrdemo://livraison"
                                    + "?wonum="    + android.net.Uri.encode(fWoNum)
                                    + "&woid="     + android.net.Uri.encode(fWoIdGuid)
                                    + "&btmac="    + android.net.Uri.encode(fMac)
                                    + "&serialid=" + android.net.Uri.encode(fSerialId)
                                    + "&lcrnode="  + fNode
                                    + "&produit="  + fProduit
                                    + "&preset="   + (int) fPreset
                                    + "&orgurl="   + android.net.Uri.encode(
                                        com.pa.lcrdemo.config.LcrConfig.getDataverseUrl(requireContext())));
                                android.content.Intent intent = new android.content.Intent(
                                    android.content.Intent.ACTION_VIEW, uri);
                                intent.setPackage(requireContext().getPackageName());
                                requireContext().startActivity(intent);
                            } catch (Exception ignored) {}
                        });
                    } else {
                        ui.post(() -> android.widget.Toast.makeText(requireContext(),
                            "Échec démarrage: " + fR.msg,
                            android.widget.Toast.LENGTH_LONG).show());
                    }
                } catch (Exception e) {
                    android.util.Log.e("RegisterTabFragment",
                        "lancerDepuisStore ERR: " + e.getMessage());
                    ui.post(() -> surErreurConnexion(e, "LANCER_LIVRAISON"));
                }
            }).start();

        } catch (Exception e) {
            android.util.Log.e("RegisterTabFragment",
                "lancerDepuisStore ERR: " + e.getMessage());
            android.widget.Toast.makeText(getContext(),
                "Erreur lancement: " + e.getMessage(),
                android.widget.Toast.LENGTH_LONG).show();
        }
    }
    private int node = 250;
    private int from = 255;

    private TextView txtLcrNode, txtFrom, txtSerialId, txtTicketNo, txtTicketPending;
    private TextView txtDeliveryUid;
    private TextView txtLive, txtQtyNet, txtQtyGross;
    private android.widget.AutoCompleteTextView spnProduct;
    private EditText edtPreset;
    private Button btnConnect, btnA, btnB, btnC, btnContinue, btnFinish;
    private Button btnReprintTicket;
    private Button btnRetourWO;
    private Button btnCustomPrint;
    private Button btnAnnuler;
    private Button btnScanProducts;
    private NestedScrollView regRootScroll;
    private CheckBox cbShowLog, cbTxRx, cbLogTs;
    private View logPanel;
    private TextView txtLog;
    private ScrollView logScroll;
    private Button btnClearLog, btnCopyLog, btnScrollDown;

    private boolean logTsEnabled = false;
    private long logViewSinceMs = 0L;
    private int ticketPendingFlag = -1;
    private volatile String lastLiveText = null;
    private volatile int lastDigits = 3;
    private volatile int lastDelCode = 0;
    private volatile long lastDelCodePollMs = 0L;
    private static final long DELCODE_POLL_MIN_MS = 800L;
    private volatile long lastHeaderValidateMs = 0L;
    private static final long HEADER_VALIDATE_MIN_MS = 5000L;
    private volatile boolean headerValidatedOnce = false;
    private volatile boolean logUserScrolling = false;
    private volatile long logUserScrollUntilMs = 0L;
    private boolean attemptedAutoAttachOnce = false;
    private boolean uiListenerAttached = false;
    private UsbManager usbManager;
    private DeliveryController controller;
    private String tabTransportKey = null;
    private volatile boolean tabMediaReady = true;
    private volatile boolean pendingReconnect = false;
    private volatile String tabMediaShort = "—";
    private String serialFromArgs = null;
    private String transportFromArgs = null;
    private boolean starting = false;
    private long startingSinceMs = 0L;
    private volatile boolean cancelInProgress = false;

    // ✅ Contexte WO stable du fragment — peuplé une fois au deep link initial,
    // jamais effacé par les flux concurrents (ActiveDeliveryStore peut être
    // écrasé/vidé par annulation, autre poll, etc. — ces champs ne le sont pas)
    private volatile String currentWoNum    = "";
    private volatile String currentWoIdGuid = "";
    private static final int TAB_LOG_MAX_LINES = 400;
    private static final long LOG_REFRESH_MIN_MS = 800;
    private long lastLogRefreshMs = 0L;
    private boolean logRefreshPending = false;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private void scheduleLogRefresh() {
        if (txtLog == null) return;
        if (cbShowLog == null || !cbShowLog.isChecked()) return;
        long now0 = System.currentTimeMillis();
        if (logUserScrolling && now0 < logUserScrollUntilMs) return;
        if (Looper.myLooper() != Looper.getMainLooper()) { ui.post(this::scheduleLogRefresh); return; }
        long now = System.currentTimeMillis();
        long dt = now - lastLogRefreshMs;
        if (dt >= LOG_REFRESH_MIN_MS && !logRefreshPending) {
            lastLogRefreshMs = now; refreshLogView(); return;
        }
        if (logRefreshPending) return;
        logRefreshPending = true;
        long delay = Math.max(0L, LOG_REFRESH_MIN_MS - dt);
        ui.postDelayed(() -> {
            logRefreshPending = false;
            lastLogRefreshMs = System.currentTimeMillis();
            refreshLogView();
        }, delay);
    }

    private void refreshDelCodeFromTickSnapshotThrottled() {
        try {
            DeliveryController c = controller;
            if (c == null) return;
            long now = System.currentTimeMillis();
            if (now - lastDelCodePollMs < DELCODE_POLL_MIN_MS) return;
            lastDelCodePollMs = now;
            ApiResult r = c.api_tickSnapshot();
            JSONObject d = (r != null) ? r.data : null;
            if (d != null) lastDelCode = d.optInt("delCode", lastDelCode);
        } catch (Exception ignored) {}
    }

    private void installLogScrollInterceptionFix() {
        try {
            if (logScroll != null) {
                logScroll.setOnTouchListener((v, ev) -> {
                    try { if (regRootScroll != null) regRootScroll.requestDisallowInterceptTouchEvent(true); } catch (Exception ignored) {}
                    int a = ev.getActionMasked();
                    if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                        logUserScrolling = true;
                        logUserScrollUntilMs = System.currentTimeMillis() + 1200L;
                    } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                        logUserScrolling = false;
                        logUserScrollUntilMs = System.currentTimeMillis() + 400L;
                    }
                    return false;
                });
            }
            if (txtLog != null) {
                txtLog.setOnTouchListener((v, ev) -> {
                    try { if (regRootScroll != null) regRootScroll.requestDisallowInterceptTouchEvent(true); } catch (Exception ignored) {}
                    int a = ev.getActionMasked();
                    if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                        logUserScrolling = true;
                        logUserScrollUntilMs = System.currentTimeMillis() + 1200L;
                    } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                        logUserScrolling = false;
                        logUserScrollUntilMs = System.currentTimeMillis() + 400L;
                    }
                    return false;
                });
            }
        } catch (Exception ignored) {}
    }

    private void scrollLogToBottomOnly() {
        try {
            if (logScroll == null) return;
            final int rootY = (regRootScroll != null) ? regRootScroll.getScrollY() : -1;
            try {
                if (regRootScroll != null) regRootScroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                if (regRootScroll != null) regRootScroll.requestDisallowInterceptTouchEvent(true);
            } catch (Exception ignored) {}
            logUserScrolling = true;
            logUserScrollUntilMs = System.currentTimeMillis() + 1200L;
            logScroll.post(() -> {
                try {
                    logScroll.requestFocus();
                    logScroll.fullScroll(View.FOCUS_DOWN);
                    if (regRootScroll != null && rootY >= 0) regRootScroll.scrollTo(0, rootY);
                } catch (Exception ignored) {
                } finally {
                    try { if (regRootScroll != null) regRootScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS); } catch (Exception ignored) {}
                    logUserScrolling = false;
                    logUserScrollUntilMs = System.currentTimeMillis() + 400L;
                }
            });
        } catch (Exception ignored) {}
    }

    private final DeliveryControllerPort.Listener uiListener = new DeliveryControllerPort.Listener() {
        @Override
        public void onStateChanged(DeliveryState state) {
            ui.post(() -> {
                if (!isAdded() || getView() == null) return;
                if (starting && state == DeliveryState.RUNNING_FLOWING) starting = false;
                if (starting && (System.currentTimeMillis() - startingSinceMs) > 12000L)
                    starting = false;
                refreshDelCodeFromTickSnapshotThrottled();
                updateButtons(state);
                scheduleLogRefresh();

                // ✅ CONNECTED post-livraison — forcer un refresh après 2s
                // pour laisser le temps à bg.execute (DB read pour btnRetourWO) de retourner
                if (state == DeliveryState.CONNECTED) {
                    ui.postDelayed(() -> {
                        if (!isAdded() || getView() == null || controller == null) return;
                        updateButtons(controller.getState());
                    }, 2000);
                }

                // ✅ Retour Field Service quand livraison terminée
                if (state == DeliveryState.ENDED) {
                    if (cancelInProgress) {
                        // Annulation — ne pas retourner dans FSM
                        cancelInProgress = false;
                        if (txtLive    != null) txtLive.setText("LIVE: CONNECTED — prêt pour nouvelle livraison");
                        if (txtQtyNet  != null) txtQtyNet.setText("NET: 0.0");
                        if (txtQtyGross != null) txtQtyGross.setText("GROSS: 0.0");
                        if (txtTicketNo != null) txtTicketNo.setText("Ticket Number : —");
                        if (txtDeliveryUid != null) txtDeliveryUid.setText("Delivery UID : —");
                    } else {
                        notifyDeliveryEndedToMainActivity();
                    }
                }

                // ✅ Poll post-livraison — détection fuite vanne après CONNECTED
                // Démarre uniquement si on vient de RUNNING_FLOWING ou RUNNING_PAUSED
                // Ne démarre PAS pendant un cycle actif (RUNNING_FLOWING, RUNNING_PAUSED, PRESTART, ENDING)
                if (state == DeliveryState.CONNECTED && controller != null
                        && controller.netAtDeliveryEnd > 0
                        && controller.ticketNoAtEnd != null
                        && !controller.ticketNoAtEnd.isEmpty()) {
                    demarrerPollPostLivraison(
                        controller.netAtDeliveryEnd,
                        controller.grossAtDeliveryEnd,
                        controller.ticketNoAtEnd
                    );
                }
            });
        }

        @Override public void onProductsUpdated(List<ProductUiItem> products, int activeIndex0) {}

        @Override public void onLog(String message) { scheduleLogRefresh(); }

        @Override
        public void onError(String context, Throwable error) {
            LogBus.api(node, "[ERR][" + context + "] " + (error != null ? error.getMessage() : ""));
            scheduleLogRefresh();
        }

        @Override
        public void onLiveQty(double net, double gross) {
            ui.post(() -> {
                if (!isAdded() || getView() == null) return;
                int d = lastDigits;
                try { if (controller != null) d = controller.getDisplayDigits(); } catch (Exception ignored) {}
                if (d < 0) d = 3;
                if (d > 6) d = 6;
                lastDigits = d;
                int show = Math.min(6, Math.max(0, d));
                String fmt = "%." + show + "f";
                if (txtQtyNet != null) txtQtyNet.setText("NET: " + String.format(Locale.ROOT, fmt, net));
                if (txtQtyGross != null) txtQtyGross.setText("GROSS: " + String.format(Locale.ROOT, fmt, gross));
            });
        }

        @Override
        public void onLiveStatus(String liveText) {
            ui.post(() -> {
                if (!isAdded() || getView() == null) return;
                lastLiveText = liveText;
                if (txtLive != null) txtLive.setText(liveText);
                ensureSerialVisibleThrottled();
                refreshDelCodeFromTickSnapshotThrottled();
                updateButtons(controller != null ? controller.getState() : null);
                scheduleLogRefresh();
            });
        }

        @Override
        public void onTicketInfo(String ticketNo, String deliveryUid) {
            ui.post(() -> {
                if (!isAdded() || getView() == null) return;
                if (txtTicketNo != null) txtTicketNo.setText("Ticket Number : " + (ticketNo == null ? "—" : ticketNo));
                if (txtDeliveryUid != null) txtDeliveryUid.setText("Delivery UID : " + (deliveryUid == null ? "—" : deliveryUid));
                ensureSerialVisibleThrottled();
                refreshDelCodeFromTickSnapshotThrottled();
                updateButtons(controller != null ? controller.getState() : null);
            });
        }
    };

    private final LogBus.Listener logListener = e -> {
        if (e == null) return;
        if (e.node != node) return;
        scheduleLogRefresh();
    };

    private final BroadcastReceiver usbStateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String a = intent.getAction();
            if (a == null) return;
            if (UsbReceiver.ACTION_USB_READY.equals(a)) {
                attemptAttachIfPossible(false);
            } else if (UsbReceiver.ACTION_USB_DETACHED.equals(a)) {
                try {
                    if (tabTransportKey != null && tabTransportKey.toUpperCase(Locale.ROOT).startsWith("BT:")) {
                        LogBus.api(node, "USB detached ignored (TAB sur " + tabTransportKey + ")");
                        return;
                    }
                } catch (Exception ignored) {}
                detachUiListenerSafe();
                controller = null;
                starting = false;
                ticketPendingFlag = -1;
                ui.post(() -> {
                    if (!isAdded() || getView() == null) return;
                    if (txtSerialId != null) txtSerialId.setText("#Série : " + ((serialFromArgs != null && !serialFromArgs.trim().isEmpty()) ? serialFromArgs : "—"));
                    if (txtTicketPending != null) txtTicketPending.setText("Ticket pending : —");
                    if (txtTicketNo != null) txtTicketNo.setText("Ticket Number : —");
                    if (txtDeliveryUid != null) txtDeliveryUid.setText("Delivery UID : —");
                    if (txtLive != null) txtLive.setText("LIVE: (en attente)");
                    if (txtQtyNet != null) txtQtyNet.setText("NET: 0.0");
                    if (txtQtyGross != null) txtQtyGross.setText("GROSS: 0.0");
                    updateButtons(null);
                });
            }
        }
    };

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        Bundle a = getArguments();
        if (a != null) {
            node = a.getInt(ARG_NODE, 250);
            from = a.getInt(ARG_FROM, 255);
            serialFromArgs = a.getString(ARG_SERIAL, null);
            transportFromArgs = a.getString(ARG_TRANSPORT, null);
            if (transportFromArgs != null && !transportFromArgs.trim().isEmpty()) {
                tabTransportKey = transportFromArgs.trim();
                String up = tabTransportKey.toUpperCase(Locale.ROOT);
                tabMediaShort = up.startsWith("BT:") ? "BT" : (up.startsWith("USB") ? "USB" : tabMediaShort);
            }
        }
        usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    @Override
    public void onStart() {
        super.onStart();
        LogBus.addListener(logListener);
        IntentFilter f = new IntentFilter();
        f.addAction(UsbReceiver.ACTION_USB_READY);
        f.addAction(UsbReceiver.ACTION_USB_DETACHED);
        // ✅ Android 14+ (API 34+) exige RECEIVER_NOT_EXPORTED ou RECEIVER_EXPORTED
        // Ce receiver écoute des broadcasts internes à l'APK — RECEIVER_NOT_EXPORTED
        // Android 9-13 : registerReceiver(receiver, filter) — sans flag
        // Android 14+  : registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            requireContext().registerReceiver(usbStateReceiver, f,
                Context.RECEIVER_NOT_EXPORTED);
        } else {
            requireContext().registerReceiver(usbStateReceiver, f);
        }
    }

    @Override
    public void onStop() {
        arreterPollPostLivraison(); // ✅ arrêt poll fuite si tab quitte
        detachUiListenerSafe();
        LogBus.removeListener(logListener);
        try { requireContext().unregisterReceiver(usbStateReceiver); } catch (Exception ignored) {}
        super.onStop();
    }

    @Override
    public void onResume() {
        super.onResume();
        // ✅ Toujours réattacher le uiListener si nécessaire au retour du tab
        // onStop() détache le listener — onResume() doit le réattacher
        // Android 9-15 : même comportement — le listener est un callback direct,
        //                pas un broadcast, il doit être réattaché à chaque onResume()
        if (!attemptedAutoAttachOnce) {
            attemptedAutoAttachOnce = true;
            ui.post(() -> attemptAttachIfPossible(true));
        } else {
            // Deuxième visite ou retour d'arrière-plan — réattacher si listener absent
            ui.post(() -> {
                if (!uiListenerAttached && controller != null) {
                    attachUiListenerIfNeeded();
                }
                attemptAttachIfPossible(false);
            });
        }
        // ✅ Toujours vérifier si livraison PENDING à afficher quand le tab devient visible
        ui.postDelayed(() -> checkPendingDeliveryForThisRegister(), 800);

        // ✅ Si controller existe et io mort → diagnostic au moment où on arrive dans le tab
        ui.postDelayed(() -> {
            if (!isAdded() || getView() == null) return;
        }, 500);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        try { ui.removeCallbacksAndMessages(null); } catch (Exception ignored) {}
        try { bg.shutdownNow(); } catch (Exception ignored) {}
        controller = null;
        attemptedAutoAttachOnce = false;  // ← ajouter cette ligne
    }
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_register_tab, container, false);
        bindUi(v);
        initUi();
        wireUi();
        installLogScrollInterceptionFix();
        return v;
    }

    private void bindUi(View v) {
        regRootScroll = v.findViewById(R.id.regRootScroll);
        txtLcrNode = v.findViewById(R.id.txtLcrNode);
        txtFrom = v.findViewById(R.id.txtFrom);
        txtSerialId = v.findViewById(R.id.txtSerialId);
        txtTicketNo = v.findViewById(R.id.txtTicketNo);
        txtTicketPending = v.findViewById(R.id.txtTicketPending);
        spnProduct = v.findViewById(R.id.spnProduct);
        if (spnProduct != null) {
            String[] items = new String[16];
            for (int i = 0; i < 16; i++) items[i] = String.valueOf(i + 1);
            android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<>(
                requireContext(), android.R.layout.simple_dropdown_item_1line, items);
            spnProduct.setAdapter(ad);
            spnProduct.setThreshold(0);
            spnProduct.setOnClickListener(v2 -> spnProduct.showDropDown());
        }
        edtPreset = v.findViewById(R.id.edtPreset);
        btnConnect = v.findViewById(R.id.btnConnectTab);
        btnA = v.findViewById(R.id.btnA);
        btnB = v.findViewById(R.id.btnB);
        btnC = v.findViewById(R.id.btnC);
        btnContinue = v.findViewById(R.id.btnContinue);
        btnFinish = v.findViewById(R.id.btnFinish);
        txtLive = v.findViewById(R.id.txtLive);
        txtQtyNet = v.findViewById(R.id.txtQtyNet);
        txtQtyGross = v.findViewById(R.id.txtQtyGross);
        txtDeliveryUid = v.findViewById(R.id.txtDeliveryUid);
        btnReprintTicket = v.findViewById(R.id.btnReprintTicket);
        btnRetourWO      = v.findViewById(R.id.btnRetourWO);
        btnCustomPrint   = v.findViewById(R.id.btnCustomPrint);
        btnAnnuler       = v.findViewById(R.id.btnAnnuler);
        btnScanProducts  = v.findViewById(R.id.btnScanProducts);
        cbShowLog = v.findViewById(R.id.cbShowLog);
        logPanel = v.findViewById(R.id.logPanel);
        txtLog = v.findViewById(R.id.txtLog);
        logScroll = v.findViewById(R.id.logScroll);
        btnClearLog = v.findViewById(R.id.btnClearLog);
        btnCopyLog = v.findViewById(R.id.btnCopyLog);
        btnScrollDown = v.findViewById(R.id.btnScrollDown);
        cbTxRx = v.findViewById(R.id.cbTxRx);
        cbLogTs = v.findViewById(R.id.cbLogTs);
    }

    private void initUi() {
        if (txtLcrNode != null) txtLcrNode.setText(String.format(Locale.ROOT, "LCR Node : %d", node));
        if (txtFrom != null) txtFrom.setText(String.format(Locale.ROOT, "From : %d", from));
        if (txtSerialId != null) txtSerialId.setText("#Série : " + ((serialFromArgs != null && !serialFromArgs.trim().isEmpty()) ? serialFromArgs : "—"));
        if (txtTicketNo != null) txtTicketNo.setText("Ticket Number : —");
        if (txtTicketPending != null) txtTicketPending.setText("Ticket pending : —");
        if (txtDeliveryUid != null) txtDeliveryUid.setText("Delivery UID : —");
        ticketPendingFlag = -1;
        lastDigits = 3;
        if (txtLive != null) txtLive.setText("LIVE: (en attente)");
        if (txtQtyNet != null) txtQtyNet.setText("NET: 0.0");
        if (txtQtyGross != null) txtQtyGross.setText("GROSS: 0.0");
        if (edtPreset != null) edtPreset.setText("50");
        if (spnProduct != null) spnProduct.setText("1", false);
        if (cbShowLog != null) cbShowLog.setChecked(false);
        if (logPanel != null) logPanel.setVisibility(View.GONE);
        logViewSinceMs = 0L;
        if (cbTxRx != null) cbTxRx.setChecked(LogBus.SHOW_IO);
        if (cbLogTs != null) cbLogTs.setChecked(LogBus.SHOW_TS);
        updateButtons(null);
    }

    public void prefillFromDeepLink(String woNum, String produit, String preset) {
        prefillFromDeepLink(woNum, "", produit, preset);
    }

    public void prefillFromDeepLink(String woNum, String woIdGuid, String produit, String preset) {
        if (woNum != null && !woNum.isEmpty()) currentWoNum = woNum;
        if (woIdGuid != null && !woIdGuid.isEmpty()) currentWoIdGuid = woIdGuid;
        if (edtPreset != null && preset != null && !preset.isEmpty())
            edtPreset.setText(preset);
        if (spnProduct != null && produit != null && !produit.isEmpty())
            spnProduct.setText(produit, false);
        if (txtDeliveryUid != null && woNum != null && !woNum.isEmpty())
            txtDeliveryUid.setText("Delivery UID : " + woNum);
    }

    private void notifyDeliveryEndedToMainActivity() {
        // ✅ Source primaire — champs stables du fragment, jamais effacés
        String woNum    = currentWoNum;
        String woIdGuid = currentWoIdGuid;
        // Fallback — ActiveDeliveryStore si les champs stables sont vides
        // (ex: tout premier appel avant tout deep link/prefill)
        if (woNum.isEmpty()) {
            try {
                com.pa.lcr.lcp.storage.ActiveDeliveryStore ads =
                    new com.pa.lcr.lcp.storage.ActiveDeliveryStore(requireContext());
                com.pa.lcr.lcp.storage.ActiveDeliveryStore.ActiveDelivery ad = ads.load();
                if (ad != null) {
                    if (ad.woNum    != null) woNum    = ad.woNum;
                    if (ad.woIdGuid != null) woIdGuid = ad.woIdGuid;
                }
            } catch (Exception ignored) {}
        }
        notifyDeliveryEndedToMainActivity(woNum, woIdGuid);
    }

    /**
     * Version explicite — utilisée par le poll de fin de livraison du bouton C,
     * où woNum/woIdGuid ont été capturés au DÉBUT de la livraison (figés) plutôt
     * que relus depuis ActiveDeliveryStore à la fin, qui peut avoir changé entre
     * temps si un autre bouton C a été cliqué pendant le poll (plusieurs minutes).
     */
    private void notifyDeliveryEndedToMainActivity(String woNumIn, String woIdGuidIn) {
        try {
            if (!(getActivity() instanceof MainActivity)) return;
            MainActivity main = (MainActivity) getActivity();

            String ticketNo = "";
            double net      = 0.0;
            double gross    = 0.0;
            String woNum    = (woNumIn    != null) ? woNumIn    : "";
            String woIdGuid = (woIdGuidIn != null) ? woIdGuidIn : "";

            try {
                if (txtTicketNo != null)
                    ticketNo = txtTicketNo.getText().toString()
                                   .replace("Ticket Number : ", "").trim();
                if (txtQtyNet != null)
                    net = Double.parseDouble(
                        txtQtyNet.getText().toString()
                                 .replace("NET: ", "").trim());
                if (txtQtyGross != null)
                    gross = Double.parseDouble(
                        txtQtyGross.getText().toString()
                                   .replace("GROSS: ", "").trim());
            } catch (Exception ignored) {}

            org.json.JSONObject extra = new org.json.JSONObject();
            try {
                if (controller != null) {
                    com.pa.lcr.lcp.ApiResult snap = controller.api_tickSnapshot();
                    if (snap != null && snap.data != null) {
                        extra = snap.data;
                    }
                }
            } catch (Exception ignored) {}

            try {
                extra.put("ticketNo", ticketNo);
                extra.put("netL",     net);
                extra.put("grossL",   gross);
            } catch (Exception ignored) {}

            main.onDeliveryEnded(woNum, woIdGuid, extra.toString());

        } catch (Exception ignored) {}
    }

    private void runStatusBLikeButton(String reason) {
        try {
            if (controller == null) return;
            if (!verifierIoAvantAction("STATUS_B")) return;
            try {
                if (tabTransportKey != null)
                    MediaTransportManager.get(requireContext())
                        .activateExclusive(tabTransportKey, reason != null ? reason : "STATUS_B");
            } catch (Exception ignored) {}
            try {
                controller.requestStatus();
            } catch (Exception e) {
                LogBus.api(node, "Status(B) ERR: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                surErreurConnexion(e, "STATUS_B");
                return;
            }
            ui.postDelayed(() -> {

                  try { if (controller != null) controller.requestLiveSample(); } catch (Exception ignored) {}

                try { if (controller != null) controller.requestLiveSample(); } catch (Exception ignored) {}
            }, 200);
        } catch (Exception ignored) {}
    }

    private void wireUi() {
        if (cbShowLog != null) {
            cbShowLog.setOnCheckedChangeListener((b, checked) -> {
                if (logPanel != null) logPanel.setVisibility(checked ? View.VISIBLE : View.GONE);
                LogBus.ui(node, ts("Afficher log: " + (checked ? "ON" : "OFF")));
                if (checked) { installLogScrollInterceptionFix(); scheduleLogRefresh(); }
            });
        }
        if (btnScrollDown != null && logScroll != null) {
            btnScrollDown.setOnClickListener(v -> scrollLogToBottomOnly());
        }
        if (btnClearLog != null) {
            btnClearLog.setOnClickListener(v -> {
                logViewSinceMs = System.currentTimeMillis();
                if (txtLog != null) txtLog.setText("");
                LogBus.ui(node, ts("Clear log (vue locale)"));
            });
        }
        if (btnCopyLog != null) {
            btnCopyLog.setOnClickListener(v -> {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("log", txtLog != null ? txtLog.getText() : ""));
                LogBus.ui(node, ts("Log copié"));
            });
        }
        if (cbLogTs != null) {
            cbLogTs.setOnCheckedChangeListener((b, checked) -> {
                logTsEnabled = checked;
                LogBus.SHOW_TS = checked;
                if (controller != null) controller.setLogTimestampsEnabled(checked);
                LogBus.ui(node, ts("Timestamps: " + (checked ? "ON" : "OFF")));
                scheduleLogRefresh();
            });
        }
        if (cbTxRx != null) {
            cbTxRx.setOnCheckedChangeListener((b, checked) -> {
                LogBus.SHOW_IO = checked;
                if (controller != null) controller.setTxRxLoggingEnabled(checked);
                LogBus.ui(node, ts("TX/RX: " + (checked ? "ON" : "OFF")));
                scheduleLogRefresh();
            });
        }
        if (btnConnect != null) btnConnect.setOnClickListener(v -> reconnectThisRegister(true));
        if (btnA != null) btnA.setOnClickListener(v -> {
            if (controller == null) return;
            // ✅ Confirmation si RUNNING_FLOWING
            if (controller.getState() == DeliveryState.RUNNING_FLOWING) {
                new android.app.AlertDialog.Builder(requireContext())
                    .setTitle("⚠️ Resolve pendant livraison")
                    .setMessage("Une livraison est en cours.\nÊtes-vous sûr de vouloir résoudre l'état du registre ?")
                    .setPositiveButton("Confirmer", (d, w) -> doResolve())
                    .setNegativeButton("Annuler", null)
                    .show();
                return;
            }
            doResolve();
        });

        // ✅ Bouton Annuler livraison
        if (btnAnnuler != null) {
            btnAnnuler.setOnClickListener(v -> {
                new android.app.AlertDialog.Builder(requireContext())
                    .setTitle("Confirmer l'annulation")
                    .setMessage("Aucun volume livré. La livraison sera annulée et le registre réinitialisé.")
                    .setPositiveButton("Annuler la livraison", (d, w) -> annulerLivraison())
                    .setNegativeButton("Continuer", null)
                    .show();
            });
        }
        if (btnB != null) btnB.setOnClickListener(v -> {
            if (controller == null) { reconnectThisRegister(true); return; }
            runStatusBLikeButton("STATUS_B");
        });
        if (btnC != null) {
            btnC.setOnClickListener(v -> {
                if (controller == null) return;

                // ✅ Vérifier si ce WO a déjà une livraison complétée (hors annulation)
                try {
                    com.pa.lcr.lcp.storage.ActiveDeliveryStore ads4 =
                        new com.pa.lcr.lcp.storage.ActiveDeliveryStore(requireContext());
                    com.pa.lcr.lcp.storage.ActiveDeliveryStore.ActiveDelivery ad4 = ads4.load();
                    String curWoNum = (ad4 != null && ad4.woNum != null) ? ad4.woNum : "";
                    if (!curWoNum.isEmpty()) {
                        com.pa.lcr.lcp.storage.LcrDeliveryStatusDb statusDb =
                            new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(requireContext());
                        com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow existing =
                            statusDb.getLatestForWo(curWoNum);
                        if (existing != null && existing.type != null
                                && !"ANNULATION".equals(existing.type)) {
                            new android.app.AlertDialog.Builder(requireContext())
                                .setTitle("⚠️ Bon déjà complété")
                                .setMessage("Le bon " + curWoNum + " a déjà été livré"
                                    + " (ticket #" + existing.ticketNo
                                    + ", " + existing.netL + "L net).\n\n"
                                    + "Voulez-vous créer une nouvelle livraison sur ce même bon ?")
                                .setPositiveButton("Continuer", (d, w) -> startNewDeliveryC())
                                .setNegativeButton("Annuler", null)
                                .show();
                            return;
                        }
                    }
                } catch (Exception ignored) {}

                startNewDeliveryC();
            });
        }
        if (btnContinue != null) btnContinue.setOnClickListener(v -> {
            if (controller == null) return;
            if (!verifierIoAvantAction("CONTINUE")) return;
            if (controller.getState() != DeliveryState.RUNNING_PAUSED) {
                try { controller.requestLiveSample(); } catch (Exception ignored) {}
                try { Toast.makeText(requireContext(), "Attendre confirmation FLOW OFF", Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {}
                LogBus.ui(node, ts("Continue ignoré: FLOW OFF pas encore confirmé"));
                return;
            }
            controller.resumeIfPaused();
        });
        if (btnFinish != null) btnFinish.setOnClickListener(v -> {
            if (controller == null) return;
            if (!verifierIoAvantAction("FINISH")) return;
            boolean stableOff2 = false;
            try { stableOff2 = controller.isFlowOffStable(); } catch (Exception ignored) {}
            if (!stableOff2) {
                try { controller.requestLiveSample(); } catch (Exception ignored) {}
                try { Toast.makeText(requireContext(), "FLOW OFF en confirmation...", Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {}
                return;
            }
            controller.endDelivery();
            ui.postDelayed(() -> {
                try { if (controller != null) controller.requestStatus(); } catch (Exception ignored) {}
                try { validateHeaderAsync(); } catch (Exception ignored) {}
                try { if (controller != null) controller.requestLiveSample(); } catch (Exception ignored) {}
                refreshDelCodeFromTickSnapshotThrottled();
                updateButtons(controller != null ? controller.getState() : null);
            }, 1500);
        });

        // ✅ REPRINT: câblage du bouton Reprint (last ticket)
        if (btnReprintTicket != null) {
            btnReprintTicket.setOnClickListener(v -> {
                DeliveryController c = controller;
                if (c == null) return;
                bg.execute(() -> {
                    try {
                        if (tabTransportKey != null) {
                            MediaTransportManager.get(requireContext())
                                    .activateExclusive(tabTransportKey, "REPRINT");
                        }

                        // ✅ Lire ticket_no AVANT reprint (field #23 s'incrémente après)
                        String ticketNoBefore = "";
                        try {
                            ApiResult snap = c.api_tickSnapshot();
                            if (snap != null && snap.data != null) {
                                ticketNoBefore = snap.data.optString("ticket_no", "");
                                org.json.JSONObject result = snap.data.optJSONObject("result");
                                if (result != null && ticketNoBefore.isEmpty())
                                    ticketNoBefore = result.optString("ticket_no", "");
                            }
                        } catch (Exception ignored) {}

                        // ✅ Envoyer CMD_PRINT_LAST_TICKET (0x06)
                        ApiResult r = c.api_ticketReprintCurrent();
                        LogBus.api(node, "[REPRINT] " + (r != null ? r.msg : "null"));

                        // ✅ Lire ticket_no APRÈS reprint
                        String ticketNoAfter = "";
                        double netL = 0, grossL = 0;
                        String woNum = "", woIdGuid = "";
                        try {
                            Thread.sleep(500); // attendre que le registre incrémente
                            ApiResult snap2 = c.api_tickSnapshot();
                            if (snap2 != null && snap2.data != null) {
                                org.json.JSONObject result = snap2.data.optJSONObject("result");
                                if (result != null) {
                                    ticketNoAfter = result.optString("ticket_no", "");
                                    netL   = result.optDouble("fs_net_l",  0);
                                    grossL = result.optDouble("fs_gross_l",0);
                                }
                            }
                        } catch (Exception ignored) {}

                        // Récupérer woNum + woIdGuid depuis lastResultJson
                        try {
                            String last = com.pa.lcrdemo.DeepLinkHandler.lastResultJson;
                            if (last != null) {
                                org.json.JSONObject j = new org.json.JSONObject(last);
                                woNum    = j.optString("wonum", "");
                                woIdGuid = j.optString("woid",  "");
                            }
                        } catch (Exception ignored) {}

                        // ✅ Créer nouvelle ligne REPRINT dans LcrDeliveryStatusDb
                        if (!ticketNoAfter.isEmpty() && !ticketNoAfter.equals(ticketNoBefore)) {
                            android.content.ContentValues cv = new android.content.ContentValues();
                            cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_WO_NUM,       woNum);
                            cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_WO_ID_GUID,   woIdGuid);
                            cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_TICKET_NO,    ticketNoAfter);
                            cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_TICKET_NO_REF,ticketNoBefore);
                            cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_NET_L,        netL);
                            cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_GROSS_L,      grossL);
                            cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_TYPE,
                                com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.TYPE_REPRINT);
                            cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SOURCE,       "REGISTRE");
                            cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_STOP_TYPE,    "LIVRAISON");
                            cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SYNC_STATUS,
                                com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.SYNC_PENDING);

                            // REPRINT = nouvelle ligne (pas UPSERT) — forcer INSERT
                            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb lcrDb =
                                new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(requireContext());
                            lcrDb.insertDelivery(cv);
                            android.util.Log.i("REPRINT", "Nouveau ticket tracé: "
                                + ticketNoBefore + " → " + ticketNoAfter + " wo=" + woNum);
                        }

                    } catch (Exception e) {
                        LogBus.api(node, "[REPRINT] ERR: " + safeMsg(e));
                        surErreurConnexion(e, "REPRINT");
                    }
                });
            });
        }

        // ✅ RETOUR WO: câblage du bouton Retour au Work Order (Bloc 5)
        if (btnRetourWO != null) {
            btnRetourWO.setOnClickListener(v -> retournerAuWorkOrder());
        }

        // ✅ Custom print — impression ligne par ligne via opPrintText
        if (btnCustomPrint != null) {
            btnCustomPrint.setOnClickListener(v -> lancerImpressionCustom());
        }
        if (btnScanProducts != null) {
            btnScanProducts.setOnClickListener(v -> lancerScanProduits());
        }
    }

    public void onTabMediaStatusChanged(boolean ready, String mediaShort) {
        tabMediaReady = ready;
        tabMediaShort = (mediaShort == null || mediaShort.trim().isEmpty()) ? "—" : mediaShort.trim();
        ui.post(() -> {
            if (!isAdded() || getView() == null) return;
            if (!tabMediaReady) {
                // ✅ Message contextuel selon l'état de la livraison
                String liveMsg;
                if (controller != null) {
                    com.pa.lcr.lcp.DeliveryState st = controller.getState();
                    if (st == com.pa.lcr.lcp.DeliveryState.RUNNING_FLOWING) {
                        liveMsg = "⚠️ " + tabMediaShort + " COUPÉ pendant livraison — le registre continue physiquement — reconnectez le BT";
                    } else if (st == com.pa.lcr.lcp.DeliveryState.RUNNING_PAUSED) {
                        liveMsg = "⚠️ " + tabMediaShort + " COUPÉ — livraison en pause — reconnectez le BT";
                    } else {
                        liveMsg = "LIVE: " + tabMediaShort + "(OFF) — reconnect requis";
                    }
                } else {
                    liveMsg = "LIVE: " + tabMediaShort + "(OFF) — reconnect requis";
                }
                if (txtLive != null) txtLive.setText(liveMsg);
                pendingReconnect = true;
                updateButtons(controller != null ? controller.getState() : null);
                return;
            }
            if (pendingReconnect) { pendingReconnect = false; reconnectThisRegister(false); }
            // ✅ Toujours vérifier PENDING quand le média devient disponible
            ui.postDelayed(() -> checkPendingDeliveryForThisRegister(), 1500);
        });
    }

    /** Appelé depuis MainActivity dialog long press. */
    public void reconnectFromDialog() {
        ui.post(() -> reconnectThisRegister(true));
    }

    private void reconnectThisRegister(boolean userInitiated) {
        if (!tabMediaReady) {
            pendingReconnect = true;
            if (userInitiated) {
                try { Toast.makeText(requireContext(), tabMediaShort + "(OFF) — en attente…", Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {}
            }
            return;
        }
        // ✅ Ne pas nullifier le controller si livraison active — le tick continue
        // Réattacher seulement le listener UI
        if (controller != null) {
            com.pa.lcr.lcp.DeliveryState st = controller.getState();
            if (st == com.pa.lcr.lcp.DeliveryState.RUNNING_FLOWING
                    || st == com.pa.lcr.lcp.DeliveryState.RUNNING_PAUSED
                    || st == com.pa.lcr.lcp.DeliveryState.ENDING) {
                android.util.Log.i("RegisterTabFragment",
                    "reconnect: livraison active (" + st + ") — réattach UI seulement, controller préservé");
                connectThisRegister(userInitiated);
                return;
            }
        }
        try { detachUiListenerSafe(); } catch (Exception ignored) {}
        controller = null;
        starting = false;
        ticketPendingFlag = -1;
        connectThisRegister(userInitiated);
    }

    private void attemptAttachIfPossible(boolean verboseLog) {
        if (!tabMediaReady && !pendingReconnect) return;
        if (uiListenerAttached && controller != null) { syncUiFromController(); return; }
        connectThisRegister(false);
    }


    private void connectThisRegister(boolean userInitiated) {
        // ✅ Si le controller existant est mort (shutdown après erreur BT "Error writing"
        // par exemple) — forcer la recréation au lieu de continuer à l'utiliser
        if (controller != null && controller.isStopped()) {
            LogBus.api(node, "Controller mort détecté — recréation forcée");
            controller = null;
        }
        // Si controller existe et transport prêt — pas besoin de recréer
        if (controller != null && tabMediaReady) {
            syncUiFromController();
            validateHeaderAsync();
            ui.postDelayed(() -> runStatusBLikeButton("TAB_REACTIVATED"), 250);
            return;
        }
        RegisterSessionManager sm = RegisterSessionManager.get(requireContext());
        DeliveryController dc = null;

        if (tabTransportKey != null && !tabTransportKey.trim().isEmpty()) {
            final String tkPinned = tabTransportKey.trim();
            try { MediaTransportManager.get(requireContext()).activateExclusive(tkPinned, "TAB_CONNECT"); } catch (Exception ignored) {}
            TransportIo io = null;
            try { io = MediaTransportManager.get(requireContext()).getByKey(tkPinned); } catch (Exception ignored) {}
            if (io == null || !io.isOpen()) {
                tabMediaReady = false;
                pendingReconnect = true;
                String msg = tabMediaShort + "(OFF) — reconnect requis";
                LogBus.api(node, msg);
                reportMediaOffToApi("CONNECT_CLICK", msg);
                ui.post(() -> {
                    if (!isAdded() || getView() == null) return;
                    if (txtLive != null) txtLive.setText("LIVE: " + tabMediaShort + "(OFF) — reconnect requis");
                    updateButtons(null);
                });
                return;
            }
            // ✅ Si le controller du session manager est aussi mort — le retirer avant getOrCreate
            try {
                if (controller != null && controller.isStopped()) {
                    android.util.Log.i("RegisterTabFragment", "Controller mort — recréation forcée");
                    controller = null;
                }
            } catch (Exception ignored) {}
            dc = sm.getOrCreate(tkPinned, node, from, io);
        }
        if (dc == null && (tabTransportKey == null || tabTransportKey.trim().isEmpty())) {
            dc = sm.resolveOrCreateForNode(node, from);
        }
        if (dc == null) {
            LogBus.api(node, "Aucun média prêt / registre introuvable pour ce node");
            if (userInitiated) {
                try { Toast.makeText(requireContext(), "Aucun média prêt (USB/BT)", Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {}
            }
            return;
        }

        controller = dc;
        try {
            String tk = sm.findTransportKeyForController(controller);
            // Ne mettre à jour tabTransportKey que si cohérent avec transportFromArgs
            if (tk != null && !tk.trim().isEmpty()) {
                if (transportFromArgs == null || transportFromArgs.trim().isEmpty()) {
                    tabTransportKey = tk.trim();
                } else if (tk.trim().equalsIgnoreCase(transportFromArgs.trim())) {
                    tabTransportKey = tk.trim();
                }
                // Sinon garder tabTransportKey original — ne pas écraser avec mauvais transport
            }
        } catch (Exception ignored) {}

        try {
            if (tabTransportKey != null) MediaTransportManager.get(requireContext()).activateExclusive(tabTransportKey, "TAB_CONNECT_FINAL");
        } catch (Exception ignored) {}
        if (!uiListenerAttached) {
            try {
                if (tabTransportKey != null) sm.attachUiListener(tabTransportKey, node, uiListener);
                else sm.attachUiListener(node, uiListener);
            } catch (Exception ignored) {}
            uiListenerAttached = true;
        }
        if (cbTxRx != null) controller.setTxRxLoggingEnabled(cbTxRx.isChecked());
        if (cbLogTs != null) controller.setLogTimestampsEnabled(cbLogTs.isChecked());
        syncUiFromController();
        validateHeaderAsync();
        ui.postDelayed(() -> runStatusBLikeButton("AUTO_AFTER_TAB_CREATE"), 250);
        if (userInitiated) LogBus.api(node, "Connect TAB: 1 - UI attached");
        scheduleLogRefresh();
    }

    // ─────────────────────────────────────────────────────────────────
    // Poll post-livraison — détection fuite vanne (seuil 0.5L)
    // ─────────────────────────────────────────────────────────────────

    private static final double POST_DELIVERY_LEAK_THRESHOLD_L = 0.5;
    private static final long   POST_DELIVERY_POLL_INTERVAL_MS = 5_000; // poll toutes les 5s
    // Pas de durée max — le poll reste actif tant que CONNECTED + livraison terminée
    // Il s'arrête uniquement si : nouvelle livraison / tab fermé / registre déconnecté
    private volatile boolean    postDeliveryPollActive = false;

    private void demarrerPollPostLivraison(double netRef, double grossRef, String ticketNo) {
        if (postDeliveryPollActive) return;
        postDeliveryPollActive = true;
        LogBus.api(node, "[POST-LIVRAISON] Poll fuite demarré — netRef=" + netRef
            + "L ticket=" + ticketNo + " seuil=" + POST_DELIVERY_LEAK_THRESHOLD_L + "L"
            + " — actif jusqu'a nouvelle livraison ou fermeture du tab");

        Runnable pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (!postDeliveryPollActive || !isAdded() || getView() == null) return;
                if (controller == null) { postDeliveryPollActive = false; return; }

                // Arrêt si une nouvelle livraison a démarré
                // NE PAS arrêter sur RUNNING_PAUSED ou FLOWING — c'est une erreur
                // Le poll ne doit pas interférer avec un cycle actif
                DeliveryState st = controller.getState();
                if (st == DeliveryState.RUNNING_FLOWING
                        || st == DeliveryState.RUNNING_PAUSED
                        || st == DeliveryState.PRESTART
                        || st == DeliveryState.ENDING) {
                    postDeliveryPollActive = false;
                    LogBus.api(node, "[POST-LIVRAISON] Arrêt — nouvelle livraison active");
                    return;
                }

                // ✅ Forcer une lecture active des compteurs du registre
                // En état CONNECTED, liveTickFuture est arrêté dans DeliveryController.
                // requestLiveSample() force une lecture LCP des compteurs net/gross
                // pour détecter toute augmentation même longtemps après la livraison.
                // Compatible Android 9-15 : appel depuis le thread UI via bg.execute
                bg.execute(() -> {
                    try {
                        if (controller != null) controller.requestLiveSample();
                    } catch (Exception ignored) {}
                });

                // Attendre que requestLiveSample() ait mis à jour lastTick (200ms)
                // puis lire le net courant
                ui.postDelayed(() -> {
                    if (!postDeliveryPollActive || !isAdded() || getView() == null) return;

                    double netCourant = -1.0;
                    double grossCourant = -1.0;
                    try {
                        netCourant   = controller.getLastNet();
                        grossCourant = controller.getLastGross();
                    } catch (Exception ignored) {}

                    if (netCourant < 0) {
                        // Pas de tick disponible — replanifier
                        ui.postDelayed(this, POST_DELIVERY_POLL_INTERVAL_MS);
                        return;
                    }

                    double delta = netCourant - netRef;
                    LogBus.api(node, "[POST-LIVRAISON] net=" + netCourant
                        + "L ref=" + netRef + "L delta=" + String.format(java.util.Locale.ROOT, "%.3f", delta) + "L");

                    if (delta >= POST_DELIVERY_LEAK_THRESHOLD_L) {
                        // ⚠ Fuite détectée — arrêt poll + alerte
                        postDeliveryPollActive = false;
                        afficherAlerteVanneOuverte(netRef, netCourant, grossRef, grossCourant,
                            ticketNo, delta);
                        return;
                    }

                    // Pas de fuite — replanifier (pas de durée max)
                    ui.postDelayed(this, POST_DELIVERY_POLL_INTERVAL_MS);
                }, 200);
            }
        };

        ui.postDelayed(pollRunnable, POST_DELIVERY_POLL_INTERVAL_MS);
    }

    private void arreterPollPostLivraison() {
        postDeliveryPollActive = false;
    }

    private void afficherAlerteVanneOuverte(double netRef, double netCourant,
            double grossRef, double grossCourant, String ticketNo, double delta) {
        if (!isAdded() || getView() == null) return;

        // ✅ Message adapté selon le type de registre via polymorphisme LcpLink/Lc3Link
        // LCR-II (LcpLink) : solénoïde défaillant — vérifier circuit hydraulique
        // LC3   (Lc3Link)  : fermeture manuelle requise par le chauffeur
        String msg;
        try {
            com.pa.lcr.lcp.LcpLink link = null;
            if (controller != null) {
                try { link = (com.pa.lcr.lcp.LcpLink) controller.getLink(); }
                catch (Exception ignored) {}
            }
            if (link != null) {
                msg = link.getLeakAlertMessage(ticketNo, netRef, netCourant, delta);
            } else {
                msg = "\u26a0 Volume d\u00e9tect\u00e9 apr\u00e8s arr\u00eat \u2014 ticket " + ticketNo
                    + " \u2014 delta " + String.format(Locale.ROOT, "%.3f", delta) + " L";
            }
        } catch (Exception e) {
            msg = "\u26a0 Volume d\u00e9tect\u00e9 apr\u00e8s arr\u00eat \u2014 ticket " + ticketNo
                + " \u2014 delta " + String.format(Locale.ROOT, "%.3f", delta) + " L";
        }

        LogBus.api(node, "[ALERTE-FUITE] ticket=" + ticketNo
            + " delta=" + delta + "L net=" + netCourant + "L");

        new android.app.AlertDialog.Builder(requireContext())
            .setTitle("⚠ Vanne encore ouverte ?")
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton("Terminer avec volumes réels", (d, w) -> {
                // Terminer la livraison avec le net/gross final et imprimer nouveau ticket
                terminerPostLivraisonAvecVolumesReels(netCourant, grossCourant, ticketNo);
            })
            .setNegativeButton("J'ai avisé le répartiteur", (d, w) -> {
                // Rester dans l'APK — log Dataverse de l'incident
                logFuiteVanneDataverse(netRef, netCourant, grossRef, grossCourant,
                    ticketNo, delta);
            })
            .show();
    }

    private void terminerPostLivraisonAvecVolumesReels(double netFinal, double grossFinal,
            String ticketNoOriginal) {
        if (controller == null) return;
        bg.execute(() -> {
            try {
                // ✅ Impression custom avec les volumes réels courants
                // N'utilise PAS api_ticketReprintCurrent() qui imprime l'ancien ticket du registre
                // Utilise le même mécanisme que lancerImpressionCustom() — ligne par ligne via opPrintText
                // Compatible Android 9-15 : appel depuis bg.execute, pas depuis le thread UI
                double netRef = controller.netAtDeliveryEnd;
                double grossRef = controller.grossAtDeliveryEnd;
                double delta = netFinal - netRef;

                // Récupérer les infos du lastResultJson pour le ticket
                String woNum = "", saleNo = "", serialId = "";
                try {
                    String last = com.pa.lcrdemo.DeepLinkHandler.lastResultJson;
                    if (last != null) {
                        org.json.JSONObject j = new org.json.JSONObject(last);
                        woNum = j.optString("wonum", "");
                        org.json.JSONObject payload = j.optJSONObject("payload");
                        if (payload != null) {
                            org.json.JSONObject result = payload.optJSONObject("result");
                            if (result != null) {
                                saleNo   = result.optString("sale_no",   "");
                                serialId = result.optString("serial_id", "");
                            }
                        }
                    }
                } catch (Exception ignored) {}

                // Construire le ticket incident fuite — 30 colonnes max (limite imprimante LCR-II)
                int cols = 30;
                String sep   = "=".repeat(cols);
                String dashs = "-".repeat(cols);
                java.util.List<String> lignes = new java.util.ArrayList<>();
                lignes.add(sep);
                lignes.add(center("INCIDENT VANNE", cols));
                lignes.add(sep);
                lignes.add("WO:" + woNum);
                lignes.add("Tkt:" + ticketNoOriginal);
                lignes.add("Ser:" + serialId);
                lignes.add(dashs);
                lignes.add(String.format(java.util.Locale.ROOT, "NET ref:%.3fL", netRef));
                lignes.add(String.format(java.util.Locale.ROOT, "NET fin:%.3fL", netFinal));
                lignes.add(String.format(java.util.Locale.ROOT, "GROSS: %.3fL", grossFinal));
                lignes.add(String.format(java.util.Locale.ROOT, "DELTA:+%.3fL", delta));
                lignes.add(dashs);
                lignes.add("FUITE POST-PRESET");
                lignes.add(new java.text.SimpleDateFormat("dd/MM HH:mm:ss",
                    java.util.Locale.ROOT).format(new java.util.Date()));
                lignes.add(sep);
                lignes.add("");

                // Envoyer ligne par ligne via opPrintText
                if (tabTransportKey != null) {
                    MediaTransportManager.get(requireContext())
                        .activateExclusive(tabTransportKey, "POST_LIVRAISON_PRINT");
                }
                for (String ligne : lignes) {
                    try {
                        controller.api_printTextLine(ligne);
                        Thread.sleep(150);
                    } catch (Exception e) {
                        LogBus.api(node, "[POST-LIVRAISON] ERR ligne impression: " + safeMsg(e));
                    }
                }

                LogBus.api(node, "[POST-LIVRAISON] Ticket incident imprimé — net=" + netFinal
                    + "L ref=" + netRef + "L delta=" + delta + "L");

                // ✅ Enregistrer dans LcrDeliveryStatusDb (filgo_lcr_delivery_status)
                // TYPE_REPRINT réutilisé — ajouter TYPE_FUITE_VANNE si la colonne est créée
                try {
                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_WO_NUM,        woNum);
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_TICKET_NO,     ticketNoOriginal);
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_TICKET_NO_REF, ticketNoOriginal);
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_NET_L,         netFinal);
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_GROSS_L,       grossFinal);
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_DELTA_NET_L,   delta);
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_DELTA_GROSS_L, grossFinal - grossRef);
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_TYPE,
                        com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.TYPE_FUITE_VANNE);
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SOURCE,        "FUITE_VANNE");
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_STOP_TYPE,     "INCIDENT");
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SYNC_STATUS,
                        com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.SYNC_PENDING);
                    com.pa.lcr.lcp.storage.LcrDeliveryStatusDb lcrDb =
                        new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(requireContext());
                    lcrDb.insertDelivery(cv);
                    LogBus.api(node, "[POST-LIVRAISON] Incident enregistré dans LcrDeliveryStatusDb PENDING");
                } catch (Exception e) {
                    LogBus.api(node, "[POST-LIVRAISON] ERR DB: " + safeMsg(e));
                }

                // Toast confirmation sur thread UI
                ui.post(() -> {
                    if (!isAdded() || getView() == null) return;
                    android.widget.Toast.makeText(requireContext(),
                        "Incident enregistré et ticket imprimé",
                        android.widget.Toast.LENGTH_SHORT).show();
                });

            } catch (Exception e) {
                LogBus.api(node, "[POST-LIVRAISON] ERR: " + e.getMessage());
                ui.post(() -> {
                    if (!isAdded() || getView() == null) return;
                    android.widget.Toast.makeText(requireContext(),
                        "Erreur impression incident: " + safeMsg(e),
                        android.widget.Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void logFuiteVanneDataverse(double netRef, double netFinal,
            double grossRef, double grossFinal, String ticketNo, double delta) {
        // Log SQLite pour sync Dataverse future
        LogBus.api(node, "[FUITE-VANNE] ticket=" + ticketNo
            + " netRef=" + netRef + "L netFinal=" + netFinal
            + "L grossRef=" + grossRef + "L grossFinal=" + grossFinal
            + "L delta=" + delta + "L — INCIDENT ENREGISTRÉ");
        // TODO: colonne Dataverse lcr_post_delivery_leak_l + lcr_post_delivery_ticket_no
        // à ajouter quand les colonnes seront créées dans la solution FilgoSonic
    }

    private void attachUiListenerIfNeeded() {
        if (uiListenerAttached) return;
        try {
            RegisterSessionManager sm = RegisterSessionManager.get(requireContext());
            if (tabTransportKey != null) sm.attachUiListener(tabTransportKey, node, uiListener);
            else sm.attachUiListener(node, uiListener);
            uiListenerAttached = true;
            // Sync immédiat de l'état actuel pour rattraper RUNNING_FLOWING manqué
            syncUiFromController();
            LogBus.api(node, "uiListener réattaché au retour du tab");
        } catch (Exception ignored) {}
    }

    private void detachUiListenerSafe() {
        if (!uiListenerAttached) return;
        try {
            RegisterSessionManager sm = RegisterSessionManager.get(requireContext());
            if (tabTransportKey != null) sm.detachUiListener(tabTransportKey, node, uiListener);
            else sm.detachUiListener(node, uiListener);
        } catch (Exception ignored) {}
        uiListenerAttached = false;
    }

    private void syncUiFromController() {
        if (controller == null) return;
        DeliveryState st = controller.getState();
        refreshDelCodeFromTickSnapshotThrottled();
        updateButtons(st);
    }

    private void ensureSerialVisibleThrottled() {
        try {
            if (txtSerialId == null) return;
            if (headerValidatedOnce) return;
            String cur = String.valueOf(txtSerialId.getText());
            boolean missing = (cur.contains("—") || cur.trim().endsWith(":") || cur.trim().endsWith(": —"));
            if (!missing) return;
            long now = System.currentTimeMillis();
            if (now - lastHeaderValidateMs < HEADER_VALIDATE_MIN_MS) return;
            lastHeaderValidateMs = now;
            validateHeaderAsync();
        } catch (Exception ignored) {}
    }

    private void validateHeaderAsync() {
        try { if (bg.isShutdown() || bg.isTerminated()) return; } catch (Exception ignored) {}
        try {
            bg.execute(() -> {
                try {
                    DeliveryController c = controller;
                    if (c == null) return;

                    // Si serial déjà connu depuis args, l'utiliser directement sans naviguer Mode 8
                    String serial = (serialFromArgs != null && !serialFromArgs.trim().isEmpty())
                        ? serialFromArgs.trim() : "";
                    int tp = -1;
                    if (serial.isEmpty()) {
                        ApiResult r = c.api_registerValidate(null, node, null, null, null, false);
                        JSONObject j = r.toJson().optJSONObject("data");
                        if (j == null) return;
                        serial = j.optString("serial_id", "");
                        tp = j.optInt("ticketPending", -1);
                    } else {
                        ApiResult r = c.api_registerValidate(null, node, null, null, null, false);
                        JSONObject j = r != null ? r.toJson().optJSONObject("data") : null;
                        if (j != null) tp = j.optInt("ticketPending", -1);
                    }

                    try { RegisterSessionManager.get(requireContext()).bindExpectedSerial(node, serial); } catch (Exception ignored) {}
                    // int tp = j.optInt("ticketPending", -1);
                    ticketPendingFlag = (tp == 1 ? 1 : (tp == 0 ? 0 : -1));

                    final String fSerial = serial;
                    ui.post(() -> {
                        if (!isAdded() || getView() == null) return;
                        if (txtSerialId != null) {
                            txtSerialId.setText("#Série : " + ((fSerial == null || fSerial.isEmpty()) ? "—" : fSerial));
                            if (fSerial != null && !fSerial.trim().isEmpty()) headerValidatedOnce = true;
                        }

                        if (txtTicketPending != null) {
                            txtTicketPending.setText("Ticket pending : " +
                                    (ticketPendingFlag == 1 ? "OUI" : (ticketPendingFlag == 0 ? "NON" : "—")));
                        }
                        refreshDelCodeFromTickSnapshotThrottled();
                        updateButtons(controller != null ? controller.getState() : null);
                        scheduleLogRefresh();
                        if (fSerial != null && !fSerial.trim().isEmpty())
                            applierDescriptionsProduits(fSerial, node);
                    });
                } catch (Exception e) {
                    LogBus.api(node, "validate header fail: " + safeMsg(e));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    private void updateButtons(DeliveryState state) {
        if (btnConnect == null || btnA == null || btnB == null || btnC == null
                || btnContinue == null || btnFinish == null) return;

        if (controller == null) {
            btnConnect.setEnabled(true);
            btnA.setEnabled(false);
            btnB.setEnabled(false);
            btnC.setEnabled(false);
            btnContinue.setEnabled(false);
            btnFinish.setEnabled(false);
            if (btnReprintTicket != null) btnReprintTicket.setEnabled(false);
            return;
        }

        DeliveryState st = (state != null) ? state : controller.getState();
        boolean connected = (st == DeliveryState.CONNECTED);
        boolean paused    = (st == DeliveryState.RUNNING_PAUSED);
        boolean flowing   = (st == DeliveryState.RUNNING_FLOWING);
        boolean ending    = (st == DeliveryState.ENDING);

        btnConnect.setEnabled(!flowing);
        btnB.setEnabled(true);
        btnA.setEnabled((connected || paused || flowing) && tabMediaReady);

        btnC.setEnabled(connected);

        if (starting) {
            btnContinue.setEnabled(false);
            btnFinish.setEnabled(false);
        } else {
            String lt = lastLiveText;
            boolean flowOffPhase = (lt != null && lt.contains("Flow OFF"));
            boolean enable = (paused || flowOffPhase) && tabMediaReady;
            btnContinue.setEnabled(enable);
            btnFinish.setEnabled(enable);
        }

        // ✅ REPRINT: actif sur CONNECTED, RUNNING_PAUSED, ENDING
        if (btnReprintTicket != null) {
            btnReprintTicket.setEnabled(connected || paused || ending);
        }

        // ✅ Custom print — même visibilité que Reprint
        if (btnCustomPrint != null) {
            btnCustomPrint.setVisibility(connected || paused || ending
                ? android.view.View.VISIBLE : android.view.View.GONE);
            btnCustomPrint.setEnabled(connected || paused || ending);
        }

        // ✅ Bouton Annuler — visible si CONNECTED ou RUNNING_FLOWING
        // Désactivé si volume détecté pendant le flow
        if (btnAnnuler != null) {
            double net   = parseDisplayNet();
            double gross = parseDisplayGross();
            boolean flowStarted = (net > 0.0 || gross > 0.0);
            // ✅ Annuler seulement si une livraison a été démarrée (WO présent)
            boolean hasActiveDelivery = false;
            try {
                String uid = txtDeliveryUid != null ?
                    txtDeliveryUid.getText().toString()
                        .replace("Delivery UID : ", "").trim() : "";
                hasActiveDelivery = (!uid.isEmpty() && !uid.equals("—"))
                    || (currentWoNum != null && !currentWoNum.isEmpty());
            } catch (Exception ignored) {}
            boolean canCancel = (connected || flowing || paused) && !starting && hasActiveDelivery;
            if (canCancel) {
                btnAnnuler.setVisibility(android.view.View.VISIBLE);
                if (flowStarted) {
                    // Volume détecté (peu importe l'état — flowing ou ticket pending) — impossible d'annuler
                    btnAnnuler.setText("⛔ Impossible d'annuler — terminez la livraison");
                    btnAnnuler.setEnabled(false);
                    btnAnnuler.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(
                            android.graphics.Color.parseColor("#888888")));
                } else {
                    btnAnnuler.setText("⛔ Annuler la livraison");
                    btnAnnuler.setEnabled(true);
                    btnAnnuler.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(
                            android.graphics.Color.parseColor("#CC2222")));
                }
            } else {
                btnAnnuler.setVisibility(android.view.View.GONE);
            }
        }

        // ✅ RETOUR WO: visible seulement quand livraison terminée (CONNECTED post-livraison)
        // et qu'on a des données de livraison (ticket non vide + WO actif)
        if (btnRetourWO != null) {
            // ✅ Source de vérité: LcrDeliveryStatusDb — indépendant du timing UI
            // Lecture en background pour ne pas bloquer le UI thread
            final boolean connectedFinal = connected;
            if (currentWoNum != null && !currentWoNum.isEmpty()) {
                final String woCheck = currentWoNum;
                bg.execute(() -> {
                    boolean hasData = false;
                    try {
                        com.pa.lcr.lcp.storage.LcrDeliveryStatusDb db =
                            new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(requireContext());
                        com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow row =
                            db.getLatestForWo(woCheck);
                        hasData = (row != null && row.ticketNo != null && !row.ticketNo.isEmpty());
                    } catch (Exception ignored) {}
                    final boolean show = connectedFinal && hasData;
                    ui.post(() -> {
                        if (!isAdded() || getView() == null || btnRetourWO == null) return;
                        if (show) {
                            btnRetourWO.setVisibility(android.view.View.VISIBLE);
                            btnRetourWO.setEnabled(true);
                            btnRetourWO.setText("Retour au Bon de livraison");
                            btnRetourWO.setBackgroundColor(android.graphics.Color.parseColor("#185FA5"));
                            btnRetourWO.setOnClickListener(v -> retournerAuWorkOrder());
                        } else {
                            btnRetourWO.setVisibility(android.view.View.GONE);
                        }
                    });
                });
            } else {
                btnRetourWO.setVisibility(android.view.View.GONE);
            }
        }
    }

    // =========================================================
    // ✅ Impression custom — ticket ligne par ligne via opPrintText
    // =========================================================
    private void lancerImpressionCustom() {
        DeliveryController c = controller;
        if (c == null) return;

        if (btnCustomPrint != null) btnCustomPrint.setEnabled(false);

        bg.execute(() -> {
            try {
                // Lire données depuis lastResultJson
                String ticketNo = "", saleNo = "", serialId = "", woNum = "";
                double netL = 0, grossL = 0;
                String startUtc = "", endUtc = "";

                try {
                    String last = com.pa.lcrdemo.DeepLinkHandler.lastResultJson;
                    if (last != null) {
                        org.json.JSONObject j = new org.json.JSONObject(last);
                        woNum = j.optString("wonum", "");
                        org.json.JSONObject payload = j.optJSONObject("payload");
                        if (payload != null) {
                            org.json.JSONObject result = payload.optJSONObject("result");
                            if (result != null) {
                                ticketNo = result.optString("ticket_no",  "");
                                saleNo   = result.optString("sale_no",    "");
                                serialId = result.optString("serial_id",  "");
                                netL     = result.optDouble("fs_net_l",   0);
                                grossL   = result.optDouble("fs_gross_l", 0);
                                startUtc = result.optString("start_utc",  "");
                                endUtc   = result.optString("end_utc",    "");
                            }
                        }
                    }
                } catch (Exception ignored) {}

                // Construire les lignes du ticket — 30 colonnes max (limite imprimante LCR-II: 25-30)
                int cols = 30;
                String sep = "=".repeat(cols);
                String dashs = "-".repeat(cols);
                java.util.List<String> lines = new java.util.ArrayList<>();
                lines.add(sep);
                lines.add(center("TICKET DE LIVRAISON", cols));
                lines.add(sep);
                lines.add("WO    : " + woNum);
                lines.add("Tkt # : " + ticketNo);
                lines.add("Vente : " + saleNo);
                lines.add("Serie : " + serialId);
                lines.add(dashs);
                lines.add(String.format(java.util.Locale.ROOT, "NET   : %.3f L", netL));
                lines.add(String.format(java.util.Locale.ROOT, "GROSS : %.3f L", grossL));
                lines.add(dashs);
                lines.add("Debut : " + formatUtc(startUtc));
                lines.add("Fin   : " + formatUtc(endUtc));
                lines.add(sep);
                lines.add("");

                // Envoyer ligne par ligne via opPrintText
                if (tabTransportKey != null) {
                    MediaTransportManager.get(requireContext())
                        .activateExclusive(tabTransportKey, "CUSTOM_PRINT");
                }
                int errors = 0;
                for (String line : lines) {
                    try {
                        c.api_printTextLine(line);
                        Thread.sleep(150);
                    } catch (Exception e) {
                        errors++;
                        LogBus.api(node, "[CUSTOM_PRINT] ERR ligne: " + safeMsg(e));
                        surErreurConnexion(e, "CUSTOM_PRINT");
                    }
                }

                final int fErrors = errors;
                ui.post(() -> {
                    if (fErrors == 0) {
                        android.widget.Toast.makeText(requireContext(),
                            "✅ Impression custom envoyée", android.widget.Toast.LENGTH_SHORT).show();
                    } else {
                        android.widget.Toast.makeText(requireContext(),
                            "⚠️ " + fErrors + " ligne(s) en erreur", android.widget.Toast.LENGTH_SHORT).show();
                    }
                    if (btnCustomPrint != null) btnCustomPrint.setEnabled(true);
                });

            } catch (Exception e) {
                LogBus.api(node, "[CUSTOM_PRINT] ERR: " + safeMsg(e));
                ui.post(() -> {
                    android.widget.Toast.makeText(requireContext(),
                        "Erreur impression: " + safeMsg(e), android.widget.Toast.LENGTH_SHORT).show();
                    if (btnCustomPrint != null) btnCustomPrint.setEnabled(true);
                });
            }
        });
    }

    private static String center(String s, int cols) {
        if (s.length() >= cols) return s.substring(0, cols);
        int pad = (cols - s.length()) / 2;
        return " ".repeat(pad) + s;
    }

    private static String formatUtc(String utc) {
        if (utc == null || utc.isEmpty()) return "—";
        // 2026-06-15T17:43:42.549Z → 15/06 17:43
        try {
            return utc.substring(8, 10) + "/" + utc.substring(5, 7)
                + " " + utc.substring(11, 16);
        } catch (Exception ignored) { return utc; }
    }
    // Compile payload complet → SQLite local → MSAL push → deep link FSM
    // =========================================================
    private void retournerAuWorkOrder() {
        android.util.Log.i("RetourWO", "Clic reçu — démarrage retournerAuWorkOrder()");
        if (!(getActivity() instanceof MainActivity)) {
            android.util.Log.w("RetourWO", "getActivity() n'est pas MainActivity — abandon");
            return;
        }
        MainActivity main = (MainActivity) getActivity();

        // Désactiver le bouton pendant le traitement
        if (btnRetourWO != null) btnRetourWO.setEnabled(false);

        bg.execute(() -> {
            try {
                // 1. Compiler le payload complet
                String ticketNo   = "";
                String saleNo     = "";
                double netL       = 0.0;
                double grossL     = 0.0;
                // ✅ Source primaire — champs stables du fragment, jamais effacés
                String woNum      = currentWoNum;
                String woIdGuid   = currentWoIdGuid;
                String payloadJson = "{}";

                // ✅ Source primaire — lecture FRAÎCHE du registre (toujours à jour,
                // même après bouton C qui ne passe pas par DeepLinkHandler)
                org.json.JSONObject freshSnap = null;
                try {
                    if (controller != null) {
                        ApiResult sr = controller.api_tickSnapshot();
                        if (sr != null && sr.data != null) {
                            freshSnap = sr.data;
                            org.json.JSONObject result = freshSnap.optJSONObject("result");
                            if (result != null) {
                                ticketNo = result.optString("ticket_no", "");
                                saleNo   = result.optString("sale_no",   "");
                                netL     = result.optDouble("fs_net_l",  0);
                                grossL   = result.optDouble("fs_gross_l",0);
                            }
                        }
                    }
                } catch (Exception ignored) {}

                // ✅ Fallback — lastResultJson (statique) seulement si lecture fraîche vide
                try {
                    if (ticketNo.isEmpty()) {
                        String lastJson = com.pa.lcrdemo.DeepLinkHandler.lastResultJson;
                        if (lastJson != null && !lastJson.isEmpty()) {
                            org.json.JSONObject last = new org.json.JSONObject(lastJson);
                            org.json.JSONObject payload = last.optJSONObject("payload");
                            if (payload != null) {
                                org.json.JSONObject result = payload.optJSONObject("result");
                                if (result != null) {
                                    ticketNo = result.optString("ticket_no", "");
                                    saleNo   = result.optString("sale_no",   "");
                                    netL     = result.optDouble("fs_net_l",  0);
                                    grossL   = result.optDouble("fs_gross_l",0);
                                }
                                // Fallback tick si result vide
                                if (netL == 0) {
                                    org.json.JSONObject tick = payload.optJSONObject("tick");
                                    if (tick != null) {
                                        netL   = tick.optDouble("net",   0);
                                        grossL = tick.optDouble("gross", 0);
                                    }
                                }
                            }
                        }
                    }
                    // woNum/woIdGuid viennent toujours de lastResultJson ou ActiveDeliveryStore
                    // (pas du registre — le registre ne connaît pas le WO)
                    String lastJson2 = com.pa.lcrdemo.DeepLinkHandler.lastResultJson;
                    if (lastJson2 != null && !lastJson2.isEmpty()) {
                        org.json.JSONObject last2 = new org.json.JSONObject(lastJson2);
                        woNum    = last2.optString("wonum", "");
                        woIdGuid = last2.optString("woid",  "");
                    }
                } catch (Exception ignored) {}

                // Fallback TextViews si tout est vide
                try {
                    if (ticketNo.isEmpty() && txtTicketNo != null)
                        ticketNo = txtTicketNo.getText().toString()
                            .replace("Ticket Number : ", "").trim();
                    if (netL == 0.0 && txtQtyNet != null)
                        netL = Double.parseDouble(
                            txtQtyNet.getText().toString().replace("NET: ", "").trim());
                    if (grossL == 0.0 && txtQtyGross != null)
                        grossL = Double.parseDouble(
                            txtQtyGross.getText().toString().replace("GROSS: ", "").trim());
                } catch (Exception ignored) {}

                // Récupérer snapshot complet du controller si encore dispo
                org.json.JSONObject snap = new org.json.JSONObject();
                try {
                    if (controller != null) {
                        ApiResult sr = controller.api_tickSnapshot();
                        if (sr != null && sr.data != null) {
                            snap = sr.data;
                            if (saleNo.isEmpty()) saleNo = snap.optString("sale_no", "");
                            org.json.JSONObject result = snap.optJSONObject("result");
                            if (result != null) {
                                if (netL == 0.0)      netL      = result.optDouble("fs_net_l",   netL);
                                if (grossL == 0.0)    grossL    = result.optDouble("fs_gross_l", grossL);
                                if (ticketNo.isEmpty()) ticketNo = result.optString("ticket_no", "");
                                if (saleNo.isEmpty())   saleNo   = result.optString("sale_no",   "");
                            }
                        }
                    }
                } catch (Exception ignored) {}

                // Récupérer woIdGuid + woNum depuis ActiveDeliveryStore en priorité
                try {
                    com.pa.lcr.lcp.storage.ActiveDeliveryStore ads =
                        new com.pa.lcr.lcp.storage.ActiveDeliveryStore(requireContext());
                    com.pa.lcr.lcp.storage.ActiveDeliveryStore.ActiveDelivery active = ads.load();
                    if (active != null) {
                        if (active.woIdGuid != null && !active.woIdGuid.isEmpty())
                            woIdGuid = active.woIdGuid;
                        if (active.woNum != null && !active.woNum.isEmpty())
                            woNum = active.woNum;
                    }
                } catch (Exception ignored) {}

                // ⚠️ Ne pas utiliser txtDeliveryUid comme fallback — contient delivery_uid pas wo_num
                // woNum doit venir de lastResultJson ou ActiveDeliveryStore uniquement
                if (woNum.isEmpty()) {
                    android.util.Log.w("RetourWO", "woNum vide — lastResultJson et ActiveDeliveryStore épuisés");
                }

                snap.put("ticketNo", ticketNo);
                snap.put("saleNo",   saleNo);
                snap.put("netL",     netL);
                snap.put("grossL",   grossL);
                snap.put("woNum",    woNum);
                snap.put("woIdGuid", woIdGuid);
                payloadJson = snap.toString();

                // 2. Écrire dans LcrDeliveryStatusDb — vérifier si ticket déjà enregistré
                com.pa.lcr.lcp.storage.LcrDeliveryStatusDb lcrDb =
                    new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(requireContext());
                com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow existing =
                    lcrDb.getLatestForWo(woNum);
                if (existing != null && !ticketNo.isEmpty() && ticketNo.equals(existing.ticketNo)) {
                    android.util.Log.i("RetourWO", "Ticket " + ticketNo
                        + " déjà enregistré (id=" + existing.id + ") — skip INSERT");
                } else {
                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_WO_NUM,      woNum);
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_WO_ID_GUID,  woIdGuid);
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_TICKET_NO,   ticketNo);
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SALE_NO,     saleNo);
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_NET_L,       netL);
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_GROSS_L,     grossL);
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_TYPE,
                        com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.TYPE_ORIGINAL);
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SOURCE,      "REGISTRE");
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_STOP_TYPE,   "LIVRAISON");
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SYNC_STATUS,
                        com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.SYNC_PENDING);
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_PAYLOAD_JSON, payloadJson);

                    long localId = lcrDb.insertDelivery(cv);
                    android.util.Log.i("RetourWO", "Delivery sauvegardée localId=" + localId
                        + " wo=" + woNum + " net=" + netL + " gross=" + grossL);
                }

                // 3. Tenter push MSAL vers Dataverse
                try {
                    com.pa.lcrdemo.auth.MsalTokenProvider msal =
                        new com.pa.lcrdemo.auth.MsalTokenProvider(requireContext());
                    final String[] tokenHolder = {null};
                    final java.util.concurrent.CountDownLatch latch =
                        new java.util.concurrent.CountDownLatch(1);
                    msal.init(new com.pa.lcrdemo.auth.MsalTokenProvider.InitCallback() {
                        @Override public void onReady() {
                            msal.acquireTokenSilentFromWorker(
                                new com.pa.lcrdemo.auth.MsalTokenProvider.TokenCallback() {
                                    @Override public void onSuccess(String token) {
                                        tokenHolder[0] = token;
                                        latch.countDown();
                                    }
                                    @Override public void onError(Exception e) {
                                        android.util.Log.w("RetourWO", "Token silent ERR: " + e.getMessage());
                                        latch.countDown();
                                    }
                                });
                        }
                        @Override public void onError(Exception e) {
                            android.util.Log.w("RetourWO", "MSAL init ERR: " + e.getMessage());
                            latch.countDown();
                        }
                    });
                    latch.await(8, java.util.concurrent.TimeUnit.SECONDS);
                    if (tokenHolder[0] != null) {
                        com.pa.lcrdemo.dataverse.LcrDeliverySync.pushPending(
                            requireContext(), tokenHolder[0]);
                        android.util.Log.i("RetourWO", "Push Dataverse OK");
                    } else {
                        android.util.Log.w("RetourWO", "Pas de token — données en PENDING local");
                    }
                } catch (Exception e) {
                    android.util.Log.w("RetourWO", "Push Dataverse ERR: " + e.getMessage());
                }

                // 4. Effacer ActiveDeliveryStore
                try {
                    new com.pa.lcr.lcp.storage.ActiveDeliveryStore(requireContext()).clear();
                } catch (Exception ignored) {}

                // 5. Retour FSM via finish() — taskAffinity ramène FSM au premier plan
                final String fWoIdGuid = woIdGuid;
                ui.post(() -> {
                    try {
                        android.util.Log.i("RetourWO", "finish() → FSM via taskAffinity woId=" + fWoIdGuid);
                        if (getActivity() != null) getActivity().finish();
                    } catch (Exception e) {
                        android.util.Log.e("RetourWO", "finish() ERR: " + e.getMessage());
                    } finally {
                        if (btnRetourWO != null) btnRetourWO.setEnabled(true);
                    }
                });

            } catch (Exception e) {
                android.util.Log.e("RetourWO", "retournerAuWorkOrder ERR: " + e.getMessage());
                ui.post(() -> {
                    android.widget.Toast.makeText(requireContext(),
                        "Erreur retour WO: " + e.getMessage(),
                        android.widget.Toast.LENGTH_SHORT).show();
                    if (btnRetourWO != null) btnRetourWO.setEnabled(true);
                });
            }
        });
    }

    private void refreshLogView() {
        if (txtLog == null) return;
        if (cbShowLog == null || !cbShowLog.isChecked()) return;
        long now0 = System.currentTimeMillis();
        if (logUserScrolling && now0 < logUserScrollUntilMs) return;
        if (Looper.myLooper() != Looper.getMainLooper()) { ui.post(this::refreshLogView); return; }
        if (!isAdded() || getView() == null) return;
        List<LogBus.LogEvent> events = LogBus.snapshotForNode(node, TAB_LOG_MAX_LINES);
        if (logViewSinceMs > 0) {
            ArrayList<LogBus.LogEvent> filtered = new ArrayList<>(events.size());
            for (LogBus.LogEvent e : events) { if (e.ts >= logViewSinceMs) filtered.add(e); }
            events = filtered;
        }
        txtLog.setText(LogBus.buildText(events));
    }

    private String ts(String msg) { if (!logTsEnabled) return msg; return uiTs() + " " + msg; }

    private String uiTs() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.CANADA_FRENCH)
                .format(new Date(System.currentTimeMillis()));
    }

    private static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }

    private static String safeMsg(Exception e) {
        if (e == null) return "";
        String m = e.getMessage();
        return (m == null) ? e.getClass().getSimpleName() : m;
    }

    public static RegisterTabFragment newInstance(int node, int from, String serialId, String transportKey) {
        RegisterTabFragment f = new RegisterTabFragment();
        Bundle b = new Bundle();
        b.putInt(ARG_NODE, node);
        b.putInt(ARG_FROM, from);
        if (serialId != null) b.putString(ARG_SERIAL, serialId);
        if (transportKey != null) b.putString(ARG_TRANSPORT, transportKey);
        f.setArguments(b);
        return f;
    }

    /**
     * ✅ Appelé dans chaque catch qui attrape une erreur LCP/BT.
     * Si c'est une erreur de connexion → affiche immédiatement l'écran de diagnostic.
     */
    /**
     * ✅ Vérifie que le transport io est ouvert avant toute action sur le registre.
     * Si fermé → déclenche immédiatement l'écran de diagnostic.
     * @return true si io est ouvert, false si diagnostic lancé
     */
    private boolean verifierIoAvantAction(String contexte) {
        try {
            if (tabTransportKey != null) {
                com.pa.lcr.lcp.transport.TransportIo io =
                    MediaTransportManager.get(requireContext()).getByKey(tabTransportKey);
                if (io == null || !io.isOpen()) {
                    surErreurConnexion(
                        new java.io.IOException("Transport fermé — BT/USB débranché"),
                        contexte);
                    return false;
                }
            }
        } catch (Exception e) {
            surErreurConnexion(e, contexte);
            return false;
        }
        return true;
    }

    private void surErreurConnexion(Exception e, String contexte) {
        android.util.Log.w("RegisterTabFragment", "surErreurConnexion [" + contexte + "]: "
            + (e != null ? e.getMessage() : "null"));
        if (com.pa.lcrdemo.RegisterConnectionHelper.estErreurConnexion(e)) {
            try {
                if (requireActivity() instanceof com.pa.lcrdemo.MainActivity) {
                    new com.pa.lcrdemo.RegisterConnectionHelper(
                        (com.pa.lcrdemo.MainActivity) requireActivity())
                        .validerConnexion(
                            tabTransportKey != null ? tabTransportKey : "",
                            node,
                            serialFromArgs != null ? serialFromArgs : "",
                            currentWoNum != null ? currentWoNum : "");
                }
            } catch (Exception ignored) {}
        }
    }

    private void reportMediaOffToApi(String origin, String detail) {
        try {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).reportMediaNotReadyFromTab(node, serialFromArgs, tabTransportKey, origin, detail);
            }
        } catch (Exception ignored) {}
    }
    private int getPendingProduct() {
        if (spnProduct != null) {
            String txt = spnProduct.getText().toString().trim();
            if (!txt.isEmpty()) {
                String num = txt.contains(" - ") ? txt.substring(0, txt.indexOf(" - ")).trim() : txt;
                try { return Integer.parseInt(num); } catch (Exception ignored) {}
            }
        }
        return 1;
    }

    public String getSerialFromArgs() { return serialFromArgs; }

    public int getNodeFromArgs() {
        Bundle a = getArguments();
        return a != null ? a.getInt(ARG_NODE, 250) : 250;
    }

    // ── Resolve (btnA) ───────────────────────────────────────────────────────────────
    // ── Bouton C — nouvelle livraison ─────────────────────────────────
    private void startNewDeliveryC() {
        if (controller == null) return;
        refreshDelCodeFromTickSnapshotThrottled();
        int dc = lastDelCode;
        boolean tp = (dc & 0x0001) != 0;
        boolean flow = (dc & 0x0004) != 0;
        boolean act = (dc & 0x0008) != 0;
        if (tp || flow || act) {
            if (txtLive != null) txtLive.setText("LIVE: registre non prêt — faire Status (B) / Resolve (A)");
            LogBus.ui(node, ts("C bloqué: delCode=0x" + Integer.toHexString(dc)));
            updateButtons(controller.getState());
            return;
        }
        if (!(getActivity() instanceof MainActivity)) return;
        MainActivity main = (MainActivity) getActivity();

        starting = true;
        startingSinceMs = System.currentTimeMillis();
        updateButtons(controller.getState());
        if (txtLive != null) txtLive.setText("LIVE: RUNNING_FLOWING (flow off - waiting progression)");

        int prod = getPendingProduct();
        String presetStr = edtPreset != null ? edtPreset.getText().toString() : "0";

        // ✅ Même principe que le deep link FieldService — woNum/woIdGuid déjà
        // connus du fragment (stables, jamais effacés), seul le ticket number
        // du registre change à chaque nouvelle livraison. Réutilise
        // lancerLivraison() de DeepLinkHandler: stabilisation BT, oneshot/start,
        // poll de fin, patchDataverse automatique — exactement le chemin testé
        // et fonctionnel du flux FSM normal.
        String tk = (tabTransportKey != null) ? tabTransportKey.trim() : "";
        main.lancerLivraisonDepuisTab(tk, node, serialFromArgs,
            currentWoNum, currentWoIdGuid, String.valueOf(prod), presetStr, "");
    }

    private void doResolve() {
        if (controller == null) return;
        if (!verifierIoAvantAction("RESOLVE")) return;
        try { controller.alignOrRecover(); } catch (Exception e) { surErreurConnexion(e, "RESOLVE"); return; }
        ui.postDelayed(() -> {
            try {
                if (tabTransportKey != null)
                    MediaTransportManager.get(requireContext())
                        .activateExclusive(tabTransportKey, "TAB_A");
            } catch (Exception ignored) {}
            try { if (controller != null) controller.requestStatus(); } catch (Exception ignored) {}
            try { validateHeaderAsync(); } catch (Exception ignored) {}
            try { if (controller != null) controller.requestLiveSample(); } catch (Exception ignored) {}
            refreshDelCodeFromTickSnapshotThrottled();
            updateButtons(controller != null ? controller.getState() : null);
        }, 900);
    }

    // ── Annuler livraison ─────────────────────────────────────────────────────────────
    private void annulerLivraison() {
        DeliveryController c = controller;
        if (c == null) return;
        if (!verifierIoAvantAction("ANNULER")) return;
        if (btnAnnuler != null) {
            btnAnnuler.setEnabled(false);
            btnAnnuler.setText("⏳ Annulation en cours — veuillez patienter");
        }
        if (btnConnect  != null) btnConnect.setEnabled(false);
        if (btnA        != null) btnA.setEnabled(false);
        if (btnB        != null) btnB.setEnabled(false);
        if (btnC        != null) btnC.setEnabled(false);
        if (btnContinue != null) btnContinue.setEnabled(false);
        if (btnFinish   != null) btnFinish.setEnabled(false);
        if (btnRetourWO != null) btnRetourWO.setEnabled(false);
        double netAtCancel   = parseDisplayNet();
        double grossAtCancel = parseDisplayGross();
        // ✅ Poser le flag AVANT bg.execute — évite race avec onStateChanged(ENDED)
        cancelInProgress = true;
        bg.execute(() -> {
            try {
                // 1. Terminer la livraison ET confirmer via lecture réelle du registre
                // (deliveryActive == false) — évite état transitoire au redémarrage
                boolean confirmed = false;
                try { confirmed = c.forceEndSync(8000); } catch (Exception ignored) {}
                android.util.Log.i("Annuler", "forceEndSync confirmed=" + confirmed);

                // 3. Resolve synchrone — consomme le ticket pending → CONNECTED propre
                // api_deliveryAlignA() est synchrone contrairement à alignOrRecover()
                try { c.api_deliveryAlignA(); } catch (Exception ignored) {}
                // ✅ Reset lastDelCode — évite btnC grisé à cause de bits stale
                lastDelCode = 0;

                // 4. Attendre CONNECTED propre (max 5s)
                for (int i = 0; i < 25; i++) {
                    try { Thread.sleep(200); } catch (Exception ignored) {}
                    if (c.getState() == com.pa.lcr.lcp.DeliveryState.CONNECTED) break;
                }
                // Reconnecter le transport si nécessaire
                try {
                    if (tabTransportKey != null)
                        com.pa.lcr.lcp.transport.MediaTransportManager.get(requireContext())
                            .activateExclusive(tabTransportKey, "ANNULATION_DONE");
                } catch (Exception ignored) {}
                // Rafraîchir l'état UI → CONNECTED Ready
                try { c.requestStatus(); } catch (Exception ignored) {}

                // 4. Lire le ticket UID généré par endDelivery
                String ticketNo = "";
                try {
                    com.pa.lcr.lcp.ApiResult snap = c.api_tickSnapshot();
                    if (snap != null && snap.data != null) {
                        ticketNo = snap.data.optString("ticket_no", "");
                        org.json.JSONObject result = snap.data.optJSONObject("result");
                        if (result != null && ticketNo.isEmpty())
                            ticketNo = result.optString("ticket_no", "");
                    }
                } catch (Exception ignored) {}

                // 5. Contexte WO depuis ActiveDeliveryStore
                String woNum = "", woIdGuid = "";
                try {
                    com.pa.lcr.lcp.storage.ActiveDeliveryStore ads =
                        new com.pa.lcr.lcp.storage.ActiveDeliveryStore(requireContext());
                    com.pa.lcr.lcp.storage.ActiveDeliveryStore.ActiveDelivery ad = ads.load();
                    if (ad != null) {
                        woNum    = ad.woNum    != null ? ad.woNum    : "";
                        woIdGuid = ad.woIdGuid != null ? ad.woIdGuid : "";
                    }
                } catch (Exception ignored) {}

                // 6. Logger TYPE_ANNULATION dans SQLite avec le ticket UID
                try {
                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_WO_NUM,      woNum);
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_WO_ID_GUID,  woIdGuid);
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_TICKET_NO,   ticketNo);
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_NET_L,       netAtCancel);
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_GROSS_L,     grossAtCancel);
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_TYPE,
                        com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.TYPE_ANNULATION);
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SOURCE,      "OPERATEUR");
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_STOP_TYPE,   "ANNULATION");
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SYNC_STATUS,
                        com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.SYNC_PENDING);
                    org.json.JSONObject payload = new org.json.JSONObject();
                    payload.put("cancelled",       true);
                    payload.put("cancel_reason",   "operator_cancel");
                    payload.put("net_at_cancel",   netAtCancel);
                    payload.put("gross_at_cancel", grossAtCancel);
                    payload.put("cancel_ts",       System.currentTimeMillis());
                    payload.put("ticket_no",       ticketNo);
                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_PAYLOAD_JSON, payload.toString());
                    new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(requireContext()).insertDelivery(cv);
                    android.util.Log.i("Annuler", "Annulation loggée wo=" + woNum + " ticket=" + ticketNo);
                } catch (Exception e) {
                    android.util.Log.w("Annuler", "Insert ERR: " + e.getMessage());
                }

                // 7. Marquer CANCELLED dans ActiveDeliveryStore — garder le contexte WO
                // pour que retournerAuWorkOrder() ait encore le woNum/woIdGuid
                // si le chauffeur relance avec bouton C sans repasser par FSM
                try {
                    com.pa.lcr.lcp.storage.ActiveDeliveryStore ads2 =
                        new com.pa.lcr.lcp.storage.ActiveDeliveryStore(requireContext());
                    com.pa.lcr.lcp.storage.ActiveDeliveryStore.ActiveDelivery ad2 = ads2.load();
                    if (ad2 != null) {
                        ads2.save(ad2.woNum, ad2.woIdGuid, ad2.jobId,
                            ad2.mac, ad2.node, ad2.serialId,
                            ad2.produit, ad2.preset, "CANCELLED");
                    }
                } catch (Exception ignored) {}

                // 8. Rester dans l'APK — remettre l'UI à zéro
                cancelInProgress = false;

                // ✅ Boucle de confirmation — réessaie jusqu'à ce que le bit ticket
                // pending soit vraiment retombé (max 5s), au lieu d'un délai fixe
                // qui pouvait être insuffisant et laisser btnC grisé à tort
                try {
                    DeliveryController cRefresh = controller;
                    if (cRefresh != null) {
                        for (int i = 0; i < 17; i++) {
                            try { Thread.sleep(300); } catch (Exception ignored) {}
                            com.pa.lcr.lcp.ApiResult snapRefresh = cRefresh.api_tickSnapshot();
                            if (snapRefresh != null && snapRefresh.data != null) {
                                int dcRefresh = snapRefresh.data.optInt("delCode", lastDelCode);
                                lastDelCode = dcRefresh;
                                boolean tpStill = (dcRefresh & 0x0001) != 0;
                                if (!tpStill) break;
                            }
                        }
                    }
                } catch (Exception ignored) {}

                ui.post(() -> {
                    if (txtQtyNet   != null) txtQtyNet.setText("NET: 0.0");
                    if (txtQtyGross != null) txtQtyGross.setText("GROSS: 0.0");
                    if (txtTicketNo != null) txtTicketNo.setText("Ticket Number : —");
                    if (txtDeliveryUid != null) txtDeliveryUid.setText("Delivery UID : —");
                    // ✅ Réactiver tous les boutons désactivés pendant l'annulation
                    if (btnAnnuler  != null) btnAnnuler.setEnabled(true);
                    if (btnConnect  != null) btnConnect.setEnabled(true);
                    if (btnA        != null) btnA.setEnabled(true);
                    if (btnB        != null) btnB.setEnabled(true);
                    if (btnC        != null) btnC.setEnabled(true);
                    if (btnContinue != null) btnContinue.setEnabled(true);
                    if (btnFinish   != null) btnFinish.setEnabled(true);
                    if (btnRetourWO != null) btnRetourWO.setEnabled(true);
                    updateButtons(controller != null ? controller.getState() : null);
                    android.widget.Toast.makeText(getContext(),
                        "Livraison annulée — registre prêt",
                        android.widget.Toast.LENGTH_SHORT).show();
                });

            } catch (Exception e) {
                cancelInProgress = false;
                android.util.Log.e("Annuler", "ERR: " + e.getMessage());
                ui.post(() -> {
                    if (btnAnnuler  != null) btnAnnuler.setEnabled(true);
                    if (btnConnect  != null) btnConnect.setEnabled(true);
                    if (btnA        != null) btnA.setEnabled(true);
                    if (btnB        != null) btnB.setEnabled(true);
                    if (btnC        != null) btnC.setEnabled(true);
                    if (btnContinue != null) btnContinue.setEnabled(true);
                    if (btnFinish   != null) btnFinish.setEnabled(true);
                    if (btnRetourWO != null) btnRetourWO.setEnabled(true);
                    android.widget.Toast.makeText(getContext(),
                        "Erreur annulation: " + e.getMessage(),
                        android.widget.Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void lancerScanProduits() {
        DeliveryController c = controller;
        if (c == null) {
            android.widget.Toast.makeText(requireContext(), "Registre non connecté", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        com.pa.lcr.lcp.DeliveryState st = c.getState();
        if (st == com.pa.lcr.lcp.DeliveryState.RUNNING_FLOWING
                || st == com.pa.lcr.lcp.DeliveryState.RUNNING_PAUSED
                || st == com.pa.lcr.lcp.DeliveryState.ENDING) {
            android.widget.Toast.makeText(requireContext(), "Impossible pendant une livraison", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (btnScanProducts != null) { btnScanProducts.setEnabled(false); btnScanProducts.setText("⏳ Scan..."); }
        final String serialId = (serialFromArgs != null && !serialFromArgs.trim().isEmpty()) ? serialFromArgs.trim() : null;
        ui.post(() -> { if (txtLive != null) txtLive.setText("🔍 Scan produits 1 / 16..."); });
        bg.execute(() -> {
            try {
                if (tabTransportKey != null)
                    com.pa.lcr.lcp.transport.MediaTransportManager.get(requireContext()).activateExclusive(tabTransportKey, "SCAN_PRODUITS");
                java.util.List<com.pa.lcr.lcp.LcpLink.ProductScanResult> results =
                    c.api_scanProductNames(msg -> {
                        LogBus.api(node, "[SCAN] " + msg);
                        try {
                            int colon = msg.indexOf(':');
                            int n = Integer.parseInt(msg.substring("Produit ".length(), colon).trim());
                            String d = msg.substring(colon + 1).trim();
                            ui.post(() -> { if (isAdded() && getView() != null && txtLive != null) txtLive.setText("🔍 Scan " + n + " / 16" + (d.isEmpty() ? "" : "  —  " + d)); });
                        } catch (Exception ignored) {}
                    });
                if (serialId != null) {
                    com.pa.lcr.lcp.storage.RegisterProductStore store = new com.pa.lcr.lcp.storage.RegisterProductStore(requireContext());
                    store.upsertAll(serialId, node, results);
                    store.close();
                }
                final String[] labels = new String[16];
                for (int i = 0; i < 16; i++) labels[i] = String.valueOf(i + 1);
                final int[] propaneRef = {-1};
                for (com.pa.lcr.lcp.LcpLink.ProductScanResult r : results) {
                    int idx = r.noteIdx - 1;
                    if (idx >= 0 && idx < 16) labels[idx] = r.toSpinnerLabel();
                    if (r.isPropane && propaneRef[0] == -1) propaneRef[0] = r.noteIdx;
                }
                ui.post(() -> {
                    try {
                        if (spnProduct != null) spnProduct.setAdapter(new android.widget.ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line, labels));
                        if (propaneRef[0] > 0 && spnProduct != null) {
                            spnProduct.setText(labels[propaneRef[0] - 1], false);
                            if (txtLive != null) txtLive.setText("✅ Scan terminé — Propane produit " + propaneRef[0]);
                            android.widget.Toast.makeText(requireContext(), "✅ Propane — produit " + propaneRef[0] + " sélectionné", android.widget.Toast.LENGTH_LONG).show();
                        } else {
                            if (txtLive != null) txtLive.setText("✅ Scan terminé — " + results.size() + " produits");
                        }
                    } finally {
                        if (btnScanProducts != null) { btnScanProducts.setEnabled(true); btnScanProducts.setText("🔍 Scan produits"); }
                    }
                });
            } catch (Exception e) {
                android.util.Log.e("RegisterTabFragment", "lancerScanProduits ERR: " + e.getMessage());
                ui.post(() -> {
                    if (txtLive != null) txtLive.setText("❌ Scan ERR: " + e.getMessage());
                    if (btnScanProducts != null) { btnScanProducts.setEnabled(true); btnScanProducts.setText("🔍 Scan produits"); }
                });
            }
        });
    }

    public void applierDescriptionsProduits(String serialId, int lcrNode) {
        if (serialId == null || serialId.isEmpty()) return;
        bg.execute(() -> {
            try {
                com.pa.lcr.lcp.storage.RegisterProductStore store = new com.pa.lcr.lcp.storage.RegisterProductStore(requireContext());
                java.util.List<com.pa.lcr.lcp.storage.RegisterProductStore.Row> rows = store.getAll(serialId, lcrNode);
                if (rows.isEmpty()) rows = store.getAll(serialId);
                store.close();
                if (rows.isEmpty()) return;
                final String[] labels = new String[16];
                for (int i = 0; i < 16; i++) labels[i] = String.valueOf(i + 1);
                for (com.pa.lcr.lcp.storage.RegisterProductStore.Row r : rows) {
                    int idx = r.noteIdx - 1;
                    if (idx >= 0 && idx < 16) labels[idx] = r.toSpinnerLabel();
                }
                ui.post(() -> {
                    if (!isAdded() || getView() == null || spnProduct == null) return;
                    String cur = spnProduct.getText().toString();
                    spnProduct.setAdapter(new android.widget.ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line, labels));
                    if (!cur.isEmpty()) spnProduct.setText(cur, false);
                });
            } catch (Exception e) {
                android.util.Log.w("RegisterTabFragment", "applierDescriptions ERR: " + e.getMessage());
            }
        });
    }

    private double parseDisplayNet() {
        try {
            if (txtQtyNet == null) return 0.0;
            return Double.parseDouble(txtQtyNet.getText().toString().replace("NET: ", "").trim());
        } catch (Exception e) { return 0.0; }
    }

    private double parseDisplayGross() {
        try {
            if (txtQtyGross == null) return 0.0;
            return Double.parseDouble(txtQtyGross.getText().toString().replace("GROSS: ", "").trim());
        } catch (Exception e) { return 0.0; }
    }
}
