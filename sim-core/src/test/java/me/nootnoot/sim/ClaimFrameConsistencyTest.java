package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class ClaimFrameConsistencyTest {
    private static final double GROUND_Y = 64.0;

    private static final double LANE_Z = 0.5;

    private static GameState lane(double attackerX, double victimX) {
        GameState s = new GameState();
        PlayerState a = s.players[0];
        a.x = attackerX;
        a.y = GROUND_Y;
        a.z = LANE_Z;
        a.yaw = -90f;
        a.onGround = true;
        a.health = 20f;

        PlayerState v = s.players[1];
        v.x = victimX;
        v.y = GROUND_Y;
        v.z = LANE_Z;
        v.yaw = 90f;
        v.onGround = true;
        v.health = 20f;
        return s;
    }

    @Test
    void aRewoundEyeUsesThePoseItWasRecordedInNotTheLiveOne() {
        GameState s = lane(1.0, 40.0);
        PlayerState a = s.players[0];
        a.sneaking = true;

        ClaimAuthority.record(s);

        a.sneaking = false;
        a.y = 70.0;

        double[] eyes = new double[(ClaimAuthority.WINDOW_FRAMES + 1) * 3];
        int n = ClaimAuthority.frameEyes(a, eyes, ClaimAuthority.WINDOW_FRAMES);

        assertEquals(2, n, "the live frame plus the one recorded frame");
        assertEquals(70.0 + Combat.EYE_STANDING, eyes[1], 1.0E-9,
                "the live origin takes the live pose");
        assertEquals(GROUND_Y + Combat.EYE_SNEAKING, eyes[4], 1.0E-9,
                "and the rewound origin takes the sneaking eye it was recorded with, not the"
                        + " standing one the attacker happens to be in now");
    }

    @Test
    void aPoseHeightMapsToTheEyeHeightVanillaGivesThatPose() {
        assertEquals(Combat.EYE_STANDING, Combat.eyeHeightForPose(Simulation.PLAYER_HEIGHT), 0.0);
        assertEquals(Combat.EYE_SNEAKING, Combat.eyeHeightForPose(Simulation.PLAYER_SNEAK_HEIGHT), 0.0);
        assertEquals(Combat.EYE_GLIDING, Combat.eyeHeightForPose(Simulation.PLAYER_SWIM_HEIGHT), 0.0);
    }

    @Test
    void theAttackerEyeWindowIsExactlyTheInputDelay() {
        assertEquals(1, ClaimAuthority.INPUT_DELAY_FRAMES,
                "the attacker's own input is delayed by one frame, so one frame of eye history is"
                        + " the whole allowance the delay can justify");

        double[] eyes = new double[(ClaimAuthority.INPUT_DELAY_FRAMES + 1) * 3];
        GameState s = lane(0.0, 40.0);
        for (int i = 0; i < ClaimAuthority.WINDOW_FRAMES; i++) {
            ClaimAuthority.record(s);
        }

        assertEquals(ClaimAuthority.INPUT_DELAY_FRAMES + 1,
                ClaimAuthority.frameEyes(s.players[0], eyes, ClaimAuthority.INPUT_DELAY_FRAMES),
                "a full rewind ring must still hand the claim only the live eye plus one");
    }

    @Test
    void anAttackerEyeOlderThanTheInputDelayCannotBeClaimedFrom() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = lane(4.0, 8.0);
        PlayerState a = s.players[0];

        for (int i = 0; i < 4; i++) {
            ClaimAuthority.record(s);
        }
        a.x = 1.0;
        for (int i = 0; i <= ClaimAuthority.INPUT_DELAY_FRAMES; i++) {
            ClaimAuthority.record(s);
        }

        assertEquals(6.7, s.players[1].x - Simulation.PLAYER_WIDTH * 0.5 - a.x, 1.0E-9);
        assertTrue(6.7 > Combat.attackReachLimit(s, a),
                "the attacker has to be out of reach from where it actually is, or this proves"
                        + " nothing");

        assertNull(ClaimAuthority.meleeClaim(s, arena, a, s.players[1]),
                "the victim never moved, so the only thing an older attacker eye buys is a hit"
                        + " from a position the attacker left four frames ago; the rewind window"
                        + " belongs to the victim, not to the attacker's own origin");
    }

    @Test
    void theAttackerEyeOneFrameBackIsStillClaimableFrom() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = lane(4.0, 8.0);

        ClaimAuthority.record(s);
        s.players[0].x = 1.0;

        assertNotNull(ClaimAuthority.meleeClaim(s, arena, s.players[0], s.players[1]),
                "the input applied this frame was chosen from the pose the client rendered one"
                        + " frame earlier, so that one eye must stay claimable or every hit taken"
                        + " while moving is refused");
    }

    @Test
    void aClaimAgainstTheFrameTheyDidShareIsHonoured() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = lane(0.0, 3.0);

        ClaimAuthority.record(s);

        s.players[0].x = 0.0;
        s.players[1].x = 20.0;

        assertNotNull(ClaimAuthority.meleeClaim(s, arena, s.players[0], s.players[1]),
                "on the recorded frame the victim stood 2.7 blocks from the eye, well inside the"
                        + " 6.0 bound, and that frame is what the claim is judged against");
    }
}
