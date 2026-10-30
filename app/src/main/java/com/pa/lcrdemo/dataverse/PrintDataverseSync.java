package com.pa.lcrdemo.dataverse;

import android.content.Context;
import android.util.Log;

import com.pa.lcr.lcp.log.LogBus;
import com.pa.lcr.lcp.storage.BanniereImpressionStore;
import com.pa.lcr.lcp.storage.FormulaireImpressionStore;
import com.pa.lcrdemo.config.LcrConfig;
import com.pa.lcrdemo.print.PrintServiceClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Synchronisation des formulaires et bannieres d'impression : D'ABORD Dataverse -> tables locales de
 * Filgo-Registre (lcr_delivery.db), ENSUITE tables locales -> service d'impression (/print-form). 30 oct 2026.
 *
 * Filgo-Registre est le seul lien entre Dataverse et le service d'impression : le service ne parle jamais a
 * Dataverse. Dataverse a toujours raison.
 *
 * Tolerance : si une table n'existe pas encore dans Dataverse (HTTP 404), elle est ignoree sans erreur et le
 * service continue d'imprimer avec ses formulaires embarques. Aucune ligne n'est retiree si la lecture de
 * Dataverse n'a pas reussi en entier.
 *
 * Hypotheses sur les tables (a CONFIRMER a leur creation, voir IMPRESSION_DEEPLINK_ET_DATAVERSE.md 2.3 et 2.4) :
 *  - filgo_print_forms : cle filgo_print_formid ; colonnes filgo_code, filgo_name, filgo_format (choix dont le LIBELLE
 *    est GRAND, PETIT ou REGISTRE), filgo_version, filgo_manifest (texte JSON), filgo_pdf_sha256, colonne FICHIER
 *    filgo_pdf (telechargee par .../filgo_pdf/$value) ;
 *  - filgo_print_banners : cle filgo_print_bannerid ; colonnes filgo_code, filgo_name, filgo_address_line1,
 *    filgo_city, filgo_province, filgo_postalcode, filgo_phone, filgo_tollfreephone, filgo_tax_info,
 *    filgo_footer_text, filgo_company (choix), filgo_version, colonne IMAGE filgo_logo (.../filgo_logo/$value).
 * Si un nom de colonne differe, la lecture echoue avec le message de Dataverse dans le journal (aucun effet sur les
 * donnees locales).
 */
public final class PrintDataverseSync {

    private static final String TAG = "PrintDataverseSync";
    private static final String ANNOT = "OData.Community.Display.V1.FormattedValue";

    private PrintDataverseSync() {}

    /** Lecture inachevee ou impossible : rien ne doit etre retire. */
    private static final class TableAbsente extends Exception {
        TableAbsente(String m) { super(m); }
    }

    /** Dataverse d'abord (si un jeton est fourni), puis le service. Ne leve jamais d'exception. */
    public static void syncAll(Context ctx, String accessToken) {
        if (accessToken != null && !accessToken.isEmpty()) {
            try { lireFormulaires(ctx, accessToken); }
            catch (Exception e) { journal("formulaires: lecture Dataverse ERR " + e.getMessage()); }
            try { lireBannieres(ctx, accessToken); }
            catch (Exception e) { journal("bannieres: lecture Dataverse ERR " + e.getMessage()); }
        } else {
            journal("pas de jeton Dataverse : seule l'etape service est faite");
        }
        try { envoyerFormulairesAuService(ctx); }
        catch (Exception e) { journal("formulaires: envoi au service ERR " + e.getMessage()); }
    }

    // ------------------------------------------------------------------ Dataverse -> tables locales

