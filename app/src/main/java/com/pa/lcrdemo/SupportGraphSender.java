package com.pa.lcrdemo;

import android.app.Activity;
import android.util.Base64;
import android.util.Log;

import com.pa.lcrdemo.auth.MsalTokenProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * ✅ AJOUTÉ (9 nov 2026, demande Paul) — envoi silencieux de la demande de support par Microsoft Graph.
 *
 *  1) Courriel à SimplifiedConfig.SUPPORT_EMAIL (crée le ticket) avec journal + message vocal en pièces jointes :
 *     POST /v1.0/me/sendMail                      (permission déléguée Mail.Send)
 *  2) Message dans la conversation Teams « Support Camion » (résumé seulement ; les pièces jointes sont dans
 *     le courriel) : POST /v1.0/chats/{id}/messages   (permission déléguée ChatMessage.Send)
 *
 * Token SILENCIEUX seulement (aucun écran de connexion). Si le token Graph ou le courriel échoue
 * (consentement Entra manquant, hors-ligne…), {@link Resultat#echec} est appelé et l'appelant utilise son repli.
 * Un échec Teams seul n'annule pas le succès du courriel.
 */
final class SupportGraphSender {

    private static final String TAG = "SupportGraph";
    private static final String GRAPH = "https://graph.microsoft.com/v1.0";

    interface Resultat {
        void courrielEnvoye(boolean teamsOk);
        void echec(String raison);
    }

    private SupportGraphSender() {}

    static void envoyer(Activity act, String sujet, String corps, String lienSoti, File journal, File audio, Resultat cb) {
        final MsalTokenProvider msal = new MsalTokenProvider(act);
        try {
            MsalTokenProvider.MSAL_SERIAL_LOCK.acquire();
        } catch (InterruptedException e) {
            cb.echec("interrompu");
            return;
        }
        msal.init(new MsalTokenProvider.InitCallback() {
            @Override public void onReady() {
                msal.acquireGraphTokenSilent(new MsalTokenProvider.TokenCallback() {
                    @Override public void onSuccess(String token) {
                        MsalTokenProvider.MSAL_SERIAL_LOCK.release();
                        poster(token, sujet, corps, lienSoti, journal, audio, cb);
                    }
                    @Override public void onError(Exception e) {
                        MsalTokenProvider.MSAL_SERIAL_LOCK.release();
                        cb.echec("token Graph : " + e.getMessage());
                    }
                });
            }
            @Override public void onError(Exception e) {
                MsalTokenProvider.MSAL_SERIAL_LOCK.release();
                cb.echec("MSAL : " + e.getMessage());
            }
        });
    }

    private static void poster(final String token, final String sujet, final String corps, final String lienSoti,
                               final File journal, final File audio, final Resultat cb) {
        // Les appels réseau ne doivent pas se faire sur le thread du callback MSAL.
        new Thread(() -> {
            try {
                String err = envoyerCourriel(token, sujet, corps, journal, audio);
                if (err != null) { cb.echec("sendMail : " + err); return; }
            } catch (Exception e) {
                cb.echec("sendMail : " + e.getMessage());
                return;
            }
            boolean teamsOk = false;
            if (!SimplifiedConfig.SUPPORT_TEAMS_CHAT_ID.isEmpty()) {
                try {
                    String err = envoyerTeams(token, sujet, corps, lienSoti);
                    teamsOk = err == null;
                    if (err != null) Log.w(TAG, "Teams : " + err);
                } catch (Exception e) {
                    Log.w(TAG, "Teams ERR : " + e.getMessage());
                }
            }
            cb.courrielEnvoye(teamsOk);
        }, "support-graph").start();
    }

    // ------------------------------------------------------------------ courriel

    private static String envoyerCourriel(String token, String sujet, String corps,
                                          File journal, File audio) throws Exception {
        JSONArray pj = new JSONArray();
        int budget = SimplifiedConfig.SUPPORT_MAX_PJ_OCTETS;
        if (audio != null && audio.exists() && audio.length() <= budget) {
            pj.put(pieceJointe(audio.getName(), "audio/mp4", lire(audio, budget)));
            budget -= (int) audio.length();
        }
        boolean tronque = journal.length() > budget;
        pj.put(pieceJointe(journal.getName(), "text/plain", lire(journal, Math.max(budget, 10_000))));

        JSONObject body = new JSONObject();
        body.put("contentType", "Text");
        body.put("content", corps + (tronque ? "\n\n(Journal tronqué : trop volumineux pour le courriel)" : ""));

        JSONObject msg = new JSONObject();
        msg.put("subject", sujet);
        msg.put("body", body);
        msg.put("toRecipients", new JSONArray().put(new JSONObject().put("emailAddress",
                new JSONObject().put("address", SimplifiedConfig.SUPPORT_EMAIL))));
        msg.put("attachments", pj);

        JSONObject req = new JSONObject();
        req.put("message", msg);
        req.put("saveToSentItems", true);
        return post(token, GRAPH + "/me/sendMail", req);   // 202 Accepted, sans corps
    }

    private static JSONObject pieceJointe(String nom, String type, byte[] octets) throws Exception {
        return new JSONObject()
                .put("@odata.type", "#microsoft.graph.fileAttachment")
                .put("name", nom)
                .put("contentType", type)
                .put("contentBytes", Base64.encodeToString(octets, Base64.NO_WRAP));
    }

    /** Lit au plus {@code max} octets (le début du fichier si plus gros). */
    private static byte[] lire(File f, int max) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.min(f.length(), max));
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n, total = 0;
            while (total < max && (n = in.read(buf, 0, Math.min(buf.length, max - total))) > 0) {
                bo.write(buf, 0, n);
                total += n;
            }
        }
        return bo.toByteArray();
    }

    // ------------------------------------------------------------------ Teams

    private static String envoyerTeams(String token, String sujet, String corps, String lienSoti) throws Exception {
        String html = "<b>" + echapper(sujet) + "</b><br>"
                + echapper(corps).replace("\n", "<br>")
                + (lienSoti.isEmpty() ? "" : "<br><a href=\"" + echapper(lienSoti).replace("\"", "%22")
                        + "\">Ouvrir la tablette dans SOTI MobiControl</a>")
                + "<br><i>Courriel détaillé (journal, message vocal) envoyé à "
                + echapper(SimplifiedConfig.SUPPORT_EMAIL) + "</i>";
        JSONObject req = new JSONObject();
        req.put("body", new JSONObject().put("contentType", "html").put("content", html));
        String url = GRAPH + "/chats/" + URLEncoder.encode(SimplifiedConfig.SUPPORT_TEAMS_CHAT_ID, "UTF-8") + "/messages";
        return post(token, url, req);
    }

    private static String echapper(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ------------------------------------------------------------------ HTTP

    /** @return null si succès (2xx), sinon le code HTTP et le début de la réponse. */
    private static String post(String token, String url, JSONObject json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(10_000);
            c.setReadTimeout(30_000);
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) { os.write(data); }
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) return null;
            InputStream es = c.getErrorStream();
            String rep = "";
            if (es != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[2048]; int n;
                while ((n = es.read(buf)) > 0 && bo.size() < 600) bo.write(buf, 0, n);
                rep = bo.toString("UTF-8");
            }
            return "HTTP " + code + " " + rep;
        } finally {
            c.disconnect();
        }
    }
}
