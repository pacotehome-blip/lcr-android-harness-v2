package com.pa.lcr.lcp.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/**
 * DemandeSupportStore — table locale demande_support (v33, 9 nov 2026).
 *
 * Garde chaque demande de support envoyée, même si le chauffeur ferme l'écran, pour retrouver la réponse
 * du support (n° de ticket) plus tard et allumer la flamme en bleu tant qu'il ne l'a pas consultée.
 */
public class DemandeSupportStore {

    public static final String TABLE = "demande_support";
    public static final String ENVOYEE  = "ENVOYEE";
    public static final String REPONDUE = "REPONDUE";

    private final DeliveryDb helper;

    public DemandeSupportStore(Context ctx) {
        this.helper = new DeliveryDb(ctx.getApplicationContext());
    }

    public static final class Row {
        public long id;
        public String reference, sujet, contexte, commentaire, statut, ticket;
        public boolean avecAudio, teamsOk, vu, avecVideo, envoyeDv, majDv;
        public long envoyeLe, reponduLe;
        public String tourneeId, stopType, stopId, chauffeur, client, citerneId, citerneNom, woNum, serie,
                etatRegistre, erreur, tablette, lienSoti, canal, dvId;
        public int stopSequence, node, nbPhotos;

        static Row from(Cursor c) {
            Row r = new Row();
            r.id = c.getLong(c.getColumnIndexOrThrow("id"));
            r.reference = c.getString(c.getColumnIndexOrThrow("reference"));
            r.sujet = c.getString(c.getColumnIndexOrThrow("sujet"));
            r.contexte = c.getString(c.getColumnIndexOrThrow("contexte"));
            r.commentaire = c.getString(c.getColumnIndexOrThrow("commentaire"));
            r.avecAudio = c.getInt(c.getColumnIndexOrThrow("avec_audio")) != 0;
            r.teamsOk = c.getInt(c.getColumnIndexOrThrow("teams_ok")) != 0;
            r.envoyeLe = c.getLong(c.getColumnIndexOrThrow("envoye_le"));
            r.statut = c.getString(c.getColumnIndexOrThrow("statut"));
            r.ticket = c.getString(c.getColumnIndexOrThrow("ticket"));
            r.reponduLe = c.getLong(c.getColumnIndexOrThrow("repondu_le"));
            r.vu = c.getInt(c.getColumnIndexOrThrow("vu")) != 0;
            r.tourneeId = c.getString(c.getColumnIndexOrThrow("tournee_id"));
            r.stopSequence = c.getInt(c.getColumnIndexOrThrow("stop_sequence"));
            r.stopType = c.getString(c.getColumnIndexOrThrow("stop_type"));
            r.stopId = c.getString(c.getColumnIndexOrThrow("stop_id"));
            r.chauffeur = c.getString(c.getColumnIndexOrThrow("chauffeur"));
            r.client = c.getString(c.getColumnIndexOrThrow("client"));
            r.citerneId = c.getString(c.getColumnIndexOrThrow("citerne_id"));
            r.citerneNom = c.getString(c.getColumnIndexOrThrow("citerne_nom"));
            r.woNum = c.getString(c.getColumnIndexOrThrow("wo_num"));
            r.serie = c.getString(c.getColumnIndexOrThrow("serie"));
            r.node = c.getInt(c.getColumnIndexOrThrow("node"));
            r.etatRegistre = c.getString(c.getColumnIndexOrThrow("etat_registre"));
            r.erreur = c.getString(c.getColumnIndexOrThrow("erreur"));
            r.tablette = c.getString(c.getColumnIndexOrThrow("tablette"));
            r.lienSoti = c.getString(c.getColumnIndexOrThrow("lien_soti"));
            r.nbPhotos = c.getInt(c.getColumnIndexOrThrow("nb_photos"));
            r.avecVideo = c.getInt(c.getColumnIndexOrThrow("avec_video")) != 0;
            r.canal = c.getString(c.getColumnIndexOrThrow("canal"));
            r.envoyeDv = c.getInt(c.getColumnIndexOrThrow("envoye_dv")) != 0;
            r.majDv = c.getInt(c.getColumnIndexOrThrow("maj_dv")) != 0;
            r.dvId = c.getString(c.getColumnIndexOrThrow("dv_id"));
            return r;
        }
    }

