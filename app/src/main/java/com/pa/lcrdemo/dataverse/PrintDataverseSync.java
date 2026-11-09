package com.pa.lcrdemo.dataverse;

import android.content.Context;
import android.util.Log;

import com.pa.lcr.lcp.log.LogBus;
import com.pa.lcr.lcp.storage.BanniereImpressionStore;
import com.pa.lcr.lcp.storage.FormulaireImpressionStore;
import com.pa.lcr.lcp.storage.ImprimanteCiterneStore;
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
 * Synchronisation des tables d'impression (vraies tables Dataverse, 9 nov 2026) :
 * D'ABORD Dataverse <-> tables locales de Filgo-Registre (lcr_delivery.db), ENSUITE tables locales -> service
 * d'impression (/print-form, /truck-printer).
 *
 *   filgo_formulairepdf  Dataverse -> local -> service   (code, nom, format, version, manifest, pdfsha256, pdf)
 *   filgo_documentheader Dataverse -> local              (en-tetes ; pas encore de route cote service)
 *   filgo_imprimante     local <-> Dataverse, local -> service  (citerne + btmac + modele)
 *
 * Filgo-Registre est le seul lien entre Dataverse et le service d'impression. Dataverse a raison, SAUF pour une
 * imprimante saisie/modifiee ici qui n'est pas encore remontee : elle est d'abord envoyee a Dataverse, puis
 * repoussee au service. Detection de changement : versionnumber systeme de Dataverse.
 *
 * Les noms d'ensemble (partie de l'URL), la cle primaire et les valeurs des choix (filgo_model) sont LUS dans les
 * metadonnees de Dataverse (voir meta()), jamais devines. Les noms de colonnes sont les noms logiques (minuscules).
 * Table absente (HTTP 404) : ignoree sans erreur ; aucune ligne n'est retiree si la lecture n'a pas reussi en entier.
 */
public final class PrintDataverseSync {

    private static final String TAG = "PrintDataverseSync";
    private static final String ANNOT = "OData.Community.Display.V1.FormattedValue";

    private PrintDataverseSync() {}

    /** Lecture inachevee ou impossible : rien ne doit etre retire. */
    private static final class TableAbsente extends Exception {
        TableAbsente(String m) { super(m); }
    }

    // ------------------------------------------------------------------ metadonnees Dataverse

    private static final class Meta {
        String set = "";
        String idAttr = "";
        String nameAttr = "";
    }
    private static final java.util.Map<String, Meta> META = new java.util.concurrent.ConcurrentHashMap<>();

    /** Nom d'ensemble, cle primaire et colonne nom de la table (mis en cache). */
    private static Meta meta(Context ctx, String token, String table) throws Exception {
        Meta m = META.get(table);
        if (m != null) return m;
        String url = LcrConfig.getDataverseUrl(ctx) + "/api/data/v9.2/EntityDefinitions(LogicalName='" + table
                + "')?$select=EntitySetName,PrimaryIdAttribute,PrimaryNameAttribute";
        JSONObject o = getJson(url, token, 15000, 30000);
        m = new Meta();
        m.set = o.optString("EntitySetName", "");
        m.idAttr = o.optString("PrimaryIdAttribute", "");
        m.nameAttr = o.optString("PrimaryNameAttribute", "");
        if (m.set.isEmpty() || m.idAttr.isEmpty()) throw new TableAbsente("metadonnees incompletes pour " + table);
        META.put(table, m);
        return m;
    }

