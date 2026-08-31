package me.nootnoot.edge;

import java.util.logging.Logger;

public final class EdgeTrace {

    private static volatile boolean enabled;
    private static volatile Logger logger;

    private EdgeTrace() {
    }

    public static void configure(boolean on, Logger log) {
        enabled = on;
        logger = log;
        if (on && log != null) {
            log.info("[trace] diagnostic tracing is ON: inventory paints, peer arc after every"
                    + " impulse, and every movement correction will be logged");
        }
    }

    public static boolean on() {
        return enabled;
    }

    public static void log(String line) {
        Logger log = logger;
        if (enabled && log != null) {
            log.info("[trace] " + line);
        }
    }
}
