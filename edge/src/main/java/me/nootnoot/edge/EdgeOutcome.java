package me.nootnoot.edge;

public final class EdgeOutcome {

    public static final String FINISHED = "FINISHED";

    public static final String LOCAL_QUIT = "LOCAL_QUIT";

    public static final String LOCAL_FORFEIT = "LOCAL_FORFEIT";

    public static final String PEER_GONE = "PEER_GONE";

    public static final String DESYNC = "DESYNC";

    public static final String SELF_FAULT = "SELF_FAULT";

    public static final String PEER_NEVER_ARRIVED = "PEER_NEVER_ARRIVED";

    public static final String ARENA_MISMATCH = "ARENA_MISMATCH";

    public static final String NO_FRAME_ZERO = "NO_FRAME_ZERO";

    public static final String DESYNC_ANNOUNCED = "DESYNC_ANNOUNCED";

    public static final String PEER_OVERRUN = "PEER_OVERRUN";

    private EdgeOutcome() {
    }
}