    public void enregistrer(Row r) {
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("reference", r.reference);
        v.put("sujet", r.sujet);
        v.put("contexte", r.contexte);
        v.put("commentaire", r.commentaire);
        v.put("avec_audio", r.avecAudio ? 1 : 0);
        v.put("teams_ok", r.teamsOk ? 1 : 0);
        v.put("envoye_le", r.envoyeLe);
        v.put("statut", ENVOYEE);
        v.put("vu", 0);
        v.put("tournee_id", r.tourneeId);
        v.put("stop_sequence", r.stopSequence);
        v.put("stop_type", r.stopType);
        v.put("stop_id", r.stopId);
        v.put("chauffeur", r.chauffeur);
        v.put("client", r.client);
        v.put("citerne_id", r.citerneId);
        v.put("citerne_nom", r.citerneNom);
        v.put("wo_num", r.woNum);
        v.put("serie", r.serie);
        v.put("node", r.node);
        v.put("etat_registre", r.etatRegistre);
        v.put("erreur", r.erreur);
        v.put("tablette", r.tablette);
        v.put("lien_soti", r.lienSoti);
        v.put("nb_photos", r.nbPhotos);
        v.put("avec_video", r.avecVideo ? 1 : 0);
        v.put("canal", r.canal);
        v.put("envoye_dv", 0);
        v.put("maj_dv", 1);
        db.insertWithOnConflict(TABLE, null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /** Demandes encore sans réponse et envoyées il y a moins de {@code maxAgeMs}. */
    public List<Row> enAttente(long maxAgeMs) {
        return lister("statut=? AND envoye_le>=?",
                new String[]{ENVOYEE, String.valueOf(System.currentTimeMillis() - maxAgeMs)}, "envoye_le ASC", 0);
    }

    /** Les {@code n} demandes les plus récentes. */
    public List<Row> recentes(int n) {
        return lister(null, null, "envoye_le DESC", n);
    }

    /** true s'il existe une réponse que le chauffeur n'a pas encore consultée. */
    public boolean aReponseNonVue() {
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT 1 FROM " + TABLE + " WHERE statut=? AND vu=0 LIMIT 1",
                new String[]{REPONDUE})) {
            return c.moveToFirst();
        }
    }

    public void marquerRepondue(String reference, String ticket) {
        ContentValues v = new ContentValues();
        v.put("statut", REPONDUE);
        v.put("ticket", ticket);
        v.put("repondu_le", System.currentTimeMillis());
        v.put("vu", 0);
        v.put("maj_dv", 1);       // à renvoyer à Dataverse (ticket + statut Répondue)
        helper.getWritableDatabase().update(TABLE, v, "reference=?", new String[]{reference});
    }

    /** Demandes à envoyer (jamais envoyées) ou à mettre à jour dans Dataverse. */
    public List<Row> aPousserDataverse() {
        return lister("envoye_dv=0 OR maj_dv=1", null, "envoye_le ASC", 50);
    }

    public void marquerPoussee(String reference, String dvId) {
        ContentValues v = new ContentValues();
        v.put("envoye_dv", 1);
        v.put("maj_dv", 0);
        v.put("dv_id", dvId);
        helper.getWritableDatabase().update(TABLE, v, "reference=?", new String[]{reference});
    }

    public void marquerToutVu() {
        ContentValues v = new ContentValues();
        v.put("vu", 1);
        helper.getWritableDatabase().update(TABLE, v, "vu=0", null);
    }

    private List<Row> lister(String where, String[] args, String order, int limit) {
        List<Row> out = new ArrayList<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor c = db.query(TABLE, null, where, args, null, null, order, limit > 0 ? String.valueOf(limit) : null)) {
            while (c.moveToNext()) out.add(Row.from(c));
        }
        return out;
    }
}
