package com.pa.lcrdemo;

// ═══════════════════════════════════════════════════════════════
// COMPATIBILITÉ ANDROID : API 28 (Android 9) → API 35 (Android 15)
// Tester sur Android 9 (192.168.134.105) ET Android 15 (R52X508K2DR)
// ═══════════════════════════════════════════════════════════════

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
        // ✅ REMPLACÉ (13 août 2026, demande Paul — "on fait les sections")
        // — l'ancienne cascade de postDelayed (runStatusBLikeButton à 300ms,
        // checkPendingDeliveryForThisRegister à 600ms, autoScanProduitsSiNecessaire
        // à 3000ms) tirait sur le registre à des moments différents, sans
        // ordre garanti ni dépendance entre eux. Remplacée par runInitSequence()
        // — une seule cascade séquentielle, chaque section attend la
        // précédente. Ancien code laissé plus bas en commentaire, pas
        // supprimé, au cas où il faille revenir en arrière rapidement.
        runInitSequence();

        /* ANCIEN CODE (13 août 2026) — désactivé, conservé pour rollback rapide :
        ui.postDelayed(() -> {
            try { runStatusBLikeButton("TAB_ACTIVATED"); } catch (Exception ignored) {}
        }, 300);
        ui.postDelayed(() -> checkPendingDeliveryForThisRegister(), 600);
        autoScanArmedForThisActivation = true;
        ui.postDelayed(() -> {
            if (autoScanArmedForThisActivation && controller != null
                    && controller.getState() == DeliveryState.CONNECTED) {
                autoScanArmedForThisActivation = false;
                autoScanProduitsSiNecessaire();
            }
        }, 3000);
        */
    }

    /**
     * Vérifie si ActiveDeliveryStore a une livraison PENDING pour ce registre.
     * Si serial + node correspondent → valider + afficher infos + bouton Lancer.
     */
    private volatile long lastCheckPendingMs = 0;
    // ✅ AJOUTÉ (13 août 2026, demande Paul — "juste une fois est suffisant
    // si on revient avec l'information") — même principe que
    // lastCheckPendingMs ci-dessus, pour rechercherWoDepuisRegistre().
    private volatile long lastWoLookupMs = 0;

    private void checkPendingDeliveryForThisRegister() {
        try {
            android.content.Context ctx = getContext();
            if (ctx == null) return;

            // ✅ FIX CRITIQUE (11 août 2026, demande Paul — "j'ai un lag
            // vraiment énorme dans le tab... le live ne suivait pas
            // pentoute") — trouvé via logcat : cette méthode se
            // reprogramme elle-même toutes les 1.5s (voir
            // onTabMediaStatusChanged, ligne ~1966) — INDÉFINIMENT, dès
            // qu'un changement de média se produit UNE SEULE fois. L'ancien
            // anti-rebond (1500ms) ne protégeait rien puisque la fenêtre de
            // reprogrammation correspond EXACTEMENT à la fenêtre de
            // throttle — chaque cycle passait à travers. Résultat :
            // getOrCreate() martelé en boucle même pendant une livraison
            // RUNNING_FLOWING active, en compétition directe avec le vrai
            // sondage live pour le même verrou partagé. Corrigé : sauté
            // complètement pendant une livraison active.
            if (controller != null) {
                com.pa.lcr.lcp.DeliveryState stCourant = controller.getState();
                if (stCourant == com.pa.lcr.lcp.DeliveryState.RUNNING_FLOWING
                        || stCourant == com.pa.lcr.lcp.DeliveryState.RUNNING_PAUSED) {
                    return;
                }
            }

            // ✅ FIX lag : cette méthode est appelée 3 fois en rafale (600/800/1500ms)
            // à chaque retour dans le tab (onTabActivated + onResume x2), chacune
            // refaisant ActiveDeliveryStore.load() sur le thread UI. Anti-rebond pour
            // n'exécuter le travail réel qu'une fois par courte fenêtre.
            long now = System.currentTimeMillis();
            if (now - lastCheckPendingMs < 1500) return;
            lastCheckPendingMs = now;

            com.pa.lcr.lcp.storage.ActiveDeliveryStore ads =
                new com.pa.lcr.lcp.storage.ActiveDeliveryStore(ctx);
            com.pa.lcr.lcp.storage.ActiveDeliveryStore.ActiveDelivery ad = ads.load();

            // ✅ Filet de sécurité — capturer le contexte WO stable dès qu'on le voit,
            // et rafraîchir le panneau cumulatif. Ceci doit tourner MÊME si le
            // controller est CONNECTED (l'état normal une fois le registre lié) —
            // c'est une opération de lecture seule, elle n'interfère avec rien.
            // ✅ FIX : ce bloc tournait avant APRÈS le garde d'état plus bas, qui
            // incluait CONNECTED dans sa liste d'états à ignorer — bloquant donc
            // systématiquement ce rafraîchissement dès que le registre était bien
            // connecté, exactement le moment où on en a besoin.
            if (ad != null && ad.woNum != null && !ad.woNum.isEmpty()) {
                currentWoNum = ad.woNum;
                if (ad.woIdGuid != null && !ad.woIdGuid.isEmpty()) currentWoIdGuid = ad.woIdGuid;
                rafraichirCumulWo();
            }

            // ✅ Si le controller est déjà en livraison active — ne pas interférer
            // avec la suite de cette méthode (proposer de reprendre une livraison
            // PENDING). CONNECTED retiré de cette liste — voir commentaire ci-dessus.
            if (controller != null) {
                com.pa.lcr.lcp.DeliveryState st = controller.getState();
                if (st == com.pa.lcr.lcp.DeliveryState.RUNNING_FLOWING
                        || st == com.pa.lcr.lcp.DeliveryState.RUNNING_PAUSED
                        || st == com.pa.lcr.lcp.DeliveryState.ENDING) return;
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
                btnRetourWO.setOnClickListener(v -> { LogBus.api(node, "[ACTION-CLIC] RETOUR_WO"); retournerAuWorkOrder(); });
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
                // ✅ (4 août 2026, demande Paul) — même garde que DeepLinkHandler
                // .lancerLivraison() : si le scan auto produits est encore en
                // cours pour CE tab (donc un tab qui vient d'être créé — sinon
                // le scan ne serait pas armé/en vol), attendre sa fin avant de
                // démarrer. Sur un tab déjà existant, isAutoProductScanBusy()
                // est déjà false ici — aucun délai. Max 10s, best-effort.
                boolean scanTermine = false;
                for (int i = 0; i < 20; i++) {
                    if (!isAutoProductScanBusy()) { scanTermine = true; break; }
                    try { Thread.sleep(500); } catch (Exception ignored) {}
                }
                android.util.Log.i("RegisterTabFragment",
                    "lancerDepuisStore: attente scan auto produits — "
                        + (scanTermine ? "terminé" : "timeout 10s, poursuite quand même"));
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
    private android.view.View panelWoCumul;
    private android.widget.TextView txtWoCumulNet, txtWoCumulGross, txtWoCumulCount;
    private Button btnReprintTicket;
    private Button btnRetourWO;
    private Button btnCustomPrint;
    private Button btnTestPrinter;
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
    // ✅ AJOUTÉ (12 août 2026, demande Paul — "il y a qq chose qui ralentit
    // le tab") — trouvé : refreshLogView() reconstruisait TOUJOURS le texte
    // COMPLET (jusqu'à 400 lignes) et le remplaçait au complet via
    // setText() — toutes les 800ms tant que le log est visible. Un
    // setText() complet force Android à tout re-mesurer/re-disposer le
    // TextView à chaque fois, même si une seule ligne a changé. Avec les
    // dizaines de lignes/seconde générées pendant une livraison active
    // (documenté toute la journée), c'est un vrai coût UI répété, distinct
    // de tout ce qu'on a corrigé côté LCP aujourd'hui. Ce filigrane
    // (horodatage du dernier événement déjà affiché) permet maintenant de
    // n'ajouter (append) que les lignes VRAIMENT nouvelles, au lieu de
    // tout reconstruire.
    private long lastRenderedLogTs = 0L;
    // ✅ AJOUTÉ (12 août 2026) — vrai si au moins un événement a été généré
    // pendant RUNNING_FLOWING sans être affiché (voir scheduleLogRefresh).
    // Sert à déclencher le rattrapage exactement une fois à la sortie de
    // l'écoulement, plutôt que de continuer à vérifier en boucle.
    private volatile boolean logRefreshDeferredByFlowing = false;
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

    // ✅ AJOUTÉ (14 août 2026, demande Paul — "un guide pour le livreur pour
    // pas qu'il se demande qu'est-ce qui arrive") — léger, volontairement :
    // un seul TextView, pas de librairie d'animation, juste .animate().alpha()
    // natif (quasi gratuit). Réutilise les points de sortie déjà en place
    // (initSectionLog) — pas un système séparé, juste un deuxième récepteur
    // du même signal.
    private android.widget.TextView txtInitGuide;

    private void showInitGuide(String texteAmical) {
        if (ui == null) return;
        ui.post(() -> {
            if (txtInitGuide == null || !isAdded()) return;
            txtInitGuide.setText(texteAmical);
            txtInitGuide.animate().cancel();
            txtInitGuide.setVisibility(View.VISIBLE);
            txtInitGuide.animate().alpha(1f).setDuration(150).start();
        });
    }

    private void fadeOutInitGuide() {
        if (ui == null) return;
        ui.postDelayed(() -> {
            if (txtInitGuide == null || !isAdded()) return;
            txtInitGuide.animate().cancel();
            txtInitGuide.animate().alpha(0f).setDuration(400)
                    .withEndAction(() -> { if (txtInitGuide != null) txtInitGuide.setVisibility(View.GONE); })
                    .start();
        }, 600); // petite pause pour que le dernier message soit lisible avant de s'effacer
    }

    // ✅ AJOUTÉ (13 août 2026, demande Paul — "on fait les sections") —
    // séquence d'initialisation en cascade : 6 sections, dans l'ordre,
    // chacune avec retry (3 tentatives) avant d'avancer, remplace les
    // mécanismes parallèles (validateHeaderAsync, runStatusBLikeButton,
    // triggerWoDetectionThrottled, l'auto-scan) qui tiraient tous sur le
    // registre à peu près en même temps sans ordre garanti. Une livraison
    // (bouton C ou deep link) exige l'approbation de TOUTES les sections
    // via peutDemarrerLivraison() — sauf PRODUIT_PRESET, qui peut être en
    // mode DÉGRADÉ (produit par défaut, pas de vrai scan) et compter quand
    // même comme approuvée.
    private enum InitSectionStatus { EN_ATTENTE, EN_COURS, OK, DEGRADE, ECHEC }
    private enum InitSection { REGISTRE, PRODUIT, PRESET, LIVE, RETOUR_WO, ACTION }
    private final java.util.Map<InitSection, InitSectionStatus> initSectionStatus =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int INIT_MAX_RETRIES = 3;

    // ✅ AJOUTÉ (13 août 2026) — valeurs validées par la séquence d'init,
    // déposées ici (pas écrites au registre tout de suite) — l'écriture
    // réelle (produit + preset ENSEMBLE) se fait au moment de l'armement,
    // comme c'était déjà le cas pour le preset seul.
    private volatile Integer initValidatedProductIdx = null; // 0-based (LCP List 0 : 0 = produit 1)
    private volatile Double initValidatedPresetL = null;

    private void initSectionLog(InitSection section, int idx, String result) {
        InitSectionStatus st = initSectionStatus.get(section);
        android.util.Log.i("InitSeq", "[INIT " + idx + "/6] " + section.name() + " — " + result
                + " (statut=" + (st != null ? st.name() : "?") + ")");
        // ✅ AJOUTÉ (14 août 2026) — même signal, texte amical pour le
        // chauffeur au lieu du format technique du log.
        String amical;
        switch (section) {
            case REGISTRE: amical = "Connexion au registre..."; break;
            case PRODUIT: amical = "Vérification du produit..."; break;
            case PRESET: amical = "Préparation du preset..."; break;
            case LIVE: amical = "Lecture des données en direct..."; break;
            case RETOUR_WO: amical = "Recherche du bon de travail..."; break;
            case ACTION: amical = "Prêt."; break;
            default: amical = "Préparation..."; break;
        }
        if (st == InitSectionStatus.OK || st == InitSectionStatus.DEGRADE) {
            showInitGuide(amical);
        }
        if (section == InitSection.ACTION && st == InitSectionStatus.OK) {
            fadeOutInitGuide(); // CONNECTED READY — on efface pour laisser la place au démarrage
        }
    }

    /** true si TOUTES les sections requises sont approuvées (PRODUIT/PRESET
     *  peuvent être DEGRADE et compter comme approuvées quand même). */
    // ✅ CORRIGÉ (14 août 2026, demande Paul — "je ne suis pas en ticket
    // pending, j'ai le net et gross, mais si je fais new C, rien ne se
    // passe") — trouvé un vrai bug bloquant : un statut jamais suivi
    // (initSectionStatus.get() retourne null — tab qui n'est jamais passé
    // par runInitSequence(), ex: tab déjà ouvert avant ce changement, ou
    // activé par un autre chemin) était traité comme un ÉCHEC PERMANENT
    // ("null != OK" est vrai), bloquant silencieusement le bouton pour
    // toujours, même sur un tab visiblement fonctionnel (net/gross
    // affichés, preuve que la connexion marche). Corrigé : null (jamais
    // suivi) n'est plus un blocage — seul un statut EXPLICITEMENT
    // EN_COURS ou ÉCHEC bloque vraiment. Ça préserve l'intention d'origine
    // (attendre pendant une vraie initialisation en cours) sans jamais
    // condamner un tab dont le suivi n'a simplement jamais démarré.
    private boolean peutDemarrerLivraison() {
        for (InitSection s : new InitSection[]{InitSection.REGISTRE, InitSection.LIVE, InitSection.RETOUR_WO}) {
            InitSectionStatus st = initSectionStatus.get(s);
            if (st == InitSectionStatus.EN_COURS || st == InitSectionStatus.ECHEC) return false;
        }
        InitSectionStatus produit = initSectionStatus.get(InitSection.PRODUIT);
        InitSectionStatus preset = initSectionStatus.get(InitSection.PRESET);
        if (produit == InitSectionStatus.EN_COURS || produit == InitSectionStatus.ECHEC) return false;
        if (preset == InitSectionStatus.EN_COURS || preset == InitSectionStatus.ECHEC) return false;
        return true;
    }

    /** Enveloppe générique : jusqu'à 3 tentatives avant d'abandonner (ECHEC),
     *  ou DEGRADE si allowDegraded=true. step doit retourner true en cas de
     *  succès. Bloquant volontairement — appelé depuis safeBg()/un thread
     *  dédié, jamais depuis le thread UI. */
    private boolean runSectionWithRetry(InitSection section, int idx, boolean allowDegraded,
                                          java.util.concurrent.Callable<Boolean> step) {
        initSectionStatus.put(section, InitSectionStatus.EN_COURS);
        for (int attempt = 1; attempt <= INIT_MAX_RETRIES; attempt++) {
            try {
                if (Boolean.TRUE.equals(step.call())) {
                    initSectionStatus.put(section, InitSectionStatus.OK);
                    initSectionLog(section, idx, "OK (tentative " + attempt + "/" + INIT_MAX_RETRIES + ")");
                    pauseLisibiliteGuide();
                    return true;
                }
            } catch (Exception e) {
                initSectionLog(section, idx, "tentative " + attempt + "/" + INIT_MAX_RETRIES + " échouée: " + e.getMessage());
            }
        }
        if (allowDegraded) {
            initSectionStatus.put(section, InitSectionStatus.DEGRADE);
            initSectionLog(section, idx, "DÉGRADÉ après " + INIT_MAX_RETRIES + " tentatives — valeur par défaut utilisée");
        } else {
            initSectionStatus.put(section, InitSectionStatus.ECHEC);
            initSectionLog(section, idx, "ÉCHEC après " + INIT_MAX_RETRIES + " tentatives");
        }
        pauseLisibiliteGuide();
        return false;
    }

    // ✅ AJOUTÉ (14 août 2026, demande Paul — "je ne vois pas les écrans
    // pour la transition entre les étapes") — les sections 2 à 5 ne font
    // que DÉCLENCHER du travail async (autoScanProduitsSiNecessaire,
    // requestLiveSample, rechercherWoDepuisRegistre — toutes fire-and-
    // forget, retournent true immédiatement sans attendre la vraie
    // réponse). Une fois REGISTRE connecté, toute la séquence défilait en
    // moins de 50ms — le texte du guide changeait trop vite pour être lu,
    // même si le mécanisme fonctionnait correctement. Pause de lisibilité
    // pure, SANS ralentir le vrai travail (déjà lancé en async au moment
    // où cette pause commence) — on est déjà sur le thread dédié
    // (safeBg), jamais l'UI, donc sans risque de geler l'affichage.
    private void pauseLisibiliteGuide() {
        try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    /** Point d'entrée unique — remplace les postDelayed dispersés de
     *  onTabActivated(). Tourne sur un thread dédié (safeBg), jamais l'UI. */
    // ✅ AJOUTÉ (14 août 2026, demande Paul — trouvé par log réel : la
    // séquence tournait DEUX FOIS coup sur coup, 09:50:16.741 puis
    // 09:50:18.771, déclenchée par deux événements d'activation du tab
    // distincts (resolveIfActiveMatches + onConfigureMediaActivated). Même
    // catégorie de doublon que ceux réglés ailleurs aujourd'hui — un simple
    // flag empêche une deuxième exécution de partir tant que la première
    // n'est pas terminée.
    private final java.util.concurrent.atomic.AtomicBoolean initSequenceRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private void runInitSequence() {
        if (!initSequenceRunning.compareAndSet(false, true)) {
            android.util.Log.i("InitSeq", "runInitSequence: déjà en cours, appel ignoré");
            return;
        }
        safeBg(() -> {
          try {
            // 1) REGISTRE — connecté, accessible. Dépendance : rien après ne
            // doit avancer si ça échoue (pas de allowDegraded).
            boolean registreOk = runSectionWithRetry(InitSection.REGISTRE, 1, false, () -> {
                if (controller == null) return false;
                controller.requestStatus();
                return controller.getState() == DeliveryState.CONNECTED
                        || controller.getState() == DeliveryState.RUNNING_FLOWING
                        || controller.getState() == DeliveryState.RUNNING_PAUSED;
            });
            if (!registreOk) return; // dépendance dure : le reste ne peut pas continuer

            // 2) PRODUIT — scan, puis VALIDATION contre le deep link (s'il y
            // en a un), sinon défaut = produit 1 (index 0). Dégradé possible
            // : si la validation échoue 3 fois, on utilise le défaut et on
            // continue — ne bloque jamais l'armement à lui seul.
            runSectionWithRetry(InitSection.PRODUIT, 2, true, () -> {
                autoScanProduitsSiNecessaire();
                String serial = serialFromArgs;
                String produitDeepLink = (getArguments() != null) ? getArguments().getString("produit") : null;
                com.pa.lcr.lcp.storage.RegisterProductStore.Row resolved = null;
                if (serial != null && !serial.trim().isEmpty()) {
                    com.pa.lcr.lcp.storage.RegisterProductStore store =
                            new com.pa.lcr.lcp.storage.RegisterProductStore(requireContext());
                    try {
                        resolved = store.resolveProduct(serial, null, produitDeepLink);
                        // ✅ FIX (14 août 2026, demande Paul — "le scan produit
                        // est important") — resolveProduct() ne fait qu'une
                        // comparaison de texte exacte (insensible à la casse,
                        // déjà le cas via norm()) entre le nom du deep link et
                        // la description stockée. Si la description réelle du
                        // registre n'est pas littéralement "propane" (un code,
                        // un libellé différent), ça échoue même si le produit
                        // EST du propane. findPropaneNoteIdx() existe
                        // justement pour ce cas — il utilise le flag isPropane
                        // (déterminé autrement lors du scan), pas le texte.
                        // Utilisé en repli quand le nom du deep link mentionne
                        // le propane et que la correspondance textuelle a
                        // échoué.
                        if (resolved == null && produitDeepLink != null
                                && produitDeepLink.trim().toLowerCase(java.util.Locale.ROOT).contains("propane")) {
                            int propaneIdx = store.findPropaneNoteIdx(serial);
                            if (propaneIdx >= 0) resolved = store.findByNoteIdx(serial, propaneIdx);
                        }
                    } catch (Exception ignored) {
                    } finally {
                        try { store.close(); } catch (Exception ignored) {}
                    }
                }
                initValidatedProductIdx = (resolved != null) ? resolved.noteIdx : 0; // défaut produit 1
                return resolved != null; // false => tentera encore, puis dégradé (défaut déjà posé)
            });

            // 2) PRESET — VALIDÉ et affiché, pas écrit au registre ici.
            runSectionWithRetry(InitSection.PRESET, 2, true, () -> {
                String presetStr = (getArguments() != null) ? getArguments().getString("preset") : null;
                double presetL = 0.0;
                try { if (presetStr != null && !presetStr.isEmpty()) presetL = Double.parseDouble(presetStr); }
                catch (NumberFormatException ignored) {}
                initValidatedPresetL = presetL;
                final double presetLFinal = presetL;
                if (ui != null && edtPreset != null) {
                    ui.post(() -> edtPreset.setText(presetLFinal > 0
                            ? String.valueOf(presetLFinal) : edtPreset.getText().toString()));
                }
                return controller != null;
            });

            // 3) LIVE — net/gross, tickets liés, CONNECTED READY
            runSectionWithRetry(InitSection.LIVE, 3, false, () -> {
                if (controller == null) return false;
                controller.requestLiveSample();
                return true;
            });

            // 4) RETOUR_WO — WO lié au ticket_number/sale_number
            runSectionWithRetry(InitSection.RETOUR_WO, 4, false, () -> {
                rechercherWoDepuisRegistre();
                return true;
            });

            // 5) ACTION — activée seulement après affichage de LIVE
            initSectionStatus.put(InitSection.ACTION, InitSectionStatus.OK);
            initSectionLog(InitSection.ACTION, 5, "activée");
          } finally {
            initSequenceRunning.set(false);
          }
        });
    }


    private String tabTransportKey = null;
    private volatile boolean tabMediaReady = true;
    private volatile boolean pendingReconnect = false;
    private volatile String tabMediaShort = "—";
    private String serialFromArgs = null;
    private String transportFromArgs = null;
    private boolean starting = false;
    // ✅ Scan auto produits (4 août 2026, demande Paul) — évite un déclenchement
    // en boucle si l'état oscille CONNECTED/DISCONNECTED/CONNECTED rapidement.
    private volatile boolean autoProductScanInFlight = false;
    // ✅ FIX CRITIQUE (10 août 2026, demande Paul — "pourquoi Auto-scan
    // produits se déclenche deux fois") — trouvé : le drapeau ci-dessus est
    // propre à CHAQUE INSTANCE de fragment. Si deux instances existent
    // brièvement pour le même tab (le même genre de duplication qu'on a
    // chassée toute la journée — FragmentTransaction asynchrone), chacune a
    // son propre drapeau à false et lance indépendamment son propre scan.
    // Ce verrou statique, partagé entre TOUTES les instances et indexé par
    // "node:serial", garantit qu'un seul scan matériel se produit pour un
    // registre donné, peu importe combien d'instances de fragment existent
    // momentanément.
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> autoProductScanInFlightGlobal =
        new java.util.concurrent.ConcurrentHashMap<>();
    // ✅ CORRIGÉ (24 août 2026, demande Paul — même principe que
    // validationEnCours plus tôt aujourd'hui) — un simple Set qui reste
    // coincé pour toujours si un seul chemin de code oublie de le
    // retirer est un risque réel, déjà rencontré aujourd'hui ailleurs.
    // Remplacé par une Map horodatée — une entrée plus vieille que 30s est
    // traitée comme expirée, peu importe pourquoi elle n'a jamais été
    // retirée explicitement.
    private static final long AUTO_SCAN_LOCK_EXPIRATION_MS = 30_000;
    private static boolean autoScanLockAcquire(String key) {
        long now = System.currentTimeMillis();
        Long existant = autoProductScanInFlightGlobal.putIfAbsent(key, now);
        if (existant == null) return true; // acquis, personne d'autre ne le tenait
        if (now - existant > AUTO_SCAN_LOCK_EXPIRATION_MS) {
            // expiré — on force l'acquisition malgré tout
            autoProductScanInFlightGlobal.put(key, now);
            return true;
        }
        return false; // vraiment tenu par quelqu'un d'autre, récemment
    }
    private static void autoScanLockRelease(String key) {
        autoProductScanInFlightGlobal.remove(key);
    }
    // "Armé" à l'activation du tab, consommé au premier CONNECTED qui suit —
    // garantit un déclenchement unique par activation de tab, au vrai moment
    // où le registre devient CONNECTED READY (pas sur un délai fixe arbitraire).
    private volatile boolean autoScanArmedForThisActivation = false;

    // ✅ (4 août 2026) — accesseur pour DeepLinkHandler : permet d'attendre la
    // fin du scan auto produits avant de démarrer une livraison sur un tab
    // qui vient tout juste d'être créé (voir lancerLivraison()).
    public boolean isAutoProductScanBusy() {
        return autoScanArmedForThisActivation || autoProductScanInFlight;
    }
    private long startingSinceMs = 0L;
    private volatile boolean cancelInProgress = false;

    // ✅ Contexte WO stable du fragment — peuplé une fois au deep link initial,
    // jamais effacé par les flux concurrents (ActiveDeliveryStore peut être
    // écrasé/vidé par annulation, autre poll, etc. — ces champs ne le sont pas)
    private volatile String currentWoNum    = "";
    // ✅ Suivre le dernier ticket_no traité pour permettre une re-détection quand
    // le registre passe à un nouveau ticket/WO — sans ça, currentWoNum restait figé
    // sur la première valeur trouvée (deep link, bouton C annulé, ActiveDeliveryStore)
    // pour toute la durée de vie du tab, bloquant la détection de tout ticket suivant.
    private volatile String lastTicketDetected = "";
    // ✅ FIX (7 août 2026, demande Paul) — évite de reconstruire delivery_uid
    // en boucle pour le même ticket déjà résolu (voir onTicketInfo).
    private volatile String lastUidReconstructedForTicket = "";
    // ✅ FIX CRITIQUE (7 août 2026, demande Paul — "je n'ai toujours pas le
    // delivery-uid dans le tab") — trouvé le vrai bug : mon propre cache
    // ci-dessus est un champ de l'INSTANCE du fragment, pas de la vue.
    // initUi() remet txtDeliveryUid à "—" à CHAQUE recréation de vue
    // (comportement Android normal, pas juste à la création initiale). Mais
    // le cache, lui, survit à cette recréation — donc la reconstruction
    // était sautée ("déjà fait pour ce ticket"), laissant la NOUVELLE vue
    // bloquée sur "—" pour toujours, même si le calcul avait réussi
    // auparavant. Ce champ garde maintenant la VALEUR calculée, pas juste
    // un drapeau — pour pouvoir la réappliquer directement si la vue se
    // recrée, sans dépendre d'un nouvel événement onTicketInfo.
    private volatile String lastUidReconstructedValue = null;
    // ✅ FIX (7 août 2026, demande Paul — "même le ticket_number ne
    // s'affichait pas") — même bug que delivery_uid, mais touche aussi
    // ticketNo : initUi() le remet à "—" à chaque recréation de vue, et
    // rien ne le réappliquait avant le prochain onTicketInfo() (qui peut
    // tarder si la session est dans un état transitoire/fantôme au moment
    // exact de la recréation). Dernière valeur connue mise en cache ici,
    // réappliquée immédiatement dans initUi().
    private volatile String lastKnownTicketNo = null;
    // ✅ FIX (4 août 2026, demande Paul — "j'ai commencé le logcat... tout est
    // présent dans ça", en creusant pourquoi l'export Support de 300 lignes
    // ne remontait pas jusqu'à l'échec réel) — un ticket qui échoue en
    // continu (ex. pas encore synchronisé Dataverse) relançait la cascade
    // complète (DB+Dataverse+backup) à CHAQUE poll (~100-800ms), sans aucun
    // throttle — des dizaines de lignes LogBus/seconde, assez pour noyer les
    // 300 lignes de l'export Support en quelques secondes et enterrer les
    // vrais événements de diagnostic sous le bruit. Le comportement voulu
    // (retenter jusqu'au succès, ne jamais verrouiller sur un échec) est
    // conservé — seule la CADENCE des tentatives est plafonnée.
    private volatile String lastFailedTicket = "";
    private volatile long lastFailedAttemptMs = 0L;
    private static final long WO_DETECT_RETRY_COOLDOWN_MS = 3000L;
    // ✅ AJOUTÉ (11 août 2026, demande Paul — "si on l'a, on veut aussi
    // voir qu'il a été traité comme il faut, inutile de l'avoir 200 fois")
    // — le mécanisme de retry lui-même reste inchangé (légitime de
    // réessayer un ticket qui échoue), mais le LOG "recherche dans DB" ne
    // s'affiche maintenant qu'à la PREMIÈRE tentative pour ce ticket —
    // les répétitions silencieuses en arrière-plan n'ajoutent rien de
    // nouveau à l'écran tant que rien ne change.
    private volatile String dernierTicketWoDetectLogue = null;
    // ✅ (fix 31 juillet 2026) Limite les appels MSAL — checkAndPullMissingDeliveryDetail()
    // est maintenant appelée à chaque activation d'onglet (pas seulement Status(B)), ce qui
    // pouvait spammer des demandes de token concurrentes et interférer avec d'autres flux
    // MSAL (ex: le push existant). On ne retente le pull Dataverse pour un même ticket
    // qu'après un délai de repos, pas à chaque activation d'onglet.
    private volatile String lastMissingDetailAttemptTicket = "";
    private volatile long lastMissingDetailAttemptTs = 0L;
    private static final long MISSING_DETAIL_COOLDOWN_MS = 60_000L;
    // ✅ true quand currentWoNum vient d'être fixé DIRECTEMENT (deep link / bouton C
    // avec contexte Field Service) — dans ce cas, la recherche par ticket_number
    // (lookupWoForTicket) ne doit PAS l'écraser, même si le registre affiche encore
    // le ticket de la livraison précédente au moment où onTicketInfo() se déclenche.
    private volatile boolean woNumFromDirectSource = false;
    private volatile boolean deliveryNotified = false; // guard anti-doublon notification
    private volatile String currentWoIdGuid = "";

    // ✅ FIX (2026-07-29) : produit et preset du deep link mémorisés ici, et non
    // seulement poussés dans spnProduct/edtPreset. Le diagnostic de reconnexion
    // tourne en thread de fond et doit relayer ces valeurs à lancerLivraison()
    // sans lire de widget hors du thread UI.
    private volatile String currentProduit  = "";
    private volatile String currentPreset   = "";
    private static final int TAB_LOG_MAX_LINES = 400;
    private static final long LOG_REFRESH_MIN_MS = 800;
    private long lastLogRefreshMs = 0L;
    private boolean logRefreshPending = false;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    /** ✅ FIX (13 août 2026, demande Paul — inventaire complet suite au vrai
     *  crash trouvé dans DeepLinkHandler/btExec, même patron ici) — bg se
     *  ferme dans onDestroyView() (voir plus bas), et 23 usages répartis
     *  dans ce fragment pourraient recevoir un RejectedExecutionException
     *  si le Fragment est détruit pendant qu'une tâche est encore en vol
     *  (rotation d'écran, tab fermé, navigation). Enveloppe unique —
     *  capture spécifiquement cette exception, log plutôt que de planter
     *  toute l'app. */
    private void safeBg(Runnable task) {
        try {
            bg.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            android.util.Log.w("RegisterTabFragment", "safeBg: bg déjà fermé (Fragment probablement détruit "
                + "pendant une tâche en vol) — tâche abandonnée proprement au lieu de planter l'app");
        }
    }

    // ✅ (ajouté 3 août 2026, suite ratés démarrage livraison / RUNNING_FLOWING absent sur
    // BD vierge) — thread dédié SÉPARÉ de bg pour la cascade complète de recherche de ticket
    // (Dataverse + scan backup). Le fix précédent (fullTicketSearchArmed) limite la FRÉQUENCE
    // de cette cascade, mais pas sa DURÉE : sur une BD vierge, la première livraison échoue
    // garantie à l'étape 1 (rien n'existe encore) et déclenche donc systématiquement la
    // cascade complète — réseau + lecture de fichiers — qui bloquerait bg (partagé par 23
    // autres usages dans ce fragment, dont l'affichage RUNNING_FLOWING et btnRetourWO) pendant
    // toute sa durée si elle y restait. Isolée ici, elle ne peut plus jamais les retarder,
    // peu importe combien de temps elle prend.
    private final ExecutorService remoteSearchExecutor = Executors.newSingleThreadExecutor();

    private void scheduleLogRefresh() {
        if (txtLog == null) return;
        if (cbShowLog == null || !cbShowLog.isChecked()) return;
        // ✅ AJOUTÉ (12 août 2026, demande Paul — "quand j'ai le running_
        // flowing, garde le log du support en attente") — aucun
        // rafraîchissement visuel pendant l'écoulement actif, moment où le
        // tick a le plus besoin de priorité. Rien n'est perdu : les
        // événements continuent de s'accumuler normalement dans LogBus
        // (et sur disque via LogBusStore) — seul l'AFFICHAGE est différé.
        // Un rattrapage automatique se déclenche dès la sortie de
        // RUNNING_FLOWING (voir onStateChanged plus bas).
        try {
            if (controller != null && controller.getState() == DeliveryState.RUNNING_FLOWING) {
                logRefreshDeferredByFlowing = true;
                return;
            }
        } catch (Exception ignored) {}
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
                // ✅ AJOUTÉ (12 août 2026) — rattrapage : si des événements se
                // sont accumulés pendant RUNNING_FLOWING sans être affichés
                // (log mis en attente exprès, voir scheduleLogRefresh), et
                // qu'on vient de SORTIR de cet état, forcer un vrai
                // rafraîchissement maintenant — rien n'est perdu, juste
                // rattrapé au bon moment.
                if (state != DeliveryState.RUNNING_FLOWING && logRefreshDeferredByFlowing) {
                    logRefreshDeferredByFlowing = false;
                    refreshLogView();
                }

                // ✅ FIX (LE chaînon manquant) : DeliveryState.DISCONNECTED n'était
                // JAMAIS traité spécifiquement ici — même après l'escalade
                // automatique (timeouts LCP répétés → shutdown(true) →
                // DISCONNECTED), rien ne réagissait pour lancer le diagnostic.
                // Le chauffeur devait deviner qu'il fallait cliquer un bouton,
                // alors que ça devrait se déclencher tout seul. Maintenant :
                // dès que le controller déclare DISCONNECTED, le diagnostic se
                // lance automatiquement, sans attendre un clic.
                if (state == DeliveryState.DISCONNECTED) {
                    tabMediaReady = false;
                    android.util.Log.w("RegisterTabFragment", "onStateChanged: DISCONNECTED reçu — déclenchement automatique du diagnostic");
                    surErreurConnexion(
                        new java.io.IOException("Controller a déclaré DISCONNECTED (timeouts LCP répétés)"),
                        "AUTO_DISCONNECTED");
                }

                // ✅ CONNECTED post-livraison — forcer un refresh après 2s
                // pour laisser le temps à bg.execute (DB read pour btnRetourWO) de retourner
                if (state == DeliveryState.CONNECTED) {
                    ui.postDelayed(() -> {
                        if (!isAdded() || getView() == null || controller == null) return;
                        updateButtons(controller.getState());
                    }, 2000);
                }
                // Lire le vrai état ticket pending depuis le registre
                if (state == DeliveryState.CONNECTED) {
                    ui.postDelayed(() -> refreshDelCodeFromTickSnapshotThrottled(), 500);
                }


                // ✅ Si pas de WO préfillé — chercher via ticket_no du registre
                // ✅ FIX (11 août 2026, demande Paul — "si j'ai tout
                // l'information [dans la validation] c'est que tu as merdé
                // qq part dans l'apk") — trouvé : cette méthode fait une
                // VRAIE communication LCP (api_registerValidate) à 800ms
                // après CONNECTED — bien avant que le sondage automatique
                // n'ait fini de se stabiliser, et bien avant mon délai de
                // 3000ms sur Auto-scan. Un déclencheur de collision de plus
                // que je n'avais pas fermé. Décalé à 3500ms (après
                // Auto-scan) pour étaler les déclencheurs plutôt que de les
                // faire tous converger vers le même instant.
                if (state == DeliveryState.CONNECTED
                        && (currentWoNum == null || currentWoNum.isEmpty())) {
                    ui.postDelayed(() -> rechercherWoDepuisRegistre(), 3500);
                }

                // ✅ Scan auto produits (4 août 2026, demande Paul : "après
                // connected ready") — se déclenche uniquement si armé par
                // onTabActivated() (une activation de tab = un déclenchement
                // max). "Consommé" immédiatement pour qu'une reconnexion
                // CONNECTED ultérieure dans la même activation ne relance rien.
                // ✅ FIX (11 août 2026, demande Paul — même principe qu'avant,
                // délai ajusté) — 300ms était insuffisant en pratique : le
                // sondage live démarre dès CONNECTED mais n'a pas fini de se
                // stabiliser à 300ms, causant une collision confirmée par
                // logcat (rc=0x26 en cascade, risque réel d'escalade vers une
                // déconnexion forcée via le compteur liveSoftSkip séparé).
                // 3000ms laisse largement le temps aux premiers cycles de
                // sondage live de se stabiliser avant que le scan ne
                // commence à se disputer le registre.
                if (state == DeliveryState.CONNECTED && autoScanArmedForThisActivation) {
                    autoScanArmedForThisActivation = false;
                    ui.postDelayed(RegisterTabFragment.this::autoScanProduitsSiNecessaire, 3000);
                }

                // ✅ Retour Field Service quand livraison terminée
                if (state == DeliveryState.ENDED) {
                    if (cancelInProgress) {
                        // Annulation — ne pas retourner dans FSM
                        // Remettre l'UI à zéro pour permettre une nouvelle livraison
                        cancelInProgress = false;
                        if (txtLive    != null) txtLive.setText("LIVE: CONNECTED — prêt pour nouvelle livraison");
                        if (txtQtyNet  != null) txtQtyNet.setText("NET: 0.0");
                        if (txtQtyGross != null) txtQtyGross.setText("GROSS: 0.0");
                        if (txtTicketNo != null) txtTicketNo.setText("Ticket Number : —");
                        if (txtDeliveryUid != null) txtDeliveryUid.setText("Delivery UID : —");
                    } else {
                        notifyDeliveryEndedToMainActivity();
                        rafraichirCumulWo();
                        // ✅ Forcer updateButtons même si notifyDeliveryEndedToMainActivity
                        // a été bloqué par deliveryNotified — le bouton retour doit toujours apparaître
                        ui.postDelayed(() -> {
                            if (!isAdded() || getView() == null || controller == null) return;
                            updateButtons(controller.getState());
                        }, 500);
                    }
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
                // ✅ AJOUTÉ (14 août 2026, demande Paul — "valider les
                // connexions au média... sonder les autres transports") —
                // sur une vraie déconnexion (shutdown réel du controller,
                // pas juste un changement d'état normal), déclenche le
                // chien de garde côté MainActivity.
                if (liveText != null && liveText.contains("DISCONNECTED")) {
                    try {
                        MainActivity main = (MainActivity) getActivity();
                        if (main != null && serialFromArgs != null && controller != null) {
                            main.probeKnownTransportsForLostRegister(
                                    serialFromArgs, getNodeFromArgs(), tabTransportKey);
                        }
                    } catch (Exception ignored) {}
                }
                // ✅ AJOUTÉ (14 août 2026, demande Paul — "il faut voir qu'il
                // y a un démarrage de livraison quand on arrive à
                // running_flowing"). Bref réaffichage, seulement sur la
                // transition (pas à chaque tick — lastLiveText le garantit).
                boolean venaitDeDemarrer = liveText != null && liveText.contains("RUNNING_FLOWING")
                        && (lastLiveText == null || !lastLiveText.contains("RUNNING_FLOWING"));
                if (venaitDeDemarrer) {
                    showInitGuide("Livraison démarrée...");
                    fadeOutInitGuide();
                }
                lastLiveText = liveText;
                if (txtLive != null) txtLive.setText(liveText);
                ensureSerialVisibleThrottled();
                refreshDelCodeFromTickSnapshotThrottled();
                updateButtons(controller != null ? controller.getState() : null);
                scheduleLogRefresh();
            });
        }

        @Override
        public void onDeliveryFinished(String serialId, String ticketNo, String saleNo,
                                         double netL, double grossL) {
            // ✅ (ajouté 3 août 2026, demande Paul : "on est supposé avoir un backup
            // automatique!!") — déclenchement GARANTI à la vraie fin de livraison,
            // peu importe si btnRetourWO existe/est visible/est cliqué. Réutilise
            // retournerAuWorkOrder() telle quelle (déjà validée) plutôt que de dupliquer
            // sa logique d'assemblage (WO/preset/produit/taxes) — évite toute divergence.
            // Idempotent : retournerAuWorkOrder() fait un UPDATE (pas un doublon INSERT)
            // si la ligne existe déjà pour ce ticket, donc un clic manuel ultérieur sur
            // le même ticket reste sans danger.
            LogBus.api(node, "[AUTO-BACKUP] DELIVERY_DONE ticket=" + ticketNo
                + " — déclenchement automatique retournerAuWorkOrder()");

            // ✅ FIX (4 août 2026, demande Paul — "le n'importe quoi est le 6.9
            // deux fois, un avec delivery-uid=null et un avec 10913") — ce
            // callback se déclenche maintenant réellement (depuis le fix
            // MuxListener de la même session — avant, il ne se déclenchait
            // JAMAIS, donc cette course n'existait pas). Il ignorait
            // complètement ticketNo/netL/grossL déjà capturés au vrai instant
            // de fin de livraison, et appelait retournerAuWorkOrder() qui
            // redérive tout via son propre cache (api_tickSnapshot()) —
            // parfois AVANT que le registre ait fini de mettre à jour son
            // ticket_no interne. Résultat observé : une première insertion
            // avec ticket_no='' (échappe à UNIQUE(wo_num,ticket_no), donc pas
            // dédupliquée), suivie ~87s plus tard d'une deuxième insertion
            // correcte via le poll FSM existant. Ici : on a DÉJÀ ticketNo
            // fiable (capturé au vrai moment de fin) — on attend que le cache
            // du controller le reflète aussi (bornée, best-effort) avant
            // d'appeler retournerAuWorkOrder(), pour que SA propre lecture
            // cache ne tombe plus sur un ticket_no vide.
            safeBg(() -> {
                if (ticketNo != null && !ticketNo.trim().isEmpty() && controller != null) {
                    for (int i = 0; i < 6; i++) {
                        try {
                            com.pa.lcr.lcp.ApiResult snap = controller.api_tickSnapshot();
                            String cachedTicket = null;
                            if (snap != null && snap.data != null) {
                                org.json.JSONObject result = snap.data.optJSONObject("result");
                                cachedTicket = (result != null) ? result.optString("ticket_no", "") : null;
                                if (cachedTicket == null || cachedTicket.isEmpty()) {
                                    org.json.JSONObject tick = snap.data.optJSONObject("tick");
                                    if (tick != null) cachedTicket = tick.optString("ticket_no", "");
                                }
                            }
                            if (ticketNo.equals(cachedTicket)) break; // cache à jour — sûr d'appeler maintenant
                        } catch (Exception ignored) {}
                        try { Thread.sleep(300); } catch (Exception ignored) {}
                    }
                }
                try { ui.post(RegisterTabFragment.this::retournerAuWorkOrder); }
                catch (Exception e) { LogBus.api(node, "[AUTO-BACKUP] ERR: " + safeMsg(e)); }
            });
        }

        @Override
        public void onTicketInfo(String ticketNo, String deliveryUid) {
            // ✅ FIX (7 août 2026) — mémorise la dernière valeur connue, pour
            // pouvoir la réappliquer si la vue se recrée avant le prochain push.
            if (ticketNo != null && !ticketNo.trim().isEmpty()) lastKnownTicketNo = ticketNo;
            ui.post(() -> {
                if (!isAdded() || getView() == null) return;
                if (txtTicketNo != null) txtTicketNo.setText("Ticket Number : " + (ticketNo == null ? "—" : ticketNo));
                if (txtDeliveryUid != null) txtDeliveryUid.setText("Delivery UID : " + (deliveryUid == null ? "—" : deliveryUid));
                ensureSerialVisibleThrottled();
                refreshDelCodeFromTickSnapshotThrottled();
                updateButtons(controller != null ? controller.getState() : null);
            });
            // ✅ onTicketInfo est un push déjà fiable (via requestStatus()) — brancher
            // la recherche WO/cumul ici aussi, en plus du polling explicite de
            // rechercherWoDepuisRegistre(), pour une détection plus robuste.
            if (ticketNo != null && !ticketNo.isEmpty()) {
                // ✅ (ajouté 3 août 2026) — consommer le flag une seule fois : si ce
                // onTicketInfo() suit un requestStatus() explicite (tab-entry/Status(B)),
                // autoriser la recherche complète pour CET appel seulement; sinon (push
                // ambiant du polling live), rester local-only.
                final boolean allowFull = fullTicketSearchArmed;
                fullTicketSearchArmed = false;
                // ✅ La cascade complète (allowFull=true) tourne sur remoteSearchExecutor,
                // JAMAIS sur bg — voir commentaire sur le champ remoteSearchExecutor.
                // Le cas local-only (allowFull=false, largement plus fréquent) reste sur
                // bg, rapide et sans risque de blocage.
                java.util.concurrent.ExecutorService target = allowFull ? remoteSearchExecutor : bg;
                target.execute(() -> {
                    try { lookupWoForTicket(ticketNo, allowFull); }
                    catch (Exception e) { LogBus.api(node, "[WO-DETECT] ERR (onTicketInfo): " + safeMsg(e)); }
                    // ✅ FIX (2026-07-29) : repli sur la BD locale quand delivery_uid
                    // arrive null.
                    //
                    // delivery_uid n'est PAS lu du registre ni stocké en colonne : il est
                    // calculé par DeliveryController comme
                    //     lastNumeroLivraison + "-" + ticketNo
                    // où lastNumeroLivraison (= le numéro de WO) est un champ volatile
                    // en mémoire, assigné uniquement dans api_deliveryOneShotStart().
                    // Dès que le controller est recréé — ce que fait toute reconnexion
                    // BT/USB après diagnostic — ce champ repart à null et l'UI affichait
                    // "Delivery UID : —" alors que le ticket_no, lui, survivait
                    // (il vient du registre via readTicketNo23()).
                    //
                    // La donnée est pourtant entièrement reconstructible : la BD locale
                    // associe déjà ticket_no → wo_num. On refait donc le même calcul.
                    if (deliveryUid != null && !deliveryUid.trim().isEmpty()) return;
                    // ✅ FIX (7 août 2026, demande Paul — "le tab a vu le
                    // registre mais n'a pas terminé sa validation") — trouvé
                    // en creusant : ce bloc de repli se relance à CHAQUE
                    // requestStatus() (toutes les 5s au minimum, via le
                    // keep-alive), pour toujours le MÊME ticket déjà terminé
                    // — puisque lastNumeroLivraison ne se repeuple jamais
                    // pour un ticket qui n'est pas UNE NOUVELLE livraison en
                    // cours. Avant ce fix : re-frappe la BD SQLite depuis le
                    // disque à chaque cycle, indéfiniment, pour un résultat
                    // qui ne changera jamais. Mis en cache une fois résolu —
                    // ne relance la reconstruction que si le ticket change.
                    if (ticketNo.equals(lastUidReconstructedForTicket) && lastUidReconstructedValue != null) {
                        // ✅ Déjà calculé pour ce ticket — pas besoin de refaire le calcul,
                        // mais on réapplique quand même la valeur à la vue actuelle, au cas
                        // où celle-ci vient d'être recréée et affiche encore "—".
                        final String fCachedUid = lastUidReconstructedValue;
                        ui.post(() -> {
                            if (!isAdded() || getView() == null) return;
                            if (txtDeliveryUid != null) txtDeliveryUid.setText("Delivery UID : " + fCachedUid);
                        });
                        return;
                    }
                    String uidRecupere = null;
                    String origine = "";
                    com.pa.lcr.lcp.storage.LcrDeliveryStatusDb dbUid = null;
                    try {
                        dbUid = new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(requireContext());
                        com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow row =
                            dbUid.getByTicketNo(ticketNo);
                        if (row != null && row.woNum != null && !row.woNum.trim().isEmpty()) {
                            uidRecupere = row.woNum.trim() + "-" + ticketNo.trim();
                            origine = "BD locale";
                        }
                    } catch (Exception e) {
                        LogBus.api(node, "[UID] repli BD ERR: " + safeMsg(e));
                    } finally {
                        // ✅ Toute connexion SQLite fermée en finally (règle projet —
                        // voir les fuites corrigées le 24 juillet dans ce fichier).
                        if (dbUid != null) { try { dbUid.close(); } catch (Exception ignored) {} }
                    }
                    // ✅ FIX (7 août 2026, demande Paul — "mets-le en
                    // deuxième si la BD est vierge") — repositionné en 2e
                    // position (juste après la recherche par ticket exact,
                    // avant les replis suivants qui dépendent tous de la BD
                    // locale — inutiles si elle est vraiment vide). Les
                    // fichiers filgo_livraison_*.json SURVIVENT à une BD
                    // vierge (réinstallation, BD corrompue) — c'est leur
                    // raison d'être — donc les vérifier tôt évite d'attendre
                    // deux tentatives DB vouées à l'échec sur ce scénario.
                    if (uidRecupere == null && serialFromArgs != null && !serialFromArgs.trim().isEmpty()) {
                        try {
                            String woFromJson = com.pa.lcr.lcp.storage.LocalDeliveryBackup
                                    .findMostRecentWoForSerialFromJsonBackups(requireContext(), serialFromArgs.trim());
                            if (woFromJson != null && !woFromJson.trim().isEmpty()) {
                                uidRecupere = woFromJson.trim() + "-" + ticketNo.trim();
                                origine = "backup JSON (Téléchargements)";
                            }
                        } catch (Exception e) {
                            LogBus.api(node, "[UID] repli backup JSON ERR: " + safeMsg(e));
                        }
                    }
                    // Ensuite : le WO courant en mémoire. Moins fiable que la
                    // BD (aucune garantie que currentWoNum corresponde à CE ticket —
                    // il peut encore pointer la livraison précédente), donc utilisé
                    // seulement si rien trouvé jusqu'ici, et journalisé distinctement.
                    if (uidRecupere == null) {
                        String wo = currentWoNum;
                        if (wo != null && !wo.trim().isEmpty()) {
                            uidRecupere = wo.trim() + "-" + ticketNo.trim();
                            origine = "WO courant (mémoire)";
                        }
                    }
                    // ✅ FIX (7 août 2026, demande Paul — "27 livraisons dans
                    // l'écran Livraisons, WO correct, mais rien affiché ici,
                    // pourquoi?") — trouvé : les replis précédents cherchent
                    // soit CE ticket exact (échoue si jamais retourné au WO),
                    // soit currentWoNum en mémoire (vide après une reconnexion —
                    // voir "currentWoNum vide" dans les logs). Ce repli-ci
                    // regarde "la dernière livraison connue pour CE #série" dans
                    // la BD — le même filet de sécurité déjà utilisé par le
                    // bouton "Retour au WO" (getLastDeliveryForSerial), en
                    // dernier recours ici puisqu'il ne sert à rien sur une BD
                    // vraiment vierge (déjà couvert par le repli JSON ci-dessus).
                    if (uidRecupere == null && serialFromArgs != null && !serialFromArgs.trim().isEmpty()) {
                        com.pa.lcr.lcp.storage.LcrDeliveryStatusDb dbFallback2 = null;
                        try {
                            dbFallback2 = new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(requireContext());
                            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow lastRow =
                                    dbFallback2.getLastDeliveryForSerial(serialFromArgs.trim());
                            if (lastRow != null && lastRow.woNum != null && !lastRow.woNum.trim().isEmpty()) {
                                uidRecupere = lastRow.woNum.trim() + "-" + ticketNo.trim();
                                origine = "dernière livraison connue pour ce #série (BD)";
                            }
                        } catch (Exception e) {
                            LogBus.api(node, "[UID] repli dernière livraison BD ERR: " + safeMsg(e));
                        } finally {
                            if (dbFallback2 != null) { try { dbFallback2.close(); } catch (Exception ignored) {} }
                        }
                    }
                    if (uidRecupere == null) {
                        LogBus.api(node, "[UID] aucun WO trouvé pour ticket=" + ticketNo
                                + " — delivery_uid reste vide");
                        return;
                    }
                    final String fUid = uidRecupere;
                    final String fOrigine = origine;
                    lastUidReconstructedForTicket = ticketNo;
                    lastUidReconstructedValue = fUid;
                    LogBus.api(node, "[UID] delivery_uid reconstruit=" + fUid + " (" + fOrigine + ")");
                    ui.post(() -> {
                        if (!isAdded() || getView() == null) return;
                        if (txtDeliveryUid != null)
                            txtDeliveryUid.setText("Delivery UID : " + fUid);
                    });
                });
            }
        }

        @Override
        public void onDiagnosticReset(String woNum, double netBeforeL, double grossBeforeL) {
            // ✅ Réintégré (24 juillet 2026) — confirmé par Paul : correction
            // automatique sur détection du bit 0x0040 (retours de pulseur) doit
            // être persistée localement ET synchronisée vers Dataverse.
            // net_l/gross_l = 0 volontairement (aucune livraison réelle n'a eu lieu
            // — ne doit JAMAIS s'additionner au total cumulé du WO dans
            // rafraichirCumulWo()). Les vraies valeurs résiduelles vont dans
            // delta_net_l/delta_gross_l, à des fins d'audit uniquement.
            safeBg(() -> {
                try {
                    android.content.Context ctx = getContext();
                    if (ctx == null) return;
                    String fWoNum = (woNum != null && !woNum.isEmpty()) ? woNum : "MAINTENANCE";
                    String ticketNoForUid = lastTicketDetected != null ? lastTicketDetected : "";
                    String deliveryUid = fWoNum + "-RESET-" + System.currentTimeMillis();

                    com.pa.lcr.lcp.storage.LcrDeliveryStatusDb db =
                        new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(ctx);
                    try {
                        android.content.ContentValues cv = new android.content.ContentValues();
                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_WO_NUM, fWoNum);
                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SERIAL_ID,
                            serialFromArgs != null ? serialFromArgs : "");
                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_LCRNODE, node);
                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_TICKET_NO, ticketNoForUid);
                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_TYPE, "DIAGNOSTIC_RESET");
                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SOURCE, "REGISTRE");
                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_NET_L,   0.0);
                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_GROSS_L, 0.0);
                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_DELTA_NET_L,   netBeforeL);
                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_DELTA_GROSS_L, grossBeforeL);
                        long id = db.insertDelivery(cv);
                        LogBus.api(node, "[DIAGNOSTIC-RESET] persisté id=" + id
                            + " wo=" + fWoNum + " netAvant=" + netBeforeL + " grossAvant=" + grossBeforeL);
                    } finally {
                        try { db.close(); } catch (Exception ignored) {}
                    }

                    // ✅ Mettre en file pour Dataverse — même mécanisme que les
                    // livraisons normales (DeliverySyncWorker/WorkOrderUpdater).
                    // ✅ FIX : marqué "consolidated" — sinon DeliverySyncWorker
                    // traiterait cet item avec patchSummary() (écrase tout le champ
                    // avec net=0/gross=0, puisque ceci n'est qu'un enregistrement de
                    // reset, pas une vraie livraison), effaçant l'historique complet
                    // du WO au lieu de le préserver.
                    org.json.JSONObject payload = new org.json.JSONObject();
                    payload.put("consolidated", true);
                    payload.put("workOrderId", currentWoIdGuid != null ? currentWoIdGuid : "");
                    payload.put("woNum", fWoNum);
                    payload.put("deliveryUid", deliveryUid);
                    payload.put("diagnosticReset", true);
                    payload.put("preDeliveryNet", netBeforeL);
                    payload.put("preDeliveryGross", grossBeforeL);

                    com.pa.lcrdemo.dataverse.DeliveryResultQueueDb queueDb =
                        new com.pa.lcrdemo.dataverse.DeliveryResultQueueDb(ctx);
                    try {
                        queueDb.upsertPending(deliveryUid, payload.toString());
                    } finally {
                        try { queueDb.close(); } catch (Exception ignored) {}
                    }
                    com.pa.lcrdemo.dataverse.DeliverySyncScheduler.triggerNow(ctx);
                } catch (Exception e) {
                    LogBus.api(node, "[DIAGNOSTIC-RESET] ERR: " + safeMsg(e));
                }
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
                    if (tabTransportKey != null && (tabTransportKey.toUpperCase(Locale.ROOT).startsWith("BT:")
                            || tabTransportKey.toUpperCase(Locale.ROOT).startsWith("TCP:"))) {
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
                tabMediaShort = up.startsWith("BT:") ? "BT" : (up.startsWith("USB") ? "USB" : (up.startsWith("TCP:") ? "TCP" : tabMediaShort));
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            requireContext().registerReceiver(usbStateReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            requireContext().registerReceiver(usbStateReceiver, f);
        }
        }
    }

    @Override
    public void onStop() {
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
        // ✅ Rafraîchir le cumul WO au retour dans le tab
        ui.postDelayed(() -> rafraichirCumulWo(), 600);
        // Si pas de WO préfillé par deep link — chercher via ticket_no du registre
        ui.postDelayed(() -> {
            if (currentWoNum == null || currentWoNum.isEmpty()) {
                rechercherWoDepuisRegistre();
            }
        }, 1200);

        // ✅ Si controller existe et io mort → diagnostic au moment où on arrive dans le tab
        ui.postDelayed(() -> {
            if (!isAdded() || getView() == null) return;
        }, 500);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // ✅ FIX CRITIQUE (6 août 2026, demande Paul — "regarde ce log et
        // dis-moi ce qui cloche" — chaque ligne apparaissait dupliquée,
        // certaines 4 fois au même timestamp) — trouvé : onDestroyView()
        // (le vrai cycle de vie Android, déclenché à chaque recréation de
        // vue — rotation, transaction de fragment, etc.) ne détachait
        // JAMAIS uiListener du MuxListener partagé dans
        // RegisterSessionManager. detachUiListenerSafe() existait déjà à 3
        // autres endroits (bouton retour, erreur de connexion, etc.) mais
        // pas ici — le plus fondamental. Chaque recréation de vue ajoutait
        // donc un NOUVEAU listener sans jamais retirer l'ancien, qui
        // continuait de recevoir chaque événement — d'où la duplication qui
        // s'accumulait au fil du temps (2x, puis 4x, etc.).
        try { detachUiListenerSafe(); } catch (Exception ignored) {}
        try { ui.removeCallbacksAndMessages(null); } catch (Exception ignored) {}
        try { bg.shutdownNow(); } catch (Exception ignored) {}
        try { remoteSearchExecutor.shutdownNow(); } catch (Exception ignored) {}
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
        // ✅ CORRIGÉ POUR DE BON (14 août 2026) — deux tentatives précédentes
        // ont toutes les deux planté avec "ScrollView can host only one
        // direct child", à des profondeurs différentes dans la hiérarchie —
        // deviner la structure interne du layout n'est pas fiable. Approche
        // robuste : n'y touche plus DU TOUT. On enveloppe le contenu
        // original (v, peu importe sa structure interne) dans un NOUVEAU
        // FrameLayout créé ici, et le guide devient un simple enfant
        // supplémentaire de CE wrapper — jamais inséré dans le ScrollView
        // lui-même. Garanti de ne jamais entrer en conflit avec la règle
        // "un seul enfant direct" d'un ScrollView, peu importe ce qu'il y a
        // dedans.
        android.widget.FrameLayout wrapper = new android.widget.FrameLayout(requireContext());
        wrapper.addView(v, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        txtInitGuide = new android.widget.TextView(requireContext());
        txtInitGuide.setPadding(24, 16, 24, 16);
        txtInitGuide.setTextColor(0xFFFFFFFF);
        txtInitGuide.setBackgroundColor(0xCC024C3D); // vert Filgo, semi-transparent
        txtInitGuide.setTextSize(14f);
        txtInitGuide.setVisibility(View.GONE);
        txtInitGuide.setAlpha(0f);
        android.widget.FrameLayout.LayoutParams guideParams = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        guideParams.gravity = android.view.Gravity.TOP;
        wrapper.addView(txtInitGuide, guideParams);

        return wrapper;
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
            // ✅ AJOUTÉ (24 août 2026, demande Paul — "si je fais un
            // changement dans la section Produits et Preset je le veux
            // dans le log du tab et dans le log support") — aucun
            // écouteur n'existait pour capter une sélection manuelle dans
            // le menu déroulant. LogBus.api() alimente déjà les deux à la
            // fois (panneau local du tab + Support), un seul appel
            // suffit — même pattern que partout ailleurs dans ce fichier.
            spnProduct.setOnItemClickListener((parent, view2, position, id) -> {
                String selection = parent.getItemAtPosition(position).toString();
                LogBus.api(node, "[PRODUIT-MANUEL] Sélection manuelle: " + selection);
            });
        }
        edtPreset = v.findViewById(R.id.edtPreset);
        if (edtPreset != null) {
            // ✅ AJOUTÉ (24 août 2026, demande Paul) — même besoin pour le
            // preset. TextWatcher plutôt que onFocusChange — capture le
            // changement peu importe comment le focus est perdu (bouton,
            // clavier, changement d'onglet). Ignore les changements faits
            // PROGRAMMATIQUEMENT par l'app elle-même (ex: setText() lors
            // du repli du dernier ticket) — seul un changement initié par
            // l'utilisateur (hasFocus au moment du changement) est tracé,
            // pour ne pas noyer Support de bruit à chaque auto-remplissage.
            edtPreset.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    if (edtPreset.hasFocus()) {
                        LogBus.api(node, "[PRESET-MANUEL] Modification manuelle: " + s.toString());
                    }
                }
            });
        }
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
        panelWoCumul     = v.findViewById(R.id.panelWoCumul);
        txtWoCumulNet    = v.findViewById(R.id.txtWoCumulNet);
        txtWoCumulGross  = v.findViewById(R.id.txtWoCumulGross);
        txtWoCumulCount  = v.findViewById(R.id.txtWoCumulCount);
        if (panelWoCumul != null) {
            panelWoCumul.setOnClickListener(vv -> afficherDetailLivraisonsWo());
            panelWoCumul.setVisibility(android.view.View.VISIBLE);
        }
        if (txtWoCumulNet   != null) txtWoCumulNet.setText("Total NET: 0.0 L");
        if (txtWoCumulGross != null) txtWoCumulGross.setText("Total GROSS: 0.0 L");
        if (txtWoCumulCount != null) txtWoCumulCount.setText("Aucune livraison");
        btnCustomPrint   = v.findViewById(R.id.btnCustomPrint);
        btnTestPrinter   = v.findViewById(R.id.btnTestPrinter);
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
        // ✅ FIX (7 août 2026, demande Paul — "même le ticket_number ne
        // s'affichait pas") — réapplique immédiatement les dernières valeurs
        // connues (ticket + delivery_uid) si la vue vient d'être recréée,
        // sans attendre le prochain onTicketInfo() qui peut tarder.
        if (lastKnownTicketNo != null && !lastKnownTicketNo.trim().isEmpty()) {
            if (txtTicketNo != null) txtTicketNo.setText("Ticket Number : " + lastKnownTicketNo);
        }
        if (lastUidReconstructedValue != null && !lastUidReconstructedValue.trim().isEmpty()) {
            if (txtDeliveryUid != null) txtDeliveryUid.setText("Delivery UID : " + lastUidReconstructedValue);
        }
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
        deliveryNotified = false; // reset pour la nouvelle livraison
        if (woNum != null && !woNum.isEmpty()) {
            currentWoNum = woNum;
            // ✅ Ce WO vient directement du deep link — priorité sur toute recherche
            // par ticket_number qui pourrait se déclencher ensuite (onTicketInfo peut
            // encore rapporter le ticket de la livraison PRÉCÉDENTE un court instant).
            woNumFromDirectSource = true;
            lastTicketDetected = ""; // permettre une future recherche si ce WO change de source
        }
        if (woIdGuid != null && !woIdGuid.isEmpty()) currentWoIdGuid = woIdGuid;
        if (produit != null && !produit.isEmpty()) currentProduit = produit;
        if (preset  != null && !preset.isEmpty())  currentPreset  = preset;
        if (edtPreset != null && preset != null && !preset.isEmpty())
            edtPreset.setText(preset);
        if (spnProduct != null && produit != null && !produit.isEmpty())
            spnProduct.setText(produit, false);
        if (txtDeliveryUid != null && woNum != null && !woNum.isEmpty())
            txtDeliveryUid.setText("Delivery UID : " + woNum);
        // ✅ Rafraîchir le cumul WO dès que le WO est connu
        ui.postDelayed(() -> rafraichirCumulWo(), 300);
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
        // ✅ Guard anti-doublon — une seule notification par livraison
        if (deliveryNotified) return;
        deliveryNotified = true;
        try {
            if (!(getActivity() instanceof MainActivity)) return;
            MainActivity main = (MainActivity) getActivity();

            String ticketNo = "";
            double net      = 0.0;
            double gross    = 0.0;
            String woNum    = (woNumIn    != null) ? woNumIn    : "";
            String woIdGuid = (woIdGuidIn != null) ? woIdGuidIn : "";

            // ✅ FIX (la vraie cause du bouton "Retour au WO" manquant) : net/gross
            // étaient lus en parsant le TEXTE affiché à l'écran (txtQtyNet/txtQtyGross)
            // — fragile, silencieusement à 0.0 si le format ne correspondait pas
            // exactement (locale, arrondi, etc.). Comme l'insertion en base plus
            // bas exige "fNet > 0" pour s'exécuter, un simple échec de parsing
            // faisait sauter l'écriture du ticket ENTIÈREMENT, empêchant à jamais
            // "Retour au WO" d'apparaître — même pour une livraison parfaitement
            // valide. On lit maintenant les valeurs RÉELLES depuis l'API du
            // contrôleur (net_l/gross_l), le parsing texte ne sert plus que de
            // filet de secours si l'API échoue.
            org.json.JSONObject snapDataEarly = null;
            try {
                if (controller != null) {
                    com.pa.lcr.lcp.ApiResult snapEarly = controller.api_tickSnapshot();
                    if (snapEarly != null && snapEarly.data != null) snapDataEarly = snapEarly.data;
                }
            } catch (Exception ignored) {}

            if (snapDataEarly != null) {
                net   = snapDataEarly.optDouble("net_l", -1);
                gross = snapDataEarly.optDouble("gross_l", -1);
                ticketNo = snapDataEarly.optString("ticket_no", "");
            }

            try {
                if ((net < 0) && txtQtyNet != null)
                    net = Double.parseDouble(
                        txtQtyNet.getText().toString()
                                 .replace("NET: ", "").trim());
                if ((gross < 0) && txtQtyGross != null)
                    gross = Double.parseDouble(
                        txtQtyGross.getText().toString()
                                   .replace("GROSS: ", "").trim());
                if (ticketNo.isEmpty() && txtTicketNo != null)
                    ticketNo = txtTicketNo.getText().toString()
                                   .replace("Ticket Number : ", "").trim();
            } catch (Exception ignored) {}
            if (net < 0) net = 0.0;
            if (gross < 0) gross = 0.0;

            org.json.JSONObject extra = new org.json.JSONObject();
            try {
                if (controller != null) {
                    com.pa.lcr.lcp.ApiResult snap = controller.api_tickSnapshot();
                    if (snap != null && snap.data != null) {
                        extra = snap.data;
                    }
                }
            } catch (Exception ignored) {}

            // ✅ Construire la structure que DeepLinkHandler.onDeliveryEnded() attend
            // Il lit result.fs_net_l / result.fs_gross_l / result.ticket_no
            try {
                org.json.JSONObject result = new org.json.JSONObject();
                result.put("fs_net_l",   net);
                result.put("fs_gross_l", gross);
                result.put("ticket_no",  ticketNo);
                result.put("net",        net);
                result.put("gross",      gross);
                extra.put("result",    result);
                extra.put("ticketNo",  ticketNo);
                extra.put("netL",      net);
                extra.put("grossL",    gross);
            } catch (Exception ignored) {}

            main.onDeliveryEnded(woNum, woIdGuid, extra.toString());

            // ✅ Insérer dans LcrDeliveryStatusDb si pas déjà fait par DeepLinkHandler
            // Cas ticket pending — le poll a quitté sans appeler onDeliveryEnded dans DeepLinkHandler
            final String fWoNum    = woNum;
            final String fTicketNo = ticketNo;
            final double fNet      = net;
            final double fGross    = gross;
            safeBg(() -> {
                com.pa.lcr.lcp.storage.LcrDeliveryStatusDb lcrDb = null;
                try {
                    lcrDb = new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(requireContext());
                    // Vérifier si déjà inséré par DeepLinkHandler
                    com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow existing =
                        lcrDb.getLatestForWo(fWoNum);
                    boolean alreadyHasData = (existing != null
                        && existing.ticketNo != null
                        && !existing.ticketNo.isEmpty()
                        && existing.netL > 0);
                    // ✅ FIX : exiger fNet > 0 pour insérer faisait qu'une livraison
                    // avec un ticket VALIDE mais un net à 0 (ou légitimement bas)
                    // n'était JAMAIS enregistrée — bloquant "Retour au WO" pour rien.
                    // Un ticket non vide suffit à justifier l'enregistrement.
                    boolean hasTicketOrVolume = (fTicketNo != null && !fTicketNo.trim().isEmpty()) || fNet > 0;
                    if (!alreadyHasData && hasTicketOrVolume) {
                        android.content.ContentValues cv = new android.content.ContentValues();
                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_WO_NUM,    fWoNum);
                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_TICKET_NO, fTicketNo);
                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_NET_L,     fNet);
                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_GROSS_L,   fGross);
                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_TYPE,
                            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.TYPE_ORIGINAL);
                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SOURCE,    "TAB");
                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SYNC_STATUS,
                            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.SYNC_PENDING);
                        lcrDb.insertDelivery(cv);
                    }
                    // Rafraîchir le cumul WO après insertion
                    ui.post(() -> rafraichirCumulWo());
                } catch (Exception ignored) {
                } finally {
                    if (lcrDb != null) { try { lcrDb.close(); } catch (Exception ignored) {} }
                }
            });

        } catch (Exception ignored) {}
    }

    /** ✅ AJOUTÉ (7 août 2026, demande Paul — "icône à gauche du tab pour
     *  faire rafraîchir le tab") — point d'entrée public appelé depuis
     *  MainActivity quand l'utilisateur clique l'icône de rafraîchissement
     *  sur ce tab précis, sans avoir à l'ouvrir. Tracé distinctement dans
     *  Support pour qu'on sache que c'est CE clic précis qui a déclenché
     *  l'action, pas un rafraîchissement automatique. */
    public void triggerManualRefreshFromTabIcon() {
        LogBus.api(node, "[TAB-REFRESH] Rafraîchissement manuel déclenché depuis l'icône du tab");
        runStatusBLikeButton("tab_refresh_icon");
    }

    private void runStatusBLikeButton(String reason) {
        try {
            if (controller == null) return;
            // ✅ FIX CRITIQUE (6 août 2026, demande Paul — "j'ai récupéré le
            // #série, le node, mais rien d'autre, j'avais que des erreurs")
            // — confirmé par log terrain : "Status(B) ERR: Task ... rejected
            // from ThreadPoolExecutor[Terminated...]" se répétait à CHAQUE
            // cycle CONNECTED, avec le MÊME executor (jamais renouvelé,
            // "completed tasks" figé). Cause : controller (le champ de ce
            // fragment) ne se rafraîchit QUE dans connectThisRegister() —
            // mais quand RegisterSessionManager migre/recrée une session
            // (ex. [RSM-MIGRATE] BT↔USB, très fréquent sur ce camion précis
            // vu le log), rien ne prévient ce fragment que sa référence
            // controller en cache pointe maintenant vers un
            // DeliveryController déjà arrêté (executor interne Terminated).
            // Chaque appel à requestStatus() dessus échouait donc
            // immédiatement, systématiquement, peu importe combien de fois
            // on réessayait — exactement "que des erreurs". Ici : avant
            // d'utiliser controller, on vérifie qu'il est encore vivant, et
            // sinon on va chercher la session RÉELLE et à jour directement
            // auprès de RegisterSessionManager plutôt que de faire confiance
            // à la référence en cache.
            if (controller.isStopped()) {
                android.util.Log.w("RegisterTabFragment", "runStatusBLikeButton: controller en cache est ARRÊTÉ "
                    + "(executor Terminated) — rafraîchissement depuis RegisterSessionManager");
                LogBus.api(node, "[STATUS-B-REFRESH] controller en cache périmé — recherche de la session à jour");
                com.pa.lcr.lcp.DeliveryController fresh = null;
                try {
                    RegisterSessionManager smRefresh = RegisterSessionManager.get(requireContext());
                    if (tabTransportKey != null) fresh = smRefresh.getController(tabTransportKey, node);
                    if (fresh == null) {
                        // Le node a peut-être migré de transport — chercher peu importe lequel.
                        fresh = smRefresh.findTransportKeyForNode(node) != null
                            ? smRefresh.getController(smRefresh.findTransportKeyForNode(node), node) : null;
                    }
                } catch (Exception ignored) {}
                if (fresh != null && !fresh.isStopped()) {
                    controller = fresh;
                    LogBus.api(node, "[STATUS-B-REFRESH] session à jour retrouvée — controller rafraîchi");
                } else {
                    LogBus.api(node, "[STATUS-B-REFRESH] aucune session vivante trouvée — déclenchement diagnostic");
                    surErreurConnexion(
                        new java.io.IOException("runStatusBLikeButton: controller en cache mort et aucune session de remplacement"),
                        "STATUS_B_STALE_CONTROLLER");
                    return;
                }
            }
            if (!verifierIoAvantAction("STATUS_B")) return;
            try {
                if (tabTransportKey != null)
                    MediaTransportManager.get(requireContext())
                        .activateExclusive(tabTransportKey, reason != null ? reason : "STATUS_B");
            } catch (Exception ignored) {}
            try {
                // ✅ (ajouté 3 août 2026, demande Paul) — armer la recherche complète
                // (Dataverse + backup) pour le PROCHAIN onTicketInfo() déclenché par ce
                // requestStatus() explicite. Couvre à la fois l'entrée sur le tab
                // (TAB_ACTIVATED/AUTO_AFTER_TAB_CREATE/TAB_REACTIVATED) et le clic Status(B),
                // puisque tous passent par cette méthode.
                fullTicketSearchArmed = true;
                controller.requestStatus();
            } catch (Exception e) {
                LogBus.api(node, "Status(B) ERR: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                surErreurConnexion(e, "STATUS_B");
                return;
            }
            ui.postDelayed(() -> {
                // ✅ FIX (13 août 2026, demande Paul — "accélérer la
                // validation... serial, lcrnode, ticket, status,
                // ticket_pending, net, gross") — trouvé : requestLiveSample()
                // était appelée deux fois de suite ici, collée par accident
                // (copier-coller), juste après que requestStatus() (ligne
                // ~1590) ait déjà lu delStatus/delCode/net/gross. Trois
                // allers-retours quasi identiques au registre pour UNE
                // activation de tab ou UN clic Status(B) — chacun en attente
                // du même verrou partagé. Retiré le doublon.
                try { if (controller != null) controller.requestLiveSample(); } catch (Exception ignored) {}
            }, 200);

            // ⏸️ (suspendu 31 juillet 2026, pending investigation régression push Dataverse) :
            // limité au clic EXPLICITE Status(B) seulement — plus déclenché automatiquement
            // à l'arrivée sur l'onglet (TAB_ACTIVATED/AUTO_AFTER_TAB_CREATE/TAB_REACTIVATED
            // appellent aussi runStatusBLikeButton(), donc sans ce filtre sur "reason" ça
            // spammait des demandes de token MSAL à chaque activation d'onglet — suspecté
            // d'avoir interféré avec le push Dataverse existant. Réactiver une fois confirmé
            // que ce n'était pas la cause (ou corrigé autrement).
            if ("STATUS_B".equals(reason)) {
                safeBg(() -> checkAndPullMissingDeliveryDetail(reason));
            }
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
        if (btnConnect != null) btnConnect.setOnClickListener(v -> {
            // ✅ FIX (7 août 2026, demande Paul — "chaque action fait dans le
            // tab qu'on puisse le suivre dans l'onglet Support... repérer ce
            // comportement selon une fonction qu'on a cliqué") — chaque
            // bouton d'action logue maintenant un marqueur clair et distinct
            // dès le clic, avant même de savoir si l'action va réussir —
            // pour qu'un futur repérage dans Support puisse toujours dire
            // "c'est CE clic qui a déclenché ce qui suit".
            LogBus.api(node, "[ACTION-CLIC] CONNECT_LCP");
            // ✅ FIX (demandé) : Connect LCP valide le transport ET le registre
            // précis avant de conclure quoi que ce soit. Si aucune session
            // n'existe encore, on reconnecte directement (rien à valider).
            // Si une session existe, on l'éprouve avec une vraie commande
            // Status — si ça réussit, déjà connecté pour de vrai ; si ça
            // échoue, reconnexion via le diagnostic (déclenché automatiquement
            // par validerTransportEtRegistrePuis en cas d'échec).
            if (controller == null) { reconnectThisRegister(true); return; }
            validerTransportEtRegistrePuis("CONNECT_LCP", () -> {
                try { Toast.makeText(requireContext(), "✅ Déjà connecté (validé)", Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {}
                // ✅ AJOUTÉ (7 août 2026, demande Paul — "récupérer le
                // firmware du registre... dans le log du support") — lu à
                // chaque validation réussie de connexion, sur bg (jamais sur
                // le thread UI), résultat logué clairement dans Support.
                DeliveryController cFw = controller;
                if (cFw != null) {
                    safeBg(() -> {
                        String fw = null, productRev = null;
                        try { fw = cFw.getFirmwareVersion(); }
                        catch (Exception e) { LogBus.api(node, "[FIRMWARE] Lecture Field #60 ERR: " + safeMsg(e)); }
                        try { productRev = cFw.getProductIdRevision(); }
                        catch (Exception e) { LogBus.api(node, "[FIRMWARE] Lecture Get Product ID ERR: " + safeMsg(e)); }
                        String fwSafe = (fw != null && !fw.isEmpty()) ? fw : "(vide)";
                        String prSafe = (productRev != null && !productRev.isEmpty()) ? productRev : "(vide)";
                        // ✅ FIX (7 août 2026, demande Paul — "donne-moi l'info
                        // des deux pour voir s'il y a une différence, mon
                        // trouble pour P142 est peut-être lié à une version
                        // firmware") — les deux sources loguées côte à côte,
                        // avec un marqueur explicite si elles divergent, pour
                        // repérer facilement une différence entre camions.
                        boolean divergent = !fwSafe.equals("(vide)") && !prSafe.equals("(vide)")
                                && !prSafe.toUpperCase(java.util.Locale.ROOT).contains(fwSafe.toUpperCase(java.util.Locale.ROOT))
                                && !fwSafe.toUpperCase(java.util.Locale.ROOT).contains(prSafe.toUpperCase(java.util.Locale.ROOT));
                        LogBus.api(node, "[FIRMWARE] Field #60 (Software_NE) = " + fwSafe
                                + "  |  Get Product ID (nom+révision) = " + prSafe
                                + (divergent ? "  ⚠ DIVERGENTES" : ""));
                    });
                }
            });
        });
        if (btnA != null) btnA.setOnClickListener(v -> {
            LogBus.api(node, "[ACTION-CLIC] RESOLVE_A");
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
                LogBus.api(node, "[ACTION-CLIC] ANNULER");
                new android.app.AlertDialog.Builder(requireContext())
                    .setTitle("Confirmer l'annulation")
                    .setMessage("Aucun volume livré. La livraison sera annulée et le registre réinitialisé.")
                    .setPositiveButton("Annuler la livraison", (d, w) -> annulerLivraison())
                    .setNegativeButton("Continuer", null)
                    .show();
            });
        }
        if (btnB != null) btnB.setOnClickListener(v -> {
            LogBus.api(node, "[ACTION-CLIC] STATUS_B");
            if (controller == null) { reconnectThisRegister(true); return; }
            // ✅ FIX (demandé) : Status(B) valide maintenant le transport ET le
            // registre précis (node+serial) en envoyant une VRAIE commande
            // Status comme test définitif — pas juste io.isOpen() qui peut
            // mentir. Si la validation réussit, Status s'exécute normalement.
            // Si elle échoue, le diagnostic se déclenche automatiquement.
            validerTransportEtRegistrePuis("STATUS_B", () -> runStatusBLikeButton("STATUS_B"));
        });
        if (btnC != null) {
            btnC.setOnClickListener(v -> {
                LogBus.api(node, "[ACTION-CLIC] NEW_C");
                if (controller == null) return;
                // ✅ FIX (audit complet) : btnC (New) n'avait AUCUNE vérification
                // IO avant d'agir — seul bouton d'action à ne pas passer par le
                // diagnostic de reconnexion. Ajouté pour cohérence avec tous les
                // autres boutons (Resolve/Status/Continuer/Terminer/Annuler).
                if (!verifierIoAvantAction("NEW_C")) return;

                // ✅ FIX : lire edtPreset sur le thread UI AVANT de passer en
                // arrière-plan — accéder à une vue depuis bg.execute() n'est pas
                // garanti sécuritaire, peu importe si ça semblait fonctionner.
                final String presetTxtUi = edtPreset != null
                    ? edtPreset.getText().toString().trim() : "";

                // ✅ FIX : cette vérification faisait des requêtes SQLite synchrones
                // DIRECTEMENT sur le thread UI (ActiveDeliveryStore.load() +
                // LcrDeliveryStatusDb.getLatestForWo()). En cas de contention avec
                // d'autres accès DB en cours (plusieurs mécanismes de reconnexion
                // tournent en parallèle dans cette app), ce clic restait bloqué en
                // silence — bouton visuellement enfoncé, aucune réaction, comme un gel.
                safeBg(() -> {
                    boolean showDialog = false;
                    String curWoNumBg = "";
                    com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow existingBg = null;
                    double presetValBg = 0;
                    try {
                        com.pa.lcr.lcp.storage.ActiveDeliveryStore ads4 =
                            new com.pa.lcr.lcp.storage.ActiveDeliveryStore(requireContext());
                        com.pa.lcr.lcp.storage.ActiveDeliveryStore.ActiveDelivery ad4 = ads4.load();
                        curWoNumBg = (ad4 != null && ad4.woNum != null) ? ad4.woNum : "";
                        if (!curWoNumBg.isEmpty()) {
                            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb statusDb =
                                new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(requireContext());
                            try {
                                existingBg = statusDb.getLatestForWo(curWoNumBg);
                            } finally {
                                try { statusDb.close(); } catch (Exception ignored) {}
                            }
                            if (existingBg != null && existingBg.type != null
                                    && !"ANNULATION".equals(existingBg.type)) {
                                try {
                                    if (!presetTxtUi.isEmpty()) presetValBg = Double.parseDouble(presetTxtUi);
                                } catch (Exception ignored) {}
                                boolean livraisonComplete = (presetValBg <= 0 || existingBg.netL >= presetValBg);
                                showDialog = livraisonComplete && !bypassDeliveryDialog;
                            }
                        }
                    } catch (Exception ignored) {}

                    final boolean fShowDialog = showDialog;
                    final String fCurWoNum = curWoNumBg;
                    final com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow fExisting = existingBg;

                    ui.post(() -> {
                        if (!isAdded()) return;
                        if (fShowDialog && fExisting != null) {
                            new android.app.AlertDialog.Builder(requireContext())
                                .setTitle("Bon déjà complété")
                                .setMessage("Le bon " + fCurWoNum + " a déjà été livré"
                                    + " (ticket #" + fExisting.ticketNo
                                    + ", " + fExisting.netL + "L net).\n\n"
                                    + "Voulez-vous créer une nouvelle livraison sur ce même bon ?")
                                .setPositiveButton("Continuer", (d, w) -> startNewDeliveryC())
                                .setNegativeButton("Annuler", null)
                                .show();
                            return;
                        }
                        bypassDeliveryDialog = false;
                        startNewDeliveryC();
                    });
                });
            });
        }
        if (btnContinue != null) btnContinue.setOnClickListener(v -> {
            LogBus.api(node, "[ACTION-CLIC] CONTINUER");
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
            LogBus.api(node, "[ACTION-CLIC] TERMINER");
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

        // ✅ REPRINT: vérification divergence net/gross avant impression
        // Lit les compteurs via opGetField(45/44) — valeurs courantes du registre
        // Si delta >= 0.5L vs WO → bloquer reprint standard + ticket incident custom
        if (btnReprintTicket != null) {
            btnReprintTicket.setOnClickListener(v -> {
                LogBus.api(node, "[ACTION-CLIC] REIMPRIMER");
                DeliveryController c = controller;
                if (c == null) return;
                // ✅ FIX (audit complet) : Reprint n'avait AUCUNE vérification IO
                // avant d'agir. Ajouté pour cohérence.
                if (!verifierIoAvantAction("REPRINT")) return;
                safeBg(() -> {
                    c.printInProgress = true;
                    LogBus.api(node, "[REPRINT] Début");
                    try {
                        if (tabTransportKey != null) {
                            MediaTransportManager.get(requireContext())
                                    .activateExclusive(tabTransportKey, "REPRINT");
                        }

                        // Lire ticket_no AVANT reprint
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

                        // Lire net/gross du WO depuis lastResultJson
                        double netWo = -1.0, grossWo = -1.0;
                        String woNum = "", woIdGuid = "";
                        try {
                            String last = com.pa.lcrdemo.DeepLinkHandler.lastResultJson;
                            if (last != null) {
                                org.json.JSONObject j = new org.json.JSONObject(last);
                                woNum    = j.optString("wonum", "");
                                woIdGuid = j.optString("woid",  "");
                                org.json.JSONObject payload = j.optJSONObject("payload");
                                if (payload != null) {
                                    org.json.JSONObject result = payload.optJSONObject("result");
                                    if (result != null) {
                                        netWo   = result.optDouble("fs_net_l",   -1.0);
                                        grossWo = result.optDouble("fs_gross_l", -1.0);
                                    }
                                }
                            }
                        } catch (Exception ignored) {}

                        // Lire net/gross courant via opGetField(45/44) — valeurs live du registre
                        double netRegistre   = -1.0;
                        double grossRegistre = -1.0;
                        try {
                            // Field #2 GrossQty_NE + Field #3 NetQty_NE
                            // Quantites facturables selon SDK LCR-II
                            double[] hw = c.readNetGrossFromHardware();
                            netRegistre   = hw[0];
                            grossRegistre = hw[1];
                        } catch (Exception ignored) {}

                        final String fTicketNoBefore = ticketNoBefore;
                        final String fWoNum          = woNum;
                        final String fWoIdGuid       = woIdGuid;
                        final double fNetWo          = netWo;
                        final double fGrossWo        = grossWo;
                        final double fNetReg         = netRegistre;
                        final double fGrossReg       = grossRegistre;

                        boolean divergence = (netWo > 0 && netRegistre > 0
                            && Math.abs(netRegistre - netWo) >= POST_DELIVERY_LEAK_THRESHOLD_L);

                        if (divergence) {
                            double delta = netRegistre - netWo;
                            LogBus.api(node, "[REPRINT] DIVERGENCE netWO=" + netWo
                                + "L netReg=" + netRegistre + "L delta=" + delta + "L");
                            ui.post(() -> {
                                if (!isAdded() || getView() == null) return;
                                StringBuilder sb = new StringBuilder();
                                sb.append("DIVERGENCE DETECTEE\n\n");
                                sb.append("Ticket ref : ").append(fTicketNoBefore).append("\n");
                                sb.append(String.format(java.util.Locale.ROOT, "NET WO       : %.3f L\n", fNetWo));
                                sb.append(String.format(java.util.Locale.ROOT, "NET registre : %.3f L\n", fNetReg));
                                sb.append(String.format(java.util.Locale.ROOT, "DELTA        : %.3f L\n\n", fNetReg - fNetWo));
                                sb.append("Reprint standard bloque.");
                                new android.app.AlertDialog.Builder(requireContext())
                                    .setTitle("Reprint bloque - Divergence")
                                    .setMessage(sb.toString())
                                    .setCancelable(false)
                                    .setPositiveButton("Imprimer ticket incident", (d, w) ->
                                        terminerPostLivraisonAvecVolumesReels(fNetReg, fGrossReg, fTicketNoBefore))
                                    .setNegativeButton("Aviser le repartiteur", (d, w) ->
                                        logFuiteVanneDataverse(fNetWo, fNetReg, fGrossWo, fGrossReg,
                                            fTicketNoBefore, fNetReg - fNetWo))
                                    .show();
                            });
                            return;
                        }

                        // Pas de divergence — reprint standard
                        ApiResult r = c.api_ticketReprintCurrent();
                        LogBus.api(node, "[REPRINT] " + (r != null ? r.msg : "null"));

                        String ticketNoAfter = "";
                        double netL = 0, grossL = 0;
                        try {
                            Thread.sleep(500);
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

                        if (!ticketNoAfter.isEmpty() && !ticketNoAfter.equals(ticketNoBefore)) {
                            LogBus.api(node, "[REPRINT] Terminé — confirmé (ticket_no " + ticketNoBefore
                                    + " → " + ticketNoAfter + ", net=" + netL + "L gross=" + grossL + "L)");
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
                            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb lcrDb =
                                new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(requireContext());
                            try {
                                lcrDb.insertDelivery(cv);
                            } finally {
                                try { lcrDb.close(); } catch (Exception ignored) {}
                            }
                        } else {
                            LogBus.api(node, "[REPRINT] Terminé — SANS CONFIRMATION (ticket_no toujours "
                                    + ticketNoBefore + " après la tentative — l'imprimante n'a probablement pas produit de vraie sortie)");
                        }

                    } catch (Exception e) {
                        LogBus.api(node, "[REPRINT] ERR: " + safeMsg(e));
                        surErreurConnexion(e, "REPRINT");
                    } finally {
                        c.printInProgress = false;
                    }
                });
            });
        }

        // ✅ RETOUR WO: câblage du bouton Retour au Work Order (Bloc 5)
        if (btnRetourWO != null) {
            btnRetourWO.setOnClickListener(v -> { LogBus.api(node, "[ACTION-CLIC] RETOUR_WO"); retournerAuWorkOrder(); });
        }

        // ✅ Custom print — impression ligne par ligne via opPrintText
        if (btnCustomPrint != null) {
            btnCustomPrint.setOnClickListener(v -> lancerImpressionCustom());
        }

        // ✅ AJOUTÉ (14 août 2026, demande Paul — section Ticket, sous
        // Reprint/Custom Print, "l'imprimante est associée au registre") —
        // test isolé, lecture seule, sur demande explicite uniquement.
        if (btnTestPrinter != null) {
            btnTestPrinter.setOnClickListener(v -> {
                LogBus.api(node, "[ACTION-CLIC] TEST_IMPRIMANTE");
                DeliveryController c = controller;
                // ✅ CORRIGÉ (17 août 2026, demande Paul — trouvé par log
                // réel : le clic était bien enregistré mais rien ne se
                // passait ensuite, aucun log ni toast — controller était
                // null à cet instant précis (probablement en pleine
                // reconnexion, "[INIT 1/6] REGISTRE — ÉCHEC" juste avant
                // dans le même log). Retour silencieux corrigé — visible
                // maintenant dans Support et à l'écran.
                if (c == null) {
                    LogBus.api(node, "[TEST_IMPRIMANTE] annulé — controller indisponible (reconnexion probable)");
                    ui.post(() -> android.widget.Toast.makeText(requireContext(),
                            "Registre non connecté — réessaie dans un instant",
                            android.widget.Toast.LENGTH_SHORT).show());
                    return;
                }
                if (!verifierIoAvantAction("TEST_IMPRIMANTE")) return;
                btnTestPrinter.setEnabled(false);
                safeBg(() -> {
                    try {
                        com.pa.lcr.lcp.ApiResult r = c.api_testImprimante();
                        String verdict = (r != null && r.data != null)
                                ? r.data.optString("verdict", r.msg) : "Résultat inconnu";
                        ui.post(() -> {
                            android.widget.Toast.makeText(requireContext(), verdict,
                                    android.widget.Toast.LENGTH_LONG).show();
                            btnTestPrinter.setEnabled(true);
                        });
                    } catch (Exception e) {
                        LogBus.api(node, "[TEST_IMPRIMANTE] ERR: " + safeMsg(e));
                        ui.post(() -> {
                            android.widget.Toast.makeText(requireContext(),
                                    "Erreur test imprimante: " + safeMsg(e),
                                    android.widget.Toast.LENGTH_SHORT).show();
                            btnTestPrinter.setEnabled(true);
                        });
                    }
                });
            });
        }
        if (btnScanProducts != null) {
            btnScanProducts.setOnClickListener(v -> {
                LogBus.api(node, "[ACTION-CLIC] SCAN_PRODUITS");
                lancerScanProduits();
            });
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
            // ✅ FIX (11 août 2026) — n'arrête PAS la boucle elle-même,
            // seulement pendant une livraison active — sinon, une fois
            // relâchée, plus rien ne relancerait jamais la vérification
            // (voir garde ajoutée au début de checkPendingDeliveryForThisRegister()
            // qui gère déjà le "sauter le travail réel").
            ui.postDelayed(() -> checkPendingDeliveryForThisRegister(), 1500);
        });
    }

    /** Appelé depuis MainActivity dialog long press. */
    public void reconnectFromDialog() {
        // ✅ DIRECTIVE CLAIRE : Reconnect (long-press) = lance le diagnostique.
        // Point final. Aucune condition sur tabMediaReady, aucun passage par
        // reconnectThisRegister()/connectThisRegister() et leurs multiples
        // branches — ce bouton force directement le même diagnostic que
        // Status/Continuer utilisent, sans détour.
        ui.post(() -> {
            try { Toast.makeText(requireContext(), "🔄 Reconnexion — diagnostic en cours...", Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {}
            android.util.Log.i("RegisterTabFragment", "reconnectFromDialog: appel direct du diagnostic (sans condition)");
            surErreurConnexion(
                new java.io.IOException("Reconnect demandé manuellement (long-press)"),
                "RECONNECT_LONGPRESS_DIRECT");
        });
    }

    private void reconnectThisRegister(boolean userInitiated) {
        if (!tabMediaReady) {
            pendingReconnect = true;
            // ✅ FIX (bétonnage) : avant, quand le média était OFF, ce chemin ne
            // faisait RIEN d'actif — juste un drapeau posé + toast passif, en
            // attendant qu'un événement externe (onTabMediaStatusChanged) se
            // déclenche tout seul. Sur une vraie déconnexion USB/BT, cet
            // événement n'arrive pas forcément — d'où "reconnect ne change
            // rien". On déclenche maintenant activement le diagnostic (même
            // mécanisme que Status/Continuer), au lieu d'attendre passivement.
            if (userInitiated) {
                try { Toast.makeText(requireContext(), tabMediaShort + "(OFF) — reconnexion en cours…", Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {}
                android.util.Log.w("RegisterTabFragment", "reconnectThisRegister: média OFF — diagnostic actif déclenché (userInitiated)");
                surErreurConnexion(
                    new java.io.IOException("Média OFF — reconnexion demandée par l'utilisateur"),
                    "RECONNECT_LONGPRESS");
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
        // ✅ FIX CRITIQUE (12 août 2026, demande Paul — "j'ai un lag sur le
        // tick durant le running_flowing", puis "corrige-moi le
        // getOrCreate") — trouvé via logcat : getOrCreate() martelé
        // toutes les 1-1.5s PENDANT TOUTE la durée de RUNNING_FLOWING,
        // alors que le registre coule normalement (net/gross progressent
        // correctement dans le même log). Tracé jusqu'à MON PROPRE
        // correctif d'aujourd'hui (la reconnexion silencieuse ajoutée
        // pour éviter le dialogue de diagnostic intrusif) —
        // connectThisRegister() n'avait JAMAIS reçu la même garde que les
        // trois autres méthodes déjà corrigées aujourd'hui. Première
        // correction : sauté pendant RUNNING_FLOWING/RUNNING_PAUSED.
        // ✅ ÉLARGI (même jour) — un deuxième log a montré le MÊME
        // martèlement (4 appels getOrCreate) pendant PRESTART, entre
        // "ARMED" et l'envoi réel de "RUN" — la garde précédente ne
        // couvrait pas cette fenêtre puisque l'état n'est pas encore
        // RUNNING_FLOWING à ce moment. Élargi pour couvrir TOUTE la
        // fenêtre active d'une livraison (PRESTART → STARTING →
        // RUNNING_FLOWING → RUNNING_PAUSED → ENDING) — un vrai clic
        // utilisateur reste toujours traité normalement, peu importe
        // l'état.
        if (!userInitiated && controller != null) {
            DeliveryState stConnect = controller.getState();
            if (stConnect == DeliveryState.PRESTART || stConnect == DeliveryState.STARTING
                    || stConnect == DeliveryState.RUNNING_FLOWING || stConnect == DeliveryState.RUNNING_PAUSED
                    || stConnect == DeliveryState.ENDING) {
                return;
            }
        }
        // ✅ Si le controller existant est mort (shutdown après erreur BT "Error writing"
        // par exemple) — forcer la recréation au lieu de continuer à l'utiliser
        if (controller != null && controller.isStopped()) {
            LogBus.api(node, "Controller mort détecté — recréation forcée");
            controller = null;
        }
        // Si controller existe et transport prêt — pas besoin de recréer
        if (controller != null && tabMediaReady) {
            syncUiFromController();
            // 🔜 AMÉLIORATION FUTURE (13 août 2026, discutée avec Paul —
            // "continue à valider le tab") — les trois appels ci-dessous
            // (validateHeaderAsync, runStatusBLikeButton, triggerWoDetection
            // Throttled) déclenchent chacun leur propre séquence complète de
            // lecture registre, tous convergeant sur CE même instant
            // (TAB_REACTIVATED). Chacun a son propre anti-rebond individuel
            // (empêche la répétition sur activations rapprochées), mais rien
            // n'empêche les trois de partir ENSEMBLE sur une seule
            // activation — trois lectures quasi simultanées au lieu d'une
            // consolidée. Ajoutés à des moments différents du projet, chacun
            // pour une bonne raison propre — pas un doublon accidentel comme
            // ceux corrigés plus tôt aujourd'hui, donc pas fusionné ici sans
            // un vrai log qui montre l'impact réel et confirme qu'aucune
            // des trois ne dépend d'un effet de bord des deux autres.
            validateHeaderAsync();
            ui.postDelayed(() -> runStatusBLikeButton("TAB_REACTIVATED"), 250);
            triggerWoDetectionThrottled();
            return;
        }
        RegisterSessionManager sm = RegisterSessionManager.get(requireContext());
        DeliveryController dc = null;

        if (tabTransportKey != null && !tabTransportKey.trim().isEmpty()) {
            final String tkPinned = tabTransportKey.trim();
            try { MediaTransportManager.get(requireContext()).activateExclusive(tkPinned, "TAB_CONNECT"); } catch (Exception ignored) {}
            TransportIo io = null;
            try { io = MediaTransportManager.get(requireContext()).getByKey(tkPinned); } catch (Exception ignored) {}
            // ✅ FIX (4 août 2026, demande Paul — "j'arrive de fieldservice...
            // ça capote et ne trouve pas le registre... je débranche/rebranche
            // le câble, ça marche") — confirmé par logcat : ce chemin se
            // contentait d'activateExclusive(), qui ne fait que MARQUER un
            // transport déjà ouvert comme actif — il n'ouvre jamais
            // physiquement le port. Un rebranchement manuel déclenche
            // ACTION_USB_DEVICE_ATTACHED → permission → scan → ouverture
            // réelle (tout ce chemin OS). L'arrivée deep link, elle, ne
            // déclenche RIEN de tout ça si le câble était déjà branché avant
            // — donc io=null, "transport pinné fermé", abandon direct vers
            // diagnostic, sans jamais avoir tenté une vraie ouverture.
            //
            // ✅ FIX (4 août 2026, demande Paul — "c'est supposé passer par le
            // même connecteur transport") — la première version de ce fix
            // appelait api_openPingUsb()/api_btActivate() directement, ce qui
            // recréait exactement la fragmentation qu'on venait de
            // consolider. Corrigé : passe maintenant par
            // api_registerConnectAuto() — le point d'entrée unique
            // (USB→BT→TCP, verrou unique) construit pour tout le reste. Un
            // seul endroit initie une connexion, pas de chemin séparé ici.
            if (io == null || !io.isOpen()) {
                try {
                    com.pa.lcr.lcp.MultiRegisterApiFacadeImpl facadeOpen =
                        new com.pa.lcr.lcp.MultiRegisterApiFacadeImpl(requireActivity());
                    String serialForConnect = (serialFromArgs != null && !serialFromArgs.trim().isEmpty())
                        ? serialFromArgs.trim() : null;
                    facadeOpen.api_registerConnectAuto(serialForConnect, node);
                } catch (Exception ignored) {}
                // ✅ FIX (4 août 2026, demande Paul — "comment peut-il avoir un
                // USB(OFF) qui arrive avec ça") — ce tab reste pinné à
                // tabTransportKey pour toute sa durée de vie (fixé une seule
                // fois à sa création, onAttach()). Si le registre (même node+
                // #série) a été retrouvé par api_registerConnectAuto() sur un
                // AUTRE transport que celui-ci, revérifier seulement l'ancien
                // transport pinné (tkPinned) déclare le tab "OFF" à tort, même
                // si le même registre répond parfaitement ailleurs. On
                // retrouve ici le transport RÉEL actuellement utilisé pour ce
                // node et on réaligne le tab dessus.
                try {
                    String realTk = sm.findTransportKeyForNode(node);
                    if (realTk != null && !realTk.equalsIgnoreCase(tkPinned)) {
                        android.util.Log.i("RegisterTabFragment", "connectThisRegister: registre retrouvé sur "
                            + realTk + " au lieu de " + tkPinned + " — tab réaligné");
                        LogBus.api(node, "[TAB-REALIGN] " + tabMediaShort + "→" + realTk
                            + " (registre retrouvé sur un autre transport)");
                        tabTransportKey = realTk;
                        String up = realTk.toUpperCase(java.util.Locale.ROOT);
                        tabMediaShort = up.startsWith("BT:") ? "BT" : (up.startsWith("USB") ? "USB" : (up.startsWith("TCP:") ? "TCP" : tabMediaShort));
                        try { io = MediaTransportManager.get(requireContext()).getByKey(realTk); } catch (Exception ignored2) {}
                    } else {
                        try { io = MediaTransportManager.get(requireContext()).getByKey(tkPinned); } catch (Exception ignored2) {}
                    }
                } catch (Exception ignored) {
                    try { io = MediaTransportManager.get(requireContext()).getByKey(tkPinned); } catch (Exception ignored2) {}
                }
            }
            if (io == null || !io.isOpen()) {
                tabMediaReady = false;
                pendingReconnect = true;
                String msg = tabMediaShort + "(OFF) — reconnect requis";
                LogBus.api(node, msg);
                android.util.Log.w("RegisterTabFragment", "connectThisRegister: io=" + (io == null ? "null" : "fermé")
                        + " pour transport=" + tkPinned + " — déclenchement diagnostic");
                reportMediaOffToApi("CONNECT_CLICK", msg);
                ui.post(() -> {
                    if (!isAdded() || getView() == null) return;
                    if (txtLive != null) txtLive.setText("LIVE: " + tabMediaShort + "(OFF) — reconnexion en cours...");
                    updateButtons(null);
                });
                // ✅ FIX (LA vraie cause, même bug que plus bas) : ce chemin ne
                // faisait que mettre à jour un texte puis s'arrêtait — JAMAIS
                // d'appel à surErreurConnexion(), donc jamais de diagnostic
                // déclenché. C'est le chemin le PLUS fréquemment emprunté
                // (tabTransportKey est presque toujours déjà défini pour un
                // onglet existant), donc probablement la cause principale du
                // "aucune réaction" sur long-press Reconnect / Status.
                // ✅ FIX CRITIQUE (11 août 2026, demande Paul — même
                // correctif que juste en dessous : ne déclenche le vrai
                // dialogue de diagnostic que sur un vrai geste utilisateur,
                // jamais pendant un appel automatique de création/lifecycle
                // de tab.
                if (userInitiated) {
                    surErreurConnexion(
                        new java.io.IOException("connectThisRegister: transport pinné fermé — " + tkPinned),
                        "CONNECT_THIS_REGISTER_PINNED");
                } else {
                    // ✅ FIX CRITIQUE (11 août 2026, demande Paul — même
                    // correctif que plus bas : reconnexion silencieuse au
                    // lieu d'abandonner complètement.
                    android.util.Log.i("RegisterTabFragment", "connectThisRegister: transport pinné fermé "
                        + "(appel automatique, userInitiated=false) — dialogue sauté, tentative silencieuse");
                    final String tkPinnedFinal = tkPinned;
                    safeBg(() -> {
                        try {
                            com.pa.lcr.lcp.MultiRegisterApiFacadeImpl facadeAuto =
                                new com.pa.lcr.lcp.MultiRegisterApiFacadeImpl(requireActivity());
                            String serialForAuto = (serialFromArgs != null && !serialFromArgs.trim().isEmpty())
                                ? serialFromArgs.trim() : null;
                            com.pa.lcr.lcp.ApiResult r = facadeAuto.api_registerConnectAuto(serialForAuto, node);
                            android.util.Log.i("RegisterTabFragment", "connectThisRegister: reconnexion silencieuse "
                                + "(transport pinné " + tkPinnedFinal + ") résultat code="
                                + (r != null ? r.code : "null"));
                        } catch (Exception e) {
                            android.util.Log.w("RegisterTabFragment", "connectThisRegister: reconnexion silencieuse ERR: "
                                + e.getMessage());
                        }
                    });
                }
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
            android.util.Log.w("RegisterTabFragment", "connectThisRegister: dc=null pour node=" + node
                    + " tabTransportKey=" + tabTransportKey + " userInitiated=" + userInitiated
                    + " — déclenchement diagnostic");
            // ✅ FIX (LA vraie cause du "aucune réaction" sur long-press Reconnect
            // et Status) : ce chemin se contentait d'un toast discret puis
            // s'arrêtait — JAMAIS d'appel à surErreurConnexion(), donc JAMAIS
            // de diagnostic déclenché, peu importe combien de fois le chauffeur
            // cliquait. C'est une fonction séparée de verifierIoAvantAction/
            // Status(B), avec sa propre logique d'abandon silencieux jamais
            // corrigée jusqu'ici.
            // ✅ FIX (11 août 2026, demande Paul — "enlève les tous, juste
            // le garder dans l'onglet du tab, il faut laisser l'apk d'abord
            // faire son travail d'ajouter un tab s'il y a un nouveau
            // transport") — ce chemin se déclenchait aussi automatiquement
            // pendant la création normale d'un tab (pas seulement sur un
            // vrai clic utilisateur), court-circuitant le flux normal de
            // découverte (upsertRegisterTabFromScan/probe) avant même qu'il
            // ✅ FIX CRITIQUE (11 août 2026, demande Paul — "j'ai encore des
            // écrans de diagnostique, regarde dans la couche transport") —
            // confirmé par logcat : ce chemin se déclenchait à la MÊME
            // milliseconde que la création du tab elle-même
            // (upsertRegisterTabFromScan), avec userInitiated=false —
            // court-circuitant le flux normal de découverte AVANT même
            // qu'il ait pu se terminer. Retirer le Toast (fait plus tôt)
            // n'était pas suffisant — surErreurConnexion() déclenchait
            // quand même le VRAI dialogue de diagnostic (4 étapes) en
            // arrière-plan. Corrigé pour de bon : le diagnostic forcé ne se
            // déclenche maintenant QUE sur un vrai geste utilisateur
            // (userInitiated=true) — un appel automatique pendant la
            // création du tab se contente de logger et laisse le flux
            // normal (upsertRegisterTabFromScan/probe) faire son travail
            // sans interférence.
            if (userInitiated) {
                surErreurConnexion(
                    new java.io.IOException("connectThisRegister: aucun média prêt/registre introuvable"),
                    "CONNECT_THIS_REGISTER");
            } else {
                // ✅ FIX CRITIQUE (11 août 2026, demande Paul — "si je ne le
                // fais pas [CONNECT_LCP] je n'ai pas la connexion au
                // registre") — trouvé : mon fix précédent bloquait TOUTE
                // escalade automatique, y compris la vraie logique de
                // connexion (api_registerConnectAuto), pas seulement le
                // dialogue intrusif. Résultat : sans clic manuel, plus rien
                // ne relançait la connexion tout seul. Corrigé : tente
                // maintenant une reconnexion SILENCIEUSE (même mécanisme
                // que "Reconnexion auto" dans MainActivity — pas de
                // dialogue, pas d'interruption visuelle) au lieu d'abandonner
                // complètement. Le dialogue de diagnostic reste réservé au
                // vrai clic utilisateur.
                android.util.Log.i("RegisterTabFragment", "connectThisRegister: appel automatique "
                    + "(userInitiated=false) — dialogue de diagnostic sauté, mais tentative de "
                    + "reconnexion silencieuse en arrière-plan (sans interférence visuelle)");
                safeBg(() -> {
                    try {
                        com.pa.lcr.lcp.MultiRegisterApiFacadeImpl facadeAuto =
                            new com.pa.lcr.lcp.MultiRegisterApiFacadeImpl(requireActivity());
                        String serialForAuto = (serialFromArgs != null && !serialFromArgs.trim().isEmpty())
                            ? serialFromArgs.trim() : null;
                        com.pa.lcr.lcp.ApiResult r = facadeAuto.api_registerConnectAuto(serialForAuto, node);
                        android.util.Log.i("RegisterTabFragment", "connectThisRegister: reconnexion silencieuse "
                            + "résultat code=" + (r != null ? r.code : "null") + " msg=" + (r != null ? r.msg : "null"));
                    } catch (Exception e) {
                        android.util.Log.w("RegisterTabFragment", "connectThisRegister: reconnexion silencieuse ERR: "
                            + e.getMessage());
                    }
                });
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
        triggerWoDetectionThrottled();
    }

    private volatile long lastWoDetectTriggerMs = 0;
    // ✅ AJOUTÉ (11 août 2026, demande Paul — "j'ai toujours le lag, trouve
    // l'erreur") — trouvé via logcat : rafraichirCumulWo() n'avait AUCUN
    // anti-rebond, appelée depuis 7 endroits séparés dans ce fichier, dont
    // au moins un self-perpétuant. Chaque appel refait un vrai scan BD
    // complet (getAllForWo, 54-56+ lignes et grandissant) — répété
    // plusieurs fois d'affilée sans rien entre les deux dans le log.
    private volatile long lastCumulWoRefreshMs = 0;
    // ✅ (ajouté 3 août 2026) — armé juste avant un déclenchement explicite (entrée tab
    // ou clic Status(B)), consommé une seule fois par onTicketInfo() pour autoriser la
    // recherche complète (Dataverse + backup). Voir lookupWoForTicket(ticketNo, allowFullSearch).
    private volatile boolean fullTicketSearchArmed = false;

    /**
     * ✅ FIX : rechercherWoDepuisRegistre()/rafraichirCumulWo() doivent tourner au
     * moment réel où la connexion est confirmée (pas seulement sur des délais fixes
     * décorrélés du temps de connexion BT) — mais connectThisRegister() peut être
     * appelée bien plus souvent qu'un vrai événement de connexion (boucles de
     * vérification périodiques existantes). Sans ce anti-rebond dédié, chaque appel
     * ouvrait une nouvelle connexion LcrDeliveryStatusDb — d'où la rafale de
     * "SQLiteConnection leaked" observée en rafale dans les logs.
     */
    private void triggerWoDetectionThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastWoDetectTriggerMs < 2000) return;
        lastWoDetectTriggerMs = now;
        rechercherWoDepuisRegistre();
        rafraichirCumulWo();
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
            safeBg(() -> {
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
            final String woForCheck = currentWoNum;
            final String serialForFallback = serialFromArgs;
            // ✅ FIX (la vraie cause du bouton manquant après reconnexion) :
            // ActiveDeliveryStore est VIDÉ une fois la livraison terminée — donc
            // si l'onglet est recréé/reconnecté APRÈS la fin d'une livraison
            // (ex: coupure USB juste après Terminer, puis reconnexion via le
            // diagnostic), currentWoNum ne peut plus se restaurer via ce chemin
            // et reste vide indéfiniment, cachant le bouton pour toujours même
            // si un ticket parfaitement valide existe déjà dans la DB locale.
            // Filet de secours : si currentWoNum est vide, retrouver le dernier
            // WO livré pour CE #série précis (pas tous registres confondus).
            safeBg(() -> {
                String woCheck = woForCheck;
                String syncStatusTrouve = null;
                if ((woCheck == null || woCheck.isEmpty()) && serialForFallback != null && !serialForFallback.isEmpty()) {
                    try {
                        com.pa.lcr.lcp.storage.LcrDeliveryStatusDb dbFallback =
                                new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(requireContext());
                        try {
                            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow lastRow =
                                    dbFallback.getLastDeliveryForSerial(serialForFallback);
                            if (lastRow != null && lastRow.woNum != null && !lastRow.woNum.isEmpty()) {
                                woCheck = lastRow.woNum;
                                syncStatusTrouve = lastRow.syncStatus;
                                final String fWoRecovered = woCheck;
                                final String fWoIdRecovered = lastRow.woIdGuid;
                                android.util.Log.i("RegisterTabFragment", "btnRetourWO: currentWoNum vide — WO récupéré depuis dernière livraison serial="
                                        + serialForFallback + " -> wo=" + fWoRecovered);
                                ui.post(() -> {
                                    currentWoNum = fWoRecovered;
                                    if (fWoIdRecovered != null && !fWoIdRecovered.isEmpty()) currentWoIdGuid = fWoIdRecovered;
                                });
                            }
                        } finally {
                            try { dbFallback.close(); } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {}
                }
                // ✅ AJOUTÉ (11 août 2026, demande Paul — "je veux le repli
                // car dans la BD locale et JSON on sait si c'est pending ou
                // pas") — deuxième étage de la cascade : si la BD locale
                // n'a toujours rien pour ce #série, chercher dans le backup
                // JSON (Téléchargements) — méthode déjà existante et
                // utilisée ailleurs dans ce fichier (reconstruction
                // delivery_uid). Le vrai statut "pending ou pas" reste
                // visible via sync_status quand la BD locale trouve
                // quelque chose (JSON backup seul ne porte pas ce champ,
                // c'est un simple fichier de secours, pas une BD de sync).
                if ((woCheck == null || woCheck.isEmpty()) && serialForFallback != null && !serialForFallback.isEmpty()) {
                    try {
                        String woJson = com.pa.lcr.lcp.storage.LocalDeliveryBackup
                            .findMostRecentWoForSerialFromJsonBackups(requireContext(), serialForFallback.trim());
                        if (woJson != null && !woJson.isEmpty()) {
                            woCheck = woJson;
                            android.util.Log.i("RegisterTabFragment", "btnRetourWO: WO récupéré depuis backup JSON serial="
                                + serialForFallback + " -> wo=" + woJson);
                            final String fWoJson = woJson;
                            ui.post(() -> currentWoNum = fWoJson);
                        }
                    } catch (Exception ignored) {}
                }
                final String fSyncStatusTrouve = syncStatusTrouve;

                boolean hasData = false;
                if (woCheck != null && !woCheck.isEmpty()) {
                    com.pa.lcr.lcp.storage.LcrDeliveryStatusDb db = null;
                    try {
                        db = new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(requireContext());
                        com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow row =
                            db.getLatestForWo(woCheck);
                        // ✅ FIX : n'exiger un ticket non vide QUE pour une livraison
                        // normale. Une ANNULATION (cas confirmé par logcat : ticket
                        // vide + net négatif après plusieurs pressions rapides sur C)
                        // est un enregistrement légitime — sans ce correctif, le
                        // chauffeur restait bloqué sans AUCUN moyen de retourner au
                        // bon de travail après une annulation.
                        boolean isAnnulation = row != null && "ANNULATION".equalsIgnoreCase(row.type);
                        boolean hasTicket = row != null && row.ticketNo != null && !row.ticketNo.isEmpty();
                        hasData = row != null && (hasTicket || isAnnulation);
                    } catch (Exception ignored) {
                    } finally {
                        if (db != null) { try { db.close(); } catch (Exception ignored) {} }
                    }
                }
                final boolean show = connectedFinal && hasData;
                ui.post(() -> {
                    if (!isAdded() || getView() == null || btnRetourWO == null) return;
                    if (show) {
                        btnRetourWO.setVisibility(android.view.View.VISIBLE);
                        btnRetourWO.setEnabled(true);
                        // ✅ AJOUTÉ (11 août 2026, demande Paul — "dans la BD
                        // locale et JSON on sait si c'est pending ou pas")
                        // — statut de synchronisation affiché directement
                        // sur le bouton quand connu (via la BD locale
                        // seulement — le backup JSON seul ne porte pas ce
                        // champ, voir commentaire plus haut).
                        String texteBouton = "Retour au Bon de livraison";
                        if ("PENDING".equals(fSyncStatusTrouve)) {
                            texteBouton += " (⏳ pas encore synchronisé)";
                        } else if ("SYNCED".equals(fSyncStatusTrouve)) {
                            texteBouton += " (✅ synchronisé)";
                        } else if ("ERROR".equals(fSyncStatusTrouve)) {
                            texteBouton += " (⚠ échec de sync)";
                        }
                        btnRetourWO.setText(texteBouton);
                        btnRetourWO.setBackgroundColor(android.graphics.Color.parseColor("#185FA5"));
                        btnRetourWO.setOnClickListener(v -> { LogBus.api(node, "[ACTION-CLIC] RETOUR_WO"); retournerAuWorkOrder(); });
                    } else {
                        btnRetourWO.setVisibility(android.view.View.GONE);
                    }
                });
            });
        }
    }

    // =========================================================
    // ✅ Impression custom — ticket ligne par ligne via opPrintText
    // =========================================================
    private void lancerImpressionCustom() {
        LogBus.api(node, "[ACTION-CLIC] IMPRESSION_CUSTOM");
        DeliveryController c = controller;
        if (c == null) return;
        // ✅ FIX (audit complet) : Print Custom n'avait AUCUNE vérification IO
        // avant d'agir. Ajouté pour cohérence.
        if (!verifierIoAvantAction("CUSTOM_PRINT")) return;

        if (btnCustomPrint != null) btnCustomPrint.setEnabled(false);

        safeBg(() -> {
            if (c != null) c.printInProgress = true;
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

                // Construire les lignes du ticket
                int cols = 30;
                String sep = "=".repeat(cols);
                String dashs = "-".repeat(cols);
                java.util.List<String> lines = new java.util.ArrayList<>();
                lines.add(sep);
                lines.add(center("TICKET DE LIVRAISON", cols));
                lines.add(sep);
                lines.add("WO       : " + woNum);
                lines.add("Ticket # : " + ticketNo);
                lines.add("Vente #  : " + saleNo);
                lines.add("Serie    : " + serialId);
                lines.add(dashs);
                lines.add(String.format("NET      : %.1f L", netL));
                lines.add(String.format("GROSS    : %.1f L", grossL));
                lines.add(dashs);
                lines.add("Debut    : " + formatUtc(startUtc));
                lines.add("Fin      : " + formatUtc(endUtc));
                lines.add(sep);
                lines.add("");

                // Envoyer ligne par ligne via opPrintText
                if (tabTransportKey != null) {
                    MediaTransportManager.get(requireContext())
                        .activateExclusive(tabTransportKey, "CUSTOM_PRINT");
                }
                int errors = 0;
                int total = lines.size();
                LogBus.api(node, "[CUSTOM_PRINT] Début — " + total + " ligne(s) à imprimer (WO=" + woNum
                        + " ticket=" + ticketNo + ")");
                int idxLigne = 0;
                for (String line : lines) {
                    idxLigne++;
                    String apercu = line.trim().isEmpty() ? "(ligne vide)" : line;
                    long t0 = System.currentTimeMillis();
                    try {
                        c.api_printTextLine(line);
                        long dt = System.currentTimeMillis() - t0;
                        LogBus.api(node, "[CUSTOM_PRINT] Ligne " + idxLigne + "/" + total
                                + " OK (" + dt + "ms): \"" + apercu + "\"");
                        Thread.sleep(150);
                    } catch (Exception e) {
                        errors++;
                        long dt = System.currentTimeMillis() - t0;
                        LogBus.api(node, "[CUSTOM_PRINT] Ligne " + idxLigne + "/" + total
                                + " ERR (" + dt + "ms): \"" + apercu + "\" — " + safeMsg(e));
                        surErreurConnexion(e, "CUSTOM_PRINT");
                    }
                }
                LogBus.api(node, "[CUSTOM_PRINT] Terminé — " + (total - errors) + "/" + total
                        + " ligne(s) réussie(s)" + (errors > 0 ? ", " + errors + " échouée(s)" : ""));

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
            } finally {
                if (c != null) c.printInProgress = false;
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

        safeBg(() -> {
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

                // ✅ FIX 4 août 2026 (demande Paul : "il faut la vérité du registre") —
                // netAtDeliveryEnd/grossAtDeliveryEnd/ticketNoAtEnd sont figés par
                // DeliveryController.onDeliveryFinishedIfNeeded() exactement au moment
                // où l'état quitte RUNNING_FLOWING/RUNNING_PAUSED (fin réelle de livraison),
                // à partir du TickBus live (pas un cache). C'est la vraie dernière lecture
                // du registre — à privilégier avant api_tickSnapshot() (cache-only, peut
                // être périmé, cf. ticket 10910 : 10.5 restauré vs 10.6 réel au registre).
                try {
                    if (controller != null
                            && controller.netAtDeliveryEnd >= 0.0
                            && controller.grossAtDeliveryEnd >= 0.0) {
                        netL   = controller.netAtDeliveryEnd;
                        grossL = controller.grossAtDeliveryEnd;
                        if (controller.ticketNoAtEnd != null
                                && !controller.ticketNoAtEnd.trim().isEmpty()) {
                            ticketNo = controller.ticketNoAtEnd;
                        }
                        android.util.Log.i("RetourWO", "Vérité registre (fin livraison) — "
                            + "ticket=" + ticketNo + " net=" + netL + " gross=" + grossL);
                    }
                } catch (Exception ignored) {}

                // Fallback — lecture cache si netAtDeliveryEnd pas encore disponible
                // (ex. livraison terminée avant ce build, ou controller recréé entretemps)
                try {
                    if (controller != null && (netL == 0.0 && grossL == 0.0)) {
                        ApiResult sr = controller.api_tickSnapshot();
                        if (sr != null && sr.data != null) {
                            freshSnap = sr.data;
                            org.json.JSONObject result = freshSnap.optJSONObject("result");
                            if (result != null) {
                                if (ticketNo.isEmpty()) ticketNo = result.optString("ticket_no", "");
                                if (saleNo.isEmpty())   saleNo   = result.optString("sale_no",   "");
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
                // ✅ FIX CRITIQUE (11 août 2026, demande Paul — fuite de
                // connexion SQLite confirmée dans un vrai logcat, "was
                // leaked! Please fix your application...") — rien
                // n'entourait ce bloc entre la création et le close() plus
                // bas (ligne d'origine ~2779) : si getLatestForWo,
                // updateDelivery ou insertDelivery levait une exception,
                // close() ne se déclenchait jamais. Ce bloc s'exécute à
                // CHAQUE fin de livraison (retournerAuWorkOrder) — le
                // risque était réel, pas théorique.
                com.pa.lcr.lcp.storage.LcrDeliveryStatusDb lcrDb =
                    new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(requireContext());
                try {
                com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow existing =
                    lcrDb.getLatestForWo(woNum);
                if (existing != null && !ticketNo.isEmpty() && ticketNo.equals(existing.ticketNo)) {
                    // Ticket déjà enregistré — mettre à jour si netL = 0
                    if (existing.netL == 0 && netL > 0) {
                        android.content.ContentValues cvUpdate = new android.content.ContentValues();
                        cvUpdate.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_NET_L,   netL);
                        cvUpdate.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_GROSS_L, grossL);
                        cvUpdate.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_WO_ID_GUID, woIdGuid);
                        lcrDb.updateDelivery(existing.id, cvUpdate);
                        android.util.Log.i("RetourWO", "Ticket " + ticketNo
                            + " mis a jour — net=" + netL + " gross=" + grossL);
                    } else {
                        android.util.Log.i("RetourWO", "Ticket " + ticketNo
                            + " deja enregistre (id=" + existing.id + ") — skip INSERT");
                    }
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
                } finally {
                    try { lcrDb.close(); } catch (Exception ignored) {}
                }

                // ✅ (demandé 31 juillet 2026, suite à la perte du ticket 10898) : backup
                // JSON durable dans Téléchargements — survit à une désinstallation, contrairement
                // à la BD SQLite privée. Écrit AVANT même la tentative de push Dataverse, donc
                // une copie existe dès que la livraison est confirmée localement, peu importe
                // si le push réussit ou non ensuite. Best-effort — ne bloque jamais le flux.
                try {
                    org.json.JSONObject backupPayload = new org.json.JSONObject();
                    backupPayload.put("wo_num", woNum != null ? woNum : "");
                    backupPayload.put("wo_id_guid", woIdGuid != null ? woIdGuid : "");
                    backupPayload.put("ticket_no", ticketNo != null ? ticketNo : "");
                    backupPayload.put("sale_no", saleNo != null ? saleNo : "");
                    backupPayload.put("net_l", netL);
                    backupPayload.put("gross_l", grossL);
                    backupPayload.put("serial_id", serialFromArgs != null ? serialFromArgs : "");
                    backupPayload.put("lcrnode", node);
                    backupPayload.put("backup_ts", System.currentTimeMillis());
                    backupPayload.put("payload_complet", payloadJson != null ? payloadJson : "");
                    // ✅ AJOUTÉ (11 août 2026, demande Paul — "ajoute-le au
                    // JSON aussi, ça fait pas de mal") — même valeur que
                    // celle mise en BD locale juste au-dessus
                    // (SYNC_PENDING à l'écriture) — permet de savoir si
                    // c'est pending ou pas directement depuis le JSON,
                    // sans avoir à recroiser avec la BD locale (utile
                    // puisque le JSON survit à une désinstallation, pas la
                    // BD locale — mais s'efface lui-même après 7 jours).
                    backupPayload.put("sync_status",
                        com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.SYNC_PENDING);
                    com.pa.lcr.lcp.storage.LocalDeliveryBackup.backupDeliveryAsync(
                        requireContext().getApplicationContext(), woNum, ticketNo, backupPayload);
                } catch (Exception e) {
                    android.util.Log.w("RetourWO", "Backup local ERR (non-bloquant): " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(node, "RegisterTabFragment.Backup", e); } catch (Exception ignored) {}
                }

                // 3. Tenter push MSAL vers Dataverse
                // ✅ (fix 31 juillet 2026, demande Paul : "il ne doit JAMAIS concurrencer
                // aucun processus") — même verrou global que le pull Dataverse, pour garantir
                // qu'aucune opération MSAL ne tourne jamais en parallèle d'une autre.
                // Semaphore(1) — PAS ReentrantLock (voir MsalTokenProvider pour le
                // raisonnement : acquire()/release() peuvent se faire sur des threads
                // différents, ce qu'un ReentrantLock interdit).
                try {
                    com.pa.lcrdemo.auth.MsalTokenProvider.MSAL_SERIAL_LOCK.acquire();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    android.util.Log.w("RetourWO", "Push Dataverse annulé — acquire() interrompu");
                    return;
                }
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
                                        android.util.Log.w("RetourWO", "Token silent ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(node, "RegisterTabFragment.Token", e); } catch (Exception ignored) {}
                                        latch.countDown();
                                    }
                                });
                        }
                        @Override public void onError(Exception e) {
                            android.util.Log.w("RetourWO", "MSAL init ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(node, "RegisterTabFragment.MSAL", e); } catch (Exception ignored) {}
                            latch.countDown();
                        }
                    });
                    latch.await(8, java.util.concurrent.TimeUnit.SECONDS);
                    if (tokenHolder[0] != null) {
                        com.pa.lcrdemo.dataverse.LcrDeliverySync.pushPending(
                            requireContext(), tokenHolder[0]);
                        android.util.Log.i("RetourWO", "Push Dataverse OK");

                        // ✅ PATCH final consolidé — toutes les livraisons du WO
                        // pushPending() envoie chaque row individuellement
                        // Ce PATCH final garantit que msdyn_workordersummary a le payload complet
                        try {
                            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb lcrFinal =
                                new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(requireContext());
                            java.util.List<com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow> allRows;
                            try {
                                allRows = lcrFinal.getAllForWo(woNum);
                            } finally {
                                try { lcrFinal.close(); } catch (Exception ignored) {}
                            }
                            org.json.JSONArray livraisons = new org.json.JSONArray();
                            for (com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow r : allRows) {
                                if (r.netL > 0 || "ANNULATION".equals(r.type)) {
                                    org.json.JSONObject entry = new org.json.JSONObject();
                                    entry.put("ticket_no", r.ticketNo != null ? r.ticketNo : "");
                                    entry.put("net_l",     r.netL);
                                    entry.put("gross_l",   r.grossL);
                                    entry.put("type",      r.type != null ? r.type : "");
                                    entry.put("end_utc",   r.endUtc != null ? r.endUtc : "");
                                    livraisons.put(entry);
                                }
                            }
                            // ✅ Lire woIdGuid depuis LcrDeliveryStatusDb — source de vérité
                            // La première livraison du WO a le GUID sauvegardé depuis le deep link
                            // Toutes les livraisons du même WO partagent le même GUID
                            String patchGuid = "";
                            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb lcrGuid = null;
                            try {
                                lcrGuid = new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(requireContext());
                                java.util.List<com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow> allForGuid =
                                    lcrGuid.getAllForWo(woNum);
                                for (com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow r : allForGuid) {
                                    if (r.woIdGuid != null && !r.woIdGuid.isEmpty()) {
                                        patchGuid = r.woIdGuid;
                                        break;
                                    }
                                }
                            } catch (Exception ignored) {
                            } finally {
                                if (lcrGuid != null) { try { lcrGuid.close(); } catch (Exception ignored) {} }
                            }
                            android.util.Log.i("RetourWO", "patchGuid=" + patchGuid + " rows=" + livraisons.length());
                            if (livraisons.length() > 0 && !patchGuid.isEmpty()) {
                                com.pa.lcrdemo.dataverse.WorkOrderUpdater.patchSummaryConsolidated(
                                    tokenHolder[0], patchGuid, woNum, livraisons);
                                android.util.Log.i("RetourWO", "Patch final consolidé OK — "
                                    + livraisons.length() + " livraisons");
                            }
                        } catch (Exception ePatch) {
                            android.util.Log.w("RetourWO", "Patch final ERR (probablement hors ligne) — "
                                + "mise en file pour retry automatique: " + ePatch.getMessage());
                            // ✅ FIX : avant, cette erreur était juste loguée — rien n'était
                            // mis en file, donc le dialogue qui promet "sera envoyé dès que
                            // le réseau revient" mentait. On met maintenant en file via
                            // DeliveryResultQueueDb (même mécanisme que DeliverySyncWorker),
                            // marqué "consolidated" pour que le retry utilise bien
                            // patchSummaryConsolidated() (avec fusion+ETag) et pas la version
                            // simple qui écraserait l'historique.
                            try {
                                org.json.JSONObject queuePayload = new org.json.JSONObject();
                                queuePayload.put("consolidated", true);
                                queuePayload.put("workOrderId", woIdGuid != null ? woIdGuid : "");
                                queuePayload.put("woNum", woNum != null ? woNum : "");
                                String queueUid = (woNum != null ? woNum : "wo") + "-consolidated-"
                                    + System.currentTimeMillis();
                                queuePayload.put("deliveryUid", queueUid);
                                com.pa.lcrdemo.dataverse.DeliveryResultQueueDb queueDbRetry =
                                    new com.pa.lcrdemo.dataverse.DeliveryResultQueueDb(requireContext());
                                try {
                                    queueDbRetry.upsertPending(queueUid, queuePayload.toString());
                                } finally {
                                    try { queueDbRetry.close(); } catch (Exception ignored) {}
                                }
                                com.pa.lcrdemo.dataverse.DeliverySyncScheduler.triggerNow(requireContext());
                                android.util.Log.i("RetourWO", "Mise en file OK — retry via DeliverySyncWorker dès réseau dispo");
                            } catch (Exception eQueue) {
                                android.util.Log.w("RetourWO", "Mise en file ERR: " + eQueue.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(node, "RegisterTabFragment.Mise", eQueue); } catch (Exception ignored) {}
                            }
                        }
                    } else {
                        android.util.Log.w("RetourWO", "Pas de token — données en PENDING local");
                    }
                } catch (Exception e) {
                    android.util.Log.w("RetourWO", "Push Dataverse ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(node, "RegisterTabFragment.Push", e); } catch (Exception ignored) {}
                } finally {
                    com.pa.lcrdemo.auth.MsalTokenProvider.MSAL_SERIAL_LOCK.release();
                } // fin verrou MSAL_SERIAL_LOCK

                // 4. Effacer ActiveDeliveryStore
                try {
                    new com.pa.lcr.lcp.storage.ActiveDeliveryStore(requireContext()).clear();
                } catch (Exception ignored) {}

                // 4b. Vérifier si des livraisons sont encore PENDING après le push
                int pendingCount = 0;
                com.pa.lcr.lcp.storage.LcrDeliveryStatusDb lcrCheck = null;
                try {
                    lcrCheck = new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(requireContext());
                    java.util.List<com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow> pendingRows =
                        lcrCheck.getPendingDeliveries();
                    // Filtrer seulement les PENDING pour ce WO
                    for (com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow r : pendingRows) {
                        if (woNum != null && woNum.equals(r.woNum)) pendingCount++;
                    }
                } catch (Exception ignored) {
                } finally {
                    if (lcrCheck != null) { try { lcrCheck.close(); } catch (Exception ignored) {} }
                }

                // 5. Retour FSM
                final String fWoIdGuid  = woIdGuid;
                final int    fPending   = pendingCount;
                ui.post(() -> {
                    if (!isAdded() || getView() == null) return;
                    if (fPending > 0) {
                        // Livraisons non synchronisées — avertir sans bloquer
                        new android.app.AlertDialog.Builder(requireContext())
                            .setTitle("Donnees en attente de sync")
                            .setMessage(fPending + " livraison(s) pas encore envoyee(s) a Dataverse."
                                + " Elles seront envoyees des que le reseau revient."
                                + "\n\nVoulez-vous retourner quand meme ?")
                            .setCancelable(false)
                            .setPositiveButton("Retourner quand même", (d, w) -> {
                                try { if (getActivity() != null) getActivity().finish(); }
                                catch (Exception ignored) {}
                            })
                            .setNegativeButton("Attendre la sync", (d, w) -> {
                                if (btnRetourWO != null) btnRetourWO.setEnabled(true);
                            })
                            .show();
                    } else {
                        try {
                            android.util.Log.i("RetourWO", "finish() → FSM woId=" + fWoIdGuid);
                            if (getActivity() != null) getActivity().finish();
                        } catch (Exception e) {
                            android.util.Log.e("RetourWO", "finish() ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(node, "RegisterTabFragment.finish", e); } catch (Exception ignored) {}
                        } finally {
                            if (btnRetourWO != null) btnRetourWO.setEnabled(true);
                        }
                    }
                });

            } catch (Exception e) {
                android.util.Log.e("RetourWO", "retournerAuWorkOrder ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(node, "RegisterTabFragment.retournerAuWorkOrder", e); } catch (Exception ignored) {}
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
        // ✅ FIX CRITIQUE (12 août 2026, demande Paul — voir commentaire sur
        // lastRenderedLogTs) — si le filigrane a changé (nouveau "depuis" /
        // premier affichage / TextView vidé ailleurs), reconstruction
        // complète nécessaire UNE fois. Sinon, n'ajoute que les événements
        // strictement plus récents que la dernière ligne déjà affichée —
        // append() au lieu de setText(), évitant un re-layout complet du
        // TextView à chaque rafraîchissement.
        boolean rebuildComplet = (lastRenderedLogTs == 0L) || (logViewSinceMs > lastRenderedLogTs);
        if (rebuildComplet) {
            txtLog.setText(LogBus.buildText(events));
        } else {
            ArrayList<LogBus.LogEvent> nouveaux = new ArrayList<>();
            for (LogBus.LogEvent e : events) { if (e.ts > lastRenderedLogTs) nouveaux.add(e); }
            if (!nouveaux.isEmpty()) {
                String texteAjoute = LogBus.buildText(nouveaux);
                if (!texteAjoute.isEmpty()) txtLog.append(texteAjoute);
            }
        }
        if (!events.isEmpty()) {
            lastRenderedLogTs = events.get(events.size() - 1).ts;
        }
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
    // ✅ POINT D'ENTRÉE UNIFIÉ (demandé) : Status(B), Connect LCP et Reconnect
    // valident maintenant le TRANSPORT ET LE REGISTRE PRÉCIS (node+serial) —
    // pas juste un flag passif (io.isOpen()) qui peut mentir. On envoie une
    // VRAIE commande Status (api_tickSnapshot) comme test définitif. Si CETTE
    // commande échoue aussi, la déconnexion est confirmée à 100% — pas une
    // supposition basée sur un compteur de timeouts passifs.
    // Tourne TOUJOURS en arrière-plan (jamais sur le thread UI).
    private void validerTransportEtRegistrePuis(String contexte, Runnable onSuccess) {
        safeBg(() -> {
            boolean ok = false;
            String raison = "";
            try {
                if (tabTransportKey == null || tabTransportKey.trim().isEmpty()) {
                    raison = "transport inconnu";
                } else {
                    com.pa.lcr.lcp.transport.TransportIo io =
                        MediaTransportManager.get(requireContext()).getByKey(tabTransportKey);
                    if (io == null || !io.isOpen()) {
                        raison = "transport fermé (" + tabTransportKey + ")";
                    } else if (controller == null) {
                        raison = "controller null";
                    } else {
                        // ✅ FIX (l'erreur signalée) : api_tickSnapshot() ne fait
                        // AUCUNE IO réelle — c'est une lecture de CACHE qui
                        // retourne TOUJOURS ok=1, peu importe l'état réel de la
                        // connexion. Ça ne validait absolument rien. La vraie
                        // commande synchrone qui fait de l'IO réelle et peut
                        // réellement échouer est api_registerValidate() — elle
                        // lit le VRAI statut du registre (readFullStatus) et
                        // retourne fail() sur une vraie exception LCP.
                        com.pa.lcr.lcp.ApiResult r = controller.api_registerValidate(
                                null, node, serialFromArgs, null, null);
                        // ✅ IMPORTANT : api_registerValidate() peut aussi échouer
                        // pour des raisons LÉGITIMES (ticket en attente, livraison
                        // active, mauvais serial) — ce ne sont PAS des signes de
                        // déconnexion. Seul err="ERR_LCP_CONNECT_FAILED" indique
                        // une vraie panne de communication. Les autres échecs sont
                        // des états métier normaux, pas une raison de diagnostiquer.
                        boolean vraieDeconnexion = r != null && r.code != 1
                                && com.pa.lcr.lcp.RegisterValidator.Codes.ERR_LCP_CONNECT_FAILED.equals(r.err);
                        ok = (r != null && r.code == 1) || (r != null && !vraieDeconnexion);
                        raison = (r != null) ? ("err=" + r.err + " msg=" + r.msg) : "r=null";
                    }
                }
            } catch (Exception e) {
                raison = "exception: " + e.getMessage();
            }
            android.util.Log.i("RegisterTabFragment", "validerTransportEtRegistrePuis [" + contexte
                    + "]: node=" + node + " serial=" + serialFromArgs + " transport=" + tabTransportKey
                    + " -> " + (ok ? "OK" : "ÉCHEC (" + raison + ")"));
            if (ok) {
                if (onSuccess != null) ui.post(onSuccess);
            } else {
                final String fRaison = raison;
                surErreurConnexion(
                    new java.io.IOException("Validation transport+registre échouée (node=" + node
                        + " serial=" + serialFromArgs + "): " + fRaison),
                    contexte);
            }
        });
    }

    private boolean verifierIoAvantAction(String contexte) {
        // ✅ AJOUTÉ (11 août 2026, demande Paul — "si je clique on revient
        // à la normal") — point d'entrée commun à la plupart des actions
        // du tab (boutons) — remet immédiatement le keep-alive à son délai
        // normal, peu importe combien de temps il avait étiré pendant
        // l'inactivité.
        try {
            RegisterSessionManager.get(requireContext()).notifierInteractionUtilisateur(tabTransportKey, node);
        } catch (Exception ignored) {}
        try {
            // ✅ FIX (bétonnage) : si tabTransportKey est null/vide, l'ancien code
            // sautait la vérification ENTIÈREMENT et retournait true par défaut —
            // laissant croire que tout va bien alors qu'on ne sait même pas à quel
            // transport se fier. Plus aucun cas ne passe sans vérification réelle.
            if (tabTransportKey == null || tabTransportKey.trim().isEmpty()) {
                android.util.Log.w("RegisterTabFragment", "verifierIoAvantAction [" + contexte
                        + "]: tabTransportKey vide/null — transport inconnu, diagnostic forcé");
                surErreurConnexion(
                    new java.io.IOException("Transport inconnu (tabTransportKey vide) — BT/USB débranché"),
                    contexte);
                return false;
            }
            com.pa.lcr.lcp.transport.TransportIo io =
                MediaTransportManager.get(requireContext()).getByKey(tabTransportKey);
            if (io == null || !io.isOpen()) {
                android.util.Log.w("RegisterTabFragment", "verifierIoAvantAction [" + contexte
                        + "]: io=" + (io == null ? "null" : "fermé") + " pour transport=" + tabTransportKey);
                surErreurConnexion(
                    new java.io.IOException("Transport fermé — BT/USB débranché"),
                    contexte);
                return false;
            }
        } catch (Exception e) {
            android.util.Log.w("RegisterTabFragment", "verifierIoAvantAction [" + contexte
                    + "]: exception " + e.getMessage());
            surErreurConnexion(e, contexte);
            return false;
        }
        return true;
    }

    /** Extrait la MAC depuis tabTransportKey ("BT:AA:BB:.."), ou "" si le
     *  transport n'est pas Bluetooth (USB / TCP). */
    private String macDepuisTransportKey() {
        try {
            String k = tabTransportKey;
            if (k != null && k.toUpperCase().startsWith("BT:")) return k.substring(3).trim();
        } catch (Exception ignored) {}
        return "";
    }

    private void surErreurConnexion(Exception e, String contexte) {
        android.util.Log.w("RegisterTabFragment", "surErreurConnexion [" + contexte + "]: "
            + (e != null ? e.getMessage() : "null"));
        // ✅ FIX (11 août 2026, demande Paul — "je te demande d'enlever le
        // diagnostic sur STATUS_B, ça obstrue ce qu'on cherche") — un échec
        // sur un clic manuel STATUS_B déclenchait tout le diagnostic forcé
        // (validerConnexion + lancerDiagnosticForce), ajoutant une rafale de
        // trafic et de bruit supplémentaire exactement pendant les périodes
        // où on essaie d'observer ce qui se passe réellement — contribuant
        // potentiellement lui-même à la collision qu'on essaie de
        // diagnostiquer. L'échec reste loggé (ligne ci-dessus), mais ne
        // déclenche plus de diagnostic en cascade pour ce contexte précis.
        // Le diagnostic automatique sur vraie déconnexion (AUTO_DISCONNECTED)
        // n'est PAS affecté — seulement ce contexte spécifique.
        if ("STATUS_B".equals(contexte)) {
            return;
        }
        if (com.pa.lcrdemo.RegisterConnectionHelper.estErreurConnexion(e)) {
            // ✅ FIX (bétonnage définitif) : validerConnexion() était appelée
            // SYNCHRONE sur le thread UI (puisque surErreurConnexion est
            // déclenchée directement depuis les clics de boutons). Si
            // validerConnexion() levait n'importe quelle exception AVANT
            // d'atteindre son propre new Thread(...) interne, elle était
            // avalée SILENCIEUSEMENT ici — "aucune réaction" à l'écran, sans
            // même une ligne dans le logcat. Toute cette étape passe
            // maintenant par son propre thread, avec logging explicite de
            // toute exception, pour qu'un échec ne soit plus jamais invisible.
            final String fContexte = contexte;
            new Thread(() -> {
                try {
                    if (getActivity() instanceof com.pa.lcrdemo.MainActivity
                            && requireActivity() instanceof com.pa.lcrdemo.MainActivity) {
                        android.util.Log.i("RegisterTabFragment", "surErreurConnexion [" + fContexte
                                + "]: appel validerConnexion — transportKey=" + tabTransportKey
                                + " node=" + node + " serial=" + serialFromArgs + " wo=" + currentWoNum);
                        new com.pa.lcrdemo.RegisterConnectionHelper(
                            (com.pa.lcrdemo.MainActivity) requireActivity())
                            .validerConnexion(
                                tabTransportKey != null ? tabTransportKey : "",
                                node,
                                serialFromArgs != null ? serialFromArgs : "",
                                currentWoNum != null ? currentWoNum : "",
                                // ✅ FIX : contexte deep link relayé jusqu'au diagnostic,
                                // sans quoi la relance automatique de la livraison ne peut
                                // pas se déclencher après une reconnexion réussie.
                                currentWoIdGuid != null ? currentWoIdGuid : "",
                                currentProduit  != null ? currentProduit  : "",
                                currentPreset   != null ? currentPreset   : "",
                                macDepuisTransportKey(),
                                (getActivity() instanceof com.pa.lcrdemo.MainActivity)
                                    ? ((com.pa.lcrdemo.MainActivity) getActivity()).getDeepLinkHandler()
                                    : null);
                    } else {
                        android.util.Log.w("RegisterTabFragment", "surErreurConnexion [" + fContexte
                                + "]: activité indisponible — diagnostic annulé");
                    }
                } catch (Exception ex) {
                    android.util.Log.e("RegisterTabFragment", "surErreurConnexion [" + fContexte
                            + "]: EXCEPTION pendant validerConnexion — " + ex, ex);
                }
            }).start();
        } else {
            android.util.Log.w("RegisterTabFragment", "surErreurConnexion [" + contexte
                    + "]: estErreurConnexion() a retourné false — diagnostic NON déclenché pour " + e);
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
    /** Appelé par DeepLinkHandler via MainActivity quand le tab existe déjà
     *  Même comportement que le bouton C mais avec préfill du WO */
    public void startNewDeliveryCFromDeepLink(String woNum, String woIdGuid,
            String produit, String presetStr) {
        // Préfiller
        if (woNum != null && !woNum.isEmpty()) {
            currentWoNum = woNum;
            woNumFromDirectSource = true;
            lastTicketDetected = "";
        }
        if (woIdGuid != null && !woIdGuid.isEmpty()) currentWoIdGuid = woIdGuid;
        if (produit != null && !produit.isEmpty() && spnProduct != null)
            spnProduct.setText(produit, false);
        if (presetStr != null && !presetStr.isEmpty() && edtPreset != null)
            edtPreset.setText(presetStr);
        deliveryNotified = false;
        // Bypasser le dialog "Bon déjà complété" — FSM a déjà validé la livraison
        // Appeler startNewDeliveryC() qui lance la livraison sans le guard du dialog
        if (controller == null) return;
        try {
            new com.pa.lcr.lcp.storage.ActiveDeliveryStore(requireContext()).clear();
        } catch (Exception ignored) {}
        bypassDeliveryDialog = true;
        startNewDeliveryC();
    }

    private void startNewDeliveryC() {
        if (controller == null) return;
        // ✅ AJOUTÉ (13 août 2026, demande Paul — "running_flowing doit
        // avoir l'approbation de toutes les sections") — garde commune,
        // peu importe si le démarrage vient du bouton C ou du deep link
        // (voir aussi lancerLivraisonDepuisTab dans MainActivity, même
        // vérification à ajouter côté deep link).
        if (!peutDemarrerLivraison()) {
            android.util.Log.w("InitSeq", "startNewDeliveryC: refusé — toutes les sections ne sont pas approuvées ("
                    + initSectionStatus.toString() + ")");
            if (ui != null) {
                ui.post(() -> android.widget.Toast.makeText(getContext(),
                        "Initialisation en cours — patiente un instant", android.widget.Toast.LENGTH_SHORT).show());
            }
            return;
        }
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
        deliveryNotified = false; // reset pour la nouvelle livraison
        // ✅ Si ActiveDeliveryStore contient encore une livraison non terminée
        // (ex: ticket pending résolu via bouton B sans passer par doResolve)
        // → notifier + effacer avant de démarrer la nouvelle livraison
        try {
            com.pa.lcr.lcp.storage.ActiveDeliveryStore ads =
                new com.pa.lcr.lcp.storage.ActiveDeliveryStore(requireContext());
            com.pa.lcr.lcp.storage.ActiveDeliveryStore.ActiveDelivery active = ads.load();
            if (active != null && active.jobId != null && !active.jobId.isEmpty()) {
                ads.clear();
                // Notifier la livraison précédente comme terminée
                notifyDeliveryEndedToMainActivity();
            }
        } catch (Exception ignored) {}

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

            // ✅ Après resolve ticket pending — notifier seulement si ActiveDeliveryStore
            // contient encore une livraison (= DeepLinkHandler n'a pas encore appelé onDeliveryEnded)
            // Si ActiveDeliveryStore est vide = DeepLinkHandler a déjà notifié → ne pas doubler
            ui.postDelayed(() -> {
                if (!isAdded() || getView() == null || controller == null) return;
                if (controller.getState() != com.pa.lcr.lcp.DeliveryState.CONNECTED) return;
                if (currentWoNum == null || currentWoNum.isEmpty()) return;
                try {
                    com.pa.lcr.lcp.storage.ActiveDeliveryStore ads =
                        new com.pa.lcr.lcp.storage.ActiveDeliveryStore(requireContext());
                    com.pa.lcr.lcp.storage.ActiveDeliveryStore.ActiveDelivery active = ads.load();
                    if (active != null && active.jobId != null && !active.jobId.isEmpty()) {
                        // ActiveDeliveryStore non vide = ticket pending non résolu par DeepLinkHandler
                        ads.clear();
                        notifyDeliveryEndedToMainActivity();
                    }
                    // Si ActiveDeliveryStore vide = DeepLinkHandler a déjà notifié → rien à faire
                } catch (Exception ignored) {}
            }, 1500);
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
        double netAtCancelRaw   = parseDisplayNet();
        double grossAtCancelRaw = parseDisplayGross();

        // ✅ FIX (1/2) : utiliser la valeur CORRIGÉE (retour de gaz) si le
        // mécanisme de correction a accumulé quelque chose — sinon, retomber
        // sur la valeur brute affichée. Avant ce correctif, l'annulation
        // sauvegardait toujours la valeur brute, même quand la correction
        // retour-de-gaz avait des données plus fiables.
        double corrNet = 0.0, corrGross = 0.0;
        try { corrNet = c.getCumulativeCorrectedNetL(); } catch (Exception ignored) {}
        try { corrGross = c.getCumulativeCorrectedGrossL(); } catch (Exception ignored) {}
        final double netAtCancel   = (corrNet   != 0.0) ? corrNet   : netAtCancelRaw;
        final double grossAtCancel = (corrGross != 0.0) ? corrGross : grossAtCancelRaw;

        // ✅ FIX (2/2) : bloquer/confirmer si le NET reste négatif (retour de
        // gaz probable — pression du gun inférieure à celle du réservoir de
        // destination). Ne jamais sauvegarder silencieusement une annulation
        // avec NET négatif sans que le chauffeur en soit conscient.
        if (netAtCancel < 0) {
            if (btnAnnuler != null) {
                btnAnnuler.setEnabled(true);
                btnAnnuler.setText("⛔ Annuler la livraison");
            }
            if (btnConnect  != null) btnConnect.setEnabled(true);
            if (btnA        != null) btnA.setEnabled(true);
            if (btnB        != null) btnB.setEnabled(true);
            if (btnC        != null) btnC.setEnabled(true);
            if (btnContinue != null) btnContinue.setEnabled(true);
            if (btnFinish   != null) btnFinish.setEnabled(true);
            if (btnRetourWO != null) btnRetourWO.setEnabled(true);
            final double fNetAtCancel = netAtCancel;
            final double fGrossAtCancel = grossAtCancel;
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("⚠️ NET négatif détecté")
                .setMessage("NET = " + String.format(Locale.ROOT, "%.2f", fNetAtCancel) + " L — probable retour de gaz "
                    + "(pression du gun inférieure à celle du réservoir de destination).\n\n"
                    + "Confirmer l'annulation quand même avec cette valeur ?")
                .setPositiveButton("Confirmer l'annulation", (d, w) ->
                    proceedWithAnnulation(c, fNetAtCancel, fGrossAtCancel))
                .setNegativeButton("Annuler (revenir en arrière)", null)
                .setCancelable(true)
                .show();
            return;
        }

        proceedWithAnnulation(c, netAtCancel, grossAtCancel);
    }

    // ✅ Extrait de la logique d'annulation existante — inchangée, juste
    // déplacée pour permettre le blocage/confirmation ci-dessus sur NET négatif.
    private void proceedWithAnnulation(DeliveryController c, double netAtCancel, double grossAtCancel) {
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
        // ✅ Poser le flag AVANT bg.execute — évite race avec onStateChanged(ENDED)
        cancelInProgress = true;
        safeBg(() -> {
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
                    com.pa.lcr.lcp.storage.LcrDeliveryStatusDb dbAnnuler =
                        new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(requireContext());
                    try {
                        dbAnnuler.insertDelivery(cv);
                    } finally {
                        try { dbAnnuler.close(); } catch (Exception ignored) {}
                    }
                    android.util.Log.i("Annuler", "Annulation loggée wo=" + woNum + " ticket=" + ticketNo);
                } catch (Exception e) {
                    android.util.Log.w("Annuler", "Insert ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(node, "RegisterTabFragment.Insert", e); } catch (Exception ignored) {}
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
                try { com.pa.lcr.lcp.log.LogBus.err(node, "RegisterTabFragment.Annuler", e); } catch (Exception ignored) {}
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

    // ✅ Scan auto produits (4 août 2026, demande Paul) — appelé une seule fois
    // par activation de tab (onTabActivated), pas à chaque transition CONNECTED.
    // Vérifie d'abord le cache local (RegisterProductStore) avant de lancer un
    // vrai scan matériel : si des produits sont déjà connus pour ce serial+node,
    // on réapplique juste le cache (rapide, pas d'IO registre). Sinon on lance
    // lancerScanProduits() (le même scan que le bouton manuel "🔍 Scan produits").
    private void autoScanProduitsSiNecessaire() {
        // ✅ AJOUTÉ (24 août 2026, demande Paul — "je ne vois pas le scan de
        // produit... je ne vois pas que le scan en premier") — trouvé :
        // TOUTES les sorties anticipées de cette méthode étaient
        // complètement silencieuses, aucun log nulle part — impossible de
        // savoir laquelle se déclenchait sans deviner. Journalisées ici,
        // une par une, pour que la prochaine session de test le confirme.
        if (!isAdded() || getView() == null || controller == null) {
            android.util.Log.i("RegisterTabFragment", "autoScanProduitsSiNecessaire: abandon — "
                + "isAdded=" + isAdded() + " getView()=" + (getView() != null) + " controller=" + (controller != null));
            return;
        }
        if (autoProductScanInFlight) {
            android.util.Log.i("RegisterTabFragment", "autoScanProduitsSiNecessaire: abandon — "
                + "autoProductScanInFlight déjà true (cette instance)");
            return;
        }
        // ✅ CORRIGÉ (24 août 2026, demande Paul — "zéro scan des produits,
        // tu as tout ce qu'il faut pour que ce soit là") — trouvé, avec
        // certitude cette fois : DeliveryState a un état IDLE, distinct de
        // CONNECTED. Le garde exigeait littéralement CONNECTED — si le
        // registre se stabilise sur IDLE après connexion (état tout aussi
        // valide pour scanner, aucune livraison en cours), ce garde
        // rejetait SYSTÉMATIQUEMENT, à chaque tentative, pour toujours —
        // pas un problème de délai, un vrai blocage permanent. Élargi pour
        // accepter tout état "au repos" (ni en livraison, ni déconnecté).
        com.pa.lcr.lcp.DeliveryState stActuel = controller.getState();
        boolean etatAcceptablePourScan = stActuel == DeliveryState.CONNECTED
                || stActuel == DeliveryState.IDLE
                || stActuel == DeliveryState.PRESTART;
        if (!etatAcceptablePourScan) {
            LogBus.api(node, "[SCAN-AUTO] abandon — état=" + stActuel
                + " (accepté: CONNECTED/IDLE/PRESTART) — pas encore prêt à scanner");
            return; // état a pu changer entretemps
        }
        final String serialId = (serialFromArgs != null && !serialFromArgs.trim().isEmpty())
                ? serialFromArgs.trim() : null;
        if (serialId == null) {
            LogBus.api(node, "[SCAN-AUTO] abandon — serialFromArgs vide/null");
            return;
        }

        // ✅ FIX CRITIQUE (10 août 2026) — verrou GLOBAL, partagé entre
        // toutes les instances de fragment pour ce registre précis — voir
        // commentaire complet sur autoProductScanInFlightGlobal. Si une
        // AUTRE instance a déjà ce registre en cours de scan, on abandonne
        // ici, avant même de lancer quoi que ce soit.
        final String globalKey = node + ":" + serialId;
        if (!autoScanLockAcquire(globalKey)) {
            LogBus.api(node, "[SCAN-AUTO] abandon — déjà en cours pour " + globalKey
                + " (autre instance de fragment, OU verrou resté coincé d'un essai précédent)");
            return;
        }
        LogBus.api(node, "[SCAN-AUTO] démarrage — serial=" + serialId + " node=" + node);

        autoProductScanInFlight = true;
        try {
            bg.execute(() -> {
            boolean cacheDisponible = false;
            try {
                com.pa.lcr.lcp.storage.RegisterProductStore store =
                        new com.pa.lcr.lcp.storage.RegisterProductStore(requireContext());
                java.util.List<com.pa.lcr.lcp.storage.RegisterProductStore.Row> rows = store.getAll(serialId, node);
                store.close();
                cacheDisponible = !rows.isEmpty();
            } catch (Exception e) {
                android.util.Log.w("RegisterTabFragment", "autoScanProduitsSiNecessaire: lecture cache ERR: " + e.getMessage());
                try { com.pa.lcr.lcp.log.LogBus.err(node, "RegisterTabFragment.autoScanProduitsSiNecessaire", e); } catch (Exception ignored) {}
            }

            if (cacheDisponible) {
                android.util.Log.i("RegisterTabFragment", "Auto-scan produits — cache déjà présent pour serial="
                        + serialId + " node=" + node + ", pas de re-scan matériel");
                applierDescriptionsProduits(serialId, node);
                autoProductScanInFlight = false;
                autoScanLockRelease(globalKey);
            } else {
                android.util.Log.i("RegisterTabFragment", "Auto-scan produits — aucun cache pour serial="
                        + serialId + " node=" + node + ", lancement scan matériel");
                ui.post(() -> {
                    try {
                        lancerScanProduits();
                    } finally {
                        autoProductScanInFlight = false;
                        autoScanLockRelease(globalKey);
                    }
                });
            }
            });
        } catch (Exception schedulingErr) {
            // ✅ FIX (10 août 2026) — si bg.execute() lui-même échoue à
            // planifier (ex. executor fermé), le verrou global doit quand
            // même se libérer — sinon il resterait bloqué pour toujours,
            // empêchant tout futur scan pour ce registre.
            autoProductScanInFlight = false;
            autoScanLockRelease(globalKey);
            android.util.Log.w("RegisterTabFragment", "autoScanProduitsSiNecessaire: échec de planification: " + schedulingErr.getMessage());
        }
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
        // ✅ AJOUTÉ (24 août 2026, demande Paul — "la section produit n'est
        // pas rafraîchie avec le produit de la livraison correspondant")
        // — lu ici aussi (déjà lu dans la section PRODUIT de l'init, mais
        // celle-là abandonnait avant que CE scan-ci ait fini — voir
        // commentaire plus haut sur le timing). Permet à CE scan de
        // sélectionner directement le bon produit dès qu'il termine,
        // peu importe si la section d'init a déjà abandonné entre-temps.
        final String produitDeepLinkPourScan = (getArguments() != null) ? getArguments().getString("produit") : null;
        ui.post(() -> { if (txtLive != null) txtLive.setText("🔍 Scan produits 1 / 16..."); });
        safeBg(() -> {
          // ✅ CORRIGÉ (24 août 2026, demande Paul — "il faut s'assurer que
          // rien n'intervienne") — trouvé : scanInProgress (le champ existe
          // déjà dans DeliveryController, et le keep-alive de
          // RegisterSessionManager le vérifie déjà pour se suspendre) n'était
          // JAMAIS posé ici — même bug que printInProgress avant le
          // correctif de ce matin. Le keep-alive continuait donc de se
          // battre pour le verrou LCP pendant tout le scan, pouvant causer
          // exactement le genre d'échec/non-complétion décrit. Garanti
          // retiré via finally, peu importe le chemin de sortie (succès,
          // échec après 3 tentatives, exception imprévue).
          c.scanInProgress = true;
          try {
            // ✅ FIX CRITIQUE (11 août 2026, demande Paul — trouvé via
            // logcat : "Auto-scan produits" abandonnait complètement dès le
            // premier rc=0x26, alors que LcpLink documente explicitement
            // que rc=0x26 sur un GET_* est un "busy/skip" volontaire, PAS
            // une erreur définitive (voir en-tête de LcpLink.java) —
            // spécialement probable juste après une reconnexion, quand le
            // scan et le sondage live se disputent le registre en même
            // temps. Réessaie jusqu'à 3 fois avec un court délai avant
            // d'abandonner pour de vrai.
            int tentativesRestantes = 3;
            while (tentativesRestantes > 0) {
                tentativesRestantes--;
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
                // ✅ AJOUTÉ (24 août 2026, demande Paul) — correspondance
                // texte réelle avec produitDeepLink (le produit attendu par
                // le ticket) — même normalisation que
                // RegisterProductStore.Row.matchesName(), pas une
                // comparaison exacte sensible à la casse/tirets. Priorité
                // sur le repli propane : si le nom du ticket matche un
                // vrai produit scanné, c'est plus précis qu'un simple flag
                // isPropane.
                final int[] matchRef = {-1};
                String produitNorm = (produitDeepLinkPourScan != null)
                        ? produitDeepLinkPourScan.trim().toLowerCase(java.util.Locale.ROOT)
                                .replace("-", " ").replace("_", " ").replaceAll("\\s+", " ")
                        : null;
                for (com.pa.lcr.lcp.LcpLink.ProductScanResult r : results) {
                    int idx = r.noteIdx - 1;
                    if (idx >= 0 && idx < 16) labels[idx] = r.toSpinnerLabel();
                    if (r.isPropane && propaneRef[0] == -1) propaneRef[0] = r.noteIdx;
                    if (produitNorm != null && !produitNorm.isEmpty() && !r.description.isEmpty()) {
                        String descNorm = r.description.trim().toLowerCase(java.util.Locale.ROOT)
                                .replace("-", " ").replace("_", " ").replaceAll("\\s+", " ");
                        if (matchRef[0] == -1 && (descNorm.equals(produitNorm)
                                || descNorm.contains(produitNorm) || produitNorm.contains(descNorm))) {
                            matchRef[0] = r.noteIdx;
                        }
                    }
                }
                // ✅ AJOUTÉ (24 août 2026, demande Paul — "le lien du produit
                // du dernier ticket n'est pas rafraîchi dans le tab") —
                // troisième niveau de repli : si ni le deep link (vide
                // quand le tab vient d'une reconnexion automatique, pas
                // d'un vrai nouveau deep link) ni le flag propane ne
                // donnent de correspondance, tente le dernier ticket connu
                // via DeepLinkHandler.lastResultJson.payload.active_product
                // — le même champ déjà câblé pour le repli "BD vierge",
                // mais qui ne s'appliquait jamais une fois le scan réussi.
                int lastTicketIdx = -1;
                if (matchRef[0] == -1 && propaneRef[0] == -1) {
                    try {
                        String lrj = com.pa.lcrdemo.DeepLinkHandler.lastResultJson;
                        if (lrj != null) {
                            org.json.JSONObject j = new org.json.JSONObject(lrj);
                            org.json.JSONObject payload = j.optJSONObject("payload");
                            if (payload != null && payload.has("active_product") && !payload.isNull("active_product")) {
                                int idx = payload.optInt("active_product", -1);
                                if (idx > 0 && idx <= 16) lastTicketIdx = idx;
                            }
                        }
                    } catch (Exception ignored) {}
                }
                final int lastTicketIdxFinal = lastTicketIdx;
                final int idxSelectionne = matchRef[0] > 0 ? matchRef[0] : (propaneRef[0] > 0 ? propaneRef[0] : lastTicketIdxFinal);
                final boolean parCorrespondanceTexte = matchRef[0] > 0;
                final boolean parDernierTicket = matchRef[0] == -1 && propaneRef[0] == -1 && lastTicketIdxFinal > 0;
                ui.post(() -> {
                    try {
                        if (spnProduct != null) spnProduct.setAdapter(new android.widget.ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line, labels));
                        if (idxSelectionne > 0 && spnProduct != null) {
                            spnProduct.setText(labels[idxSelectionne - 1], false);
                            initValidatedProductIdx = idxSelectionne;
                            String quoi = parCorrespondanceTexte ? "produit du ticket (\"" + produitDeepLinkPourScan + "\")"
                                    : parDernierTicket ? "dernier ticket connu" : "Propane";
                            if (txtLive != null) txtLive.setText("✅ Scan terminé — " + quoi + " — produit " + idxSelectionne);
                            android.widget.Toast.makeText(requireContext(), "✅ " + quoi + " — produit " + idxSelectionne + " sélectionné", android.widget.Toast.LENGTH_LONG).show();
                            LogBus.api(node, "[SCAN] sélection auto — produit=" + idxSelectionne + " ("
                                    + (parCorrespondanceTexte ? "correspondance texte deep link"
                                        : parDernierTicket ? "repli dernier ticket connu (lastResultJson)" : "repli propane") + ")");
                        } else {
                            if (txtLive != null) txtLive.setText("✅ Scan terminé — " + results.size() + " produits"
                                    + (produitDeepLinkPourScan != null ? " — aucune correspondance pour \"" + produitDeepLinkPourScan + "\"" : ""));
                            if (produitDeepLinkPourScan != null) {
                                LogBus.api(node, "[SCAN] aucune correspondance trouvée pour produit deep link=\"" + produitDeepLinkPourScan + "\"");
                            }
                        }
                    } finally {
                        if (btnScanProducts != null) { btnScanProducts.setEnabled(true); btnScanProducts.setText("🔍 Scan produits"); }
                    }
                });
                return; // succès — sort complètement, pas besoin de réessayer
            } catch (Exception e) {
                boolean busy = e.getMessage() != null && e.getMessage().contains("rc=0x26");
                if (busy && tentativesRestantes > 0) {
                    android.util.Log.w("RegisterTabFragment", "lancerScanProduits: rc=0x26 (occupé) — "
                        + "nouvel essai dans 500ms, " + tentativesRestantes + " tentative(s) restante(s)");
                    try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    continue; // relance le while — pas un vrai échec, le registre était juste occupé
                }
                android.util.Log.e("RegisterTabFragment", "lancerScanProduits ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(node, "RegisterTabFragment.lancerScanProduits", e); } catch (Exception ignored) {}
                ui.post(() -> {
                    if (txtLive != null) txtLive.setText("❌ Scan ERR: " + e.getMessage());
                    if (btnScanProducts != null) { btnScanProducts.setEnabled(true); btnScanProducts.setText("🔍 Scan produits"); }
                });
                return;
            }
            }
          } finally {
              c.scanInProgress = false;
          }
        });
    }

    public void applierDescriptionsProduits(String serialId, int lcrNode) {
        if (serialId == null || serialId.isEmpty()) return;
        safeBg(() -> {
            try {
                com.pa.lcr.lcp.storage.RegisterProductStore store = new com.pa.lcr.lcp.storage.RegisterProductStore(requireContext());
                java.util.List<com.pa.lcr.lcp.storage.RegisterProductStore.Row> rows = store.getAll(serialId, lcrNode);
                if (rows.isEmpty()) rows = store.getAll(serialId);
                store.close();
                if (rows.isEmpty()) {
                    // ✅ AJOUTÉ (24 août 2026, demande Paul — "BD vierge →
                    // récupérer depuis le JSON de la dernière livraison,
                    // avec type/description/code produit") — register_products
                    // vide (première utilisation, DB effacée...) — repli
                    // dégradé, mais complet cette fois (description+code+type,
                    // pas juste le numéro de slot), depuis
                    // DeepLinkHandler.lastResultJson.payload — voyage
                    // maintenant jusque-là via api_deliveryJobGet() (voir
                    // DeliveryController). Étiqueté clairement "récupéré",
                    // jamais confondu avec un vrai scan frais.
                    try {
                        String lrj = com.pa.lcrdemo.DeepLinkHandler.lastResultJson;
                        if (lrj != null) {
                            org.json.JSONObject j = new org.json.JSONObject(lrj);
                            org.json.JSONObject payload = j.optJSONObject("payload");
                            if (payload != null) {
                                Integer idx = payload.has("active_product") && !payload.isNull("active_product")
                                        ? payload.optInt("active_product", -1) : null;
                                String desc = payload.optString("active_product_description", null);
                                String code = payload.optString("active_product_code", null);
                                int type = payload.optInt("active_product_type", -1);
                                if (idx != null && idx > 0 && idx <= 16 && (desc != null || code != null)) {
                                    final String[] labelsFallback = new String[16];
                                    for (int i = 0; i < 16; i++) labelsFallback[i] = String.valueOf(i + 1);
                                    StringBuilder sb = new StringBuilder(String.valueOf(idx));
                                    if (desc != null && !desc.isEmpty()) sb.append(" - ").append(desc);
                                    if (code != null && !code.isEmpty()) sb.append(" (").append(code).append(")");
                                    if (type >= 0) sb.append(" [").append(com.pa.lcr.lcp.LcpLink.decodeProductType(type)).append("]");
                                    sb.append(" — récupéré, pas encore rescanné");
                                    labelsFallback[idx - 1] = sb.toString();
                                    final String curLabel = labelsFallback[idx - 1];
                                    ui.post(() -> {
                                        if (!isAdded() || getView() == null || spnProduct == null) return;
                                        spnProduct.setAdapter(new android.widget.ArrayAdapter<>(requireContext(),
                                                android.R.layout.simple_dropdown_item_1line, labelsFallback));
                                        spnProduct.setText(curLabel, false);
                                        if (txtLive != null) txtLive.setText("⚠️ Produit récupéré de la dernière livraison — scan recommandé pour confirmer");
                                    });
                                    LogBus.api(node, "[PRODUIT-FALLBACK] BD vierge — récupéré depuis lastResultJson : "
                                            + "slot=" + idx + " desc=" + desc + " code=" + code + " type=" + type);
                                }
                            }
                        }
                    } catch (Exception e2) {
                        android.util.Log.w("RegisterTabFragment", "applierDescriptions: repli lastResultJson ERR: " + e2.getMessage());
                    }
                    return;
                }
                final String[] labels = new String[16];
                for (int i = 0; i < 16; i++) labels[i] = String.valueOf(i + 1);
                for (com.pa.lcr.lcp.storage.RegisterProductStore.Row r : rows) {
                    int idx = r.noteIdx - 1;
                    if (idx >= 0 && idx < 16) labels[idx] = r.toSpinnerLabel();
                }
                // ✅ AJOUTÉ (24 août 2026, demande Paul — "à l'ouverture du
                // tab, je n'ai toujours pas le produit appliqué selon le
                // dernier ticket") — trouvé : cette branche (cache déjà
                // présent — le cas COURANT après le tout premier scan)
                // n'appliquait JAMAIS aucune sélection intelligente,
                // contrairement à lancerScanProduits() (le scan frais).
                // Même logique à trois niveaux ajoutée ici : deep link →
                // propane → dernier ticket connu (lastResultJson).
                final String produitDeepLinkPourCache = (getArguments() != null) ? getArguments().getString("produit") : null;
                String produitNormCache = (produitDeepLinkPourCache != null)
                        ? produitDeepLinkPourCache.trim().toLowerCase(java.util.Locale.ROOT)
                                .replace("-", " ").replace("_", " ").replaceAll("\\s+", " ")
                        : null;
                int matchIdx = -1, propaneIdx = -1;
                for (com.pa.lcr.lcp.storage.RegisterProductStore.Row r : rows) {
                    if (r.isPropane && propaneIdx == -1) propaneIdx = r.noteIdx;
                    if (produitNormCache != null && !produitNormCache.isEmpty() && !r.description.isEmpty()) {
                        String descNorm = r.description.trim().toLowerCase(java.util.Locale.ROOT)
                                .replace("-", " ").replace("_", " ").replaceAll("\\s+", " ");
                        if (matchIdx == -1 && (descNorm.equals(produitNormCache)
                                || descNorm.contains(produitNormCache) || produitNormCache.contains(descNorm))) {
                            matchIdx = r.noteIdx;
                        }
                    }
                }
                int lastTicketIdxCache = -1;
                if (matchIdx == -1 && propaneIdx == -1) {
                    try {
                        String lrj = com.pa.lcrdemo.DeepLinkHandler.lastResultJson;
                        if (lrj != null) {
                            org.json.JSONObject j = new org.json.JSONObject(lrj);
                            org.json.JSONObject payload = j.optJSONObject("payload");
                            if (payload != null && payload.has("active_product") && !payload.isNull("active_product")) {
                                int idx2 = payload.optInt("active_product", -1);
                                if (idx2 > 0 && idx2 <= 16) lastTicketIdxCache = idx2;
                            }
                        }
                    } catch (Exception ignored) {}
                }
                final int matchIdxF = matchIdx, propaneIdxF = propaneIdx, lastTicketIdxCacheF = lastTicketIdxCache;
                final int idxASelectionner = matchIdxF > 0 ? matchIdxF : (propaneIdxF > 0 ? propaneIdxF : lastTicketIdxCacheF);
                ui.post(() -> {
                    if (!isAdded() || getView() == null || spnProduct == null) return;
                    String cur = spnProduct.getText().toString();
                    spnProduct.setAdapter(new android.widget.ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line, labels));
                    if (!cur.isEmpty()) {
                        spnProduct.setText(cur, false);
                    } else if (idxASelectionner > 0 && idxASelectionner <= 16) {
                        spnProduct.setText(labels[idxASelectionner - 1], false);
                        initValidatedProductIdx = idxASelectionner;
                        String quoi = matchIdxF > 0 ? "produit du ticket (\"" + produitDeepLinkPourCache + "\")"
                                : propaneIdxF > 0 ? "Propane" : "dernier ticket connu";
                        if (txtLive != null) txtLive.setText("✅ Produit — " + quoi + " — produit " + idxASelectionner);
                        LogBus.api(node, "[PRODUIT-CACHE] sélection auto (depuis cache existant) — produit="
                                + idxASelectionner + " (" + (matchIdxF > 0 ? "correspondance texte deep link"
                                : propaneIdxF > 0 ? "repli propane" : "repli dernier ticket connu (lastResultJson)") + ")");
                    }
                });
            } catch (Exception e) {
                android.util.Log.w("RegisterTabFragment", "applierDescriptions ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(node, "RegisterTabFragment.applierDescriptions", e); } catch (Exception ignored) {}
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
    private static final double POST_DELIVERY_LEAK_THRESHOLD_L = 0.5;
    private static final long   POST_DELIVERY_POLL_INTERVAL_MS = 5_000;
    private volatile boolean    postDeliveryPollActive = false;
    private volatile boolean    bypassDeliveryDialog   = false; // true quand lancé depuis FSM

    private void demarrerPollPostLivraison(double netRef, double grossRef, String ticketNo) {
        if (postDeliveryPollActive) return;
        postDeliveryPollActive = true;
        // 📝 NOTE POUR AMÉLIORATION FUTURE (11 août 2026, demande Paul —
        // "on s'en tient aux paires, la fuite sera intéressant, ajoute-le
        // dans le code comme amélioration") — [POST-LIVRAISON] Poll
        // demarré existe DÉJÀ comme marqueur de début, mais n'est
        // actuellement PAS relié à [FUITE-VANNE] (qui ne se logue que
        // lors d'un vrai incident, sans référencer ce début). Idée
        // validée : même principe que [CONNEXION]/[DÉCONNEXION] —
        //   1) Retenir l'horodatage de CE début (déjà loggé ici) dans un
        //      champ dédié (ex. lastPostLivraisonStartTs).
        //   2) Si [FUITE-VANNE] se déclenche (voir arreterPollPostLivraison()
        //      / le point d'incident plus bas dans ce fichier), référencer
        //      ce début — "détecté après Xs de surveillance".
        //   3) Ajouter aussi un marqueur de FIN propre quand la surveillance
        //      se termine SANS incident (actuellement arreterPollPostLivraison()
        //      remet juste le drapeau à false, sans jamais logger la fin) —
        //      pour voir que la surveillance a bien tourné même quand rien
        //      n'est trouvé, pas seulement les incidents isolés.
        LogBus.api(node, "[POST-LIVRAISON] Poll demarré netRef=" + netRef + "L ticket=" + ticketNo);
        Runnable pollRunnable = new Runnable() {
            @Override public void run() {
                if (!postDeliveryPollActive || !isAdded() || getView() == null) return;
                if (controller == null) { postDeliveryPollActive = false; return; }
                DeliveryState st = controller.getState();
                if (st == DeliveryState.RUNNING_FLOWING || st == DeliveryState.RUNNING_PAUSED
                        || st == DeliveryState.PRESTART  || st == DeliveryState.ENDING) {
                    postDeliveryPollActive = false;
                    return;
                }
                safeBg(() -> {
                    double netCourant = -1.0, grossCourant = -1.0;
                    try {
                        if (controller != null) {
                            double[] hw = controller.readNetGrossFromHardware();
                            netCourant   = hw[0];
                            grossCourant = hw[1];
                        }
                    } catch (Exception ignored) {}
                    final double fNet = netCourant, fGross = grossCourant;
                    ui.post(() -> {
                        if (!postDeliveryPollActive || !isAdded() || getView() == null) return;
                        if (fNet < 0) { ui.postDelayed(this, POST_DELIVERY_POLL_INTERVAL_MS); return; }
                        double delta = fNet - netRef;
                        if (delta >= POST_DELIVERY_LEAK_THRESHOLD_L) {
                            postDeliveryPollActive = false;
                            afficherAlerteVanneOuverte(netRef, fNet, grossRef, fGross, ticketNo, delta);
                            return;
                        }
                        ui.postDelayed(this, POST_DELIVERY_POLL_INTERVAL_MS);
                    });
                });
            }
        };
        ui.postDelayed(pollRunnable, POST_DELIVERY_POLL_INTERVAL_MS);
    }

    private void arreterPollPostLivraison() { postDeliveryPollActive = false; }

    private void afficherAlerteVanneOuverte(double netRef, double netCourant,
            double grossRef, double grossCourant, String ticketNo, double delta) {
        if (!isAdded() || getView() == null) return;
        String msg;
        try {
            com.pa.lcr.lcp.LcpLink link = null;
            if (controller != null) {
                try { link = (com.pa.lcr.lcp.LcpLink) controller.getLink(); } catch (Exception ignored) {}
            }
            msg = (link != null)
                ? link.getLeakAlertMessage(ticketNo, netRef, netCourant, delta)
                : "Volume detecte apres arret ticket=" + ticketNo;
        } catch (Exception e) { msg = "Volume detecte apres arret ticket=" + ticketNo; }
        new android.app.AlertDialog.Builder(requireContext())
            .setTitle("Vanne encore ouverte ?")
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton("Terminer avec volumes reels", (d, w) ->
                terminerPostLivraisonAvecVolumesReels(netCourant, grossCourant, ticketNo))
            .setNegativeButton("J'ai avise le repartiteur", (d, w) ->
                logFuiteVanneDataverse(netRef, netCourant, grossRef, grossCourant, ticketNo, delta))
            .show();
    }

    private void terminerPostLivraisonAvecVolumesReels(double netFinal, double grossFinal,
            String ticketNoOriginal) {
        if (controller == null) return;
        safeBg(() -> {
            try {
                double netRef   = controller.netAtDeliveryEnd;
                double grossRef = controller.grossAtDeliveryEnd;
                double delta    = netFinal - netRef;
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
                int cols = 30;
                String sep = "=".repeat(cols), dashs = "-".repeat(cols);
                java.util.List<String> lignes = new java.util.ArrayList<>();
                lignes.add(sep);
                lignes.add(center("INCIDENT VANNE", cols));
                lignes.add(sep);
                lignes.add("WO :" + woNum);
                lignes.add("Tkt:" + ticketNoOriginal);
                lignes.add("Ser:" + serialId);
                lignes.add(dashs);
                lignes.add(String.format(java.util.Locale.ROOT, "NET ref:%.3fL", netRef));
                lignes.add(String.format(java.util.Locale.ROOT, "NET fin:%.3fL", netFinal));
                lignes.add(String.format(java.util.Locale.ROOT, "GROSS : %.3fL", grossFinal));
                lignes.add(String.format(java.util.Locale.ROOT, "DELTA :+%.3fL", delta));
                lignes.add(dashs);
                lignes.add("FUITE POST-PRESET");
                lignes.add(new java.text.SimpleDateFormat("dd/MM HH:mm:ss",
                    java.util.Locale.ROOT).format(new java.util.Date()));
                lignes.add(sep);
                lignes.add("");
                if (tabTransportKey != null)
                    MediaTransportManager.get(requireContext())
                        .activateExclusive(tabTransportKey, "POST_LIVRAISON_PRINT");
                for (String ligne : lignes) {
                    try { controller.api_printTextLine(ligne); Thread.sleep(150); }
                    catch (Exception e) { LogBus.api(node, "[POST-LIVRAISON] ERR: " + safeMsg(e)); }
                }
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
                    com.pa.lcr.lcp.storage.LcrDeliveryStatusDb dbFuite =
                        new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(requireContext());
                    try {
                        dbFuite.insertDelivery(cv);
                    } finally {
                        try { dbFuite.close(); } catch (Exception ignored) {}
                    }
                } catch (Exception e) { LogBus.api(node, "[POST-LIVRAISON] ERR DB: " + safeMsg(e)); }
                ui.post(() -> {
                    if (!isAdded() || getView() == null) return;
                    android.widget.Toast.makeText(requireContext(),
                        "Incident enregistre et ticket imprime", android.widget.Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) { LogBus.api(node, "[POST-LIVRAISON] ERR: " + e.getMessage()); }
        });
    }

    private void logFuiteVanneDataverse(double netRef, double netFinal,
            double grossRef, double grossFinal, String ticketNo, double delta) {
        LogBus.api(node, "[FUITE-VANNE] ticket=" + ticketNo + " delta=" + delta + "L INCIDENT");
    }



    // ✅ Afficher le cumul WO à droite du live — lecture depuis LcrDeliveryStatusDb
    private void rafraichirCumulWo() {
        if (currentWoNum == null || currentWoNum.isEmpty()) {
            LogBus.api(node, "[CUMUL-WO] appelée mais currentWoNum vide — skip");
            // Ne pas cacher le panel — il doit toujours etre visible
            return;
        }
        // ✅ FIX CRITIQUE (11 août 2026, demande Paul — "j'ai toujours le
        // lag, trouve l'erreur") — cette méthode n'avait AUCUN anti-rebond,
        // appelée depuis 7 endroits séparés (dont au moins un
        // self-perpétuant, onTabMediaStatusChanged) — chaque appel refait
        // un vrai scan BD complet (getAllForWo), répété plusieurs fois
        // d'affilée sans rien entre les deux, confirmé dans le log. Même
        // principe que le throttle déjà appliqué à
        // checkPendingDeliveryForThisRegister() et rechercherWoDepuisRegistre()
        // aujourd'hui : anti-rebond de 2s + saut complet pendant une
        // livraison active (RUNNING_FLOWING/RUNNING_PAUSED — le panel de
        // cumul n'a aucune raison de se rafraîchir en boucle pendant que
        // le vrai sondage live devrait avoir la priorité).
        if (controller != null) {
            DeliveryState stCumul = controller.getState();
            // ✅ ÉLARGI (12 août 2026, même principe que les correctifs
            // d'aujourd'hui sur connectThisRegister) — couvre maintenant
            // toute la fenêtre active d'une livraison (PRESTART/STARTING
            // inclus), pas seulement RUNNING_FLOWING/RUNNING_PAUSED.
            if (stCumul == DeliveryState.PRESTART || stCumul == DeliveryState.STARTING
                    || stCumul == DeliveryState.RUNNING_FLOWING || stCumul == DeliveryState.RUNNING_PAUSED
                    || stCumul == DeliveryState.ENDING) {
                return;
            }
        }
        long nowCumul = System.currentTimeMillis();
        if (nowCumul - lastCumulWoRefreshMs < 2000) return;
        lastCumulWoRefreshMs = nowCumul;
        final String wo = currentWoNum;
        LogBus.api(node, "[CUMUL-WO] recherche pour wo=" + wo);
        safeBg(() -> {
            double totalNet = 0, totalGross = 0;
            int count = 0;
            try {
                com.pa.lcr.lcp.storage.LcrDeliveryStatusDb db =
                    new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(requireContext());
                java.util.List<com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow> rows;
                try {
                    rows = db.getAllForWo(wo);
                } finally {
                    try { db.close(); } catch (Exception ignored) {}
                }
                LogBus.api(node, "[CUMUL-WO] getAllForWo(" + wo + ") a retourné " + rows.size() + " ligne(s)");
                for (com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow r : rows) {
                    if (!"ANNULATION".equals(r.type) && r.netL > 0) {
                        totalNet   += r.netL;
                        totalGross += r.grossL;
                        count++;
                    }
                }
            } catch (Exception e) {
                LogBus.api(node, "[CUMUL-WO] ERR: " + safeMsg(e));
            }
            final double fNet = totalNet, fGross = totalGross;
            final int fCount = count;
            ui.post(() -> {
                if (!isAdded() || getView() == null || panelWoCumul == null) {
                    LogBus.api(node, "[CUMUL-WO] UI non prête (isAdded/getView/panelWoCumul) — mise à jour ignorée");
                    return;
                }
                // Toujours afficher panelWoCumul — même si 0 livraisons
                panelWoCumul.setVisibility(android.view.View.VISIBLE);
                if (txtWoCumulNet != null)
                    txtWoCumulNet.setText(String.format(java.util.Locale.ROOT,
                        "Total NET: %.1f L", fNet));
                if (txtWoCumulGross != null)
                    txtWoCumulGross.setText(String.format(java.util.Locale.ROOT,
                        "Total GROSS: %.1f L", fGross));
                if (txtWoCumulCount != null)
                    txtWoCumulCount.setText(fCount > 0
                        ? fCount + " livraison" + (fCount > 1 ? "s" : "")
                        : "Aucune livraison");
            });
        });
    }


    // ✅ Chercher le WO depuis le ticket_no courant du registre
    // Utilisé quand on arrive dans l'APK sans deep link FSM
    // Le deep link a toujours précédence — appelé seulement si currentWoNum est vide
    // ✅ delivery_uid n'est pas stocké tel quel dans LcrDeliveryStatusDb —
    // il se reconstruit toujours de la même façon que dans DeliveryController
    // (numero_livraison + "-" + ticketNo). Permet de valider Field Service
    // Mobile vs l'APK pour ce même WO si une synchronisation a été manquée.
    //
    // TODO AMÉLIORATION FUTURE (discuté le 22 juillet 2026) :
    // Actuellement, la validation Field Service Mobile vs APK est VISUELLE
    // uniquement — le chauffeur compare les delivery_uid affichés ici avec ce
    // qu'il voit dans Field Service Mobile, à l'œil. Amélioration envisagée :
    // interroger Dataverse directement (via MSAL, même mécanisme que
    // patchDataverse) pour comparer automatiquement les enregistrements
    // filgo_lcr_delivery_statuses (ou table équivalente) côté serveur avec
    // LcrDeliveryStatusDb côté local, et signaler explicitement les écarts
    // (livraison locale non synchronisée, ticket manquant côté Dataverse, etc.)
    // au lieu de compter sur une comparaison manuelle. Pas encore implémenté —
    // en attente de confirmation du besoin exact avec Paul.
    private void rechercherWoDepuisRegistre() {
        if (controller == null) return;
        // ✅ FIX CRITIQUE (11 août 2026, demande Paul — "dès que j'ai le
        // live RUNNING_FLOWING, il a la priorité sur tout le tab, il ne
        // devrait pas avoir rien d'autres") — pendant une livraison
        // ACTIVEMENT en train de couler, la détection de WO n'a plus
        // aucune raison de tourner (elle ne sert qu'à démarrer une
        // NOUVELLE livraison) — mais rien ne l'empêchait de se déclencher
        // quand même et d'entrer en compétition avec le sondage live
        // net/gross pour le même verrou partagé, causant exactement le lag
        // observé une fois le flow démarré.
        DeliveryState stActuel = controller.getState();
        // ✅ ÉLARGI (12 août 2026, même principe) — couvre maintenant toute
        // la fenêtre active, pas seulement RUNNING_FLOWING/RUNNING_PAUSED.
        if (stActuel == DeliveryState.PRESTART || stActuel == DeliveryState.STARTING
                || stActuel == DeliveryState.RUNNING_FLOWING || stActuel == DeliveryState.RUNNING_PAUSED
                || stActuel == DeliveryState.ENDING) {
            return;
        }
        // ✅ AJOUTÉ (13 août 2026, demande Paul — "juste une fois est
        // suffisant si on revient avec l'information") — onResume() (1200ms)
        // ET onTabActivated() (3500ms) programment toutes les deux cet
        // appel à l'ouverture du tab. Sans garde temporelle, les deux
        // refaisaient la lecture registre complète (api_registerValidate,
        // 5 lectures) à 2.3s d'écart pour arriver la plupart du temps à la
        // même conclusion — le ticket_no n'a pas pu changer entre les deux
        // si aucune livraison n'a démarré. Anti-rebond simple, même
        // principe que lastCheckPendingMs plus haut.
        long nowWo = System.currentTimeMillis();
        if (nowWo - lastWoLookupMs < 2500) return;
        lastWoLookupMs = nowWo;
        safeBg(() -> {
            try {
                // ✅ FIX MAJEUR : api_tickSnapshot() ne contient JAMAIS de champ
                // "ticket_no" (buildTickJsonSnapshot() expose seulement seq/net/gross/
                // devStatus/prnStatus/delStatus/delCode/state/jobId — jamais ticket_no).
                // optString("ticket_no", "") retournait donc TOUJOURS "" et cette
                // méthode sortait silencieusement ici à chaque appel, depuis le tout
                // début — c'est pour ça que [WO-DETECT] n'est jamais apparu dans aucun
                // log de toute la session. api_registerValidate() est la bonne méthode :
                // elle met toujours ticket_no dans son JSON, peu importe le résultat.
                ApiResult snap = controller.api_registerValidate(null, null, null, null, null);
                if (snap == null || snap.data == null) return;
                String ticketNo = snap.data.optString("ticket_no", "");
                if (ticketNo.isEmpty()) return;
                // ✅ FIX CRITIQUE (11 août 2026, demande Paul — "j'ai
                // plusieurs lignes qui sont inutiles si j'ai déjà mon
                // wo-detect, on a une boucle sans fin de status") — trouvé
                // via logcat : une fois delivery_uid reconstruit avec
                // succès (via lastUidReconstructedForTicket/Value, déjà
                // suivis ailleurs), cette méthode continuait quand même à
                // relancer toute la cascade DB→Dataverse (appel réseau!)
                // →backup, à CHAQUE cycle de statut (~2s), pour rien —
                // triggerWoDetectionThrottled() ne limite que par le TEMPS,
                // jamais par "est-ce déjà résolu". Corrigé ici, à la
                // source, pour protéger tous les appelants d'un coup.
                if (ticketNo.equals(lastUidReconstructedForTicket) && lastUidReconstructedValue != null) {
                    return; // déjà résolu pour ce ticket précis — rien à refaire
                }
                // ✅ (ajouté 3 août 2026) — cascade complète toujours autorisée ici
                // (appel explicite/throttlé à 2s), mais isolée sur remoteSearchExecutor,
                // jamais sur bg (voir commentaire sur le champ remoteSearchExecutor).
                // ✅ FIX (13 août 2026, même raison que DeepLinkHandler.safeExecute) —
                // remoteSearchExecutor fait une vraie recherche Dataverse (réseau),
                // et se ferme dans onDestroyView() — même risque de plantage que
                // btExec si le Fragment se détruit pendant que cette recherche est
                // en vol.
                try {
                    remoteSearchExecutor.execute(() -> lookupWoForTicket(ticketNo, true));
                } catch (java.util.concurrent.RejectedExecutionException rex) {
                    android.util.Log.w("RegisterTabFragment", "WO-DETECT: remoteSearchExecutor déjà fermé — recherche abandonnée proprement");
                }
            } catch (Exception e) {
                LogBus.api(node, "[WO-DETECT] ERR: " + safeMsg(e));
            }
        });
    }

    /**
     * ✅ Recherche du WO + rafraîchissement du cumul à partir d'un ticket_no déjà
     * connu — appelée à la fois par rechercherWoDepuisRegistre() (poll explicite via
     * api_registerValidate) ET par onTicketInfo() (push déjà fiable du contrôleur via
     * requestStatus()). onTicketInfo étant le mécanisme qui affiche déjà le ticket
     * avec succès dans le tab, la brancher ici aussi rend la détection du WO/cumul
     * beaucoup plus robuste, sans dépendre uniquement du polling explicite.
     * Doit être appelée depuis un thread d'arrière-plan (fait des requêtes SQLite).
     */
    /**
     * Tentative de récupération d'une livraison sur Dataverse par ticket_no + serial_id + lcrnode,
     * quand elle n'existe pas (encore) localement. Best-effort : MSAL silent avec timeout 8s
     * (même pattern que le push Dataverse existant plus bas dans ce fichier) — si le token
     * échoue (offline, pas de compte, etc.), retourne simplement null sans bloquer le flux.
     * Doit être appelée depuis un thread d'arrière-plan (bloquant réseau + SQLite).
     */
    /**
     * (Demandé 31 juillet 2026) Après Status(B) ou sur demande explicite : si le ticket
     * actuellement affiché par le registre n'a PAS de détail de livraison local (BD vide
     * pour ce ticket, ou #delivery-uid/#wo manquant), avertit l'utilisateur ET tente
     * automatiquement un pull Dataverse — sans attendre le prochain poll de
     * rechercherWoDepuisRegistre() (qui peut prendre jusqu'à 800ms, et surtout qui
     * n'agit que si le ticket n'a jamais encore été "verrouillé" via lastTicketDetected).
     * Doit être appelée depuis un thread d'arrière-plan.
     */
    private static final String LOGCAT_TAG = "WO-DETECT";

    private void checkAndPullMissingDeliveryDetail(String reason) {
        android.util.Log.i(LOGCAT_TAG, "[" + reason + "] checkAndPullMissingDeliveryDetail: appelé, controller="
                + (controller == null ? "null" : "OK"));
        if (controller == null) return;
        try {
            ApiResult snap = controller.api_registerValidate(null, null, null, null, null);
            String snapTicket = (snap != null && snap.data != null) ? snap.data.optString("ticket_no", "") : null;
            android.util.Log.i(LOGCAT_TAG, "[" + reason + "] api_registerValidate: snap="
                    + (snap == null ? "null" : "OK") + " ticket_no=" + snapTicket);
            if (snap == null || snap.data == null) return;
            String ticketNo = snap.data.optString("ticket_no", "");
            if (ticketNo.isEmpty()) {
                android.util.Log.i(LOGCAT_TAG, "[" + reason + "] aucun ticket actif sur le registre — rien à vérifier");
                return;
            }

            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb db =
                new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(requireContext());
            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow row;
            try {
                row = db.getByTicketNo(ticketNo);
            } finally {
                try { db.close(); } catch (Exception ignored) {}
            }

            boolean missing = (row == null)
                    || row.woNum == null || row.woNum.isEmpty()
                    || row.woIdGuid == null || row.woIdGuid.isEmpty();
            android.util.Log.i(LOGCAT_TAG, "[" + reason + "] ticket=" + ticketNo + " — ligne locale="
                    + (row == null ? "absente" : ("wo=" + row.woNum + " woIdGuid=" + row.woIdGuid))
                    + " missing=" + missing);
            if (!missing) return; // détail déjà présent localement — rien à faire

            // ✅ (fix 31 juillet 2026) : ne retente pas le pull Dataverse (donc pas de
            // nouvelle demande MSAL) pour ce même ticket avant MISSING_DETAIL_COOLDOWN_MS —
            // évite le spam de tokens à chaque activation/réactivation d'onglet.
            long now = System.currentTimeMillis();
            if (ticketNo.equals(lastMissingDetailAttemptTicket)
                    && (now - lastMissingDetailAttemptTs) < MISSING_DETAIL_COOLDOWN_MS) {
                android.util.Log.i(LOGCAT_TAG, "[" + reason + "] ticket=" + ticketNo
                        + " — cooldown actif (" + (MISSING_DETAIL_COOLDOWN_MS - (now - lastMissingDetailAttemptTs)) / 1000
                        + "s restantes), pas de nouvelle tentative");
                return;
            }
            lastMissingDetailAttemptTicket = ticketNo;
            lastMissingDetailAttemptTs = now;

            android.util.Log.i(LOGCAT_TAG, "[" + reason + "] ticket=" + ticketNo
                    + " — BD vide/incomplète, lancement tryPullDeliveryFromDataverse()");
            LogBus.api(node, "[" + reason + "] ticket=" + ticketNo
                    + " — BD vide/incomplète (pas de #delivery-uid), tentative Dataverse");
            ui.post(() -> {
                try {
                    Toast.makeText(requireContext(),
                        "Aucune donnée locale pour ce ticket — tentative Dataverse...",
                        Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {}
            });

            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow pulled =
                    tryPullDeliveryFromDataverse(ticketNo);

            android.util.Log.i(LOGCAT_TAG, "[" + reason + "] ticket=" + ticketNo
                    + " — résultat tryPullDeliveryFromDataverse: "
                    + (pulled == null ? "null (échec)" : ("wo=" + pulled.woNum)));

            if (pulled != null && pulled.woNum != null && !pulled.woNum.isEmpty()) {
                // Permet à lookupWoForTicket() normal de reprendre la main proprement
                // (rafraîchit currentWoNum/currentWoIdGuid/UI comme d'habitude).
                lastTicketDetected = "";
                final String fWo = pulled.woNum;
                ui.post(() -> {
                    try {
                        Toast.makeText(requireContext(),
                            "Livraison récupérée depuis Dataverse (WO " + fWo + ")",
                            Toast.LENGTH_SHORT).show();
                    } catch (Exception ignored) {}
                });
                lookupWoForTicket(ticketNo);
            } else {
                ui.post(() -> {
                    try {
                        Toast.makeText(requireContext(),
                            "Aucune donnée trouvée (local + Dataverse) pour ce ticket.",
                            Toast.LENGTH_SHORT).show();
                    } catch (Exception ignored) {}
                });
            }
        } catch (Exception e) {
            LogBus.api(node, "[" + reason + "] checkAndPullMissingDeliveryDetail ERR: " + safeMsg(e));
            android.util.Log.e(LOGCAT_TAG, "[" + reason + "] checkAndPullMissingDeliveryDetail EXCEPTION", e);
        }
    }

    private com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow tryPullDeliveryFromDataverse(String ticketNo) {
        final String serialId = (serialFromArgs != null && !serialFromArgs.trim().isEmpty())
                ? serialFromArgs.trim() : null;
        android.util.Log.i(LOGCAT_TAG, "tryPullDeliveryFromDataverse: ticket=" + ticketNo
                + " serialFromArgs=" + serialId + " node=" + node);
        if (serialId == null) {
            LogBus.api(node, "[WO-DETECT] pull Dataverse annulé — serial_id inconnu pour ce fragment");
            android.util.Log.w(LOGCAT_TAG, "tryPullDeliveryFromDataverse: ANNULÉ — serialFromArgs est null pour ce fragment");
            return null;
        }

        try {
            // ✅ (fix 31 juillet 2026, demande Paul) : verrou global — garantit qu'aucune
            // opération MSAL (celle-ci ou toute autre dans l'app, ex: le push existant)
            // ne tourne jamais en parallèle d'une autre, où que ce soit.
            com.pa.lcrdemo.auth.MsalTokenProvider.MSAL_SERIAL_LOCK.acquire();
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
                                android.util.Log.i(LOGCAT_TAG, "tryPullDeliveryFromDataverse: token MSAL obtenu (silent)");
                                latch.countDown();
                            }
                            @Override public void onError(Exception e) {
                                LogBus.api(node, "[WO-DETECT] pull Dataverse — token silent ERR: " + safeMsg(e));
                                android.util.Log.w(LOGCAT_TAG, "tryPullDeliveryFromDataverse: token silent ERR", e);
                                latch.countDown();
                            }
                        });
                }
                @Override public void onError(Exception e) {
                    LogBus.api(node, "[WO-DETECT] pull Dataverse — MSAL init ERR: " + safeMsg(e));
                    android.util.Log.w(LOGCAT_TAG, "tryPullDeliveryFromDataverse: MSAL init ERR", e);
                    latch.countDown();
                }
            });
            latch.await(8, java.util.concurrent.TimeUnit.SECONDS);

            if (tokenHolder[0] == null) {
                LogBus.api(node, "[WO-DETECT] pull Dataverse annulé — pas de token (offline ou non connecté)");
                android.util.Log.w(LOGCAT_TAG, "tryPullDeliveryFromDataverse: ANNULÉ — aucun token après 8s (offline, pas de compte, ou init/silent en échec)");
                return null;
            }

            boolean found = com.pa.lcrdemo.dataverse.LcrDeliverySync.pullDeliveryByTicket(
                    requireContext(), tokenHolder[0], serialId, node, ticketNo);
            android.util.Log.i(LOGCAT_TAG, "tryPullDeliveryFromDataverse: pullDeliveryByTicket found=" + found
                    + " (serial=" + serialId + " lcrnode=" + node + " ticket=" + ticketNo + ")");
            if (!found) return null;

            // Relit la ligne locale (maintenant upsertée) pour connaître le #wo résolu
            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb db2 =
                new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(requireContext());
            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow resolved;
            try {
                resolved = db2.getByTicketNo(ticketNo);
            } finally {
                try { db2.close(); } catch (Exception ignored) {}
            }

            // ✅ (demandé 31 juillet 2026) : un ticket ne donne qu'UNE transaction — mais
            // plusieurs transactions peuvent partager le même #wo (chaque impression du
            // registre génère un nouveau ticket_no). Rapatrie TOUTES les transactions de
            // ce #wo pour ce #série, pas seulement celle du ticket demandé.
            if (resolved != null && resolved.woNum != null && !resolved.woNum.isEmpty()) {
                try {
                    int n = com.pa.lcrdemo.dataverse.LcrDeliverySync.pullAllDeliveriesForWorkOrder(
                            requireContext(), tokenHolder[0], resolved.woNum, serialId);
                    LogBus.api(node, "[WO-DETECT] wo=" + resolved.woNum + " — " + n
                            + " transaction(s) au total rapatriée(s) depuis Dataverse");
                } catch (Exception e) {
                    LogBus.api(node, "[WO-DETECT] pullAllDeliveriesForWorkOrder ERR: " + safeMsg(e));
                }
            }

            // ✅ (demandé 31 juillet 2026) : au-delà d'un même #wo, rapatrie aussi les
            // AUTRES arrêts de la même journée pour ce registre (#série) — utile quand la
            // tournée du jour comporte plusieurs #wo différents sur le même registre.
            // Ancre de date = start_utc de la livraison déjà résolue (jamais devinée).
            if (resolved != null && resolved.startUtc != null && !resolved.startUtc.isEmpty()) {
                try {
                    int n = com.pa.lcrdemo.dataverse.LcrDeliverySync.pullAllDeliveriesForDay(
                            requireContext(), tokenHolder[0], serialId, resolved.startUtc);
                    LogBus.api(node, "[WO-DETECT] jour=" + resolved.startUtc.substring(0, Math.min(10, resolved.startUtc.length()))
                            + " — " + n + " livraison(s) de la journée rapatriée(s) pour serial=" + serialId);
                } catch (Exception e) {
                    LogBus.api(node, "[WO-DETECT] pullAllDeliveriesForDay ERR: " + safeMsg(e));
                }
            } else {
                LogBus.api(node, "[WO-DETECT] pull journée sauté — start_utc inconnu pour ce ticket");
            }

            return resolved;
            } finally {
                com.pa.lcrdemo.auth.MsalTokenProvider.MSAL_SERIAL_LOCK.release();
            } // fin verrou MSAL_SERIAL_LOCK
        } catch (Exception e) {
            LogBus.api(node, "[WO-DETECT] pull Dataverse ERR: " + safeMsg(e));
            return null;
        }
    }

    private void lookupWoForTicket(String ticketNo) {
        lookupWoForTicket(ticketNo, true);
    }

    /**
     * @param allowFullSearch si false, s'arrête après la recherche locale (étape 1) —
     *                        n'exécute JAMAIS les étapes 2 (Dataverse) et 3 (backup local).
     *                        (ajouté 3 août 2026, demande Paul suite aux ratés de démarrage
     *                        de livraison / RUNNING_FLOWING qui n'affichait pas) — onTicketInfo()
     *                        est déclenché en continu par le polling live du registre (toutes
     *                        les ~100-800ms pendant une livraison active). Avant ce fix, CHAQUE
     *                        déclenchement relançait la cascade complète (appel réseau Dataverse
     *                        + scan de fichiers backup) dès qu'un ticket restait "introuvable"
     *                        ne serait-ce que quelques secondes — martelant le thread unique
     *                        partagé (bg) avec des opérations réseau/IO répétées, au détriment
     *                        des autres tâches en file sur ce même thread (dont l'affichage de
     *                        RUNNING_FLOWING et la visibilité de btnRetourWO). La recherche
     *                        complète est désormais réservée à l'entrée sur le tab et au clic
     *                        explicite Status(B) — voir fullTicketSearchArmed.
     */
    private void lookupWoForTicket(String ticketNo, boolean allowFullSearch) {
        if (ticketNo == null || ticketNo.isEmpty()) return;
        // ✅ Ne pas sortir juste parce que currentWoNum est déjà connu — vérifier
        // plutôt si CE ticket précis a déjà été traité. Si le registre est passé à
        // un nouveau ticket depuis, on doit re-détecter le nouveau WO.
        if (ticketNo.equals(lastTicketDetected)) return;
        // ✅ FIX (4 août 2026) — throttle sur les échecs répétés du MÊME ticket
        // (voir commentaire du champ lastFailedTicket). N'empêche pas les
        // nouvelles tentatives après le cooldown, ni un ticket DIFFÉRENT.
        if (ticketNo.equals(lastFailedTicket)
                && (System.currentTimeMillis() - lastFailedAttemptMs) < WO_DETECT_RETRY_COOLDOWN_MS) {
            return;
        }

        // ✅ (11 août 2026) — log "recherche dans DB" seulement à la
        // première tentative pour ce ticket précis — évite la répétition
        // de la même ligne à chaque retry silencieux en arrière-plan.
        // ✅ FIX CRITIQUE (11 août 2026, demande Paul — "on veut voir que
        // c'est traité, inutile de l'avoir 200 fois") — trouvé la vraie
        // cause : lastFailedTicket/lastFailedAttemptMs ne se marquaient
        // qu'APRÈS toute la cascade (DB+Dataverse+backup), jamais au
        // début. Si plusieurs appels arrivaient en rafale rapprochée
        // (ex. plusieurs événements UI quasi simultanés), chacun passait
        // le verrou de recul avant que le premier n'ait fini — produisant
        // la ligne "recherche dans DB" plusieurs fois d'affilée. Marqué
        // maintenant DÈS le début de la tentative, pas seulement après un
        // échec confirmé — bloque immédiatement tout appel concurrent pour
        // le même ticket pendant la fenêtre de recul.
        lastFailedTicket = ticketNo;
        lastFailedAttemptMs = System.currentTimeMillis();
        if (!ticketNo.equals(dernierTicketWoDetectLogue)) {
            LogBus.api(node, "[WO-DETECT] ticket=" + ticketNo + " — recherche dans DB");
            dernierTicketWoDetectLogue = ticketNo;
        }

        // Chercher ce ticket dans LcrDeliveryStatusDb
        com.pa.lcr.lcp.storage.LcrDeliveryStatusDb db =
            new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(requireContext());
        com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow row;
        try {
            row = db.getByTicketNo(ticketNo);
        } finally {
            try { db.close(); } catch (Exception ignored) {}
        }
        // ✅ (ajouté 3 août 2026, fix boucle infinie signalé par Paul) — capturer si la
        // restauration backup a DÉJÀ été tentée pour ce ticket, peu importe si elle a
        // abouti à un woNum utilisable. Sans ce flag, chaque poll (toutes les 800ms)
        // relançait findLatestByTicketNo() + insertDelivery() indéfiniment dès que
        // row.woNum restait vide après restauration — dupliquant une nouvelle ligne
        // PENDING à chaque cycle.
        boolean backupDejaTente = row != null
            && "RESTORE_BACKUP".equals(row.source);

        if (row == null || row.woNum == null || row.woNum.isEmpty()) {
            if (!allowFullSearch) {
                // ✅ Déclenchement passif (onTicketInfo via polling live du registre) —
                // pas de recherche Dataverse/backup ici, seulement en local. Sinon chaque
                // cycle de poll relancerait un appel réseau + un scan de fichiers sur le
                // thread unique partagé (bg), au détriment de l'affichage RUNNING_FLOWING
                // et de btnRetourWO. La recherche complète se fera au prochain Status(B)
                // ou à la prochaine entrée sur le tab (voir fullTicketSearchArmed).
                return;
            }
            LogBus.api(node, "[WO-DETECT] ticket=" + ticketNo + " — pas dans DB, tentative Dataverse (online)");
            row = tryPullDeliveryFromDataverse(ticketNo);
            if (row == null || row.woNum == null || row.woNum.isEmpty()) {
                // ✅ (ajouté 3 août 2026, demande Paul) — étape 3 : ni local ni Dataverse
                // n'ont ce ticket. Avant d'abandonner, chercher un backup JSON local
                // (Téléchargements) pour ce ticket précis. S'il existe (potentiellement
                // plusieurs versions — on garde la plus récente par backup_ts), on le
                // réinsère en PENDING et on déclenche le service de sync existant pour
                // reconstruire l'enregistrement manquant dans Dataverse.
                //
                // ✅ SAUF si une restauration a déjà été tentée pour ce ticket (voir
                // backupDejaTente) — dans ce cas on ne réinsère jamais une deuxième fois,
                // qu'elle ait ou non produit un woNum valide. Le prochain push Dataverse
                // (service de sync existant, déjà déclenché à la tentative précédente)
                // reste seul responsable de compléter/corriger cette ligne.
                if (backupDejaTente) {
                    LogBus.api(node, "[WO-DETECT] ticket=" + ticketNo
                        + " — restauration backup déjà tentée, pas de nouvelle insertion, nouvel essai au prochain poll");
                    lastFailedTicket = ticketNo;
                    lastFailedAttemptMs = System.currentTimeMillis();
                    return;
                }

                LogBus.api(node, "[WO-DETECT] ticket=" + ticketNo + " — introuvable (local + Dataverse), recherche backup local");
                com.pa.lcr.lcp.storage.LocalDeliveryBackup.BackupMatch match =
                    com.pa.lcr.lcp.storage.LocalDeliveryBackup.findLatestByTicketNo(requireContext(), ticketNo);

                if (match != null) {
                    com.pa.lcr.lcp.storage.LcrDeliveryStatusDb db2 =
                        new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(requireContext());
                    try {
                        long localId = db2.insertDelivery(
                            com.pa.lcr.lcp.storage.LocalDeliveryBackup.toContentValues(match.json));
                        LogBus.api(node, "[WO-DETECT] ticket=" + ticketNo
                            + " — trouvé backup local (backup_ts=" + match.backupTs
                            + "), réinséré en PENDING (id=" + localId + "), reconstruction Dataverse déclenchée");
                        row = db2.getByTicketNo(ticketNo);
                    } finally {
                        try { db2.close(); } catch (Exception ignored) {}
                    }
                    try {
                        com.pa.lcrdemo.dataverse.DeliverySyncScheduler.triggerNow(requireContext().getApplicationContext());
                    } catch (Exception e) {
                        LogBus.api(node, "[WO-DETECT] triggerNow ERR (non-bloquant): " + safeMsg(e));
                    }
                }

                if (row == null || row.woNum == null || row.woNum.isEmpty()) {
                    // ✅ FIX : ne PAS marquer lastTicketDetected ici — sinon un échec temporaire
                    // (token MSAL pas encore prêt, latence réseau, Dataverse pas encore à jour)
                    // bloque tout nouvel essai pour ce ticket, même si rechercherWoDepuisRegistre()
                    // repolle toutes les 800ms. On ne verrouille lastTicketDetected qu'après un
                    // SUCCÈS réel (voir plus bas), pour permettre les tentatives suivantes.
                    // ✅ (11 août 2026) — même principe : logué une seule
                    // fois par ticket, pas à chaque nouvel essai silencieux.
                    if (!ticketNo.equals(dernierTicketWoDetectLogue + ":echec")) {
                        LogBus.api(node, "[WO-DETECT] ticket=" + ticketNo + " — introuvable (local + Dataverse + backup), nouvel essai au prochain poll");
                        dernierTicketWoDetectLogue = ticketNo + ":echec";
                    }
                    lastFailedTicket = ticketNo;
                    lastFailedAttemptMs = System.currentTimeMillis();
                    return;
                }
                LogBus.api(node, "[WO-DETECT] ticket=" + ticketNo + " — reconstruit depuis backup local, wo=" + row.woNum);
            } else {
                LogBus.api(node, "[WO-DETECT] ticket=" + ticketNo + " — trouvé sur Dataverse, wo=" + row.woNum);
            }
        }

        // ✅ Recherche réussie (locale ou Dataverse) — on peut maintenant verrouiller ce ticket
        lastTicketDetected = ticketNo;

        final String fWoNum    = row.woNum;

        // ✅ Un WO connu directement (deep link) a priorité — ne pas l'écraser avec
        // le résultat d'une recherche par ticket_number qui pointerait vers un WO
        // différent (probablement encore le ticket de la livraison PRÉCÉDENTE, le
        // registre n'ayant pas encore émis de nouveau ticket pour la nouvelle
        // livraison). Si le ticket confirme le MÊME WO, on peut lever la priorité
        // en toute sécurité — les deux sources sont maintenant d'accord.
        if (woNumFromDirectSource) {
            if (!fWoNum.equals(currentWoNum)) {
                LogBus.api(node, "[WO-DETECT] ticket=" + ticketNo + " → WO=" + fWoNum
                    + " ignoré, WO direct prioritaire=" + currentWoNum);
                return;
            }
            woNumFromDirectSource = false; // les deux sources concordent désormais
        }

        final String fWoIdGuid = row.woIdGuid != null ? row.woIdGuid : "";
        // ✅ delivery_uid n'est pas stocké tel quel dans LcrDeliveryStatusDb —
        // il se reconstruit toujours de la même façon que dans DeliveryController
        // (numero_livraison + "-" + ticketNo). Permet de valider Field Service
        // Mobile vs l'APK pour ce même WO si une synchronisation a été manquée.
        final String fDeliveryUid = fWoNum + "-" + ticketNo;

        // ✅ Le preset doit venir en priorité de la ligne trouvée dans
        // filgo_delivery_status (row.presetL/row.produitNo — la vraie valeur de
        // CETTE livraison complétée). ActiveDeliveryStore ne sert que pour une
        // livraison EN COURS (deep link pas encore complété) — il est vidé
        // (clear()) une fois la livraison terminée.
        String fPresetStr  = (row.presetL > 0) ? String.valueOf(row.presetL) : null;
        String fProduitStr = (row.produitNo > 0) ? String.valueOf(row.produitNo) : null;
        if (fPresetStr == null || fProduitStr == null) {
            try {
                com.pa.lcr.lcp.storage.ActiveDeliveryStore ads =
                    new com.pa.lcr.lcp.storage.ActiveDeliveryStore(requireContext());
                com.pa.lcr.lcp.storage.ActiveDeliveryStore.ActiveDelivery active = ads.load();
                if (active != null && fWoNum.equals(active.woNum)) {
                    if (fPresetStr == null)  fPresetStr  = String.valueOf(active.preset);
                    if (fProduitStr == null) fProduitStr = String.valueOf(active.produit);
                }
            } catch (Exception ignored) {}
        }
        final String ffPresetStr  = fPresetStr;
        final String ffProduitStr = fProduitStr;

        ui.post(() -> {
            currentWoNum    = fWoNum;
            currentWoIdGuid = fWoIdGuid;
            LogBus.api(node, "[WO-DETECT] WO trouvé=" + fWoNum + " deliveryUid=" + fDeliveryUid);
            if (txtTicketNo != null)
                txtTicketNo.setText("Ticket Number : " + ticketNo);
            if (txtDeliveryUid != null)
                txtDeliveryUid.setText("Delivery UID : " + fDeliveryUid);
            if (ffPresetStr != null && edtPreset != null)
                edtPreset.setText(ffPresetStr);
            if (ffProduitStr != null && spnProduct != null)
                spnProduct.setText(ffProduitStr, false);
            rafraichirCumulWo();
        });
    }

    // ✅ Dialog liste de toutes les livraisons du WO
    private void afficherDetailLivraisonsWo() {
        if (currentWoNum == null || currentWoNum.isEmpty()) return;
        safeBg(() -> {
            try {
                com.pa.lcr.lcp.storage.LcrDeliveryStatusDb db =
                    new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(requireContext());
                java.util.List<com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow> rows;
                try {
                    rows = db.getAllForWo(currentWoNum);
                } finally {
                    try { db.close(); } catch (Exception ignored) {}
                }

                StringBuilder sb = new StringBuilder();
                sb.append("WO : ").append(currentWoNum).append("\n");
                sb.append("─────────────────────────\n");
                double totalNet = 0, totalGross = 0;
                for (com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow r : rows) {
                    if (r.netL > 0) {
                        String uid = currentWoNum + "-" + r.ticketNo;
                        sb.append("Ticket : ").append(r.ticketNo)
                          .append("  (UID: ").append(uid).append(")\n");
                        sb.append(String.format(java.util.Locale.ROOT,
                            "  NET: %.3f L  GROSS: %.3f L\n", r.netL, r.grossL));
                        totalNet   += r.netL;
                        totalGross += r.grossL;
                    }
                }
                sb.append("─────────────────────────\n");
                sb.append(String.format(java.util.Locale.ROOT,
                    "TOTAL NET  : %.3f L\n", totalNet));
                sb.append(String.format(java.util.Locale.ROOT,
                    "TOTAL GROSS: %.3f L\n", totalGross));
                sb.append(rows.size() + " livraison(s)");

                final String msg = sb.toString();
                ui.post(() -> {
                    if (!isAdded() || getView() == null) return;
                    new android.app.AlertDialog.Builder(requireContext())
                        .setTitle("Livraisons — WO " + currentWoNum)
                        .setMessage(msg)
                        .setPositiveButton("Retour", null)
                        .show();
                });
            } catch (Exception e) {
                LogBus.api(node, "[WO-DETAIL] ERR: " + safeMsg(e));
            }
        });
    }


}
