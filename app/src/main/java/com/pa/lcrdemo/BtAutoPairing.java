package com.pa.lcrdemo;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Looper;
import android.util.Log;

import com.pa.lcr.lcp.log.LogBus;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ✅ AJOUTÉ (13 oct 2026, demande Paul — "faisons un test, si ça fonctionne
 * on laisse comme ça, ça marche avec 0000 automatiquement") — appairage
 * Bluetooth automatique d'un registre dont on connaît la MAC (paramètre
 * btmac du deep link).
 *
 * Pourquoi : api_registerConnectAuto() (étape BT → api_btActivate()) ne
 * parcourt QUE les appareils déjà appairés à la tablette. Un registre jamais
 * appairé n'était donc jamais essayé par ce chemin.
 *
 * Fonctionnement :
 *  - MAC déjà appairée → rien à faire, retourne immédiatement (chemin actuel
 *    strictement inchangé).
 *  - Sinon : createBond() + écoute de ACTION_PAIRING_REQUEST ; si l'appareil
 *    demande un PIN, setPin("0000") (les Parani sont programmés à 0000) ;
 *    attente de ACTION_BOND_STATE_CHANGED → BONDED (délai max DELAI_MS).
 *
 * Ne lève jamais d'exception vers l'appelant : tout est retourné dans
 * Resultat, et chaque étape laisse une trace "[APPAIRAGE]" dans Support.
 * À appeler depuis un thread d'arrière-plan (jamais le thread principal :
 * l'appel attend la fin de l'appairage).
 */
public final class BtAutoPairing {

    private static final String TAG = "BtAutoPairing";

    /** PIN programmé dans tous les Parani. */
    public static final String PIN_DEFAUT = "0000";

    /** Attente max de la fin de l'appairage. */
    public static final long DELAI_MS = 30_000L;

    // Valeurs littérales de l'API Android (stables) — évite toute dépendance
    // à des constantes qui varient selon la version du SDK de compilation.
    private static final String EXTRA_PAIRING_VARIANT =
        "android.bluetooth.device.extra.PAIRING_VARIANT";
    private static final int PAIRING_VARIANT_PIN = 0;

    private BtAutoPairing() {}

    /** Résultat d'une demande d'appairage. */
    public static final class Resultat {
        /** true = la MAC est appairée à la tablette (déjà ou à l'instant). */
        public final boolean ok;
        /** true = elle l'était déjà, aucun appairage n'a été lancé. */
        public final boolean dejaAppaire;
        /** Raison lisible (échec) ou courte description (succès). */
        public final String raison;

        Resultat(boolean ok, boolean dejaAppaire, String raison) {
            this.ok = ok;
            this.dejaAppaire = dejaAppaire;
            this.raison = raison;
        }
    }

    private static void trace(int node, String msg) {
        Log.i(TAG, msg);
        try { LogBus.api(node, "[APPAIRAGE] " + msg); } catch (Exception ignored) {}
    }

    private static Resultat echec(int node, String raison) {
        trace(node, "ÉCHEC — " + raison);
        return new Resultat(false, false, raison);
    }

    private static String nomEtat(int st) {
        switch (st) {
            case BluetoothDevice.BOND_NONE:    return "NON_APPAIRE";
            case BluetoothDevice.BOND_BONDING: return "EN_COURS";
            case BluetoothDevice.BOND_BONDED:  return "APPAIRE";
            default:                           return "?" + st;
        }
    }

