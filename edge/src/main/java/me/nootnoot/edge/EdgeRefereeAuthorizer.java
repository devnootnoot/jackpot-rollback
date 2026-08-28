package me.nootnoot.edge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import me.nootnoot.edge.tools.DevRefereeAuthorizer;
import me.nootnoot.sim.net.SlotTokens;

public final class EdgeRefereeAuthorizer {

    public static final String SLOT_SECRET_ENV = "RELAY_SLOT_SECRET";

    public static final String CONTROL_PORT_ENV = "RELAY_CONTROL_PORT";

    private final Logger log;

    private final Set<Long> settled = ConcurrentHashMap.newKeySet();

    public EdgeRefereeAuthorizer(Logger log) {
        this.log = log;
    }

    public boolean isAuthorized(long sessionId) {
        return settled.contains(sessionId);
    }

    public void forget(long sessionId) {
        settled.remove(sessionId);
    }

    public boolean owns(EdgeAssignment assignment) {
        return assignment != null && assignment.devKit() && !assignment.hasSetup();
    }

    public boolean claim(EdgeAssignment assignment) {
        return owns(assignment) && settled.add(assignment.sessionId());
    }

    public byte[] encodeFrameZero(EdgeAssignment assignment) {
        try {
            for (String line : EdgeGameTypes.warningLines(assignment.gameType(),
                    EdgeDemoKits.materials(EdgeDevKit.kitFor(assignment)),
                    "referee frame 0 for session " + assignment.sessionId())) {
                log.warning("[referee] " + line);
            }
            return EdgeDevKit.encodeAddressed(assignment);
        } catch (RuntimeException | LinkageError ex) {
            log.warning("[referee] session " + assignment.sessionId() + " gets NO referee: the demo"
                    + " kit would not encode here (" + ex + "), so there is no frame 0 to hand the"
                    + " relay. The duel still runs, but its result rests on the two clients"
                    + " corroborating each other instead of on a signed third witness.");
            return null;
        }
    }

    public void publish(EdgeAssignment assignment, byte[] setup, byte[] arenaBlob) {
        long sessionId = assignment.sessionId();
        String control = env(DevRefereeAuthorizer.CONTROL_SECRET_ENV);
        if (control == null) {
            log.warning("[referee] session " + sessionId + " gets NO referee: "
                    + DevRefereeAuthorizer.CONTROL_SECRET_ENV + " is not set on this edge, so it"
                    + " cannot sign a control frame the relay would accept. Set it to the same"
                    + " value the relay was started with - gradlew devRun does that for you.");
            return;
        }
        String slotSecret = env(SLOT_SECRET_ENV);
        if (slotSecret == null) {
            log.warning("[referee] session " + sessionId + " gets NO referee: " + SLOT_SECRET_ENV
                    + " is not set on this edge, so the per-slot tokens the relay binds HELLO"
                    + " against cannot be derived here and the referee would reject both peers.");
            return;
        }
        byte[] secret = slotSecret.getBytes(StandardCharsets.UTF_8);
        byte[] message = DevRefereeAuthorizer.message(sessionId,
                System.currentTimeMillis() + DevRefereeAuthorizer.SESSION_TTL_MS,
                SlotTokens.derive(secret, sessionId, 0), SlotTokens.derive(secret, sessionId, 1),
                setup, arenaBlob);
        int port = controlPort();
        String host = assignment.relayHost();
        try {
            DevRefereeAuthorizer.send(host, port, control, message);
        } catch (IOException | RuntimeException unreachable) {
            log.warning("[referee] session " + sessionId + " gets NO referee: the relay control"
                    + " endpoint at " + host + ":" + port + " was unreachable (" + unreachable
                    + "). The duel still runs on two witnesses.");
            return;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        }
        log.info("[referee] session " + sessionId + " authorized with the relay at " + host + ":"
                + port + " (" + setup.length + "B of frame 0, "
                + (arenaBlob == null ? 0 : arenaBlob.length) + "B of arena). The relay"
                + " re-simulates both input streams and its signed verdict outranks either"
                + " client's word.");
    }

    private int controlPort() {
        String raw = env(CONTROL_PORT_ENV);
        if (raw == null) {
            return DevRefereeAuthorizer.DEFAULT_CONTROL_PORT;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException notANumber) {
            return DevRefereeAuthorizer.DEFAULT_CONTROL_PORT;
        }
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
