package com.gomocalc.app.engine.rapfi;

import com.gomocalc.app.engine.EngineOptions;

/**
 * Rapfi wire/protocol names shared by the JNI transport, the native bridge and the adapter.
 * Parsing and transport stay in the Rapfi package; UI code must consume EngineEvent snapshots.
 */
public final class RapfiProtocol {
    // ---- input commands ----
    public static final String START = "START";
    public static final String RESTART = "RESTART";
    public static final String END = "END";
    public static final String INFO = "INFO";
    public static final String BOARD = "BOARD";            // board + immediately think
    public static final String YXBOARD = "YXBOARD";        // board, no immediate think
    public static final String YXNBEST = "YXNBEST";        // multi-PV analysis
    public static final String YXSTOP = "YXSTOP";          // stop current search
    public static final String YXSHOWFORBID = "YXSHOWFORBID";
    public static final String YXBALANCEONE = "YXBALANCEONE";
    public static final String YXBALANCETWO = "YXBALANCETWO";

    // ---- Yixinboard-compatible analysis database (cross-session cache) ----
    /** Open/switch to the database at the following absolute path (rest of the line). */
    public static final String YXSETDATABASE = "YXSETDATABASE";
    /** Flush in-memory records to the database file (no-op when nothing changed). */
    public static final String YXSAVEDATABASE = "YXSAVEDATABASE";

    // ---- INFO parameters ----
    public static final String INFO_RULE = "RULE";
    public static final String INFO_THREAD_NUM = "THREAD_NUM";
    public static final String INFO_CAUTION_FACTOR = "CAUTION_FACTOR";
    public static final String INFO_STRENGTH = "STRENGTH";
    public static final String INFO_TIMEOUT_TURN = "TIMEOUT_TURN";
    public static final String INFO_TIMEOUT_MATCH = "TIMEOUT_MATCH";
    public static final String INFO_MAX_DEPTH = "MAX_DEPTH";
    public static final String INFO_MAX_NODE = "MAX_NODE";
    public static final String INFO_SHOW_DETAIL = "SHOW_DETAIL";
    public static final String INFO_PONDERING = "PONDERING";
    public static final String INFO_SWAPABLE = "SWAPABLE";
    public static final String INFO_HASH_SIZE = "HASH_SIZE";
    public static final String INFO_TIME_LEFT = "TIME_LEFT";
    /** 1 attaches the configured database, 0 detaches it. */
    public static final String INFO_USEDATABASE = "USEDATABASE";
    /** 1 disables database writes (read-only), 0 allows writes. */
    public static final String INFO_DATABASE_READONLY = "DATABASE_READONLY";

    // ---- output tokens ----
    public static final String OK = "OK";
    public static final String SWAP = "SWAP";
    public static final String MESSAGE = "MESSAGE";
    public static final String FORBID = "FORBID";
    public static final String ERROR = "ERROR";
    public static final String DONE = "DONE";

    public static final String PV = "PV";
    public static final String NUMPV = "NUMPV";
    public static final String DEPTH = "DEPTH";
    public static final String SELDEPTH = "SELDEPTH";
    public static final String NODES = "NODES";
    public static final String TOTALNODES = "TOTALNODES";
    public static final String TOTALTIME = "TOTALTIME";
    public static final String SPEED = "SPEED";
    public static final String EVAL = "EVAL";
    public static final String WINRATE = "WINRATE";
    public static final String BESTLINE = "BESTLINE";

    /** Engine message emitted after a database file is loaded, e.g.
     *  "Loaded Yixin database (12 records) using 1 ms.". */
    public static final String DB_LOADED_TAG = "Loaded Yixin database";

    /**
     * Extract the record count from the engine's database-loaded message, e.g.
     * {@code "Loaded Yixin database (12 records) using 1 ms."} -> 12.
     *
     * @return the record count, or null when the line is not a database-loaded message.
     */
    public static Integer parseDatabaseRecordCount(String line) {
        if (line == null || !line.contains(DB_LOADED_TAG))
            return null;
        int open = line.indexOf('(', line.indexOf(DB_LOADED_TAG));
        if (open < 0)
            return null;
        int close = line.indexOf(' ', open + 1);
        if (close < 0)
            return null;
        try {
            return Integer.valueOf(Integer.parseInt(line.substring(open + 1, close).trim()));
        } catch (NumberFormatException error) {
            return null;
        }
    }

    /** Rapfi rule ids as sent through {@code INFO RULE <id>}. */
    public static int ruleId(EngineOptions.Rule rule) {
        switch (rule) {
            case FREESTYLE: return 0;
            case STANDARD: return 1;
            case RENJU: return 2;
            default: return 0;
        }
    }

    private RapfiProtocol() { }
}
