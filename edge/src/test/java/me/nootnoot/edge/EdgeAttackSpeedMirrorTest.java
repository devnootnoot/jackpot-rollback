package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class EdgeAttackSpeedMirrorTest {

    private static final int SWORD_ID = 1;
    private static final int APPLE_ID = 2;

    private static final float SWORD_SPEED = 1.6f;

    private static ItemDict dict() {
        ItemDict.Builder b = new ItemDict.Builder();
        b.add(SWORD_ID, 1, 2031, 0, 0, 8f, SWORD_SPEED, 0, 0, 0, 0, 0, 0, 0f, 0, 0,
                0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_NONE, -1);
        b.add(APPLE_ID, 64, 0, 0, 0, 1f, 0f, 0, 0, 0, 0, 0, 4, 9.6f, 0, 0,
                0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_NONE, -1);
        return b.build();
    }

    private static PlayerState holding(int slot, int entry, int count) {
        PlayerState p = new PlayerState();
        p.heldSlot = slot;
        p.slotEntry[slot] = entry;
        p.slotCount[slot] = count;
        return p;
    }

    @Test
    void theHeldWeaponsSimSpeedIsWhatTheClientMustBeToldToShow() {
        assertEquals(SWORD_SPEED, EdgeStatusMirror.simMeleeSpeed(dict(), holding(0, 1, 1)), 1.0E-6,
                "an unmodded player times swings off the vanilla cooldown indicator, which reads"
                        + " the ATTACK_SPEED attribute. The sim charges the held weapon's own"
                        + " speed, so the attribute has to be driven from the dict entry the sim"
                        + " will actually use - warning about the skew in the server log leaves"
                        + " the player swinging to a rhythm the sim does not honour");
    }

    @Test
    void anEmptyOrNonWeaponHandFallsBackToTheFistSpeed() {
        assertEquals(EdgeHeldItems.FIST_SPEED,
                EdgeStatusMirror.simMeleeSpeed(dict(), holding(3, 2, 1)), 1.0E-6,
                "a dict entry with no melee speed is a fist in the sim, so the indicator has to be"
                        + " a fist too");
        assertEquals(EdgeHeldItems.FIST_SPEED,
                EdgeStatusMirror.simMeleeSpeed(dict(), holding(4, 1, 0)), 1.0E-6,
                "an empty slot is a fist even when the slot still remembers an entry");
    }

    @Test
    void aHeldSlotOutsideTheHotbarIsAFistRatherThanAnIndexCrash() {
        PlayerState p = new PlayerState();
        p.heldSlot = ItemDict.HOTBAR;
        assertEquals(EdgeHeldItems.FIST_SPEED,
                EdgeStatusMirror.simMeleeSpeed(dict(), p), 1.0E-6);
        p.heldSlot = -1;
        assertEquals(EdgeHeldItems.FIST_SPEED,
                EdgeStatusMirror.simMeleeSpeed(dict(), p), 1.0E-6);
    }
}
