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
public final class SupportGraphSender {

    private static final String TAG = "SupportGraph";
    private static final String GRAPH = "https://graph.microsoft.com/v1.0";

    interface Resultat {
        void courrielEnvoye(boolean teamsOk);
        void echec(String raison);
    }

    private SupportGraphSender() {}

    public interface Jeton { void ok(String t); void ko(String raison); }

    /** Instance MSAL conservée (une seule, évite de multiplier les instances qui se disputent le cache). */
    private static volatile MsalTokenProvider prov;

    /** Jeton Graph SILENCIEUX pour les scopes donnés. Verrou MSAL tenu seulement pendant l'obtention du jeton. */
    private static void jeton(android.content.Context act, final String[] scopes, final Jeton j) {
        try {
            MsalTokenProvider.MSAL_SERIAL_LOCK.acquire();
        } catch (InterruptedException e) {
            j.ko("interrompu");
            return;
        }
        final MsalTokenProvider p = prov;
        if (p != null) { jetonAvec(p, scopes, j); return; }
        final MsalTokenProvider neuf = new MsalTokenProvider(act.getApplicationContext());
        neuf.init(new MsalTokenProvider.InitCallback() {
            @Override public void onReady() { prov = neuf; jetonAvec(neuf, scopes, j); }
            @Override public void onError(Exception e) {
                MsalTokenProvider.MSAL_SERIAL_LOCK.release();
                j.ko("MSAL : " + e.getMessage());
            }
        });
    }

    private static void jetonAvec(MsalTokenProvider p, String[] scopes, final Jeton j) {
        p.acquireGraphTokenSilent(scopes, new MsalTokenProvider.TokenCallback() {
            @Override public void onSuccess(String token) {
                MsalTokenProvider.MSAL_SERIAL_LOCK.release();
                j.ok(token);
            }
            @Override public void onError(Exception e) {
                MsalTokenProvider.MSAL_SERIAL_LOCK.release();
                j.ko("token Graph : " + e.getMessage());
            }
        });
    }

    /** Jeton Dataverse SILENCIEUX (même instance MSAL, même verrou que les jetons Graph). */
    public static void jetonDataverse(android.content.Context ctx, final Jeton j) {
        try {
            MsalTokenProvider.MSAL_SERIAL_LOCK.acquire();
        } catch (InterruptedException e) {
            j.ko("interrompu");
            return;
        }
        final MsalTokenProvider p = prov;
        if (p != null) { jetonDvAvec(p, j); return; }
        final MsalTokenProvider neuf = new MsalTokenProvider(ctx.getApplicationContext());
        neuf.init(new MsalTokenProvider.InitCallback() {
            @Override public void onReady() { prov = neuf; jetonDvAvec(neuf, j); }
            @Override public void onError(Exception e) {
                MsalTokenProvider.MSAL_SERIAL_LOCK.release();
                j.ko("MSAL : " + e.getMessage());
            }
        });
    }

    private static void jetonDvAvec(MsalTokenProvider p, final Jeton j) {
        p.acquireTokenSilentFromWorker(new MsalTokenProvider.TokenCallback() {
            @Override public void onSuccess(String token) {
                MsalTokenProvider.MSAL_SERIAL_LOCK.release();
                j.ok(token);
            }
            @Override public void onError(Exception e) {
                MsalTokenProvider.MSAL_SERIAL_LOCK.release();
                j.ko("jeton Dataverse : " + e.getMessage());
            }
        });
    }

    static void envoyer(android.content.Context act, final String sujet, final String corps, final String lienSoti,
                        final File journal, final File audio, final java.util.List<File> medias, final Resultat cb) {
        jeton(act, MsalTokenProvider.GRAPH_SCOPES, new Jeton() {
            @Override public void ok(String token) { poster(act, token, sujet, corps, lienSoti, journal, audio, medias, cb); }
            @Override public void ko(String raison) { cb.echec(raison); }
        });
    }

