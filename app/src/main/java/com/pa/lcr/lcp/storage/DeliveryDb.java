package com.pa.lcr.lcp.storage;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

/**
 * SQLite DB for delivery traceability (API + UI).
 *
 * Rotation is handled by deleting old rows in delivery_summary (cascade to attempt/event).
 */
public class DeliveryDb extends SQLiteOpenHelper {

    public static final String DB_NAME = "lcr_delivery.db";

    // v1: base tables
    // v2: add time columns to delivery_summary + index
    // v3: add media_profile/media_event
    // v4: add structured error columns to delivery_event (event_level/event_code/event_where/detail_short)
    // v5: add truck_profile + truck_drift tables
    // v6: add active_delivery table (livraison courante persistée)
    // v7: add produit/preset/status to active_delivery
    // v8: add bt_signal table (perdue lors du revert à 3f79a08)
    // v9: add register_products table
    // v10: add is_propane, lcr_node to register_products
    // v11: add missing columns to bt_signal (mac, rssi_quality, source, io_errors, io_timeouts, io_latency_avg_ms)
    // v12: add known_tcp_device table (N-Port TCP mémorisés, équivalent BT paired pour raw TCP)
    // v13: add v_diagnostic_events view (chronologie unifiée pour le diagnostic intelligent — lecture seule, pas de migration de schéma)
    // v14: add api_trace table (REQ/RESP HTTP, attempt_id nullable/best-effort, PAS de FK stricte —
    //      delivery_event.attempt_id est NOT NULL + FK ON, donc les traces API orphelines ne peuvent
    //      pas y vivre; v_diagnostic_events étendue en UNION ALL avec api_trace)
    // v15: add diagnostic_rules table (moteur de règles, Phase 2 — plan diagnostic intelligent),
    //      seedée avec les 4 premières règles les plus fiables du plan (#1, #4, #5, #7)
    // v16: add incident_history table (boucle de rétroaction, Phase 3 — plan diagnostic intelligent).
    //      FK vers diagnostic_rules(rule_id) nullable (diagnostic manuel possible, rule_id=null)
    // v17: add log_bus_event table (persistance de LogBus — UI/API/IO_TX/IO_RX, demande Paul
    //      31 juillet 2026 : "tout persister"). Rotation par COUNT (garde les N plus récents)
    //      pour éviter la croissance illimitée vu le volume TX/RX potentiel.
    // v18: add sync_watermark (marque le dernier id synchronisé par table, pour la future
    //      sync périodique vers la BD support centrale — évite de repousser les mêmes lignes
    //      chaque soir) + diagnostic_match_history (persiste chaque résultat de
    //      DiagnosticRuleEngine, jusqu'ici calculé à la volée et jamais stocké — nécessaire
    //      pour calibrer les règles / futur agent IA, demande Paul 31 juillet 2026).
    public static final int DB_VERSION = 20;

    private static final String TAG = "DeliveryDb";

