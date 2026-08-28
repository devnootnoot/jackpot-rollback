package me.nootnoot.edge;

import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.PlayerState;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.SoundGroup;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

public final class EdgeDigDisplay {

    private static final int STAGES = 10;
    private static final int CLEAR_STAGE = -1;
    private static final int HIT_SOUND_PERIOD = 4;

    private static final float HIT_VOLUME_SCALE = 0.25f;
    private static final float HIT_PITCH_SCALE = 0.5f;

    private final Player viewer;
    private final World world;
    private final int localSlot;

    private final long[] shownCell = {Long.MIN_VALUE, Long.MIN_VALUE};
    private final int[] shownStage = {CLEAR_STAGE, CLEAR_STAGE};

    private int hitSoundTimer;
    private int peerEntityId = -1;

    public EdgeDigDisplay(Player viewer, World world, int localSlot) {
        this.viewer = viewer;
        this.world = world;
        this.localSlot = localSlot;
    }

    public void peerEntityId(int entityId) {
        this.peerEntityId = entityId;
    }

    public void render(GameState head) {
        if (!viewer.isOnline()) {
            return;
        }
        for (int slot = 0; slot < head.players.length && slot < shownCell.length; slot++) {
            crack(slot, head.players[slot]);
        }
        digSound(head.players[localSlot]);
        peerSwing(head.players[1 - localSlot]);
    }

    private void crack(int slot, PlayerState state) {
        boolean mining = state.miningTarget != Long.MIN_VALUE && state.miningProgress > 0f;
        long want = mining ? state.miningTarget : Long.MIN_VALUE;
        int stage = mining
                ? Math.max(0, Math.min(STAGES - 1, (int) (state.miningProgress * STAGES)))
                : CLEAR_STAGE;
        if (want == shownCell[slot] && stage == shownStage[slot]) {
            return;
        }
        int breaker = EdgeEntityBands.breaker(slot);
        if (shownCell[slot] != Long.MIN_VALUE && shownCell[slot] != want) {
            EdgePackets.breakStage(viewer, breaker, BlockStore.unpackX(shownCell[slot]),
                    BlockStore.unpackY(shownCell[slot]), BlockStore.unpackZ(shownCell[slot]),
                    CLEAR_STAGE);
        }
        shownCell[slot] = want;
        shownStage[slot] = stage;
        if (want == Long.MIN_VALUE) {
            return;
        }
        EdgePackets.breakStage(viewer, breaker, BlockStore.unpackX(want), BlockStore.unpackY(want),
                BlockStore.unpackZ(want), stage);
    }

    private void digSound(PlayerState mine) {
        if (mine.miningTarget == Long.MIN_VALUE || mine.miningProgress <= 0f || world == null) {
            hitSoundTimer = 0;
            return;
        }
        if (--hitSoundTimer > 0) {
            return;
        }
        hitSoundTimer = HIT_SOUND_PERIOD;
        int x = BlockStore.unpackX(mine.miningTarget);
        int y = BlockStore.unpackY(mine.miningTarget);
        int z = BlockStore.unpackZ(mine.miningTarget);
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return;
        }
        Block block = world.getBlockAt(x, y, z);
        if (block.getType().isAir()) {
            return;
        }
        SoundGroup group = block.getBlockData().getSoundGroup();
        viewer.playSound(new Location(world, x + 0.5, y + 0.5, z + 0.5), group.getHitSound(),
                SoundCategory.BLOCKS, group.getVolume() * HIT_VOLUME_SCALE,
                group.getPitch() * HIT_PITCH_SCALE);
    }

    private void peerSwing(PlayerState other) {
        if (peerEntityId < 0 || other.miningTarget == Long.MIN_VALUE || other.miningProgress <= 0f) {
            return;
        }
        EdgePackets.swing(viewer, peerEntityId);
    }

    public void clear() {
        for (int slot = 0; slot < shownCell.length; slot++) {
            if (shownCell[slot] == Long.MIN_VALUE) {
                continue;
            }
            EdgePackets.breakStage(viewer, EdgeEntityBands.breaker(slot),
                    BlockStore.unpackX(shownCell[slot]), BlockStore.unpackY(shownCell[slot]),
                    BlockStore.unpackZ(shownCell[slot]), CLEAR_STAGE);
            shownCell[slot] = Long.MIN_VALUE;
            shownStage[slot] = CLEAR_STAGE;
        }
    }
}
