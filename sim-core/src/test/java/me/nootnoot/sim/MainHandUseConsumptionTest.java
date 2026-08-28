package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.contract.HostFrameContract;
import org.junit.jupiter.api.Test;

class MainHandUseConsumptionTest {

    private static final String VANILLA =
            "MinecraftClient.doItemUse walks Hand.values() MAIN then OFF. With a BLOCK under the"
                    + " crosshair it calls interactBlock(MAIN) first and returns on Success AND on"
                    + " Fail - only a PASS falls through to the off hand. EndCrystalItem.useOn"
                    + " returns FAIL for a base that is not obsidian/bedrock and SUCCESS when it"
                    + " places, and BlockItem.useOn returns place(), which is FAIL or SUCCESS."
                    + " So a main hand that places into the world consumes the right click"
                    + " whenever it is aimed at a block, whether or not the placement lands";

    private static final String EQUIP =
            "an equippable armour piece has no aim requirement at all: doItemUse reaches"
                    + " interactItem(MAIN) for it on a MISS as well, and Equippable.swapWithEquipmentSlot"
                    + " returns Success, so the off hand is never tried";

    @Test
    void aPlacerAimedAtABlockConsumesTheRightClick() {
        assertTrue(HostFrameContract.mainHandConsumesUse(true, false, true), VANILLA);
    }

    @Test
    void aPlacerAimedAtNothingLetsTheOffHandThrough() {
        assertFalse(HostFrameContract.mainHandConsumesUse(true, false, false),
                "holding a crystal and looking at the sky does nothing in vanilla:"
                        + " EndCrystalItem has no use() override, so Item.use returns PASS and"
                        + " doItemUse continues to the off hand. Suppressing the off hand here"
                        + " would eat a totem swap a vanilla player still gets");
    }

    @Test
    void anEquippableConsumesTheRightClickWhereverItIsAimed() {
        assertTrue(HostFrameContract.mainHandConsumesUse(false, true, false), EQUIP);
        assertTrue(HostFrameContract.mainHandConsumesUse(false, true, true), EQUIP);
    }

    @Test
    void aPassiveMainHandNeverConsumesTheRightClick() {
        assertFalse(HostFrameContract.mainHandConsumesUse(false, false, true),
                "a sword aimed at a block returns PASS from every hand step, which is what lets"
                        + " an off hand pearl or gapple fire while you are looking at the floor");
        assertFalse(HostFrameContract.mainHandConsumesUse(false, false, false),
                "an empty or passive hand aimed at nothing is the plain off hand fallthrough");
    }

    @Test
    void theRuleIsTheSameExpressionTheEdgeAlreadyApplied() {
        for (int mask = 0; mask < 8; mask++) {
            boolean places = (mask & 1) != 0;
            boolean equippable = (mask & 2) != 0;
            boolean aimedAtBlock = (mask & 4) != 0;
            boolean edge = equippable || (places && aimedAtBlock);
            assertTrue(edge == HostFrameContract.mainHandConsumesUse(places, equippable,
                            aimedAtBlock),
                    "EdgeInputSource.sample computed mainInteractable as equippable ||"
                            + " (interactsWithWorld && intent.againstBlock()). The mod kept a"
                            + " SECOND copy of that rule as a hand written item list, and that"
                            + " list omitted END_CRYSTAL - so a modded crystal placer let the off"
                            + " hand fire and an unmodded one did not. Both hosts read this one"
                            + " expression now; only the placesIntoWorld fact is host local");
        }
    }
}
