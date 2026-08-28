package me.nootnoot.sim;

import java.util.LinkedHashMap;
import java.util.Map;
import me.nootnoot.sim.state.ItemDict;

final class HostParityDecisions {

    static final String EDGE = "EdgeInputSource";
    static final String MOD = "McInputSource";

    record Emission(String token, boolean edge, boolean mod, String why) {

        boolean bothHosts() {
            return edge && mod;
        }
    }

    record Bypass(String producer, String counter, String expression, String why) {

        String key() {
            return bypassKey(producer, counter, normalise(expression));
        }
    }

    static String normalise(String expr) {
        return expr.replaceAll("\s+", " ").trim();
    }

    static String bypassKey(String producer, String counter, String normalisedExpression) {
        return producer + "." + counter + " = " + normalisedExpression;
    }

    static String contractMethod(String counter) {
        String qualified = clickCounters().get(counter);
        return qualified == null ? null : qualified.substring(qualified.lastIndexOf('.') + 1);
    }

    static Map<String, String> arbitratedChannels() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("leftClickTarget", "a left click names ONE target. crystalHit and meleeHit are"
                + " mutually exclusive on the wire and the nearer candidate wins, which is the"
                + " rule HostFrameContract.leftClickTarget spells. A producer that stopped calling"
                + " it would be arbitrating the two channels by hand again, which is exactly how"
                + " the mod came to detonate AND punch on one click while the edge only punched");
        out.put("crystalPickDistanceSq", "WHICH crystal a left click names, and how far away the"
                + " host says it was, is one rule over three numbers: the reach, the hitbox and"
                + " the distance metric. The edge cast to Combat.BLOCK_REACH (4.5) against a"
                + " hand-built box inflated by a private CRYSTAL_INFLATE of 0.3 and reported"
                + " (t * BLOCK_REACH) squared; the mod cast to the held weapon's own"
                + " attackPickReachAt (3.0 for a plain weapon) against the LIVE EndCrystal entity"
                + " box inflated by that item's attackHitboxMargin and reported"
                + " eye.distanceToSqr(hit). So on the same crystal an unmodded player detonated"
                + " from a block and a half further out than a modded one, and the two hosts fed"
                + " leftClickTarget two different quantities to arbitrate crystal against melee"
                + " with - in the flagship mode. HostFrameContract.crystalPickDistanceSq is now"
                + " the only answer, and it is bounded by the same attackPickReachAt the sim's"
                + " own withinCrystalAttackRange bounds the hit by");
        out.put("minesThisFrame", "mining is what a left click means when it named no entity."
                + " A producer that stopped calling HostFrameContract.minesThisFrame would be"
                + " deciding on its own whether a swing at a crystal also starts a dig");
        out.put("anchorAction", "which of place / charge / detonate a right click on a respawn"
                + " anchor means is decided from the charge alone, and both producers must read"
                + " that ladder off HostFrameContract.anchorAction rather than reimplement it");
        out.put("anchorCharge", "the NUMBER that ladder is read from must come out of the same"
                + " world on both hosts. The edge read GameState.anchors, the replicated map the"
                + " sim charges and detonates out of; the mod read"
                + " RespawnAnchorBlock.CHARGE off the LIVE CLIENT BLOCK that McSimRenderer paints"
                + " from that same map one frame later. So for the tick between a charge landing"
                + " in the sim and the renderer repainting the cell, the two hosts read different"
                + " charges off the same anchor, and anchorAction turned the same right click"
                + " into a CHARGE on one host and a DETONATE on the other - on the item the"
                + " crystal mode ends rounds with. HostFrameContract.anchorCharge is now the only"
                + " reader, and it reads the sim");
        out.put("projectileClaim", "an arrow claim is a REWIND, not a hit report. The mod swept"
                + " its own live sim arrows against the RENDERED opponent entity box and kept its"
                + " own armedArrows set as a second copy of ProjectileState.leftOwner; the edge"
                + " produced NOTHING, because a vanilla client never tells the server its arrow"
                + " connected. That is not a latency compensation an honest unmodded player can"
                + " opt into: ClaimAuthority.arrowClaim grants the claim against hulls rewound up"
                + " to ClaimAuthority.WINDOW_FRAMES ticks, so a modded archer's arrow resolved"
                + " against where the target was up to 25 ticks ago while an unmodded archer's"
                + " resolved only against the live hull in the tick its segment crossed."
                + " HostFrameContract.projectileClaim is the same sweep over the same replicated"
                + " arrows and the same sim hulls, so both hosts now produce it and neither needs"
                + " the client world to do it");
        out.put("chestEquip", "the elytra / chestplate hot swap is one equip per right click on"
                + " both hosts. The mod fed the HELD level into a parameter named usePressEdge, so"
                + " a button that stayed down re-armed every CHEST_EQUIP_COOLDOWN ticks and swapped"
                + " the chestplate straight back over the elytra, which is why gliding never"
                + " started for a modded player and always did for an unmodded one");
        out.put("chestEquipCooldown", "the lockout that follows an equip is measured from the last"
                + " use frame, so a producer that ages it on its own clock can re-fire the swap on"
                + " a cadence the other host does not have");
        out.put("mainHandConsumesUse", "whether the MAIN hand ate the right click decides whether"
                + " the off hand gets it at all, and vanilla decides it in doItemUse: a hand step"
                + " that returns Success or Fail ends the loop and only a PASS falls through. The"
                + " edge spelled that as equippable || (interactsWithWorld && againstBlock) while"
                + " the mod kept a second copy as a hand written item list, and that list omitted"
                + " END_CRYSTAL - so holding a crystal let a modded player's off hand fire on"
                + " every placement while an unmodded player's never did, on the one interaction"
                + " the crystal mode is made of");
        out.put("useFrame", "the frame's use bit is (a use action fired) || (the main hand is"
                + " mid continuous use), which is what EdgeInputSource can see and all it can"
                + " see. The mod read the raw GLFW mouse button instead, so it produced use on"
                + " frames where an unmodded client provably reports none - the three ticks of"
                + " every four that vanilla's itemUseCooldown swallows, and every tick of a hold"
                + " with nothing usable in either hand");
        out.put("ruledBlockAction", "whether the game type forbids a world action is the SIM's"
                + " question, and Combat.ruleFiltered is the one place it is answered. A producer"
                + " may not refuse the action itself: it emits the intent and counts its clicks"
                + " off HostFrameContract.ruledBlockAction, which is the same predicate the sim"
                + " runs. The mod used to carry its own && allowExplosion / && allowBucket chain"
                + " instead, so with explosions off a modded player spent four use clicks on a"
                + " refused crystal place where an unmodded one spent one and queued three - the"
                + " same press then resolved the NEXT action differently on the two hosts");
        out.put("placeableCell", "whether a right click on a cell yields a block PLACE is the sim's"
                + " question, and Combat.placementOpen is the one place it is answered: support,"
                + " sight, an empty cell, and no player / crystal / dropped item in it. The mod"
                + " asked the LIVE CLIENT WORLD instead (canBeReplaced plus a rendered-entity"
                + " sweep plus a hand-built self box) and carried a rule the edge never had -"
                + " refuse every placement while the crosshair is on a respawn anchor - so a"
                + " modded player could not place at all where an unmodded one could, and could"
                + " place where the sim then refused. Both producers now ask the shared"
                + " predicate over the replicated state, which is the same answer the sim will"
                + " give when the frame lands");
        out.put("webbableCell", "a cobweb is the one placement vanilla allows INTO a cell an"
                + " entity occupies, so it has its own home: Combat.webPlacementOpen, the block"
                + " conjunction minus the player-overlap term. The mod tested the client world's"
                + " canBeReplaced and the edge tested a Material tag, which are two answers to"
                + " one question and neither was the sim's");
        out.put("crystalBaseCell", "an end crystal goes on obsidian or bedrock with two clear"
                + " cells above it, and Combat.crystalPlacementOpen is that whole test. The edge"
                + " kept a Material list that included CRYING_OBSIDIAN, which 26.2"
                + " EndCrystalItem.useOn rejects, so an unmodded player spent a use click on a"
                + " place the sim always refused; the mod kept a second copy that raycast the"
                + " client's own entity list for the crystal already standing there");
        out.put("containerOpens", "vanilla refuses to open a shulker or an ender chest with a"
                + " solid block in the space it opens into (EnderChestBlock.onUse tests the cell"
                + " above, ShulkerBoxBlock.canOpen tests the swing box) while still CONSUMING the"
                + " right click. The mod ran that test against the client world and the edge ran"
                + " no test at all, so an unmodded player opened a buried shulker that a modded"
                + " player could not");
        out.put("leftClickActs", "while an item is being used, vanilla drains the attack presses"
                + " into an empty while-loop in MinecraftClient.handleInputEvents, so an unmodded"
                + " client sends NO attack, NO swing and NO interact-entity for the whole raise."
                + " The mod reads the raw GLFW mouse button instead, so nothing physical stops"
                + " it: the crystal detonation raycast fired mid-bite and mid-bow-draw, which is"
                + " a hit an unmodded player provably cannot produce. Both producers now gate the"
                + " left-click channels on the shared rule");
        out.put("attackFrame", "the frame's ATTACK bit is what EdgeInputSource can see and all it"
                + " can see: an arm-swing packet arrived this tick, or a dig is running. A held"
                + " mouse button is NEITHER. Vanilla swings once per counted click in startAttack"
                + " and then once per tick only for as long as continueAttack is really destroying"
                + " a block, so a modded player holding the button at thin air reported attack on"
                + " every tick of the hold where an unmodded player reported none - which is a"
                + " different prevAttack, a different Combat.attackEdge and a different round"
                + " ready. The mod now synthesises the same two facts and both hosts hand them to"
                + " HostFrameContract.attackFrame");
        out.put("offhandUseKind", "which use-kinds may fire from the OFF hand is one list, and it"
                + " lives in HostFrameContract.offhandUseKind. Both hosts classify a STACK with"
                + " their own single classifier - there is no other way, one has Material and the"
                + " other has Item - and then filter it through the shared list. The mod kept a"
                + " second hand-written list instead and it had drifted: it tested the FOOD data"
                + " component alone, so an off-hand potion or milk bucket was USE_NONE for a"
                + " modded player and USE_FOOD for an unmodded one, on the same item");
        out.put("offhandUseFrame", "the off hand's held bit is (the use action that fell through"
                + " to it) || (the off hand is raised), which is what the edge reads off the use"
                + " packet and getHandRaised(). The mod drove it from the raw mouse button, so it"
                + " claimed an off-hand use on every tick of a hold - the same defect useFrame"
                + " closed for the main hand, left open on the other one");
        out.put("offhandUsePress", "the press half of the same channel. Combat.useAttempts and"
                + " useFires read offhandUsePress as the discrete click that fires an off-hand"
                + " throw inside one sample, so a host that does not emit it is a host whose"
                + " off-hand pearl can only fire off the held bit");
        out.put("chestEquipPress", "what counts as the right click that equips must be one"
                + " expression over the two bits both hosts put on the wire, the frame's use and"
                + " usePress. The mod read the raw mouse button and the edge read the use intent"
                + " packet, so holding the button and THEN switching to an elytra equipped it for"
                + " an unmodded player and did nothing for a modded one");
        return out;
    }

    record ReachBand(String weapon, double simMax, double simMin, double unmoddedPick,
                     String why) {

        double maxGap() {
            return simMax - unmoddedPick;
        }
    }

    static final double VANILLA_ENTITY_INTERACTION_RANGE = 3.0;

    static Map<String, ReachBand> crossPlayReachLedger() {
        Map<String, ReachBand> out = new LinkedHashMap<>();
        out.put("plain", new ReachBand("any plain weapon, tool or fist",
                ItemDict.DEFAULT_ATTACK_RANGE + ItemDict.DEFAULT_ATTACK_HITBOX_MARGIN,
                ItemDict.DEFAULT_ATTACK_MIN_RANGE,
                VANILLA_ENTITY_INTERACTION_RANGE,
                "the sim's default attack pick is the vanilla entity interaction range with no"
                        + " margin on top, so the band a modded producer raycasts and the band a"
                        + " vanilla client will send an INTERACT_ENTITY inside are the same 3.0"
                        + " blocks. Nothing is given up either way, and no minimum applies"));
        out.put("spear", new ReachBand("spear (ItemDict.FLAG_SPEAR)",
                ItemDict.SPEAR_ATTACK_RANGE + ItemDict.SPEAR_ATTACK_HITBOX_MARGIN,
                ItemDict.SPEAR_ATTACK_MIN_RANGE,
                VANILLA_ENTITY_INTERACTION_RANGE,
                "a spear is the one item whose reach band is not the vanilla one at BOTH ends,"
                        + " and both ends used to be a cross-play gap. MAXIMUM: the mod raycasts"
                        + " Combat.attackPickReachAt itself, so it claims out to 4.625 blocks,"
                        + " while an unmodded player's melee claim is an INTERACT_ENTITY packet"
                        + " that a vanilla client only sends inside its own"
                        + " entity_interaction_range attribute - 3.0 blocks by default. That is"
                        + " 1.625 blocks of spear the unmodded player could not reach even though"
                        + " ClaimAuthority.meleeLimit would have granted it. EdgeStatusMirror now"
                        + " mirrors the sim's own attackPickReachAt onto that attribute the same"
                        + " way it already mirrors ATTACK_SPEED, so the vanilla client picks at"
                        + " the range the sim will grant. MINIMUM: nothing in the vanilla client"
                        + " has a minimum attack range at all, so it can never be pushed onto an"
                        + " unmodded player from the outside - the mod alone started its ray at"
                        + " eye + look * 2.0 and refused a point-blank spear. That gate moved"
                        + " into ClaimAuthority.meleeClaim, which both hosts run, so the 2.0"
                        + " blocks of dead zone now bind whoever holds the spear"));
        return out;
    }

    private HostParityDecisions() {
    }

    private static void held(Map<String, Emission> out, String name) {
        out.put(name, new Emission("new Input(", true, true,
                "a held key or a look value, carried by the twelve argument Input constructor that"
                        + " both producers call once per sample"));
    }

    static Map<String, Emission> inputComponents() {
        Map<String, Emission> out = new LinkedHashMap<>();
        held(out, "forward");
        held(out, "back");
        held(out, "left");
        held(out, "right");
        held(out, "jump");
        held(out, "sprint");
        held(out, "sneak");
        held(out, "attack");
        held(out, "use");
        held(out, "yaw");
        held(out, "pitch");
        held(out, "heldSlot");
        out.put("usePress", new Emission(".withUsePress(", true, true,
                "the right click EDGE, which the sim reads as the discrete press that fires an"
                        + " instant throw inside one sample"));
        out.put("offhandUse", new Emission(".withOffhandUse(", true, true,
                "the off hand held bit"));
        out.put("offhandUsePress", new Emission(".withOffhandUsePress(", true, true,
                "the off hand's discrete right click. This entry USED to read EDGE ONLY, on the"
                        + " argument that the mod set offhandUse from useHeld || rightPress so"
                        + " the held bit covered the same frames. That argument died with the"
                        + " thing it rested on: the mod's held bit was the raw mouse button, and"
                        + " once offhandUse became HostFrameContract.offhandUseFrame - a use"
                        + " ACTION or an off-hand raise, which is all the edge can see - the two"
                        + " halves of Combat's press || held disjunction stopped being"
                        + " interchangeable. An off-hand pearl is not continuousUse, so it has no"
                        + " raise to ride, and useAttempts counts the PRESS: without the bit the"
                        + " mod would have thrown an off-hand pearl only off the held path the"
                        + " edge does not use. Both hosts now emit it, and both build it from"
                        + " HostFrameContract.offhandUsePress over the same use action"));
        out.put("meleeHit", new Emission(".withMeleeHit(", true, true,
                "the entity attack claim"));
        out.put("dropItem", new Emission(".withDrop(", true, true, "the toss claim"));
        out.put("dropStack", new Emission(".withDrop(", true, true,
                "whether the toss was the whole stack"));
        out.put("swapHands", new Emission(".withSwapHands(", true, true,
                "the offhand swap claim, whose COUNT is the fifth click counter"));
        out.put("blockAction", new Emission(".withBlockAction(", true, true,
                "the one world action opcode a frame may name"));
        out.put("targetX", new Emission(".withBlockAction(", true, true, "the block action cell"));
        out.put("targetY", new Emission(".withBlockAction(", true, true, "the block action cell"));
        out.put("targetZ", new Emission(".withBlockAction(", true, true, "the block action cell"));
        out.put("projectileHit", new Emission(".withProjectileHit(", true, true,
                "an arrow claim is lag compensation, not a permission to land a hit the sim"
                        + " already lands. Without a claim an arrow hits only where"
                        + " Projectiles.stepOne's own sweep meets the victim on the SHARED"
                        + " timeline this frame; with one, ClaimAuthority.arrowClaim tests the"
                        + " arrow against the victim's REWOUND hull path (candidates(),"
                        + " WINDOW_FRAMES deep) inflated by ARROW_MARGIN and sight checked. This"
                        + " entry read MOD ONLY on the argument that the edge had nothing to"
                        + " build a claim from, since a vanilla client never asserts an arrow"
                        + " hit. That argument was wrong about where the mod's claim came from:"
                        + " the mod did not read a hit off the client either, it swept its OWN"
                        + " live sim arrows - replicated state both hosts hold - and the only"
                        + " thing it took from the client world was the rendered opponent's box,"
                        + " which is a picture of the sim hull it already had."
                        + " HostFrameContract.projectileClaim is that sweep over the sim's own"
                        + " arrows and hulls, so the edge produces the identical claim from"
                        + " nothing but the head state and the modded archer's rewind stops being"
                        + " modded-only. It stays bounded by Projectiles.tick refusing a claim on"
                        + " a synthetic frame, one claim per owner per frame (claimOpen), one per"
                        + " arrow (claimSpent), an id that must match the arrow, and arrowClaim's"
                        + " own obstruction test"));
        out.put("invAction", new Emission(".withInvAction(", true, true,
                "the named inventory operation"));
        out.put("invSrc", new Emission(".withInvAction(", true, true, "its source slot"));
        out.put("invDst", new Emission(".withInvAction(", true, true, "its destination slot"));
        out.put("authority", new Emission(".withAuthority(", true, false,
                "EDGE ONLY, and deliberately. An unmodded player's position is the SERVER's"
                        + " reading of it; a modded client runs the sim itself and is its own"
                        + " authority, and Authority.NONE is how the sim spells no correction"));
        out.put("clicks", new Emission(".withClicks(", true, true,
                "the five per tick counters, each of which has its own entry in clickCounters()"));
        out.put("crystalHit", new Emission(".withCrystalHit(", true, true,
                "the crystal detonation channel, which is INDEPENDENT of blockAction so one"
                        + " frame can hit a crystal and place the next one on the same tick"));
        out.put("crystalX", new Emission(".withCrystalHit(", true, true, "the detonated cell"));
        out.put("crystalY", new Emission(".withCrystalHit(", true, true, "the detonated cell"));
        out.put("crystalZ", new Emission(".withCrystalHit(", true, true, "the detonated cell"));
        out.put("synthetic", new Emission(".withSynthetic(", false, false,
                "NEITHER frame producer, and deliberately. It does not describe anything the"
                        + " player did - it marks a frame the LOCAL SESSION invented, to close a"
                        + " catch-up deficit or to stand in for a peer that has gone quiet, and"
                        + " only the shared netcode knows a frame is one of those."
                        + " Both hosts run that same NetSession, so the field is symmetric by"
                        + " construction rather than by two producers agreeing, and"
                        + " aComponentTheSimReadsIsProducedByBothHostsOrRecordedAsOneSided checks"
                        + " the shared session really does stamp it. A host that emitted it would"
                        + " be handing the sim a licence to ignore a frame the player did send"));
        out.put("elytraStart", new Emission(".withElytraStart(", true, true,
                "the elytra deploy, named on its own frame instead of folded into the jump bit."
                        + " The edge reads it off START_FLYING_WITH_ELYTRA and the mod off"
                        + " HostFrameContract.elytraDeploy, so a held jump key can still deploy"
                        + " and a deploy can never inject a jump nobody pressed"));
        return out;
    }

    static final String SESSION = "sim-core/src/main/java/me/nootnoot/sim/net/NetSession.java";

    static Map<String, String> sessionComponents() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("synthetic", "stamped by NetSession.catchUpFiller on the frames it invents to"
                + " close a catch-up deficit, and by RollbackController.knownRemote on the decayed"
                + " prediction it invents for a peer that has gone quiet. Filler frames are sent to"
                + " the peer like any other, so the flag has to ride the wire, but no host frame"
                + " producer may set it");
        return out;
    }

    static Map<String, String> clickCounters() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("attack", "HostFrameContract.attackClicks");
        out.put("use", "HostFrameContract.useClicks");
        out.put("drop", "HostFrameContract.dropClicks");
        out.put("inv", "HostFrameContract.invClicks");
        out.put("swap", "HostFrameContract.swapClicks");
        return out;
    }

    static Map<String, Bypass> counterBypassLedger() {
        return new LinkedHashMap<>();
    }

    private static void record(Map<String, Bypass> out, Bypass b) {
        out.put(b.key(), b);
    }
}
