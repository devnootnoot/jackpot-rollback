package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class ClaimWindowIsNotPeerDerivedTest {

    private static Path claimAuthoritySource() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null) {
            Path candidate = p.resolve(
                    "sim-core/src/main/java/me/nootnoot/sim/ClaimAuthority.java");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("ClaimAuthority.java was not found from "
                + Path.of("").toAbsolutePath());
    }

    @Test
    void theVictimRewindIsTheSampleToResolveIntervalPlusPresentationLag() {
        assertEquals(ClaimAuthority.INPUT_DELAY_FRAMES
                        + ClaimAuthority.RECORD_OFFSET_FRAMES
                        + ClaimAuthority.PRESENTATION_LAG_FRAMES,
                ClaimAuthority.WINDOW_FRAMES,
                "the window is the sum of the three intervals over which the victim moves"
                        + " underneath a crosshair that has already been aimed, and nothing else");
        assertEquals(1, ClaimAuthority.RECORD_OFFSET_FRAMES,
                "record() runs at the TOP of Simulation.tick, so the newest rewind entry is the"
                        + " position at the end of the previous frame - one frame of offset that is"
                        + " there even at zero input delay");
    }

    @Test
    void theWindowDoesNotTrackAProtocolTolerance() throws IOException {
        String source = Files.readString(claimAuthoritySource(), StandardCharsets.UTF_8);

        assertFalse(source.contains("MAX_PEER_DELAY_ALLOWANCE"),
                "WINDOW_FRAMES used to read MAX_PEER_DELAY_ALLOWANCE + INPUT_DELAY_FRAMES."
                        + " MAX_PEER_DELAY_ALLOWANCE is a NETWORK tolerance on how far ahead of its"
                        + " own head a peer may author inputs; it is consulted by"
                        + " fillerFrameIsPossible and by the catch-up target and says nothing about"
                        + " how stale an attacker's view of the victim is. Wiring it into a hitreg"
                        + " window meant that tuning a transport knob for a transport reason would"
                        + " silently move how far back in time a sword can reach.");
        assertFalse(source.contains("import me.nootnoot.sim.net."),
                "the claim window is a property of the simulation pipeline, so ClaimAuthority has"
                        + " no business importing the wire protocol at all");
    }

    @Test
    void theAttackerEyeAndTheVictimHullRewindTheSameInterval() {
        assertTrue(ClaimAuthority.WINDOW_FRAMES >= ClaimAuthority.INPUT_DELAY_FRAMES,
                "the victim cannot be rewound less far than the attacker's own eye, or the pair"
                        + " describe different instants");
        assertTrue(ClaimAuthority.WINDOW_FRAMES <= PlayerState.REWIND_FRAMES,
                "the window may never exceed the ring that stores it");
    }

    @Test
    void theCandidateArrayIsSizedFromTheWindow() {
        assertEquals(ClaimAuthority.WINDOW_FRAMES * ClaimAuthority.PATH_LINK_MAX_STEPS + 1,
                ClaimAuthority.CANDIDATE_CAPACITY,
                "candidates() writes one hull per rewind entry plus up to PATH_LINK_MAX_STEPS - 1"
                        + " interpolated links, plus the live hull");
    }
}
