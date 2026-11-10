package com.pa.lcrdemo.dataverse;

import android.content.Context;
import android.util.Log;

import com.pa.lcr.lcp.log.LogBus;
import com.pa.lcr.lcp.storage.DemandeSupportStore;
import com.pa.lcrdemo.config.LcrConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Envoi des demandes de support à Dataverse (table filgo_demandesupport, 10 nov 2026).
 *
 * Chaque demande est d'abord enregistrée localement (table demande_support) ; ici on la crée dans Dataverse
 * (clé = référence SUP-…), puis on la met à jour quand la réponse d'Octopus arrive (n° de ticket, statut Répondue).
 * Les lignes sont envoyées tant que envoye_dv = 0 ou maj_dv = 1 : hors-ligne ou table absente = on réessaie plus tard.
 * Le nom d'ensemble, la colonne nom et les valeurs du choix filgo_statut sont LUS dans les métadonnées Dataverse.
 */
public final class SupportDataverseSync {

    private static final String TAG = "SupportDataverseSync";
    public static final String TABLE = "filgo_demandesupport";
    private static final long PAUSE_MIN_MS = 45_000L;        // pas plus d'un essai par 45 s (sauf demande immédiate)
    private static final long TABLE_ABSENTE_MS = 10 * 60_000L;

    private static final AtomicBoolean EN_COURS = new AtomicBoolean(false);
    private static volatile long dernierEssaiMs = 0L;
    private static volatile long tableAbsenteJusqua = 0L;

    private SupportDataverseSync() {}

    /** À appeler après un envoi ou une réponse ({@code immediat}) et périodiquement ; retourne tout de suite. */
    public static void pousser(final Context ctx, boolean immediat) {
        final long now = System.currentTimeMillis();
        if (!immediat && now - dernierEssaiMs < PAUSE_MIN_MS) return;
        if (!immediat && now < tableAbsenteJusqua) return;
        if (!EN_COURS.compareAndSet(false, true)) return;
        dernierEssaiMs = now;
        final Context app = ctx.getApplicationContext();
        try {
            if (new DemandeSupportStore(app).aPousserDataverse().isEmpty()) { EN_COURS.set(false); return; }
        } catch (Exception e) {
            EN_COURS.set(false);
            return;
        }
        com.pa.lcrdemo.SupportGraphSender.jetonDataverse(app, new com.pa.lcrdemo.SupportGraphSender.Jeton() {
            @Override public void ok(final String token) {
                new Thread(() -> {
                    try { pousserAvec(app, token); }
                    catch (Exception e) { journal("ERR " + e.getMessage()); }
                    finally { EN_COURS.set(false); }
                }, "support-dv").start();
            }
            @Override public void ko(String raison) {
                journal("pas de jeton Dataverse : " + raison);
                EN_COURS.set(false);
            }
        });
    }

    private static void pousserAvec(Context ctx, String token) throws Exception {
        DemandeSupportStore store = new DemandeSupportStore(ctx);
        List<DemandeSupportStore.Row> lignes = store.aPousserDataverse();
        if (lignes.isEmpty()) return;
        PrintDataverseSync.Meta m;
        try { m = PrintDataverseSync.meta(ctx, token, TABLE); }
        catch (PrintDataverseSync.TableAbsente e) {
            tableAbsenteJusqua = System.currentTimeMillis() + TABLE_ABSENTE_MS;
            journal("table " + TABLE + " absente de Dataverse : envoi reporte (" + lignes.size() + " demande(s) en attente)");
            return;
        }
        Map<Integer, String> statuts = null;
        try { statuts = PrintDataverseSync.options(ctx, token, TABLE, "filgo_statut"); }
        catch (Exception e) { journal("choix filgo_statut illisible : " + e.getMessage()); }

        String org = LcrConfig.getDataverseUrl(ctx);
        int ok = 0, err = 0;
        for (DemandeSupportStore.Row r : lignes) {
            try {
                JSONObject body = corps(r, m, statuts);
                String filtre = m.nameAttr + " eq '" + r.reference.replace("'", "''") + "'";
                String urlFind = org + "/api/data/v9.2/" + m.set + "?$select=" + m.idAttr + "&$top=1&$filter="
                        + java.net.URLEncoder.encode(filtre, "UTF-8").replace("+", "%20");
                JSONArray ex = PrintDataverseSync.getJson(urlFind, token).optJSONArray("value");
                JSONObject rep;
                if (ex != null && ex.length() > 0) {
                    String id = ex.getJSONObject(0).optString(m.idAttr, "");
                    rep = PrintDataverseSync.ecrire("PATCH", org + "/api/data/v9.2/" + m.set + "(" + id + ")?$select=" + m.idAttr, body, token);
                } else {
                    rep = PrintDataverseSync.ecrire("POST", org + "/api/data/v9.2/" + m.set + "?$select=" + m.idAttr, body, token);
                }
                store.marquerPoussee(r.reference, PrintDataverseSync.str(rep, m.idAttr));
                ok++;
            } catch (Exception e) {
                err++;
                journal(r.reference + " non envoyee : " + e.getMessage());
            }
        }
        journal(ok + " demande(s) envoyee(s) a Dataverse" + (err > 0 ? ", " + err + " en erreur (reste a envoyer)" : ""));
    }