    private static final java.util.Map<String, java.util.Map<Integer, String>> OPTIONS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Valeurs -> libelles d'une colonne a choix. */
    private static java.util.Map<Integer, String> options(Context ctx, String token, String table, String col) throws Exception {
        String key = table + "." + col;
        java.util.Map<Integer, String> m = OPTIONS.get(key);
        if (m != null) return m;
        String url = LcrConfig.getDataverseUrl(ctx) + "/api/data/v9.2/EntityDefinitions(LogicalName='" + table
                + "')/Attributes(LogicalName='" + col + "')/Microsoft.Dynamics.CRM.PicklistAttributeMetadata"
                + "?$select=LogicalName&$expand=OptionSet($select=Options)";
        JSONObject o = getJson(url, token, 15000, 30000);
        m = new java.util.LinkedHashMap<>();
        JSONObject os = o.optJSONObject("OptionSet");
        JSONArray arr = os == null ? null : os.optJSONArray("Options");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject op = arr.optJSONObject(i);
            if (op == null) continue;
            JSONObject lab = op.optJSONObject("Label");
            JSONObject ul = lab == null ? null : lab.optJSONObject("UserLocalizedLabel");
            m.put(op.optInt("Value"), ul == null ? "" : ul.optString("Label", ""));
        }
        OPTIONS.put(key, m);
        return m;
    }

    /** Libelle du choix filgo_model (nom d'enum du pilote ou libelle du catalogue, sinon mots-cles) -> nom d'enum. */
    static String driverDepuisLibelle(String label) {
        if (label == null) return "";
        String l = label.trim();
        for (com.pa.lcrdemo.print.PrinterCatalog.Driver d : com.pa.lcrdemo.print.PrinterCatalog.DRIVERS) {
            if (d.name.equalsIgnoreCase(l) || d.label.equalsIgnoreCase(l)) return d.name;
        }
        String u = l.toUpperCase(Locale.ROOT);
        boolean reg = u.contains("REGISTRE") || u.contains("HTTP");
        if (u.contains("PJ-862") || u.contains("PJ862")) return "BROTHER_PJ_862";
        if (u.contains("4250")) return "BROTHER_RJ_4250WB";
        if (u.contains("MF4") || u.contains("O'NEIL") || u.contains("ONEIL")) return "ONEIL_MF4TE_DPL";
        if (u.contains("COGNITIVE")) return reg ? "REGISTRE_COGNITIVE_CPL" : "ZPL_COGNITIVE";
        if (u.contains("TM-T88") || u.contains("EPSON")) return reg ? "ZPL_COGNITIVE_REGISTRE" : "EPSON_SDK";
        return "";
    }

    /** GUID de la citerne d'une imprimante locale : le truckId s'il en est un, sinon via le registre (citerne_guid). */
    private static String guidCiternePour(Context ctx, ImprimanteCiterneStore.Row r) {
        String g = guid(r.truckId);
        if (!g.isEmpty()) return g;
        try {
            com.pa.lcr.lcp.storage.RegistreStore rs = new com.pa.lcr.lcp.storage.RegistreStore(ctx);
            if (r.registreSerial != null && !r.registreSerial.trim().isEmpty()) {
                com.pa.lcr.lcp.storage.RegistreStore.Row reg = rs.getBySerial(r.registreSerial.trim());
                if (reg != null && !guid(reg.citerneGuid).isEmpty()) return guid(reg.citerneGuid);
            }
            for (com.pa.lcr.lcp.storage.RegistreStore.Row reg : rs.listAll()) {
                if (reg.citerneNom != null && reg.citerneNom.trim().equalsIgnoreCase(r.truckId == null ? "" : r.truckId.trim())
                        && !guid(reg.citerneGuid).isEmpty()) return guid(reg.citerneGuid);
            }
        } catch (Exception ignored) { }
        return "";
    }

    /** Nom de citerne connu du registre pour ce GUID ; "" si inconnu. */
    private static String nomPourGuid(Context ctx, String g) {
        try {
            for (com.pa.lcr.lcp.storage.RegistreStore.Row reg : new com.pa.lcr.lcp.storage.RegistreStore(ctx).listAll()) {
                if (g.equals(guid(reg.citerneGuid)) && reg.citerneNom != null && !reg.citerneNom.trim().isEmpty()) {
                    return reg.citerneNom.trim();
                }
            }
        } catch (Exception ignored) { }
        return "";
    }

    /** Identifiant local de la citerne pour une ligne Dataverse : le NOM connu du registre (comme aujourd'hui), sinon le GUID. */
    private static String truckIdPourGuid(Context ctx, String guid) {
        try {
            for (com.pa.lcr.lcp.storage.RegistreStore.Row reg : new com.pa.lcr.lcp.storage.RegistreStore(ctx).listAll()) {
                if (guid.equals(guid(reg.citerneGuid)) && reg.citerneNom != null && !reg.citerneNom.trim().isEmpty()) {
                    return reg.citerneNom.trim();
                }
            }
        } catch (Exception ignored) { }
        return guid;
    }

    /** GUID de citerne : minuscules, sans accolades ; "" si ce n'est pas un GUID. */
    static String guid(String s) {
        String g = s == null ? "" : s.trim().replace("{", "").replace("}", "").toLowerCase(Locale.ROOT);
        return g.matches("[0-9a-f]{8}-([0-9a-f]{4}-){3}[0-9a-f]{12}") ? g : "";
    }

    /** Valeur texte d'une colonne ; "" si absente OU null (optString renvoie "null" pour un null JSON). */
    private static String str(JSONObject r, String k) {
        return r == null || r.isNull(k) ? "" : r.optString(k, "");
    }

    private static String versionNumber(JSONObject r) {
        Object v = r.opt("versionnumber");
        return v == null || v == JSONObject.NULL ? str(r, "modifiedon") : String.valueOf(v);
    }

    // ------------------------------------------------------------------ point d'entree

    /** Dataverse d'abord (si un jeton est fourni), puis le service. Ne leve jamais d'exception. */
    public static void syncAll(Context ctx, String accessToken) {
        if (accessToken != null && !accessToken.isEmpty()) {
            try { lireEntetes(ctx, accessToken); }
            catch (Exception e) { journal("en-tetes: lecture Dataverse ERR " + e.getMessage()); }
            try { lireFormulaires(ctx, accessToken); }
            catch (Exception e) { journal("formulaires: lecture Dataverse ERR " + e.getMessage()); }
            // Imprimantes : ce qui a ete saisi ici monte d'abord a Dataverse, puis on lit Dataverse.
            try { envoyerImprimantesADataverse(ctx, accessToken); }
            catch (Exception e) { journal("imprimantes: envoi a Dataverse ERR " + e.getMessage()); }
            try { lireImprimantes(ctx, accessToken); }
            catch (Exception e) { journal("imprimantes: lecture Dataverse ERR " + e.getMessage()); }
        } else {
            journal("pas de jeton Dataverse : seule l'etape service est faite");
        }
        try { envoyerFormulairesAuService(ctx); }
        catch (Exception e) { journal("formulaires: envoi au service ERR " + e.getMessage()); }
        try { envoyerImprimantesAuService(ctx); }
        catch (Exception e) { journal("imprimantes: envoi au service ERR " + e.getMessage()); }
    }

    // ------------------------------------------------------------------ Dataverse -> formulaires

    private static final String FORM_SELECT = "filgo_code,filgo_nom,filgo_format,filgo_version,filgo_manifest,"
            + "filgo_pdfsha256,modifiedon,statecode,versionnumber";

    private static void lireFormulaires(Context ctx, String token) throws Exception {
        Meta m;
        try { m = meta(ctx, token, LcrConfig.DV_PRINT_FORMS_TABLE); }
        catch (TableAbsente e) {
            journal("formulaires: table " + LcrConfig.DV_PRINT_FORMS_TABLE + " absente de Dataverse : ignoree");
            return;
        }
        String org = LcrConfig.getDataverseUrl(ctx);
        String url = org + "/api/data/v9.2/" + m.set + "?$select=" + m.idAttr + "," + FORM_SELECT;
        List<JSONObject> rows;
        try {
            rows = lireToutesLesLignes(url, token);
        } catch (TableAbsente e) {
            journal("formulaires: table " + m.set + " absente de Dataverse : ignoree (le service garde ses formulaires embarques)");
            return;
        }
        FormulaireImpressionStore store = new FormulaireImpressionStore(ctx);
        Set<String> codesDataverse = new HashSet<>();
        int nouveaux = 0, ignores = 0;
        for (JSONObject r : rows) {
            String code = FormulaireImpressionStore.keyOf(str(r, "filgo_code"));
            if (code.isEmpty()) { ignores++; continue; }
            codesDataverse.add(code);
            int res = traiterFormulaire(ctx, token, org, m, store, r);
            if (res > 0) nouveaux++;
            else if (res < 0) ignores++;
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

    /** @return 1 = ligne nouvelle ou modifiee, 0 = inchangee, -1 = ignoree (erreur journalisee). */
    private static int traiterFormulaire(Context ctx, String token, String org, Meta m,
                                         FormulaireImpressionStore store, JSONObject r) throws Exception {
        String code = FormulaireImpressionStore.keyOf(str(r, "filgo_code"));
        String format = libelle(r, "filgo_format").trim().toUpperCase(Locale.ROOT);
        if (!format.equals("GRAND") && !format.equals("PETIT") && !format.equals("REGISTRE")) {
            journal("formulaires: " + code + " ignore, format inconnu '" + format + "' (GRAND, PETIT ou REGISTRE)");
            return -1;
        }
        FormulaireImpressionStore.Row n = new FormulaireImpressionStore.Row();
        n.code = code;
        n.name = str(r, "filgo_nom");
        n.format = format;
        n.version = str(r, "filgo_version");
        n.active = r.optInt("statecode", 0) == 0;
        n.manifest = str(r, "filgo_manifest");
        n.dataverseId = str(r, m.idAttr);
        n.dataverseModified = versionNumber(r);
        String shaAnnonce = str(r, "filgo_pdfsha256").trim();

        FormulaireImpressionStore.Row old = store.get(code);
        boolean inchange = old != null && old.dataverseModified.equals(n.dataverseModified) && old.pdf != null;
        if (n.active && !"REGISTRE".equals(format)) {
            if (inchange) {
                n.pdf = old.pdf;
                n.pdfSha256 = old.pdfSha256;
            } else {
                byte[] pdf;
                try {
                    pdf = telecharger(org + "/api/data/v9.2/" + m.set + "(" + n.dataverseId + ")/filgo_pdf/$value", token);
                } catch (Exception e) {
                    journal("formulaires: " + code + " ignore, telechargement du PDF impossible : " + e.getMessage());
                    return -1;
                }
                if (pdf == null || pdf.length == 0) {
                    journal("formulaires: " + code + " ignore, PDF absent dans Dataverse");
                    return -1;
                }
                String sha = sha256(pdf);
                if (!shaAnnonce.isEmpty() && !shaAnnonce.equalsIgnoreCase(sha)) {
                    journal("formulaires: " + code + " ignore, sha256 du PDF different de celui annonce par Dataverse");
                    return -1;
                }
                n.pdf = pdf;
                n.pdfSha256 = sha;
            }
        } else if (old != null) {
            n.pdf = old.pdf; // inactif ou REGISTRE : rien a telecharger
            n.pdfSha256 = old.pdfSha256;
        }
        return store.upsertFromDataverse(n) ? 1 : 0;
    }

    /**
     * Verification d'UN formulaire (deeplink FieldService -> service d'impression -> route /v1/print/form-check).
     * Lit la ligne dans Dataverse, met a jour la copie locale et pousse au service si Dataverse est plus recent.
     * @return "A_JOUR", "MAJ_APPLIQUEE" ou "INDISPONIBLE" (jamais d'exception)
     */
    public static String verifierFormulaire(Context ctx, String token, String code, String versionService) {
        try {
            Meta m = meta(ctx, token, LcrConfig.DV_PRINT_FORMS_TABLE);
            String org = LcrConfig.getDataverseUrl(ctx);
            String url = org + "/api/data/v9.2/" + m.set + "?$select=" + m.idAttr + "," + FORM_SELECT
                    + "&$filter=" + java.net.URLEncoder.encode("filgo_code eq '" + code.replace("'", "''") + "'", "UTF-8")
                    .replace("+", "%20") + "&$top=1";
            JSONObject page = getJson(url, token, 2500, 4000);
            JSONArray arr = page.optJSONArray("value");
            if (arr == null || arr.length() == 0) return "A_JOUR"; // inconnu de Dataverse : le service garde son formulaire
            JSONObject r = arr.getJSONObject(0);
            FormulaireImpressionStore store = new FormulaireImpressionStore(ctx);
            int res = traiterFormulaire(ctx, token, org, m, store, r);
            if (res < 0) return "INDISPONIBLE";
            FormulaireImpressionStore.Row local = store.get(code);
            boolean serviceAJour = local != null && FormulaireImpressionStore.SYNC_SENT.equals(local.serviceSync)
                    && local.version.equals(versionService == null ? "" : versionService.trim());
            if (serviceAJour) return "A_JOUR";
            String resume = envoyerFormulairesAuService(ctx);
            journal("formulaire " + code + " verifie : " + resume);
            local = store.get(code);
            return local != null && FormulaireImpressionStore.SYNC_SENT.equals(local.serviceSync) ? "MAJ_APPLIQUEE" : "INDISPONIBLE";
        } catch (Exception e) {
            journal("verification du formulaire " + code + " impossible : " + e.getMessage());
            return "INDISPONIBLE";
        }
    }

    // ------------------------------------------------------------------ Dataverse -> en-tetes (filgo_documentheader)

    private static void lireEntetes(Context ctx, String token) throws Exception {
        Meta m;
        try { m = meta(ctx, token, LcrConfig.DV_PRINT_HEADERS_TABLE); }
        catch (TableAbsente e) {
            journal("en-tetes: table " + LcrConfig.DV_PRINT_HEADERS_TABLE + " absente de Dataverse : ignoree");
            return;
        }
        String org = LcrConfig.getDataverseUrl(ctx);
        String url = org + "/api/data/v9.2/" + m.set + "?$select=" + m.idAttr
                + ",filgo_key,filgo_companyname,filgo_address,filgo_city,filgo_stateprovince,filgo_postalcode,"
                + "filgo_phone,filgo_tollfreenumber,filgo_revision,modifiedon,statecode,versionnumber";
        List<JSONObject> rows;
        try { rows = lireToutesLesLignes(url, token); }
        catch (TableAbsente e) { journal("en-tetes: table " + m.set + " absente de Dataverse : ignoree"); return; }
        BanniereImpressionStore store = new BanniereImpressionStore(ctx);
        Set<String> codes = new HashSet<>();
        int nouvelles = 0;
        for (JSONObject r : rows) {
            String code = BanniereImpressionStore.keyOf(str(r, "filgo_key"));
            if (code.isEmpty()) continue;
            codes.add(code);
            BanniereImpressionStore.Row n = new BanniereImpressionStore.Row();
            n.code = code;
            n.name = str(r, "filgo_companyname");
            n.addressLine1 = str(r, "filgo_address");
            n.city = str(r, "filgo_city");
            n.province = str(r, "filgo_stateprovince");
            n.postalCode = str(r, "filgo_postalcode");
            n.phone = str(r, "filgo_phone");
            n.tollFreePhone = str(r, "filgo_tollfreenumber");
            // TPS / TVQ / pied de page : pas dans Dataverse pour l'instant (decision 9 nov : ajoutes localement).
            n.version = str(r, "filgo_revision");
            n.active = r.optInt("statecode", 0) == 0;
            n.dataverseId = str(r, m.idAttr);
            n.dataverseModified = versionNumber(r);
            BanniereImpressionStore.Row old = store.get(code);
            boolean inchange = old != null && old.dataverseModified.equals(n.dataverseModified) && old.version.equals(n.version);
            if (inchange) continue;
            if (old != null) { n.taxInfo = old.taxInfo; n.footerText = old.footerText; n.company = old.company; }
            if (n.active) {
                try {
                    byte[] logo = telecharger(org + "/api/data/v9.2/" + m.set + "(" + n.dataverseId + ")/filgo_logo/$value", token);
                    if (logo != null && logo.length > 0) { n.logo = logo; n.logoSha256 = sha256(logo); }
                } catch (Exception e) {
                    journal("en-tetes: logo de " + code + " non lu : " + e.getMessage());
                    if (old != null) { n.logo = old.logo; n.logoSha256 = old.logoSha256; }
                }
            }
            if (store.upsertFromDataverse(n)) nouvelles++;
        }
        int retirees = 0;
        for (BanniereImpressionStore.Row l : store.listAll()) {
            if (!codes.contains(l.code)) { store.delete(l.code); retirees++; }
        }
        journal("en-tetes: Dataverse lu (" + rows.size() + " ligne(s), " + nouvelles + " nouvelle(s)/modifiee(s), " + retirees + " retiree(s))");
    }

    // ------------------------------------------------------------------ imprimantes : Dataverse <-> local

    /** Dataverse -> local. Une ligne locale pas encore envoyee a Dataverse n'est jamais ecrasee. */
    private static void lireImprimantes(Context ctx, String token) throws Exception {
        Meta m;
        try { m = meta(ctx, token, LcrConfig.DV_PRINT_PRINTERS_TABLE); }
        catch (TableAbsente e) {
            journal("imprimantes: table " + LcrConfig.DV_PRINT_PRINTERS_TABLE + " absente de Dataverse : ignoree");
            return;
        }
        String url = LcrConfig.getDataverseUrl(ctx) + "/api/data/v9.2/" + m.set + "?$select=" + m.idAttr
                + (m.nameAttr.isEmpty() ? "" : "," + m.nameAttr)
                + ",filgo_citerne_id,filgo_registre,filgo_model,filgo_btmac,filgo_version,statecode,versionnumber";
        List<JSONObject> rows;
        try { rows = lireToutesLesLignes(url, token); }
        catch (TableAbsente e) { journal("imprimantes: table " + m.set + " absente de Dataverse : ignoree"); return; }
        ImprimanteCiterneStore store = new ImprimanteCiterneStore(ctx);
        int changees = 0, ignorees = 0;
        for (JSONObject r : rows) {
            if (r.optInt("statecode", 0) != 0) continue; // inactive : on ne la reprend pas
            String citerne = guid(str(r, "filgo_citerne_id"));
            if (citerne.isEmpty()) {
                journal("imprimantes: ligne ignoree, citerne absente ou pas un GUID : '" + str(r, "filgo_citerne_id") + "'");
                ignorees++;
                continue;
            }
            String modele = libelle(r, "filgo_model");
            String driver = driverDepuisLibelle(modele);
            if (driver.isEmpty()) {
                journal("imprimantes: " + citerne + " ignoree, modele inconnu '" + modele + "' (voir PrinterCatalog)");
                ignorees++;
                continue;
            }
            String mac = ImprimanteCiterneStore.macOf(str(r, "filgo_btmac"));
            if (com.pa.lcrdemo.print.PrinterCatalog.isRegistre(driver)) mac = com.pa.lcrdemo.print.PrinterCatalog.REGISTRE_TARGET;
            if (mac.isEmpty()) {
                journal("imprimantes: " + citerne + " ignoree, btmac vide pour un pilote non registre");
                ignorees++;
                continue;
            }
            ImprimanteCiterneStore.Row n = new ImprimanteCiterneStore.Row();
            // Cle locale (= celle du service et du deeplink) : le NOM de la citerne, lisible. Colonne nom principale
            // de la ligne ; a defaut le nom connu du registre ; a defaut le GUID.
            String nomCiterne = m.nameAttr.isEmpty() ? "" : str(r, m.nameAttr).trim();
            n.truckId = !nomCiterne.isEmpty() ? nomCiterne : truckIdPourGuid(ctx, citerne);
            n.driverType = driver;
            n.format = com.pa.lcrdemo.print.PrinterCatalog.formatFor(driver);
            n.btMac = mac;
            n.printerName = modele;
            n.company = "";
            n.registreSerial = str(r, "filgo_registre");
            ImprimanteCiterneStore.Row old = store.get(n.truckId, mac);
            if (old != null) n.company = old.company == null ? "" : old.company;
            if (store.upsertFromDataverse(n, str(r, m.idAttr), versionNumber(r))) changees++;
        }
        journal("imprimantes: Dataverse lu (" + rows.size() + " ligne(s), " + changees + " nouvelle(s)/modifiee(s), " + ignorees + " ignoree(s))");
    }

    /** Local -> Dataverse : cree ou met a jour filgo_imprimante pour chaque ligne saisie / modifiee ici. */
    private static void envoyerImprimantesADataverse(Context ctx, String token) throws Exception {
        ImprimanteCiterneStore store = new ImprimanteCiterneStore(ctx);
        List<ImprimanteCiterneStore.Row> aEnvoyer = store.listToPushToDataverse();
        if (aEnvoyer.isEmpty()) return;
        Meta m;
        try { m = meta(ctx, token, LcrConfig.DV_PRINT_PRINTERS_TABLE); }
        catch (TableAbsente e) {
            journal("imprimantes: table " + LcrConfig.DV_PRINT_PRINTERS_TABLE + " absente de Dataverse : envoi reporte");
            return;
        }
        java.util.Map<Integer, String> choix = options(ctx, token, LcrConfig.DV_PRINT_PRINTERS_TABLE, "filgo_model");
        String org = LcrConfig.getDataverseUrl(ctx);
        int ok = 0, err = 0;
        for (ImprimanteCiterneStore.Row r : aEnvoyer) {
            try {
                Integer valeurModele = null;
                for (java.util.Map.Entry<Integer, String> e : choix.entrySet()) {
                    if (r.driverType.equals(driverDepuisLibelle(e.getValue()))) { valeurModele = e.getKey(); break; }
                }
                if (valeurModele == null) {
                    journal("imprimantes: " + r.truckId + " non envoyee, aucun choix filgo_model ne correspond au pilote " + r.driverType);
                    err++;
                    continue;
                }
                String citerne = guidCiternePour(ctx, r);
                if (citerne.isEmpty()) {
                    journal("imprimantes: " + r.truckId + " non envoyee a Dataverse, GUID de la citerne introuvable (registre " + r.registreSerial + ")");
                    err++;
                    continue;
                }
                String mac = ImprimanteCiterneStore.macOf(r.btMac);
                JSONObject body = new JSONObject();
                body.put("filgo_citerne_id", citerne);
                body.put("filgo_registre", r.registreSerial == null ? "" : r.registreSerial);
                body.put("filgo_btmac", mac);
                body.put("filgo_model", valeurModele);
                // Colonne nom principale = nom de la citerne (lisible pour qui ajoute / verifie l'imprimante).
                // Si le truckId local est lui-meme un GUID, on garde le nom connu du registre.
                String nomCiterne = r.truckId == null ? "" : r.truckId.trim();
                if (!guid(nomCiterne).isEmpty()) nomCiterne = nomPourGuid(ctx, citerne);
                if (nomCiterne.isEmpty()) nomCiterne = citerne;
                if (!m.nameAttr.isEmpty() && !body.has(m.nameAttr)) body.put(m.nameAttr, nomCiterne);

                // Existe-t-elle deja (citerne + btmac) ?
                String filtre = "filgo_citerne_id eq '" + citerne + "' and filgo_btmac eq '" + mac.replace("'", "''") + "'";
                String urlFind = org + "/api/data/v9.2/" + m.set + "?$select=" + m.idAttr + ",filgo_version&$top=1&$filter="
                        + java.net.URLEncoder.encode(filtre, "UTF-8").replace("+", "%20");
                JSONArray ex = getJson(urlFind, token, 15000, 30000).optJSONArray("value");
                JSONObject reponse;
                if (ex != null && ex.length() > 0) {
                    String id = ex.getJSONObject(0).optString(m.idAttr, "");
                    reponse = ecrire("PATCH", org + "/api/data/v9.2/" + m.set + "(" + id + ")?$select=" + m.idAttr + ",versionnumber", body, token);
                } else {
                    body.put("filgo_version", "1");
                    reponse = ecrire("POST", org + "/api/data/v9.2/" + m.set + "?$select=" + m.idAttr + ",versionnumber", body, token);
                }
                store.markDataverse(r.id, reponse.optString(m.idAttr, ""), versionNumber(reponse));
                ok++;
            } catch (Exception e) {
                err++;
                journal("imprimantes: envoi a Dataverse de " + r.truckId + " ERR " + e.getMessage());
            }
        }
        journal("imprimantes: " + ok + " envoyee(s) a Dataverse" + (err > 0 ? ", " + err + " en erreur (la ligne reste a envoyer)" : ""));
    }

    // ------------------------------------------------------------------ tables locales -> service d'impression

    /** Envoie au service les imprimantes pas encore SENT (POST /truck-printer). */
    public static String envoyerImprimantesAuService(Context ctx) {
        ImprimanteCiterneStore store = new ImprimanteCiterneStore(ctx);
        List<ImprimanteCiterneStore.Row> aEnvoyer = store.listToSendToService();
        if (aEnvoyer.isEmpty()) return "Aucune imprimante à envoyer au service.";
        int ok = 0, err = 0;
        String dernier = "";
        for (ImprimanteCiterneStore.Row r : aEnvoyer) {
            PrintServiceClient.Result res = PrintServiceClient.push(r, false);
            if (res.ok) {
                store.markService(r.id, ImprimanteCiterneStore.SYNC_SENT, null);
                ok++;
            } else {
                store.markService(r.id, ImprimanteCiterneStore.SYNC_ERROR, res.message);
                err++;
                dernier = res.message;
                if (res.message.contains("injoignable")) break;
            }
        }
        String resume = ok + " imprimante(s) envoyée(s) au service" + (err > 0 ? ", " + err + " en erreur (" + dernier + ")" : "");
        journal("imprimantes: " + resume);
        return resume;
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
        String f = str(r, col + "@" + ANNOT);
        return f.isEmpty() ? str(r, col) : f;
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
        return getJson(urlStr, token, 15000, 30000);
    }

    /** POST / PATCH JSON vers Dataverse ; retourne la ligne ecrite (Prefer: return=representation). */
    private static JSONObject ecrire(String methode, String urlStr, JSONObject body, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            // PATCH n'est pas toujours accepte par HttpURLConnection : on passe par l'en-tete de surcharge.
            conn.setRequestMethod("POST");
            if ("PATCH".equals(methode)) conn.setRequestProperty("X-HTTP-Method-Override", "PATCH");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("OData-MaxVersion", "4.0");
            conn.setRequestProperty("OData-Version", "4.0");
            conn.setRequestProperty("Prefer", "return=representation");
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (java.io.OutputStream os = conn.getOutputStream()) { os.write(payload); }
            int code = conn.getResponseCode();
            if (code == 200 || code == 201) {
                return new JSONObject(new String(lire(conn.getInputStream()), StandardCharsets.UTF_8));
            }
            if (code == 204) return new JSONObject();
            throw new RuntimeException("HTTP " + code + ": " + corpsErreur(conn));
        } finally {
            conn.disconnect();
        }
    }

    private static JSONObject getJson(String urlStr, String token, int connMs, int readMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(connMs);
            conn.setReadTimeout(readMs);
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