    // ------------------------------------------------------------------ n° de ticket

    interface Ticket {
        /** Réponse trouvée pour {@code reference} ; {@code numero} peut être null si aucun n° n'est reconnu. */
        void trouve(String reference, String numero);
        /** Vérification terminée (qu'il y ait eu des réponses ou non, ou que la lecture ait échoué). */
        void termine();
    }

    /**
     * Cherche dans la boîte de réception, parmi les messages reçus depuis {@code depuisMs}, la réponse du
     * support qui reprend la référence de chacune des demandes en attente, puis en extrait le n° de ticket
     * (SimplifiedConfig.SUPPORT_TICKET_REGEX). Appelle {@code trouve} par demande résolue, puis {@code termine}.
     */
    static void chercherTicket(android.content.Context ctx, final java.util.List<com.pa.lcr.lcp.storage.DemandeSupportStore.Row> attente,
                               final long depuisMs, final Ticket cb) {
        jeton(ctx, MsalTokenProvider.GRAPH_SCOPES_LECTURE, new Jeton() {
            @Override public void ok(final String token) {
                new Thread(() -> {
                    try { lire(token, attente, depuisMs, cb); }
                    catch (Exception e) { Log.w(TAG, "ticket ERR : " + e.getMessage()); }
                    finally { cb.termine(); }
                }, "support-ticket").start();
            }
            @Override public void ko(String raison) { Log.w(TAG, "ticket : " + raison); cb.termine(); }
        });
    }