    public DeliveryDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
        try (Cursor c = db.rawQuery("PRAGMA journal_mode=WAL", null)) {
            // Optional: read mode returned
        } catch (Exception e) {
            Log.w(TAG, "WAL not enabled (fallback to default journal mode)", e);
        }
        try {
            db.execSQL("PRAGMA foreign_keys=ON;");
        } catch (Exception e) {
            Log.w(TAG, "PRAGMA foreign_keys=ON failed (FK may still be enabled)", e);
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createDeliveryTables(db);
        createMediaTables(db);
        createTruckTables(db);
        createActiveDeliveryTable(db);
        createBtSignalTable(db);
        createRegisterProductsTable(db);
        createKnownTcpDeviceTable(db);
        createApiTraceTable(db);
        createDiagnosticEventsView(db);
        createDiagnosticRulesTable(db);
        seedDiagnosticRules(db);
        createIncidentHistoryTable(db);
        createLogBusEventTable(db);
        createSyncWatermarkTable(db);
        createDiagnosticMatchHistoryTable(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v2 columns
        if (oldVersion < 2) {
            addColumnIfMissing(db, "delivery_summary", "start_ms",    "INTEGER");
            addColumnIfMissing(db, "delivery_summary", "end_ms",      "INTEGER");
            addColumnIfMissing(db, "delivery_summary", "start_utc",   "TEXT");
            addColumnIfMissing(db, "delivery_summary", "end_utc",     "TEXT");
            addColumnIfMissing(db, "delivery_summary", "duration_ms", "INTEGER");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_summary_time ON delivery_summary(start_ms, end_ms);");
        }
        // v3 tables
        if (oldVersion < 3) {
            createMediaTables(db);
        }
        // v4 columns
        if (oldVersion < 4) {
            addColumnIfMissing(db, "delivery_event", "event_level",  "TEXT");
            addColumnIfMissing(db, "delivery_event", "event_code",   "TEXT");
            addColumnIfMissing(db, "delivery_event", "event_where",  "TEXT");
            addColumnIfMissing(db, "delivery_event", "detail_short", "TEXT");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_event_level_ts ON delivery_event(event_level, ts);");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_event_code_ts ON delivery_event(event_code, ts);");
        }
        // v5 tables
        if (oldVersion < 5) {
            createTruckTables(db);
        }
        // v6: active_delivery
        if (oldVersion < 6) {
            createActiveDeliveryTable(db);
        }
        // v7: produit/preset/status dans active_delivery
        if (oldVersion < 7) {
            addColumnIfMissing(db, "active_delivery", "produit",  "INTEGER");
            addColumnIfMissing(db, "active_delivery", "preset",   "REAL");
            addColumnIfMissing(db, "active_delivery", "status",   "TEXT");
            addColumnIfMissing(db, "active_delivery", "wo_id_guid", "TEXT");
        }
        // v8: bt_signal table
        if (oldVersion < 8) {
            createBtSignalTable(db);
        }
        if (oldVersion < 9) {
            createRegisterProductsTable(db);
        }
        if (oldVersion < 10) {
            addColumnIfMissing(db, "register_products", "is_propane", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(db, "register_products", "lcr_node",   "INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 11) {
            addColumnIfMissing(db, "bt_signal", "mac",               "TEXT");
            addColumnIfMissing(db, "bt_signal", "rssi_quality",      "TEXT");
            addColumnIfMissing(db, "bt_signal", "source",            "TEXT");
            addColumnIfMissing(db, "bt_signal", "io_errors",         "INTEGER");
            addColumnIfMissing(db, "bt_signal", "io_timeouts",       "INTEGER");
            addColumnIfMissing(db, "bt_signal", "io_latency_avg_ms", "INTEGER");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_bt_signal_mac ON bt_signal(mac, ts_ms);");
        }
        if (oldVersion < 12) {
            createKnownTcpDeviceTable(db);
        }
        // v13: v_diagnostic_events (vue, aucune migration de données — DROP+CREATE est sans risque)
        if (oldVersion < 13) {
            createDiagnosticEventsView(db);
        }
        // v14: api_trace (nouvelle table, attempt_id nullable, sans FK stricte) + vue étendue en UNION
        if (oldVersion < 14) {
            createApiTraceTable(db);
            createDiagnosticEventsView(db);
        }
        // v15: diagnostic_rules (moteur de règles Phase 2) + seed des 4 premières règles
        if (oldVersion < 15) {
            createDiagnosticRulesTable(db);
            seedDiagnosticRules(db);
        }
        // v16: incident_history (boucle de rétroaction Phase 3)
        if (oldVersion < 16) {
            createIncidentHistoryTable(db);
        }
        // v17: log_bus_event (persistance LogBus complète — UI/API/IO_TX/IO_RX)
        if (oldVersion < 17) {
            createLogBusEventTable(db);
        }
        // v18: sync_watermark + diagnostic_match_history (préparation sync BD support centrale)
        if (oldVersion < 18) {
            createSyncWatermarkTable(db);
            createDiagnosticMatchHistoryTable(db);
        }
        // v19: 5e règle diagnostic (push Dataverse échoué) — appelée explicitement ici
        // car seedDiagnosticRules() ne se relance jamais sur une BD déjà seedée
        // (table non vide = skip, voir son garde en tête de méthode).
        if (oldVersion < 19) {
            seedDataversePushFailedRule(db);
        }
        // v20: v_diagnostic_events expose maintenant attempt_id (demande Paul, 3 août 2026 —
        // "afficher le processus lié" à un événement). DROP+CREATE d'une vue est toujours
        // sans risque de perte de données.
        if (oldVersion < 20) {
            createDiagnosticEventsView(db);
        }
    }

    // =========================================================
    // LogBus event table (demande Paul, 31 juillet 2026 : "tout persister" — UI/API/IO_TX/IO_RX)
    //
    // LogBus (com.pa.lcr.lcp.log.LogBus) était jusqu'ici UN BUFFER 100% EN MÉMOIRE (5000
    // événements max, jamais persisté, jamais dans android.util.Log) — donc invisible pour
    // le RCA après coup, et absent du système de diagnostic (v_diagnostic_events). Cette
    // table le persiste réellement. Pas de FK vers delivery_attempt : LogBus est scopé par
    // "node" (registre), pas par ticket_no — un événement LogBus n'a pas de notion de ticket
    // au moment où il est émis (contrairement à delivery_event). Rotation par COUNT (voir
    // LogBusStore.pruneIfNeeded()) pour éviter la croissance illimitée vu le volume TX/RX.
    // =========================================================
    private static void createLogBusEventTable(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS log_bus_event (" +
            "id      INTEGER PRIMARY KEY AUTOINCREMENT," +
            "ts      INTEGER NOT NULL," +
            "node    INTEGER NOT NULL," +
            "src     TEXT NOT NULL," +   // UI/API/IO_TX/IO_RX (LogBus.Src.name())
            "msg     TEXT" +
            ");"
        );
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_log_bus_event_node_ts ON log_bus_event(node, ts);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_log_bus_event_ts ON log_bus_event(ts);");
    }

    // =========================================================
    // Sync watermark (demande Paul, 31 juillet 2026 — préparation sync BD support centrale)
    //
    // Marque le dernier id local synchronisé, PAR TABLE, vers Dataverse/BD support. Évite de
    // repousser les mêmes lignes chaque soir (sync incrémentale par delta, pas full-refresh).
    // table_name = nom de la table locale suivie (ex: 'delivery_event', 'log_bus_event').
    // =========================================================
    private static void createSyncWatermarkTable(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS sync_watermark (" +
            "table_name      TEXT PRIMARY KEY," +
            "last_synced_id  INTEGER NOT NULL DEFAULT 0," +
            "updated_ts      INTEGER NOT NULL" +
            ");"
        );
    }

    // =========================================================
    // Diagnostic match history (demande Paul, 31 juillet 2026 — préparation agent IA)
    //
    // Persiste CHAQUE résultat de DiagnosticRuleEngine (jusqu'ici calculé à la volée et
    // jamais stocké — donc invisible pour calibrer les confidence ou pour un futur agent IA
    // qui analyserait la BD support centrale). rule_id nullable + pas de FK stricte : même
    // raisonnement que incident_history, un diagnostic peut en théorie être enregistré même
    // si la règle source a depuis été supprimée/modifiée.
    // =========================================================
    private static void createDiagnosticMatchHistoryTable(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS diagnostic_match_history (" +
            "id                  INTEGER PRIMARY KEY AUTOINCREMENT," +
            "ts                  INTEGER NOT NULL," +   // moment où le diagnostic a été évalué (pas celui de l'événement)
            "rule_id             INTEGER," +            // nullable, pas de FK stricte
            "rule_name           TEXT," +
            "ticket_no           TEXT," +
            "event_id            INTEGER," +            // event_id de v_diagnostic_events qui a matché
            "event_ts            INTEGER," +            // ts de cet événement (utile pour corréler dans le temps)
            "diagnostic          TEXT," +
            "confidence          INTEGER," +
            "support_level       TEXT," +
            "recommended_action  TEXT" +
            ");"
        );
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_diag_match_hist_ticket ON diagnostic_match_history(ticket_no);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_diag_match_hist_ts ON diagnostic_match_history(ts);");
    }

    // =========================================================
    // API trace table (Phase 1c — plan diagnostic intelligent, 27 juillet 2026)
    // attempt_id INTENTIONNELLEMENT nullable et SANS foreign key : delivery_event.attempt_id
    // est NOT NULL + FK stricte (foreign_keys=ON), donc les traces API qui arrivent hors
    // contexte d'une livraison (ex: avant qu'un delivery_attempt existe) ne peuvent pas y vivre.
    // =========================================================
    private static void createApiTraceTable(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS api_trace (" +
            "trace_id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "ts INTEGER NOT NULL," +
            "method TEXT," +
            "path TEXT," +
            "status INTEGER," +
            "duration_ms INTEGER," +
            "serial_id TEXT," +      // best-effort, extrait du body JSON ou de la query string
            "ticket_no TEXT," +      // best-effort
            "attempt_id INTEGER," +  // nullable, pas de FK — best-effort seulement
            "detail_short TEXT" +
            ");"
        );
    }

    // =========================================================
    // Diagnostic rules engine (Phase 2 — plan diagnostic intelligent, 27 juillet 2026)
    //
    // ÉCART ASSUMÉ vs le schéma du plan original : deux colonnes ajoutées
    // (detail_like, data_json_like) qui n'étaient pas dans la proposition initiale.
    // Raison : les règles #4 (Queued timeout) et #7 (level=TRANSPORT) filtrent sur du
    // texte libre dans detail_short/data_json, pas sur event_code/event_type. Sans ces
    // colonnes, ces deux règles auraient dû être codées en dur dans DiagnosticRuleEngine
    // au lieu de vivre dans la table — ce qui aurait cassé l'objectif même d'avoir une
    // table de règles configurable (impossible d'ajouter une règle similaire plus tard
    // sans redéployer du code). À valider avec Paul si ce n'est pas le comportement voulu.
    // =========================================================
    private static void createDiagnosticRulesTable(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS diagnostic_rules (" +
            "rule_id            INTEGER PRIMARY KEY AUTOINCREMENT," +
            "name                TEXT NOT NULL," +
            "event_code          TEXT," +            // filtre principal (ex: 'ERR_MEDIA_NOT_READY')
            "event_type          TEXT," +             // filtre secondaire (ex: 'ALIGN_FAIL')
            "detail_like         TEXT," +             // pattern LIKE optionnel sur detail_short
            "data_json_like      TEXT," +             // pattern LIKE optionnel sur data_json
            "window_seconds      INTEGER DEFAULT 5," + // fenêtre de corrélation avec l'événement précédent
            "precondition_code   TEXT," +             // format 'TYPE=SOUS-CHAINE' (EXISTS) ou '!TYPE=SOUS-CHAINE' (NOT EXISTS)
            "diagnostic          TEXT NOT NULL," +
            "confidence          INTEGER NOT NULL," +  // 0-100, point de départ — à recalibrer via incident_history (Phase 3)
            "support_level       TEXT NOT NULL," +     // N1/N2/N3/N4
            "recommended_action  TEXT" +
            ");"
        );
    }

    private static void seedDiagnosticRules(SQLiteDatabase db) {
        // Ne seed que si la table est vide (idempotent — évite les doublons sur upgrade répété)
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM diagnostic_rules", null)) {
            if (c.moveToFirst() && c.getInt(0) > 0) return;
        } catch (Exception e) {
            Log.w(TAG, "seedDiagnosticRules: count check failed", e);
        }

        // Règle #1 — ALIGN_FAIL précédé (5s) d'un TAB_MEDIA_STATUS=OFF
        insertRule(db, "ALIGN_FAIL_MEDIA_OFF", null, "ALIGN_FAIL", null, null, 5,
                "TAB_MEDIA_STATUS=OFF",
                "Bluetooth/USB déconnecté pendant l'alignement",
                85, "N1", "Vérifier la connexion physique/appairage avant de relancer l'alignement");

        // Règle #4 — CONTINUE_RUN_FAIL avec 'Queued timeout' dans detail_short
        insertRule(db, "CONTINUE_RUN_FAIL_QUEUED_TIMEOUT", null, "CONTINUE_RUN_FAIL",
                "%Queued timeout%", null, 0, null,
                "Registre ne répond plus après CMD_RUN — perte de communication post-RUN",
                88, "N2", "Vérifier l'état du registre et relancer la livraison; escalader si récurrent");

        // Règle #5 — ERR_REGISTER_NOT_FOUND sans TAB_MEDIA_STATUS=READY dans les 30s précédentes
        insertRule(db, "REGISTER_NOT_FOUND_NO_READY", "ERR_REGISTER_NOT_FOUND", null, null, null, 30,
                "!TAB_MEDIA_STATUS=READY",
                "Registre jamais détecté sur ce transport — vérifier appairage/branchement physique",
                82, "N1", "Vérifier le branchement physique et l'appairage Bluetooth/USB");

        // Règle #7 — data_json contient level=TRANSPORT (tagErrorLevel(), déjà catégorisé dans le code)
        insertRule(db, "TRANSPORT_LEVEL_CONFIRMED", null, null, null, "%\"level\":\"TRANSPORT\"%", 0, null,
                "Exception de transport confirmée — pas une erreur logique applicative",
                90, "N1", "Vérifier la couche transport (BT/USB/TCP) plutôt que la logique métier");

        seedDataversePushFailedRule(db);
    }

    // =========================================================
    // Règle #8 — Push Dataverse échoué (ajouté 3 août 2026, suite ticket 10899/10900
    // introuvables dans filgo_lcr_delivery_statuses malgré résumé WO à jour). Matche sur
    // api_trace (via ApiTraceStore.addTraceAsync() dans LcrDeliverySync.pushPending()),
    // donc visible dans v_diagnostic_events (UNION api_trace) — contrairement à un simple
    // LogBus.api(), invisible au moteur de règles (log_bus_event n'est PAS dans la vue).
    // Extrait dans sa propre méthode (plutôt que seedDiagnosticRules()) pour pouvoir aussi
    // l'appeler depuis onUpgrade() sur les BD existantes déjà seedées (v18 et antérieures),
    // où seedDiagnosticRules() ne se relance jamais (table non vide = skip).
    // =========================================================
    private static void seedDataversePushFailedRule(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM diagnostic_rules WHERE name = ?",
                new String[]{"DATAVERSE_PUSH_FAILED"})) {
            if (c.moveToFirst() && c.getInt(0) > 0) return; // déjà présente — idempotent
        } catch (Exception e) {
            Log.w(TAG, "seedDataversePushFailedRule: count check failed", e);
            return;
        }

