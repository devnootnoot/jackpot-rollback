package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import me.nootnoot.sim.contract.HostFrameContract;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class BlockUseSingleClickRuleTest {

    private static Path rollbackRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null) {
            if (Files.isRegularFile(p.resolve("settings.gradle"))
                    && Files.isDirectory(p.resolve("sim-core"))) {
                return p;
            }
            p = p.getParent();
        }
        return Path.of("nowhere");
    }

    @Test
    void theRuleHasOneDefinitionAndTheFrameContractDelegatesToIt() throws IOException {
        for (int action = Input.BLOCK_NONE; action <= Input.BLOCK_CLOSE_CONTAINER; action++) {
            for (int presses = 0; presses <= Clicks.MAX; presses++) {
                assertEquals(Combat.blockUseClicks(action, presses),
                        HostFrameContract.useClicks(action, presses),
                        "how many uses a frame is worth is the SIM's rule. HostFrameContract is"
                                + " the producers' window onto it, not a second copy of it."
                                + " action=" + action + " presses=" + presses);
            }
        }

        String contract = Files.readString(rollbackRoot().resolve(
                "sim-core/src/main/java/me/nootnoot/sim/contract/HostFrameContract.java"),
                StandardCharsets.UTF_8);
        assertTrue(contract.contains("return Combat.blockUseClicks("),
                "HostFrameContract.useClicks has grown its own arithmetic again. The numbers"
                        + " agreeing today is not the property worth having: the property is that"
                        + " there is only one place to change");
    }

    @Test
    void aBlockUseFrameIsWorthOneUseNoMatterHowManyPressesRideIt() {
        for (int presses = 1; presses <= Clicks.MAX; presses++) {
            assertEquals(1, Combat.blockUseClicks(Input.BLOCK_PLACE_CRYSTAL, presses),
                    "a frame that names ONE cell acts on it once. presses=" + presses);
            assertEquals(presses, Combat.blockUseClicks(Input.BLOCK_NONE, presses),
                    "and a frame that names no cell spends every press on ordinary item use,"
                            + " which is what keeps a fast clicker from being pinned to 10 uses a"
                            + " second. presses=" + presses);
        }
        assertEquals(0, Combat.blockUseClicks(Input.BLOCK_PLACE_CRYSTAL, 0),
                "a frame with no counted press must not be promoted to one");
    }

    private static Input rest(int action, int x, int y, int z, int useClicks) {
        return new Input(false, false, false, false, false, false, false, false, false,
                0f, 0f, 0)
                .withBlockAction(action, x, y, z)
                .withClicks(new Clicks(0, useClicks, 0, 0, 0));
    }

    private static GameState standing() {
        GameState s = new GameState();
        for (PlayerState p : s.players) {
            p.x = 0.5;
            p.y = 64.0;
            p.z = 0.5;
            p.onGround = true;
            p.health = 20f;
        }
        s.players[1].x = 10_000.0;
        return s;
    }

    private static final int ANCHOR_ITEM_ID = 8100;
    private static final int GLOWSTONE_ITEM_ID = 8101;

    private static final int AX = 1;
    private static final int AY = 64;
    private static final int AZ = 0;

    private static GameState anchorInReach() {
        GameState s = standing();
        s.players[0].z = 2.5;
        s.players[0].x = AX + 0.5;
        s.players[0].y = AY;
        TestKit kit = TestKit.of(s);
        kit.give(0, 0, 1, TestKit.item().itemId(ANCHOR_ITEM_ID)
                .flags(ItemDict.FLAG_RESPAWN_ANCHOR));
        kit.give(0, 1, 64, TestKit.item().itemId(GLOWSTONE_ITEM_ID)
                .flags(ItemDict.FLAG_GLOWSTONE));
        s.blocks.place(AX, AY, AZ, ANCHOR_ITEM_ID);
        s.anchors.put(BlockStore.key(AX, AY, AZ), 0);
        return s;
    }

    private static Input charge(int useClicks) {
        return rest(Input.BLOCK_CHARGE_ANCHOR, AX, AY, AZ, useClicks).withHeldSlot(1);
    }

    @Test
    void theSimSpendsExactlyOneUseClickOnABlockUseFrameNoMatterWhatItCarries() {
        GameState s = anchorInReach();
        Arena arena = new Arena(64.0, new double[0][]);

        Combat.resolveBlockActions(s, arena, charge(Clicks.MAX),
                rest(Input.BLOCK_NONE, 0, 0, 0, 0));

        assertEquals(Combat.BLOCK_USE_CLICKS,
                (int) s.anchors.get(BlockStore.key(AX, AY, AZ)),
                "how many uses a block-use frame is worth is Combat.blockUseClicks, and the SIM is"
                        + " where that number is defined. Combat.handleBlockAction used to loop"
                        + " over whatever the frame carried instead, so a peer that skipped the"
                        + " producers' clamp charged an anchor from empty to full inside one tick"
                        + " and both sims agreed - the referee saw nothing wrong because nothing"
                        + " in the sim knew what the frame was worth");
        assertEquals(0, s.players[0].clickBudget.use,
                "and the presses it refused are gone, not banked: the budget is minted with the"
                        + " same cap the producers mint their counter with");
    }

    @Test
    void theSurplusIsRefusedRatherThanHandedToTheItemUsePathInstead() {
        GameState s = anchorInReach();
        Arena arena = new Arena(64.0, new double[0][]);
        Input hostile = charge(Clicks.MAX);

        Combat.resolveBlockActions(s, arena, hostile, rest(Input.BLOCK_NONE, 0, 0, 0, 0));
        Combat.resolve(s, arena, hostile, rest(Input.BLOCK_NONE, 0, 0, 0, 0));

        assertEquals(Combat.USE_REPEAT_DELAY, s.players[0].useDelay,
                "capping the loop is only half the accounting: the clicks it no longer spends"
                        + " must not fall through to handleUse and buy item uses instead."
                        + " startBlockUse pins useDelay for the tick and Combat.useFires refuses"
                        + " anything while useSpentThisTick, so one right click is one action"
                        + " whether the frame was honest about its count or not");
    }
}
