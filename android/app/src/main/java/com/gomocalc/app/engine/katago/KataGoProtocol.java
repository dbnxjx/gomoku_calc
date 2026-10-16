package com.gomocalc.app.engine.katago;

/** Protocol conversion constants shared by the Android KataGo transport. */
public final class KataGoProtocol {
    public static final String YXBOARD = "YXBOARD";
    public static final String YXNBEST = "YXNBEST";
    public static final String START = "START";
    public static final String STOP = "stop";
    public static final String YXSTOP = "YXSTOP";
    public static final String PLAY = "PLAY";
    public static final String UNDO = "UNDO";
    public static final String SET_POSITION = "SET_POSITION";

    private KataGoProtocol() { }
}