    private static JSONObject corps(DemandeSupportStore.Row r, PrintDataverseSync.Meta m, Map<Integer, String> statuts) throws Exception {
        JSONObject b = new JSONObject();
        if (!m.nameAttr.isEmpty()) b.put(m.nameAttr, r.reference);
        put(b, "filgo_sujet", r.sujet, 400);
        put(b, "filgo_numeroticket", r.ticket, 20);
        b.put("filgo_envoyele", iso(r.envoyeLe));
        if (r.reponduLe > 0) b.put("filgo_repondule", iso(r.reponduLe));
        put(b, "filgo_tablette", r.tablette, 100);
        put(b, "filgo_lien_soti", r.lienSoti, 400);
        put(b, "filgo_citerne_id", r.citerneId, 50);
        put(b, "filgo_citerne_nom", r.citerneNom, 100);
        put(b, "filgo_wo_num", r.woNum, 50);
        put(b, "filgo_tournee_id", r.tourneeId, 50);
        if (r.stopSequence > 0) b.put("filgo_stop_sequence", r.stopSequence);
        put(b, "filgo_stop_type", r.stopType, 50);
        put(b, "filgo_stop_id", r.stopId, 50);
        put(b, "filgo_chauffeur", r.chauffeur, 100);
        put(b, "filgo_client", r.client, 200);
        put(b, "filgo_serie_registre", r.serie, 20);
        if (r.node > 0) b.put("filgo_node", r.node);
        put(b, "filgo_etat_registre", r.etatRegistre, 200);
        put(b, "filgo_erreur", r.erreur, 2000);
        put(b, "filgo_commentaire", r.commentaire, 4000);
        put(b, "filgo_contexte", r.contexte, 4000);
        b.put("filgo_teams_ok", r.teamsOk);
        b.put("filgo_avec_audio", r.avecAudio);
        b.put("filgo_nb_photos", r.nbPhotos);
        b.put("filgo_avec_video", r.avecVideo);
        Integer st = statut(statuts, DemandeSupportStore.REPONDUE.equals(r.statut) ? "repond" : "envoy");
        if (st != null) b.put("filgo_statut", st);
        return b;
    }

    /** Valeur du choix dont le libellé (sans accents, minuscules) commence par {@code debut}. */
    private static Integer statut(Map<Integer, String> options, String debut) {
        if (options == null) return null;
        for (Map.Entry<Integer, String> e : options.entrySet()) {
            String l = Normalizer.normalize(e.getValue() == null ? "" : e.getValue(), Normalizer.Form.NFD)
                    .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
            if (l.startsWith(debut)) return e.getKey();
        }
        journal("aucun choix filgo_statut ne commence par « " + debut + " » : statut non envoye");
        return null;
    }

    private static void put(JSONObject b, String col, String v, int max) throws Exception {
        if (v == null || v.trim().isEmpty()) return;
        b.put(col, v.length() > max ? v.substring(0, max) : v);
    }

    private static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    private static void journal(String msg) {
        Log.i(TAG, msg);
        try { LogBus.api(0, "[SUPPORT-SYNC] " + msg); } catch (Exception ignored) {}
    }
}