    private static void lireFormulaires(Context ctx, String token) throws Exception {
        String org = LcrConfig.getDataverseUrl(ctx);
        String set = LcrConfig.DV_PRINT_FORMS_SET;
        String url = org + "/api/data/v9.2/" + set
                + "?$select=filgo_print_formid,filgo_code,filgo_name,filgo_format,filgo_version,filgo_manifest,"
                + "filgo_pdf_sha256,modifiedon,statecode";
        List<JSONObject> rows;
        try {
            rows = lireToutesLesLignes(url, token);
        } catch (TableAbsente e) {
            journal("formulaires: table " + set + " absente de Dataverse : ignoree (le service garde ses formulaires embarques)");
            return;
        }
        FormulaireImpressionStore store = new FormulaireImpressionStore(ctx);
        Set<String> codesDataverse = new HashSet<>();
        int nouveaux = 0, ignores = 0;
        for (JSONObject r : rows) {
            String code = FormulaireImpressionStore.keyOf(r.optString("filgo_code", ""));
            if (code.isEmpty()) { ignores++; continue; }
            String format = libelle(r, "filgo_format").trim().toUpperCase(Locale.ROOT);
            if (!format.equals("GRAND") && !format.equals("PETIT") && !format.equals("REGISTRE")) {
                journal("formulaires: " + code + " ignore, format inconnu '" + format + "' (GRAND, PETIT ou REGISTRE)");
                ignores++;
                continue;
            }
            codesDataverse.add(code);
            FormulaireImpressionStore.Row n = new FormulaireImpressionStore.Row();
            n.code = code;
            n.name = r.optString("filgo_name", "");
            n.format = format;
            n.version = r.optString("filgo_version", "");
            n.active = r.optInt("statecode", 0) == 0;
            n.manifest = r.optString("filgo_manifest", "");
            n.dataverseId = r.optString("filgo_print_formid", "");
            n.dataverseModified = r.optString("modifiedon", "");
            String shaAnnonce = r.optString("filgo_pdf_sha256", "").trim();

            FormulaireImpressionStore.Row old = store.get(code);
            boolean inchange = old != null && old.dataverseModified.equals(n.dataverseModified) && old.pdf != null;
            if (n.active && !"REGISTRE".equals(format)) {
                if (inchange) {
                    n.pdf = old.pdf;
                    n.pdfSha256 = old.pdfSha256;
                } else {
                    byte[] pdf = null;
                    try {
                        pdf = telecharger(org + "/api/data/v9.2/" + set + "(" + n.dataverseId + ")/filgo_pdf/$value", token);
                    } catch (Exception e) {
                        journal("formulaires: " + code + " ignore, telechargement du PDF impossible : " + e.getMessage());
                        continue;
                    }
                    if (pdf == null || pdf.length == 0) {
                        journal("formulaires: " + code + " ignore, PDF absent dans Dataverse");
                        continue;
                    }
                    String sha = sha256(pdf);
                    if (!shaAnnonce.isEmpty() && !shaAnnonce.equalsIgnoreCase(sha)) {
                        journal("formulaires: " + code + " ignore, sha256 du PDF different de celui annonce par Dataverse");
                        continue;
                    }
                    n.pdf = pdf;
                    n.pdfSha256 = sha;
                }
            } else if (old != null) {
                n.pdf = old.pdf; // inactif ou REGISTRE : rien a telecharger
                n.pdfSha256 = old.pdfSha256;
            }
            if (store.upsertFromDataverse(n)) nouveaux++;
        }
        // Lignes locales que Dataverse n'a plus : retirees (et retirees du service), seulement parce que la lecture est complete.
        int retires = 0;
        for (FormulaireImpressionStore.Row l : store.listAll()) {
            if (!codesDataverse.contains(l.code)) {
                PrintServiceClient.deleteForm(l.code);
                store.delete(l.code);
                retires++;
            }
        }
        journal("formulaires: Dataverse lu (" + rows.size() + " ligne(s), " + nouveaux + " nouveau(x)/modifie(s), "
                + retires + " retire(s), " + ignores + " ignore(s))");
    }

    private static void lireBannieres(Context ctx, String token) throws Exception {
        String org = LcrConfig.getDataverseUrl(ctx);
        String set = LcrConfig.DV_PRINT_BANNERS_SET;
        String url = org + "/api/data/v9.2/" + set
                + "?$select=filgo_print_bannerid,filgo_code,filgo_name,filgo_address_line1,filgo_city,filgo_province,"
                + "filgo_postalcode,filgo_phone,filgo_tollfreephone,filgo_tax_info,filgo_footer_text,filgo_company,"
                + "filgo_version,modifiedon,statecode";
        List<JSONObject> rows;
        try {
            rows = lireToutesLesLignes(url, token);
        } catch (TableAbsente e) {
            journal("bannieres: table " + set + " absente de Dataverse : ignoree");
            return;
        }
        BanniereImpressionStore store = new BanniereImpressionStore(ctx);
        Set<String> codes = new HashSet<>();
        int nouvelles = 0;
        for (JSONObject r : rows) {
            String code = BanniereImpressionStore.keyOf(r.optString("filgo_code", ""));
            if (code.isEmpty()) continue;
            codes.add(code);
            BanniereImpressionStore.Row n = new BanniereImpressionStore.Row();
            n.code = code;
            n.name = r.optString("filgo_name", "");
            n.addressLine1 = r.optString("filgo_address_line1", "");
            n.city = r.optString("filgo_city", "");
            n.province = r.optString("filgo_province", "");
            n.postalCode = r.optString("filgo_postalcode", "");
            n.phone = r.optString("filgo_phone", "");
            n.tollFreePhone = r.optString("filgo_tollfreephone", "");
            n.taxInfo = r.optString("filgo_tax_info", "");
            n.footerText = r.optString("filgo_footer_text", "");
            n.company = libelle(r, "filgo_company");
            n.version = r.optString("filgo_version", "");
            n.active = r.optInt("statecode", 0) == 0;
            n.dataverseId = r.optString("filgo_print_bannerid", "");
            n.dataverseModified = r.optString("modifiedon", "");
            BanniereImpressionStore.Row old = store.get(code);
            boolean inchange = old != null && old.dataverseModified.equals(n.dataverseModified) && old.version.equals(n.version);
            if (inchange) continue;
            if (n.active) {
                try {
                    byte[] logo = telecharger(org + "/api/data/v9.2/" + set + "(" + n.dataverseId + ")/filgo_logo/$value", token);
                    if (logo != null && logo.length > 0) { n.logo = logo; n.logoSha256 = sha256(logo); }
                } catch (Exception e) {
                    journal("bannieres: logo de " + code + " non lu : " + e.getMessage());
                    if (old != null) { n.logo = old.logo; n.logoSha256 = old.logoSha256; }
                }
            }
            if (store.upsertFromDataverse(n)) nouvelles++;
        }
        int retirees = 0;
        for (BanniereImpressionStore.Row l : store.listAll()) {
            if (!codes.contains(l.code)) { store.delete(l.code); retirees++; }
        }
        journal("bannieres: Dataverse lu (" + rows.size() + " ligne(s), " + nouvelles + " nouvelle(s)/modifiee(s), " + retirees + " retiree(s))");
    }

