package com.gomocalc.app.engine.rapfi;

/**
 * Rapfi wire/protocol names shared by a future JNI or local-process adapter.
 * Parsing and transport stay in the Rapfi package; UI code must consume EngineEvent snapshots.
 */
public final class RapfiProtocol {
    public static final String SHOW_FORBID = "YXSHOWFORBID";
    public static final String FORBID = "FORBID";
    public static final String INFO = "INFO";
    public static final String BESTLINE = "BESTLINE";

    private RapfiProtocol() { }
}
