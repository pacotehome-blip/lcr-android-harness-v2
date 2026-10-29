package com.pa.lcrdemo.print;

/**
 * PrinterCatalog — liste des pilotes d'imprimante du service d'impression (com.filgo.printspooler)
 * et règle du format de papier. Miroir de PrinterConfigStore.DriverType et de
 * PrinterConfigStore.formatFor() côté service : si l'un change, l'autre doit suivre.
 *
 * Règle (demande Paul, 29 oct 2026) : le format n'est jamais saisi, il est DÉDUIT du pilote —
 * BROTHER_PJ_862 (rouleau 8,5 po) = GRAND, tous les autres = PETIT.
 */
public final class PrinterCatalog {

    private PrinterCatalog() {}

    public static final String FORMAT_GRAND = "GRAND";
    public static final String FORMAT_PETIT = "PETIT";
    public static final String FORMAT_REGISTRE = "REGISTRE";

    /** Pilote : nom d'enum côté service + libellé affiché + transport indicatif. */
    public static final class Driver {
        public final String name;
        public final String label;
        public final boolean bluetooth; // true = la cible est une adresse MAC Bluetooth
        Driver(String name, String label, boolean bluetooth) {
            this.name = name; this.label = label; this.bluetooth = bluetooth;
        }
        @Override public String toString() { return label; }
    }

    public static final Driver[] DRIVERS = {
        new Driver("EPSON_SDK",              "Epson (SDK Bluetooth)",                     true),
        new Driver("EPSON_SIMPLE_SERIAL",    "Epson (série simple)",                      true),
        new Driver("EPSON_SDK_WIFI",         "Epson (SDK Wi-Fi)",                         false),
        new Driver("BROTHER_RJ_4250WB",      "Brother RJ-4250WB",                         true),
        new Driver("BROTHER_PJ_862",         "Brother PJ-862 (grand format)",             true),
        new Driver("ZPL_COGNITIVE",          "Cognitive ZPL",                             true),
        new Driver("ZPL_COGNITIVE_9600",     "Cognitive ZPL (9600 bauds)",                true),
        new Driver("ZPL_COGNITIVE_TEXTE",    "Cognitive DLXi texte (9600 bauds)",         true),
        new Driver("ZPL_COGNITIVE_REGISTRE", "Epson TM-T88VII registre 3\" (http)", false),
        new Driver("REGISTRE_COGNITIVE_CPL", "Cognitive registre 4\" (http)", false),
        new Driver("ONEIL_MF4TE_DPL",        "O'Neil microFlash MF4te (DPL)",             true),
    };

    public static final String[] COMPANIES = { "", "TEG_PROPANE", "TEG_PETROLE", "AUTRE" };
    public static final String[] COMPANY_LABELS = { "(aucune)", "TEG Propane", "TEG Pétrole", "Autre" };

    /** Pilotes d'une imprimante branchée au registre : la cible est toujours 127.0.0.1 (relais HTTP du registre). */
    public static boolean isRegistre(String driverName) {
        return "ZPL_COGNITIVE_REGISTRE".equals(driverName) || "REGISTRE_COGNITIVE_CPL".equals(driverName);
    }
    public static final String REGISTRE_TARGET = "127.0.0.1";

    public static Driver find(String name) {
        if (name == null) return null;
        for (Driver d : DRIVERS) if (d.name.equals(name.trim())) return d;
        return null;
    }

    /** GRAND pour la PJ-862, REGISTRE pour les deux pilotes registre (HTTP), PETIT pour tout le reste. */
    public static String formatFor(String driverName) {
        String d = driverName == null ? "" : driverName.trim();
        if ("BROTHER_PJ_862".equals(d)) return FORMAT_GRAND;
        if (isRegistre(d)) return FORMAT_REGISTRE;
        return FORMAT_PETIT;
    }

    public static String formatLabel(String format) {
        if (FORMAT_GRAND.equalsIgnoreCase(format)) return "Grand format (Letter)";
        if (FORMAT_REGISTRE.equalsIgnoreCase(format)) return "Imprimante du registre (HTTP)";
        return "Petit format (4 po)";
    }

    /** MAC Bluetooth au format AA:BB:CC:DD:EE:FF (majuscules) ou null si invalide. */
    public static String normalizeMac(String s) {
        if (s == null) return null;
        String t = s.trim().toUpperCase(java.util.Locale.ROOT).replace('-', ':');
        if (t.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}")) return t;
        if (t.matches("[0-9A-F]{12}")) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 12; i += 2) { if (i > 0) b.append(':'); b.append(t, i, i + 2); }
            return b.toString();
        }
        return null;
    }
}
