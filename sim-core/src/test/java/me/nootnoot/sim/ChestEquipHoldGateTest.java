package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.contract.HostFrameContract;
import me.nootnoot.sim.contract.InventoryIntents;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class ChestEquipHoldGateTest {
    private static final double GROUND_Y = 64.0;
    private static final int HOTBAR_SLOT = 6;
    private static final int FALL_HEIGHT = 60;
    private static final int TICKS = 40;
    private static final int DEPLOY_TICK = 8;

    private record Flight(int equipIntents, boolean wearingElytraAtEnd, boolean everGlided,
                          int glidingTicks, String chestTrace) {
    }

    private static Flight fly(boolean gateOnHeld) {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = CrystalKitFixture.build(GROUND_Y);
        PlayerState p = s.players[0];

        Simulation.tick(s, arena, Input.NONE, Input.NONE);
        Simulation.tick(s, arena,
                Input.NONE.withInvAction(Input.INV_SWAP_SLOT, CrystalKitFixture.SLOT_ELYTRA,
                        HOTBAR_SLOT).withClicks(new Clicks(0, 0, 0, 1, 0)),
                Input.NONE);
        assertTrue(s.dict.isElytra(p.slotEntry[HOTBAR_SLOT]),
                "the CRYSTAL kit keeps its elytra in storage slot "
                        + CrystalKitFixture.SLOT_ELYTRA + " with a netherite chestplate already"
                        + " worn, so it has to reach the hotbar before it can be right-clicked on");

        p.y = GROUND_Y + FALL_HEIGHT;
        p.onGround = false;

        int cooldown = 0;
        int equips = 0;
        int glidingTicks = 0;
        boolean everGlided = false;
        boolean prevUseHeld = false;
        StringBuilder trace = new StringBuilder();
        for (int t = 0; t < TICKS; t++) {
            boolean useHeld = true;
            boolean pressEdge = useHeld && !prevUseHeld;
            prevUseHeld = useHeld;
            if (cooldown > 0) {
                cooldown--;
            }
            boolean chestArmour = InventoryIntents.chestArmour(s, p, HOTBAR_SLOT);
            Input in = new Input(false, false, false, false, t >= DEPLOY_TICK, false, false, false,
                    useHeld, 0f, 0f, HOTBAR_SLOT).withUsePress(pressEdge);
            if (HostFrameContract.chestEquip(gateOnHeld ? useHeld || pressEdge : pressEdge,
                    chestArmour, cooldown)) {
                InventoryIntents.Intent equip = InventoryIntents.chestEquip(HOTBAR_SLOT);
                in = in.withInvAction(equip.action(), equip.src(), equip.dst())
                        .withClicks(new Clicks(0, 0, 0, 1, 0));
                cooldown = HostFrameContract.CHEST_EQUIP_COOLDOWN;
                equips++;
            }
            Simulation.tick(s, arena, in, Input.NONE);
            trace.append(s.dict.isElytra(p.slotEntry[ItemDict.ARMOR_CHEST]) ? 'E' : '-');
            if (p.gliding) {
                glidingTicks++;
                everGlided = true;
            }
        }
        return new Flight(equips, p.hasElytra, everGlided, glidingTicks, trace.toString());
    }

    @Test
    void aPressEdgeEquipsTheElytraOnceAndTheWingsOpen() {
        Flight edge = fly(false);
        assertEquals(1, edge.equipIntents(),
                "one press, one equip - this is the rule HostFrameContract.chestEquip names with"
                        + " its usePressEdge parameter and the rule EdgeInputSource obeys");
        assertTrue(edge.wearingElytraAtEnd(), "trace " + edge.chestTrace());
        assertTrue(edge.everGlided(), "trace " + edge.chestTrace());
    }

    @Test
    void gatingTheChestEquipOnHeldRightClickTogglesTheElytraBackOffAndNoGlideEverStarts() {
        Flight held = fly(true);

        assertEquals(1, held.equipIntents(),
                "McInputSource passes useHeld||rightPress into chestEquip, whose first parameter is"
                        + " usePressEdge. A held button therefore re-arms every"
                        + " CHEST_EQUIP_COOLDOWN ticks, and because the netherite chestplate that"
                        + " got swapped OUT is itself chest armour, InventoryIntents.chestArmour is"
                        + " still true, so the next equip swaps it straight back. Chest slot per"
                        + " tick: " + held.chestTrace());

        assertTrue(held.wearingElytraAtEnd(),
                "the elytra must stay on while the button is down. Chest slot per tick: "
                        + held.chestTrace());

        assertTrue(held.everGlided(),
                "reported as: flying with an elytra doesn't work, i never enter the gliding state,"
                        + " and I can't test firework rockets with that either. The jump edge at"
                        + " tick " + DEPLOY_TICK + " lands inside a chestplate window of the"
                        + " toggle, so hasElytra is false on the one frame that carries a deploy"
                        + " edge; after that the jump key is merely held and there is no edge left"
                        + " for the rest of the fall. Chest slot per tick: " + held.chestTrace());

        assertFalse(held.chestTrace().contains("E-"),
                "the chest slot may never lose the elytra while the button is still down:"
                        + " " + held.chestTrace());
    }
}