        insertRule(db, "DATAVERSE_PUSH_FAILED", "POST " + "filgo_lcr_delivery_statuses",
                "API_TRACE", "%push ERR%", null, 0, null,
                "Push Dataverse échoué — livraison restée en ERROR, ne sera PLUS retentée "
                    + "automatiquement (getPendingDeliveries() ne relit que les lignes PENDING)",
                90, "N3",
                "Vérifier le message d'erreur (réseau/MSAL/HTTP) dans le detail_short, "
                    + "puis relancer manuellement le push ou corriger getPendingDeliveries() "
                    + "pour inclure aussi les lignes ERROR");
    }

    private static void insertRule(SQLiteDatabase db, String name, String eventCode, String eventType,
                                    String detailLike, String dataJsonLike, int windowSeconds,
                                    String preconditionCode, String diagnostic, int confidence,
                                    String supportLevel, String recommendedAction) {
        android.content.ContentValues cv = new android.content.ContentValues();
        cv.put("name", name);
        cv.put("event_code", eventCode);
        cv.put("event_type", eventType);
        cv.put("detail_like", detailLike);
        cv.put("data_json_like", dataJsonLike);
        cv.put("window_seconds", windowSeconds);
        cv.put("precondition_code", preconditionCode);
        cv.put("diagnostic", diagnostic);
        cv.put("confidence", confidence);
        cv.put("support_level", supportLevel);
        cv.put("recommended_action", recommendedAction);
        db.insert("diagnostic_rules", null, cv);
    }

    // =========================================================
    // Incident history (Phase 3 — plan diagnostic intelligent, 27 juillet 2026)
    // rule_id nullable ET FK non contraignante en pratique (nullable != NOT NULL, donc
    // aucun conflit avec foreign_keys=ON — contrairement au cas delivery_event.attempt_id) :
    // un diagnostic manuel (sans règle automatique) peut donc être enregistré avec rule_id=NULL.
    // =========================================================
    private static void createIncidentHistoryTable(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS incident_history (" +
            "incident_id        INTEGER PRIMARY KEY AUTOINCREMENT," +
            "rule_id            INTEGER," +           // nullable — quelle règle a détecté ça (null si diagnostic manuel)
            "serial_id          TEXT," +
            "ticket_no          TEXT," +
            "symptom            TEXT NOT NULL," +
            "root_cause         TEXT," +
            "resolution         TEXT," +
            "resolution_time_ms INTEGER," +
            "validated_by       TEXT," +              // qui a confirmé (nom du dev/support)
            "occurrence_count   INTEGER DEFAULT 1," +
            "created_ts         INTEGER NOT NULL," +
            "FOREIGN KEY (rule_id) REFERENCES diagnostic_rules(rule_id)" +
            ");"
        );
    }


    private static void createDiagnosticEventsView(SQLiteDatabase db) {
        // DROP puis CREATE : une VIEW n'a pas d'état propre, donc pas de perte de données
        // possible en la recréant à chaque upgrade/onCreate. Garde onUpgrade idempotent.
        db.execSQL("DROP VIEW IF EXISTS v_diagnostic_events;");
        db.execSQL(
            "CREATE VIEW v_diagnostic_events AS " +
            "SELECT " +
            "  e.event_id, " +
            "  a.attempt_id, " +
            "  e.ts, " +
            "  a.serial_id, " +
            "  a.ticket_no, " +
            "  a.job_id, " +
            "  a.source            AS attempt_source, " +
            "  e.level, " +
            "  e.type               AS event_type, " +
            "  e.event_code, " +
            "  e.event_where, " +
            "  e.detail_short, " +
            "  e.data_json, " +
            "  s.last_state, " +
            "  s.result_json, " +
            "  s.error_json " +
            "FROM delivery_event e " +
            "JOIN delivery_attempt a ON a.attempt_id = e.attempt_id " +
            "LEFT JOIN delivery_summary s " +
            "  ON s.serial_id = a.serial_id AND s.ticket_no = a.ticket_no " +
            "UNION ALL " +
            "SELECT " +
            "  t.trace_id           AS event_id, " +
            "  NULL                 AS attempt_id, " +
            "  t.ts, " +
            "  t.serial_id, " +
            "  t.ticket_no, " +
            "  NULL                 AS job_id, " +
            "  'API_TRACE'           AS attempt_source, " +
            "  'INFO'                AS level, " +
            "  'API_TRACE'           AS event_type, " +
            "  (t.method || ' ' || t.path) AS event_code, " +
            "  'ApiServer'           AS event_where, " +
            "  (COALESCE(t.detail_short, '') || " +
            "   ' status=' || COALESCE(CAST(t.status AS TEXT), '?') || " +
            "   ' dur=' || COALESCE(CAST(t.duration_ms AS TEXT), '?') || 'ms') AS detail_short, " +
            "  NULL                 AS data_json, " +
            "  NULL                 AS last_state, " +
            "  NULL                 AS result_json, " +
            "  NULL                 AS error_json " +
            "FROM api_trace t " +
            "ORDER BY ts;"
        );
    }

    // =========================================================
    // Table creation helpers
    // =========================================================
    private static void createDeliveryTables(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS delivery_summary (" +
            "serial_id TEXT NOT NULL," +
            "ticket_no TEXT NOT NULL," +
            "sale_no TEXT," +
            "last_state TEXT NOT NULL," +
            "last_source TEXT NOT NULL," +
            "last_job_id TEXT," +
            "first_ts INTEGER NOT NULL," +
            "last_ts INTEGER NOT NULL," +
            "result_json TEXT," +
            "error_json TEXT," +
            "start_ms INTEGER," +
            "end_ms INTEGER," +
            "start_utc TEXT," +
            "end_utc TEXT," +
            "duration_ms INTEGER," +
            "PRIMARY KEY (serial_id, ticket_no)" +
            ");"
        );
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS delivery_attempt (" +
            "attempt_id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "serial_id TEXT NOT NULL," +
            "ticket_no TEXT NOT NULL," +
            "source TEXT NOT NULL," +
            "job_id TEXT," +
            "start_ts INTEGER NOT NULL," +
            "end_ts INTEGER," +
            "outcome TEXT," +
            "result_json TEXT," +
            "error_json TEXT," +
            "FOREIGN KEY (serial_id, ticket_no) " +
            "REFERENCES delivery_summary(serial_id, ticket_no) " +
            "ON DELETE CASCADE" +
            ");"
        );
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_attempt_lookup " +
            "ON delivery_attempt(serial_id, ticket_no, source, job_id);"
        );
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS delivery_event (" +
            "event_id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "attempt_id INTEGER NOT NULL," +
            "ts INTEGER NOT NULL," +
            "level TEXT NOT NULL," +
            "type TEXT NOT NULL," +
            "message TEXT," +
            "data_json TEXT," +
            "event_level TEXT," +
            "event_code TEXT," +
            "event_where TEXT," +
            "detail_short TEXT," +
            "FOREIGN KEY (attempt_id) " +
            "REFERENCES delivery_attempt(attempt_id) " +
            "ON DELETE CASCADE" +
            ");"
        );
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_event_attempt_ts " +
            "ON delivery_event(attempt_id, ts);"
        );
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_event_level_ts ON delivery_event(event_level, ts);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_event_code_ts ON delivery_event(event_code, ts);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_summary_time ON delivery_summary(start_ms, end_ms);");
    }

    private static void createMediaTables(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS media_profile (" +
            "media_id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "media_type TEXT NOT NULL," +
            "display_name TEXT," +
            "enabled INTEGER NOT NULL DEFAULT 1," +
            "is_active INTEGER NOT NULL DEFAULT 0," +
            "status TEXT NOT NULL DEFAULT 'DISCONNECTED'," +
            "last_error TEXT," +
            "created_ts INTEGER NOT NULL," +
            "last_seen_ts INTEGER," +
            "last_ok_ts INTEGER," +
            "usb_vid INTEGER," +
            "usb_pid INTEGER," +
            "usb_device_name TEXT," +
            "usb_permission INTEGER," +
            "serial_baud INTEGER," +
            "serial_data_bits INTEGER," +
            "serial_stop_bits INTEGER," +
            "serial_parity TEXT," +
            "serial_flow_control TEXT," +
            "bt_name TEXT," +
            "bt_mac TEXT," +
            "bt_uuid TEXT," +
            "bt_bond_state TEXT," +
            "bt_socket_state TEXT" +
            ");"
        );
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_active ON media_profile(is_active);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_type ON media_profile(media_type);");
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS media_event (" +
            "event_id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "ts INTEGER NOT NULL," +
            "media_id INTEGER," +
            "media_type TEXT," +
            "level TEXT NOT NULL," +
            "code TEXT," +
            "message TEXT," +
            "data_json TEXT" +
            ");"
        );
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_event_ts ON media_event(ts);");
    }

    // =========================================================
    // Truck profile tables (v5)
    // =========================================================
    private static void createTruckTables(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS truck_profile (" +
            "truck_id TEXT PRIMARY KEY," +
            "bt_mac TEXT," +
            "bt_name TEXT," +
            "lcrnode_dec INTEGER," +
            "serial_id TEXT," +
            "default_product INTEGER," +
            "compartments TEXT," +
            "notes TEXT," +
            "active INTEGER NOT NULL DEFAULT 0," +
            "ts_created_ms INTEGER NOT NULL," +
            "ts_updated_ms INTEGER NOT NULL" +
            ");"
        );
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_truck_active ON truck_profile(active);");
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS truck_drift (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "truck_id TEXT NOT NULL," +
            "field_name TEXT NOT NULL," +
            "expected_value TEXT," +
            "actual_value TEXT," +
            "delivery_uid TEXT," +
            "acknowledged INTEGER NOT NULL DEFAULT 0," +
            "ts_ms INTEGER NOT NULL" +
            ");"
        );
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_drift_truck ON truck_drift(truck_id, ts_ms);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_drift_ack ON truck_drift(acknowledged);");
    }

    // =========================================================
    // Active delivery table (v6)
    // Une seule ligne (id=1) — livraison courante en cours.
    // Effacée à onDeliveryEnded. Permet de reprendre le poll
    // si l'APK est relancé pendant une livraison active.
    // =========================================================
    private static void createActiveDeliveryTable(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS active_delivery (" +
            "id INTEGER PRIMARY KEY CHECK (id = 1)," +
            "wo_num TEXT," +
            "wo_id_guid TEXT," +
            "job_id TEXT," +
            "mac TEXT," +
            "node INTEGER," +
            "serial_id TEXT," +
            "produit INTEGER," +
            "preset REAL," +
            "status TEXT," +
            "ts_started_ms INTEGER" +
            ");"
        );
    }

    // =========================================================
    // BT Signal table (v8) — historique signal BT par transport
    // =========================================================
    private static void createBtSignalTable(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS bt_signal (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "transport_key TEXT NOT NULL," +
            "mac TEXT," +
            "ts_ms INTEGER NOT NULL," +
            "delivery_active INTEGER NOT NULL DEFAULT 0," +
            "source TEXT," +
            "rssi INTEGER," +
            "rssi_quality TEXT," +
            "io_samples INTEGER," +
            "io_score TEXT," +
            "io_errors INTEGER," +
            "io_timeouts INTEGER," +
            "io_latency_avg_ms INTEGER," +
            "notes TEXT" +
            ");"
        );
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_bt_signal_ts ON bt_signal(transport_key, ts_ms);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_bt_signal_mac ON bt_signal(mac, ts_ms);");
    }

    private static void createRegisterProductsTable(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS register_products (" +
            "serial_id   TEXT    NOT NULL," +
            "note_idx    INTEGER NOT NULL," +
            "description TEXT    NOT NULL DEFAULT ''," +
            "lcr_node    INTEGER NOT NULL DEFAULT 0," +
            "is_propane  INTEGER NOT NULL DEFAULT 0," +
            "updated_at  INTEGER NOT NULL DEFAULT 0," +
            "sync_status TEXT    NOT NULL DEFAULT 'PENDING'," +
            "PRIMARY KEY (serial_id, note_idx)" +
            ");"
        );
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_rp_sync ON register_products(sync_status);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_rp_serial_desc ON register_products(serial_id, description);");
    }

    /**
     * ✅ known_tcp_device — équivalent "appareils appairés" pour raw TCP (N-Port).
     * Contrairement au Bluetooth, il n'existe aucun appairage niveau OS pour un
     * N-Port : cette table est la mémoire locale de l'APK, alimentée à chaque
     * connexion TCP réussie (manuelle ou via scan subnet), pour éviter de
     * retaper l'IP à chaque livraison.
     */
    private static void createKnownTcpDeviceTable(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS known_tcp_device (" +
            "ip           TEXT    NOT NULL," +
            "port         INTEGER NOT NULL," +
            "label        TEXT    NOT NULL DEFAULT ''," +
            "serial_id    TEXT," +
            "lcr_node     INTEGER," +
            "last_ok_ms   INTEGER NOT NULL DEFAULT 0," +
            "created_ms   INTEGER NOT NULL DEFAULT 0," +
            "PRIMARY KEY (ip, port)" +
            ");"
        );
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ktd_last_ok ON known_tcp_device(last_ok_ms DESC);");
    }

    // =========================================================
    // Column helper
    // =========================================================
    private static void addColumnIfMissing(SQLiteDatabase db, String table, String col, String type) {
        boolean exists = false;
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            int nameIdx = c.getColumnIndex("name");
            while (c.moveToNext()) {
                String n = c.getString(nameIdx);
                if (col.equalsIgnoreCase(n)) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + col + " " + type);
        }
    }
}