    private static void lire(String token, java.util.List<com.pa.lcr.lcp.storage.DemandeSupportStore.Row> attente,
                             long depuisMs, Ticket cb) throws Exception {
        java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ROOT);
        iso.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String filtre = "receivedDateTime ge " + iso.format(new java.util.Date(depuisMs - 60_000L));
        String url = GRAPH + "/me/mailFolders/inbox/messages?$select=subject,body,receivedDateTime"
                + "&$orderby=" + URLEncoder.encode("receivedDateTime desc", "UTF-8")
                + "&$top=50&$filter=" + URLEncoder.encode(filtre, "UTF-8");
        String rep = get(token, url);
        if (rep == null) return;
        JSONArray vals = new JSONObject(rep).optJSONArray("value");
        if (vals == null) return;
        java.util.regex.Pattern motif = java.util.regex.Pattern.compile(SimplifiedConfig.SUPPORT_TICKET_REGEX);
        for (com.pa.lcr.lcp.storage.DemandeSupportStore.Row d : attente) {
            for (int i = 0; i < vals.length(); i++) {
                JSONObject m = vals.getJSONObject(i);
                JSONObject corpsMsg = m.optJSONObject("body");
                String texte = m.optString("subject", "") + "\n" + (corpsMsg != null ? corpsMsg.optString("content", "") : "");
                if (!texte.contains(d.reference)) continue;
                java.util.regex.Matcher mt = motif.matcher(texte.replace(d.reference, ""));
                String numero = null;
                if (mt.find()) numero = mt.groupCount() >= 1 && mt.group(1) != null ? mt.group(1) : mt.group();
                cb.trouve(d.reference, numero);
                break;
            }
        }
    }

    /** @return le corps de la réponse (2xx) ou null. */
    private static String get(String token, String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("GET");
            c.setConnectTimeout(10_000);
            c.setReadTimeout(20_000);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Prefer", "outlook.body-content-type=\"text\"");   // corps COMPLET en texte (bodyPreview est tronqué)
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) { Log.w(TAG, "GET HTTP " + code); return null; }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            }
            return bo.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static void poster(final android.content.Context ctx, final String token, final String sujet, final String corps, final String lienSoti,
                               final File journal, final File audio, final java.util.List<File> medias, final Resultat cb) {
        // Les appels réseau ne doivent pas se faire sur le thread du callback MSAL.
        new Thread(() -> {
            try {
                String err = envoyerCourriel(token, sujet, corps, journal, audio, medias);
                if (err != null) { cb.echec("sendMail : " + err); return; }
            } catch (Exception e) {
                cb.echec("sendMail : " + e.getMessage());
                return;
            }
            boolean teamsOk = false;
            if (!SimplifiedConfig.SUPPORT_TEAMS_CHAT_ID.isEmpty()) {
                try {
                    String err = envoyerTeams(ctx, token, sujet, corps, lienSoti, journal, audio);
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
                                          File journal, File audio, java.util.List<File> medias) throws Exception {
        JSONArray pj = new JSONArray();
        int budget = SimplifiedConfig.SUPPORT_MAX_PJ_OCTETS;
        int nonJoints = 0;
        // Priorité : photos/vidéo, puis message vocal, puis journal (tronqué au besoin).
        if (medias != null) {
            for (File f : medias) {
                if (f == null || !f.exists()) continue;
                if (f.length() > budget) { nonJoints++; continue; }
                String type = f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".mp4") ? "video/mp4" : "image/jpeg";
                pj.put(pieceJointe(f.getName(), type, lire(f, budget)));
                budget -= (int) f.length();
            }
        }
        if (audio != null && audio.exists()) {
            if (audio.length() <= budget) {
                pj.put(pieceJointe(audio.getName(), "audio/mp4", lire(audio, budget)));
                budget -= (int) audio.length();
            } else nonJoints++;
        }
        boolean tronque = journal.length() > budget;
        pj.put(pieceJointe(journal.getName(), "text/plain", lire(journal, Math.max(budget, 10_000))));
        if (nonJoints > 0) corps = corps + "\n\n(" + nonJoints + " pièce(s) jointe(s) non envoyée(s) : trop volumineuse(s))";

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

    /**
     * Message dans la conversation Teams. Le journal et le message vocal y sont joints comme fichiers :
     * déposés dans OneDrive (dossier « Microsoft Teams Chat Files ») puis référencés dans le message.
     * Si le jeton OneDrive (Files.ReadWrite) ou un dépôt échoue, le message part quand même, sans ces fichiers.
     */
    private static String envoyerTeams(android.content.Context ctx, String token, String sujet, String corps,
                                       String lienSoti, File journal, File audio) throws Exception {
        JSONArray attaches = new JSONArray();
        StringBuilder balises = new StringBuilder();
        String fichiersJetons = jetonFichiers(ctx);
        if (fichiersJetons != null) {
            for (File f : new File[]{audio, journal}) {
                if (f == null || !f.exists()) continue;
                try {
                    JSONObject a = deposerOneDrive(fichiersJetons, f);
                    if (a == null) continue;
                    attaches.put(a);
                    balises.append("<attachment id=\"").append(a.getString("id")).append("\"></attachment>");
                } catch (Exception e) {
                    Log.w(TAG, "dépôt OneDrive de " + f.getName() + " ERR : " + e.getMessage());
                }
            }
        }
        String html = "<b>" + echapper(sujet) + "</b><br>"
                + echapper(corps).replace("\n", "<br>")
                + (lienSoti.isEmpty() ? "" : "<br><a href=\"" + echapper(lienSoti).replace("\"", "%22")
                        + "\">Ouvrir la tablette dans SOTI MobiControl</a>")
                + (attaches.length() > 0 ? "<br>" + balises
                        : "<br><i>Journal et message vocal : voir le courriel envoyé à "
                          + echapper(SimplifiedConfig.SUPPORT_EMAIL) + "</i>");
        JSONObject req = new JSONObject();
        req.put("body", new JSONObject().put("contentType", "html").put("content", html));
        if (attaches.length() > 0) req.put("attachments", attaches);
        String url = GRAPH + "/chats/" + URLEncoder.encode(SimplifiedConfig.SUPPORT_TEAMS_CHAT_ID, "UTF-8") + "/messages";
        return post(token, url, req);
    }

    /** Jeton Graph « fichiers » (Files.ReadWrite), SILENCIEUX ; null s'il n'est pas disponible (consentement absent…). */
    private static String jetonFichiers(android.content.Context ctx) {
        final String[] rep = new String[1];
        final java.util.concurrent.CountDownLatch fin = new java.util.concurrent.CountDownLatch(1);
        jeton(ctx, MsalTokenProvider.GRAPH_SCOPES_FICHIERS, new Jeton() {
            @Override public void ok(String t) { rep[0] = t; fin.countDown(); }
            @Override public void ko(String raison) { Log.w(TAG, "OneDrive indisponible : " + raison); fin.countDown(); }
        });
        try { fin.await(30, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return rep[0];
    }

    /**
     * Dépose le fichier dans OneDrive (dossier des fichiers de clavardage Teams), crée un lien de lecture
     * pour l'organisation, et retourne la pièce jointe Teams {id, contentType=reference, contentUrl, name}.
     * Dépôt simple : 4 Mo au plus (sinon null).
     */
    private static JSONObject deposerOneDrive(String token, File f) throws Exception {
        if (f.length() > 4_000_000L) { Log.w(TAG, f.getName() + " trop gros pour Teams"); return null; }
        byte[] data = lire(f, 4_000_000);
        String type = f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".m4a") ? "audio/mp4" : "text/plain";
        String chemin = "Microsoft%20Teams%20Chat%20Files/" + URLEncoder.encode(f.getName(), "UTF-8").replace("+", "%20");
        String[] up = requete("PUT", token, GRAPH + "/me/drive/root:/" + chemin
                + ":/content?@microsoft.graph.conflictBehavior=rename", data, type);
        if (!up[0].startsWith("2")) throw new RuntimeException("dépôt HTTP " + up[0] + " " + up[1]);
        JSONObject item = new JSONObject(up[1]);
        String idItem = item.getString("id");
        String nom = item.optString("name", f.getName());
        String webUrl = item.optString("webUrl", "");

        JSONObject lien = new JSONObject().put("type", "view").put("scope", "organization");
        String[] cl = requete("POST", token, GRAPH + "/me/drive/items/" + idItem + "/createLink",
                lien.toString().getBytes(StandardCharsets.UTF_8), "application/json");
        if (cl[0].startsWith("2")) {
            JSONObject l = new JSONObject(cl[1]).optJSONObject("link");
            if (l != null && !l.optString("webUrl", "").isEmpty()) webUrl = l.getString("webUrl");
        } else {
            Log.w(TAG, "createLink HTTP " + cl[0] + " " + cl[1]);
        }
        if (webUrl.isEmpty()) return null;

        // L'id de la pièce jointe Teams est le GUID de l'eTag : "{GUID},n"
        String etag = item.optString("eTag", "");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{([0-9A-Fa-f-]{36})\\}").matcher(etag);
        String guid = m.find() ? m.group(1) : java.util.UUID.randomUUID().toString();
        return new JSONObject().put("id", guid).put("contentType", "reference")
                .put("contentUrl", webUrl).put("name", nom);
    }

    /** @return {code HTTP, corps} (corps limité à 4000 caractères). */
    private static String[] requete(String methode, String token, String url, byte[] data, String contentType) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(methode);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(60_000);
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Content-Type", contentType);
            c.setFixedLengthStreamingMode(data.length);
            try (OutputStream os = c.getOutputStream()) { os.write(data); }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (is != null) {
                byte[] buf = new byte[8192]; int n;
                while ((n = is.read(buf)) > 0 && bo.size() < 200_000) bo.write(buf, 0, n);
            }
            String corps = bo.toString("UTF-8");
            return new String[]{String.valueOf(code), code >= 400 && corps.length() > 400 ? corps.substring(0, 400) : corps};
        } finally {
            c.disconnect();
        }
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
