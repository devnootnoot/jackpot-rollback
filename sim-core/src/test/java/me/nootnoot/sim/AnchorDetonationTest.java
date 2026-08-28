package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockProps;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class AnchorDetonationTest {
    private static final double GROUND_Y = 64.0;
    private static final int ANCHOR_ITEM_ID = 7501;
    private static final int DIRT_ITEM_ID = 7502;

    private static final int AX = 3;
    private static final int AY = 64;
    private static final int AZ = 0;
    private static final long AKEY = BlockStore.key(AX, AY, AZ);

    private static GameState duel() {
        GameState s = new GameState();
        PlayerState p = s.players[0];
        p.x = 0.5;
        p.y = GROUND_Y;
        p.z = 0.5;
        p.yaw = -90f;
        p.onGround = true;
        p.health = 20f;
        PlayerState v = s.players[1];
        v.x = 10_000.0;
        v.y = GROUND_Y;
        v.z = 0.5;
        v.health = 20f;
        BlockProps.Builder b = new BlockProps.Builder();
        b.add(ANCHOR_ITEM_ID, 0f, 1200f, ANCHOR_ITEM_ID, -1, ItemDict.TOOL_NONE, false);
        b.add(DIRT_ITEM_ID, 0f, 0.5f, DIRT_ITEM_ID, -1, ItemDict.TOOL_NONE, false);
        s.blockProps = b.build();
        return s;
    }

    private static void anchor(GameState s, int charge) {
        s.blocks.place(AX, AY, AZ, ANCHOR_ITEM_ID);
        s.blockResistance.put(AKEY, 1200f);
        s.anchors.put(AKEY, charge);
    }

    private static void dirtRing(GameState s) {
        for (int x = AX - 2; x <= AX + 2; x++) {
            for (int z = AZ - 2; z <= AZ + 2; z++) {
                if (x == AX && z == AZ) {
                    continue;
                }
                s.blocks.place(x, AY, z, DIRT_ITEM_ID);
                s.blockResistance.put(BlockStore.key(x, AY, z), 0.5f);
            }
        }
    }

    private static int explosions(GameState s) {
        int n = 0;
        for (CombatEvent e : s.events) {
            if (e.type() == CombatEvent.EXPLOSION) {
                n++;
            }
        }
        return n;
    }

    private static Input detonate() {
        return Input.NONE.withBlockAction(Input.BLOCK_DETONATE_ANCHOR, AX, AY, AZ);
    }

    @Test
    void aDetonatedAnchorLeavesNothingBehindAndTakesTheTerrainWithIt() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel();
        anchor(s, 1);
        dirtRing(s);
        int before = s.blocks.size();

        Simulation.tick(s, arena, detonate(), Input.NONE);

        assertEquals(1, explosions(s), "the anchor must go off");
        assertFalse(s.blocks.contains(AX, AY, AZ), "the anchor block itself must be gone");
        assertNull(s.anchors.get(AKEY), "and so must its charge");
        assertNull(s.blockResistance.get(AKEY),
                "a 1200 blast resistance left behind on a cell with no block is a landmine: the"
                        + " next block placed there would swallow every explosion that touched it");
        assertTrue(s.blocks.size() < before - 1,
                "power 5 at the anchor's own centre has to eat the soft blocks around it,"
                        + " was " + s.blocks.size() + " of " + before);
    }

    @Test
    void breakingAnAnchorLeavesNoPhantomChargeBehind() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel();
        anchor(s, 4);

        Simulation.tick(s, arena, Input.NONE.withBlockAction(Input.BLOCK_BREAK, AX, AY, AZ),
                Input.NONE);
        assertFalse(s.blocks.contains(AX, AY, AZ), "the mining pass has to take the block");

        assertNull(s.anchors.get(AKEY),
                "the charge belongs to the block. Left behind, that cell can never be broken by"
                        + " any blast again (blastBlocks steps over live anchor cells) and it can"
                        + " still be detonated with nothing there to remove");
    }

    @Test
    void aPhantomAnchorCannotBeDetonated() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel();
        s.anchors.put(AKEY, 4);

        Simulation.tick(s, arena, detonate(), Input.NONE);

        assertEquals(0, explosions(s),
                "an anchor with no anchor block is not an anchor; blowing one up is an explosion"
                        + " that removes nothing, which is what a stale charge looks like in play");
        assertNull(s.anchors.get(AKEY), "and the stale charge is dropped on sight");
    }

    @Test
    void aBlastCanBreakACellAFormerAnchorUsedToOccupy() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel();
        anchor(s, 4);

        Simulation.tick(s, arena, Input.NONE.withBlockAction(Input.BLOCK_BREAK, AX, AY, AZ),
                Input.NONE);
        s.blocks.place(AX, AY, AZ, DIRT_ITEM_ID);
        s.blockResistance.put(AKEY, 0.5f);

        Combat.explode(s, arena, AX + 0.5, AY + 1.5, AZ + 0.5, Combat.CRYSTAL_POWER, 0, false);

        assertFalse(s.blocks.contains(AX, AY, AZ),
                "a leaked anchor charge makes its cell permanently blast proof");
    }
}