    /**
     * S'assure que la MAC est appairée à la tablette, en l'appairant avec le
     * PIN par défaut au besoin.
     *
     * @param avantAppairage exécuté juste avant createBond() (ex: afficher un
     *                       toast) — jamais appelé si la MAC est déjà appairée.
     */
    public static Resultat assurerAppairage(Context ctx, String macBrut, int node,
                                            Runnable avantAppairage) {
        final String mac = (macBrut == null) ? "" : macBrut.trim().toUpperCase(Locale.ROOT);
        BroadcastReceiver receiver = null;
        Context app = null;
        try {
            trace(node, "demande reçue — mac=" + mac);

            if (ctx == null) return echec(node, "contexte absent");
            app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;

            if (!BluetoothAdapter.checkBluetoothAddress(mac)) {
                return echec(node, "MAC invalide : \"" + mac + "\"");
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                return echec(node, "appel depuis le thread principal (interdit)");
            }

            final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return echec(node, "Bluetooth non disponible sur cette tablette");
            if (!adapter.isEnabled()) return echec(node, "Bluetooth désactivé");

            if (Build.VERSION.SDK_INT >= 31
                    && app.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                return echec(node, "permission BLUETOOTH_CONNECT non accordée");
            }

            final BluetoothDevice dev = adapter.getRemoteDevice(mac);

            if (dev.getBondState() == BluetoothDevice.BOND_BONDED) {
                trace(node, "déjà appairé — aucune action (mac=" + mac + ")");
                return new Resultat(true, true, "déjà appairé");
            }

            trace(node, "non appairé (état=" + nomEtat(dev.getBondState())
                + ") — lancement de l'appairage, PIN " + PIN_DEFAUT
                + ", délai max " + (DELAI_MS / 1000) + "s");

            if (avantAppairage != null) {
                try { avantAppairage.run(); } catch (Exception ignored) {}
            }

            final CountDownLatch fini = new CountDownLatch(1);
            final AtomicInteger pinEnvoye = new AtomicInteger(0);

            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    try {
                        if (i == null) return;
                        BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                        if (d == null || d.getAddress() == null
                                || !mac.equalsIgnoreCase(d.getAddress())) return;
                        String action = i.getAction();

                        if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(action)) {
                            int variante = i.getIntExtra(EXTRA_PAIRING_VARIANT, -1);
                            if (variante == PAIRING_VARIANT_PIN) {
                                boolean ok = d.setPin(PIN_DEFAUT.getBytes(StandardCharsets.UTF_8));
                                pinEnvoye.incrementAndGet();
                                trace(node, "PIN demandé par l'appareil — setPin(" + PIN_DEFAUT
                                    + ") → " + (ok ? "accepté par le système" : "REFUSÉ par le système"));
                                if (ok) {
                                    // Évite l'affichage de la fenêtre système de saisie du PIN.
                                    try { abortBroadcast(); } catch (Exception ignored) {}
                                }
                            } else {
                                trace(node, "demande d'appairage de variante " + variante
                                    + " (pas un PIN) — non gérée par l'APK");
                            }
                        } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                            int st   = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                                                     BluetoothDevice.BOND_NONE);
                            int prev = i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                                                     BluetoothDevice.BOND_NONE);
                            trace(node, "état d'appairage " + nomEtat(prev) + " → " + nomEtat(st));
                            if (st == BluetoothDevice.BOND_BONDED
                                    || (st == BluetoothDevice.BOND_NONE
                                        && prev == BluetoothDevice.BOND_BONDING)) {
                                fini.countDown();
                            }
                        }
                    } catch (Exception e) {
                        trace(node, "récepteur ERR: " + e.getMessage());
                    }
                }
            };

            IntentFilter filtre = new IntentFilter();
            filtre.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
            filtre.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            filtre.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY - 1);
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filtre, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(receiver, filtre);
            }

            try { adapter.cancelDiscovery(); } catch (Exception ignored) {}

            boolean lance = dev.createBond();
            trace(node, "createBond() → " + lance);
            if (!lance) {
                return echec(node, "le système a refusé de lancer l'appairage (createBond=false)");
            }

            boolean termine = fini.await(DELAI_MS, TimeUnit.MILLISECONDS);
            int etat = dev.getBondState();

            if (etat == BluetoothDevice.BOND_BONDED) {
                trace(node, "APPAIRÉ (mac=" + mac + ", PIN envoyé " + pinEnvoye.get() + "x)");
                // Laisse le temps au profil de se stabiliser avant la connexion.
                try { Thread.sleep(800); } catch (InterruptedException ignored) {}
                return new Resultat(true, false, "appairé");
            }
            if (!termine) {
                return echec(node, "délai de " + (DELAI_MS / 1000) + "s dépassé — registre éteint, "
                    + "hors de portée ou non en attente d'appairage (état=" + nomEtat(etat) + ")");
            }
            return echec(node, "appairage refusé — PIN incorrect ou appareil non appairable "
                + "(PIN envoyé " + pinEnvoye.get() + "x, état=" + nomEtat(etat) + ")");

        } catch (InterruptedException ie) {
            try { Thread.currentThread().interrupt(); } catch (Exception ignored) {}
            return echec(node, "interrompu");
        } catch (Exception e) {
            try { LogBus.err(node, "BtAutoPairing", e); } catch (Exception ignored) {}
            return echec(node, "exception : " + e.getClass().getSimpleName()
                + (e.getMessage() != null ? " — " + e.getMessage() : ""));
        } finally {
            if (receiver != null && app != null) {
                try { app.unregisterReceiver(receiver); } catch (Exception ignored) {}
            }
        }
    }
}
