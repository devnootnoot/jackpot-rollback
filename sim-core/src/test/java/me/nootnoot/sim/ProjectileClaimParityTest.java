package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.contract.HostFrameContract;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.PlayerState;
import me.nootnoot.sim.state.ProjectileState;
import org.junit.jupiter.api.Test;

class ProjectileClaimParityTest {

    private static final double GROUND_Y = 64.0;

    private static final String THE_GAP =
            "McInputSource swept its own live sim arrows against the RENDERED opponent entity box"
                    + " and stamped the arrow id on a crossing; EdgeInputSource produced nothing,"
                    + " because a vanilla client never tells the server its arrow connected. The"
                    + " claim is not cosmetic: ClaimAuthority.arrowClaim resolves it against hulls"
                    + " rewound up to ClaimAuthority.WINDOW_FRAMES ticks, so a modded archer hit"
                    + " where the target had been and an unmodded archer only ever hit the live"
                    + " hull its arrow segment crossed";

    private static GameState duel() {
        GameState s = new GameState();
        PlayerState a = s.players[0];
        a.x = 0.0;
        a.y = GROUND_Y;
        a.z = 0.5;
        a.yaw = -90f;
        a.onGround = true;
        a.health = 20f;

        PlayerState v = s.players[1];
        v.x = 6.0;
        v.y = GROUND_Y;
        v.z = 0.5;
        v.yaw = 90f;
        v.onGround = true;
        v.health = 20f;
        return s;
    }

    private static ProjectileState arrow(int id, int owner, double x, double vx) {
        ProjectileState p = new ProjectileState();
        p.id = id;
        p.type = ProjectileState.TYPE_ARROW;
        p.owner = owner;
        p.x = x;
        p.y = GROUND_Y + 1.4;
        p.z = 0.5;
        p.vx = vx;
        p.life = Projectiles.MAX_LIFE;
        p.fresh = false;
        p.leftOwner = true;
        return p;
    }

    @Test
    void theClaimIsProducedFromTheReplicatedArrowsAndHullsAlone() {
        GameState s = duel();
        ProjectileState p = arrow(0x123, 0, 5.0, 1.5);
        s.projectiles.add(p);

        assertEquals(p.id & Input.PROJECTILE_HIT_ID_MASK,
                HostFrameContract.projectileClaim(s, 0),
                "the sweep needs nothing but GameState.projectiles and the opponent's sim hull, so"
                        + " a host with no client world can make exactly the same claim. " + THE_GAP);
        assertEquals(Input.NO_PROJECTILE_HIT, HostFrameContract.projectileClaim(s, 1),
                "an arrow claims only for the player who shot it");
    }

    @Test
    void anArrowThatIsNowhereNearTheOpponentClaimsNothing() {
        GameState s = duel();
        s.projectiles.add(arrow(0x123, 0, 1.0, 1.5));

        assertEquals(Input.NO_PROJECTILE_HIT, HostFrameContract.projectileClaim(s, 0),
                "the claim is a rewind grant, not a licence: an arrow whose this-tick segment"
                        + " crosses nothing must stay on the sim's own favour-the-victim path");
    }

    @Test
    void theSelfHitArmsOffTheSimsOwnLeftOwnerFlagAndNotASecondCopyOfIt() {
        GameState s = duel();
        ProjectileState p = arrow(0x321, 0, 0.0, -0.2);
        p.leftOwner = false;
        s.projectiles.add(p);

        assertEquals(Input.NO_PROJECTILE_HIT, HostFrameContract.projectileClaim(s, 0),
                "a freshly loosed arrow still overlapping its owner must not claim a self hit."
                        + " The mod used to answer that from its own armedArrows set, a second"
                        + " copy of ProjectileState.leftOwner that only one host kept");

        p.leftOwner = true;
        assertEquals(Input.PROJECTILE_HIT_SELF | (p.id & Input.PROJECTILE_HIT_ID_MASK),
                HostFrameContract.projectileClaim(s, 0),
                "once the sim says the arrow separated, an arrow arcing back onto its owner"
                        + " claims a self hit - and both hosts read that from the same flag");
    }

    @Test
    void anOpponentCrossingWinsOverASelfCrossingInTheSameTick() {
        GameState s = duel();
        PlayerState v = s.players[1];
        v.x = 0.9;
        s.projectiles.add(arrow(0x111, 0, 0.4, 0.3));

        assertEquals(0x111, HostFrameContract.projectileClaim(s, 0),
                "when one arrow could be read as hitting either player in the same tick the"
                        + " opponent is the claim, which is the order the mod's loop had and the"
                        + " order the shared rule must keep");
    }

    @Test
    void aClaimTheSimHasAlreadySpentIsNotOfferedAgain() {
        GameState s = duel();
        ProjectileState p = arrow(0x123, 0, 5.0, 1.5);
        p.claimSpent = true;
        s.projectiles.add(p);

        assertEquals(Input.NO_PROJECTILE_HIT, HostFrameContract.projectileClaim(s, 0),
                "Projectiles.stepOne marks an arrow claimSpent the first time it consumes a claim"
                        + " for it, so a producer that kept re-offering the same arrow would burn"
                        + " the frame's one claim slot on an arrow that can no longer use it");
    }

    @Test
    void theRewindTheClaimBuysIsTheGapAnUnmoddedArcherUsedToGiveUp() {
        GameState s = duel();
        Arena arena = Arena.flat(GROUND_Y);
        ProjectileState p = arrow(0x123, 0, 6.0, 1.5);
        s.projectiles.add(p);

        PlayerState v = s.players[1];
        ClaimAuthority.record(s);
        v.x = 9.0;

        assertEquals(Input.NO_PROJECTILE_HIT, HostFrameContract.projectileClaim(s, 0),
                "the producer sweep is against the LIVE hull, so once the target has moved off it"
                        + " the frame carries no claim at all");
        assertTrue(ClaimAuthority.arrowClaim(s, arena, p, 1),
                "but the arrow that DID cross the target one tick ago is still claimable, and"
                        + " ClaimAuthority grants it against the rewound hull. That grant is worth"
                        + " up to " + ClaimAuthority.WINDOW_FRAMES + " ticks of the target's"
                        + " movement, which is what the unmodded producer used to hand back."
                        + " " + THE_GAP);
        assertNotEquals(0, ClaimAuthority.WINDOW_FRAMES,
                "a zero window would mean the claim bought nothing and this whole channel could"
                        + " be deleted rather than shared");
    }
}