    // ------------------------------------------------------------------ tables locales -> service d'impression

    /** Envoie au service tous les formulaires pas encore SENT. @return resume lisible. */
    public static String envoyerFormulairesAuService(Context ctx) {
        FormulaireImpressionStore store = new FormulaireImpressionStore(ctx);
        List<FormulaireImpressionStore.Row> aEnvoyer = store.listToSend();
        if (aEnvoyer.isEmpty()) {
            return "Aucun formulaire à envoyer au service.";
        }
        int ok = 0, err = 0;
        String dernier = "";
        for (FormulaireImpressionStore.Row f : aEnvoyer) {
            PrintServiceClient.Result res = PrintServiceClient.pushForm(f);
            if (res.ok) {
                store.markService(f.code, FormulaireImpressionStore.SYNC_SENT, null);
                ok++;
            } else {
                store.markService(f.code, FormulaireImpressionStore.SYNC_ERROR, res.message);
                err++;
                dernier = res.message;
                if (res.message.contains("injoignable")) break; // inutile d'insister sur chaque ligne
            }
        }
        String resume = ok + " formulaire(s) envoyé(s) au service" + (err > 0 ? ", " + err + " en erreur (" + dernier + ")" : "");
        journal("formulaires: " + resume);
        return resume;
    }

    // ------------------------------------------------------------------ utilitaires HTTP

    private static String libelle(JSONObject r, String col) {
        String f = r.optString(col + "@" + ANNOT, "");
        return f.isEmpty() ? r.optString(col, "") : f;
    }

    /** Lit toutes les pages (@odata.nextLink). 404 : TableAbsente ; autre echec : exception (rien n'est retire). */
    private static List<JSONObject> lireToutesLesLignes(String premiereUrl, String token) throws Exception {
        List<JSONObject> out = new java.util.ArrayList<>();
        String url = premiereUrl;
        int pages = 0;
        while (url != null && pages++ < 50) {
            JSONObject page = getJson(url, token);
            JSONArray arr = page.optJSONArray("value");
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(o);
            }
            url = page.optString("@odata.nextLink", "");
            if (url.isEmpty()) url = null;
        }
        return out;
    }

    private static JSONObject getJson(String urlStr, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("OData-MaxVersion", "4.0");
            conn.setRequestProperty("OData-Version", "4.0");
            conn.setRequestProperty("Prefer", "odata.include-annotations=\"" + ANNOT + "\"");
            int code = conn.getResponseCode();
            if (code == 200) {
                return new JSONObject(new String(lire(conn.getInputStream()), StandardCharsets.UTF_8));
            }
            String err = corpsErreur(conn);
            if (code == 404) throw new TableAbsente(err);
            throw new RuntimeException("HTTP " + code + ": " + err);
        } finally {
            conn.disconnect();
        }
    }

    /** Telecharge le contenu d'une colonne fichier/image. null si la colonne est vide (204 ou 404 sur la colonne). */
    private static byte[] telecharger(String urlStr, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("OData-MaxVersion", "4.0");
            conn.setRequestProperty("OData-Version", "4.0");
            int code = conn.getResponseCode();
            if (code == 200) return lire(conn.getInputStream());
            if (code == 204 || code == 404) return null;
            throw new RuntimeException("HTTP " + code + ": " + corpsErreur(conn));
        } finally {
            conn.disconnect();
        }
    }

    private static String corpsErreur(HttpURLConnection conn) {
        try {
            String e = new String(lire(conn.getErrorStream()), StandardCharsets.UTF_8);
            return e.substring(0, Math.min(300, e.length()));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static byte[] lire(InputStream is) throws java.io.IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    private static void journal(String msg) {
        Log.i(TAG, msg);
        try { LogBus.api(0, "[PRINT-SYNC] " + msg); } catch (Exception ignored) {}
    }
}
