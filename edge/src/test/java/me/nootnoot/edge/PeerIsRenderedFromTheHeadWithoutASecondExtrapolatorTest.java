package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PeerIsRenderedFromTheHeadWithoutASecondExtrapolatorTest {

    private static String read(String p) throws IOException {
        return Files.readString(Path.of(p), StandardCharsets.UTF_8);
    }

    @Test
    void thePeerNpcFollowsTheSimHeadWithoutExtrapolation() throws IOException {
        String s = read("src/main/java/me/nootnoot/edge/EdgeRenderer.java");
        assertTrue(s.contains("peerAbsorb.follow(other, peerJumped)"),
                "the edge renders exactly ONCE per sim tick, so there is no sub-tick gap to fill."
                        + " The mod dead reckons because it renders at display framerate BETWEEN"
                        + " sim ticks - copying that onto the edge stacks a second, mis-phased"
                        + " extrapolator on top of the client's own interpolator and adds lag that"
                        + " is visible even at 0ms, on the attacker's screen only, because only"
                        + " the attacker sees the victim through this path");
    }

    @Test
    void thereIsNoServerSideEasingOfThePeer() throws IOException {
        String s = read("src/main/java/me/nootnoot/edge/EdgeRenderer.java");
        assertFalse(s.contains("EdgePeerSmoother"),
                "server-side easing of the opponent trades correctness for smoothness and loses"
                        + " both: the rendered box is what melee rays hit, so easing it also"
                        + " desyncs aim from what the sim will adjudicate");
    }

    @Test
    void thePeerMoveIsInterpolableRatherThanASnapEveryTick() throws IOException {
        String s = read("src/main/java/me/nootnoot/edge/EdgePlayerEntity.java");
        assertTrue(s.contains("WrapperPlayServerEntityRelativeMoveAndRotation"),
                "a vanilla client SNAPS on a teleport packet and interpolates only a relative move."
                        + " Teleporting every tick delivers a 10 tick knockback arc as 10 discrete"
                        + " jumps with nothing drawn between them, which at 60+ fps reads as a"
                        + " glitchy, wrong-shaped arc even though the SIMULATED arc is a correct"
                        + " vanilla parabola - traced and confirmed identical to the mod path");
        assertTrue(s.contains("WrapperPlayServerEntityTeleport"),
                "the teleport path must survive for jumps a relative move cannot encode, such as a"
                        + " pearl or a round reset");
    }

    @Test
    void nothingElseIsStackedOnTopOfTheClientInterpolator() throws IOException {
        String s = read("src/main/java/me/nootnoot/edge/EdgeRenderer.java");
        assertFalse(s.contains("EdgePeerSmoother"),
                "interpolable moves were first shipped WITH a server-side smoother stacked on them,"
                        + " which double-extrapolated and read as lag. The two were then reverted"
                        + " together, so interpolation was never judged on its own. The client's"
                        + " own interpolator is the only smoothing that belongs here");
    }

    @Test
    void aMotionlessPeerIsPinnedRatherThanLeftUnsent() throws IOException {
        String s = read("src/main/java/me/nootnoot/edge/EdgePlayerEntity.java");
        assertTrue(s.contains("boolean idle = sentPosition"),
                "a stationary peer must still receive a packet. Sending NOTHING leaves the client's"
                        + " own entity ticking with its own gravity and no update to re-anchor it,"
                        + " so it sinks into the floor - measured: the edge never rendered the peer"
                        + " below y=6.000, so the sink was happening entirely on the client after"
                        + " our last packet");
        assertTrue(s.contains("MOVE_EPSILON_SQ"),
                "every packet resets the vanilla PositionInterpolator to a 1/3 step, so a peer that"
                        + " is sent a move EVERY tick never finishes converging and permanently"
                        + " trails the real position by about two ticks. At the end of a knockback"
                        + " that reads as the victim not coming all the way down to the floor."
                        + " Vanilla suppresses sub-threshold moves for exactly this reason");
    }
}
