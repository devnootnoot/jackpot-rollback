package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class VanillaInteractionRangeTest {
    private static final double BLOCK_INTERACTION_RANGE_ATTRIBUTE = 4.5;

    private static final double VANILLA_BLOCK_BOUND = 5.5;
    private static final double VANILLA_CONTAINER_BOUND = 8.5;
    private static final double VANILLA_DEFAULT_ATTACK_BOUND = 6.0;
    private static final double VANILLA_SPEAR_ATTACK_BOUND = 7.625;

    private static GameState armed(int flags) {
        GameState s = new GameState();
        PlayerState a = s.players[0];
        a.health = 20f;
        if (flags != 0) {
            TestKit.of(s).give(0, 0, 1, TestKit.item().itemId(7001).maxStack(1).flags(flags));
        }
        return s;
    }

    @Test
    void theBlockInteractionAttributeIsTheVanillaDefault() {
        assertEquals(BLOCK_INTERACTION_RANGE_ATTRIBUTE, Combat.BLOCK_REACH, 0.0,
                "Player.DEFAULT_BLOCK_INTERACTION_RANGE = 4.5F, registered as"
                        + " new RangedAttribute(\"attribute.name.block_interaction_range\", 4.5, 0.0, 64.0)");
    }

    @Test
    void theServerSideBuffersAreTheOnesEveryCallSitePasses() {
        assertEquals(1.0, Combat.BLOCK_INTERACTION_BUFFER, 0.0,
                "ServerPlayerGameMode.handleBlockBreakAction, handleUseItemOn and"
                        + " handlePickItemFromBlock all call isWithinBlockInteractionRange(pos, 1.0)");
        assertEquals(4.0, Combat.CONTAINER_INTERACTION_BUFFER, 0.0,
                "AbstractContainerMenu.stillValid passes 4.0 and Container.stillValidBlockEntity"
                        + " defaults its distanceBuffer to 4.0F");
        assertEquals(3.0, Combat.ENTITY_ATTACK_BUFFER, 0.0,
                "ServerGamePacketListenerImpl.handleAttack calls"
                        + " isWithinAttackRange(mainHandItem, targetBounds, 3.0)");
    }

    @Test
    void theAttackRangeComponentDefaultsAreTheVanillaRuntimeDefaults() {
        assertEquals(3.0, ItemDict.DEFAULT_ATTACK_RANGE, 0.0,
                "AttackRange.defaultFor gives maxReach 3.0 to every item without an ATTACK_RANGE"
                        + " component, which is a sword, an axe, a mace and the empty hand");
        assertEquals(0.0, ItemDict.DEFAULT_ATTACK_HITBOX_MARGIN, 0.0,
                "the runtime default hitboxMargin is 0.0; the 0.3 in the datapack codec is only"
                        + " the CODEC default and never reaches an item built in code");
    }

    @Test
    void theSpearAttackRangeComponentIsTheOneVanillaShips() {
        assertEquals(4.5, ItemDict.SPEAR_ATTACK_RANGE, 0.0,
                "Items spears carry AttackRange(2.0, 4.5, 2.0, 6.5, 0.125, 0.5), so maxReach 4.5");
        assertEquals(0.125, ItemDict.SPEAR_ATTACK_HITBOX_MARGIN, 0.0,
                "the same component's hitboxMargin is 0.125");
    }

    @Test
    void theBlockGateIsVanillasBoundExactly() {
        assertEquals(VANILLA_BLOCK_BOUND, Combat.blockReachLimit(), 1.0E-9,
                "Player.isWithinBlockInteractionRange is blockInteractionRange() + buffer = 4.5 +"
                        + " 1.0, measured squared from the eye to new AABB(pos), and vanilla's own"
                        + " 1.0 buffer already is the lag slack, so we add nothing on top");
    }

    @Test
    void theContainerGateIsSeparateFromAndWiderThanTheBlockGate() {
        assertEquals(VANILLA_CONTAINER_BOUND, Combat.containerReachLimit(), 1.0E-9,
                "Player.getContainerInteractionRange() returns blockInteractionRange(), but the"
                        + " container call sites pass a 4.0 buffer rather than the 1.0 interaction one");
        assertTrue(Combat.containerReachLimit() > Combat.blockReachLimit(),
                "an open menu must not be revalidated through the block interaction gate");
    }

    @Test
    void aDefaultWeaponAttacksToVanillasSixBlocks() {
        GameState fist = armed(0);
        assertEquals(VANILLA_DEFAULT_ATTACK_BOUND, Combat.attackReachLimit(fist, fist.players[0]),
                1.0E-9,
                "isWithinAttackRange is effectiveMaxRange + hitboxMargin + extraBuffer ="
                        + " 3.0 + 0.0 + 3.0, and handleAttack is the only caller");

        GameState sword = armed(ItemDict.FLAG_SWORD);
        assertEquals(VANILLA_DEFAULT_ATTACK_BOUND, Combat.attackReachLimit(sword, sword.players[0]),
                1.0E-9,
                "a sword carries no ATTACK_RANGE component either, so it takes the same default");
    }

    @Test
    void aSpearAttacksToVanillasSevenPointSixTwoFiveBlocks() {
        GameState s = armed(ItemDict.FLAG_SPEAR);
        assertEquals(VANILLA_SPEAR_ATTACK_BOUND, Combat.attackReachLimit(s, s.players[0]), 1.0E-9,
                "AttackRange(2.0, 4.5, 2.0, 6.5, 0.125, 0.5) gives 4.5 + 0.125 + 3.0, so the reach"
                        + " has to be read off the held entry and not off one shared constant");
    }

    @Test
    void theAttackCompareIsTheInclusiveSquareRootVanillaUses() {
        double bound = VANILLA_DEFAULT_ATTACK_BOUND;
        assertTrue(Combat.withinAttackRange(bound * bound, bound),
                "isWithinAttackRange takes Math.sqrt(distanceFunction(eye)) and accepts"
                        + " distance <= maxReach, so the bound itself is a hit");
        assertFalse(Combat.withinAttackRange(bound * bound + 1.0E-6, bound),
                "and anything past it is not");
    }

    @Test
    void noGateSitsBelowTheVanillaBoundItModels() {
        assertTrue(Combat.blockReachLimit() >= VANILLA_BLOCK_BOUND,
                "vanilla accepts a block interaction out to 4.5 + 1.0 = 5.5 blocks from the eye to"
                        + " the nearest point of the target cell; a tighter gate refuses legal builds");
        assertTrue(Combat.containerReachLimit() >= VANILLA_CONTAINER_BOUND,
                "vanilla keeps a menu open out to 4.5 + 4.0 = 8.5 blocks and only force-closes past"
                        + " that, in ServerPlayer.tick");
        GameState s = armed(0);
        assertTrue(Combat.attackReachLimit(s, s.players[0]) >= VANILLA_DEFAULT_ATTACK_BOUND,
                "a default weapon attacks out to AttackRange.defaultFor maxReach 3.0 + the 3.0"
                        + " handleAttack buffer = 6.0 blocks, measured to the target's bounding box");
    }

    @Test
    void theMeasuredUseCooldownsAreTheOnlyThrowRateLimitsVanillaHas() {
        assertEquals(20, Combat.PEARL_COOLDOWN_TICKS,
                "Items.ENDER_PEARL useCooldown(1.0F), and UseCooldown.ticks() is (int)(seconds * 20)");
        assertEquals(10, Combat.WIND_CHARGE_COOLDOWN_TICKS,
                "Items.WIND_CHARGE useCooldown(0.5F) truncates to 10 ticks");
        assertEquals(0, Combat.useCooldownTicks(Combat.USE_SNOWBALL));
        assertEquals(0, Combat.useCooldownTicks(Combat.USE_EGG));
        assertEquals(0, Combat.useCooldownTicks(Combat.USE_SPLASH_POTION));
        assertEquals(0, Combat.useCooldownTicks(Combat.USE_XP_BOTTLE));
        assertEquals(20, Combat.useCooldownTicks(Combat.USE_PEARL));
        assertEquals(10, Combat.useCooldownTicks(Combat.USE_WIND_CHARGE));
    }
}
