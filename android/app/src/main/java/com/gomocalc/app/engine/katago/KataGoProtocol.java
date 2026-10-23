package com.gomocalc.app.engine.katago;

/**
 * Names for the future KataGo transport.  The adapter should normalize KataGo data to
 * AnalysisState (black-perspective win rate, visits, heatmap, and forbidden points) before
 * publishing EngineEvent objects.
 */
public final class KataGoProtocol {
    public static final String ANALYSIS_REQUEST = "analysisRequest";
    public static final String MOVE = "move";
    public static final String VISITS = "visits";
    public static final String WINRATE = "winrate";

    private KataGoProtocol() { }
}
