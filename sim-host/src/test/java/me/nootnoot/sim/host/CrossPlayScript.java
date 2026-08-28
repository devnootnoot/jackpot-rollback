package me.nootnoot.sim.host;

import me.nootnoot.sim.state.Authority;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.Input;

final class CrossPlayScript implements InputSource {

    private final int slot;
    private final boolean stamped;
    private final double x;
    private int frame;

    CrossPlayScript(int slot, boolean stamped, double x) {
        this.slot = slot;
        this.stamped = stamped;
        this.x = x;
    }

    @Override
    public Input sample() {
        return at(slot, frame++, x, stamped);
    }

    static Input at(int slot, int frame, double x, boolean stamped) {
        int phase = frame % 60;
        float yaw = slot == CrossPlayFixture.EDGE_SLOT ? 90f : -90f;
        int cellX = slot == CrossPlayFixture.EDGE_SLOT ? -3 : 3;
        int y = CrossPlayFixture.CELL_Y;
        Input in = switch (phase) {
            case 5 -> Input.NONE.withBlockAction(Input.BLOCK_PLACE, cellX, y, 0)
                    .withHeldSlot(CrossPlayFixture.SLOT_OBSIDIAN).withUsePress(true)
                    .withClicks(new Clicks(0, 1, 0, 0, 0));
            case 9 -> Input.NONE.withBlockAction(Input.BLOCK_PLACE_CRYSTAL, cellX, y, 0)
                    .withHeldSlot(CrossPlayFixture.SLOT_CRYSTAL).withUsePress(true)
                    .withClicks(new Clicks(0, 1, 0, 0, 0));
            case 13 -> new Input(false, false, false, false, false, false, false, true, false,
                    yaw, 0f, CrossPlayFixture.SLOT_CRYSTAL)
                    .withBlockAction(Input.BLOCK_HIT_CRYSTAL, cellX, y, 0)
                    .withCrystalHit(true, cellX, y, 0)
                    .withClicks(new Clicks(1, 0, 0, 0, 0));
            case 20 -> Input.NONE.withBlockAction(Input.BLOCK_PLACE, cellX, y, 2)
                    .withHeldSlot(CrossPlayFixture.SLOT_SHULKER).withUsePress(true)
                    .withClicks(new Clicks(0, 1, 0, 0, 0));
            case 24 -> Input.NONE.withBlockAction(Input.BLOCK_OPEN_CONTAINER, cellX, y, 2)
                    .withHeldSlot(CrossPlayFixture.SLOT_SHULKER)
                    .withClicks(new Clicks(0, 1, 0, 0, 0));
            case 26, 27, 28, 29, 30 -> Input.NONE
                    .withInvAction(Input.INV_CONTAINER_TAKE, phase - 26, 20 + phase - 26)
                    .withClicks(new Clicks(0, 0, 0, 1, 0));
            case 31 -> Input.NONE.withBlockAction(Input.BLOCK_CLOSE_CONTAINER, 0, 0, 0);
            case 34 -> new Input(false, false, false, false, false, false, false, false, true,
                    yaw, 0f, CrossPlayFixture.SLOT_CROSSBOW).withUsePress(true)
                    .withClicks(new Clicks(0, 1, 0, 0, 0));
            case 38 -> Input.NONE.withBlockAction(Input.BLOCK_PLACE_ANCHOR, cellX, y, 4)
                    .withHeldSlot(CrossPlayFixture.SLOT_ANCHOR).withUsePress(true)
                    .withClicks(new Clicks(0, 1, 0, 0, 0));
            case 41, 44 -> Input.NONE.withBlockAction(Input.BLOCK_CHARGE_ANCHOR, cellX, y, 4)
                    .withHeldSlot(CrossPlayFixture.SLOT_GLOWSTONE).withUsePress(true)
                    .withClicks(new Clicks(0, 1, 0, 0, 0));
            case 47 -> Input.NONE.withBlockAction(Input.BLOCK_DETONATE_ANCHOR, cellX, y, 4)
                    .withHeldSlot(CrossPlayFixture.SLOT_GLOWSTONE).withUsePress(true)
                    .withClicks(new Clicks(0, 1, 0, 0, 0));
            case 55 -> new Input(false, false, false, false, false, false, false, true, false,
                    yaw, 0f, CrossPlayFixture.SLOT_SWORD).withMeleeHit(true)
                    .withClicks(new Clicks(1, 0, 0, 0, 0));
            default -> new Input(false, false, false, false, frame % 17 == 0, false, false,
                    frame % 11 == 0, false, yaw, 0f, CrossPlayFixture.SLOT_SWORD)
                    .withClicks(new Clicks(frame % 11 == 0 ? 1 : 0, 0, 0, 0, 0));
        };
        return stamped
                ? in.withAuthority(Authority.at(x, CrossPlayFixture.GROUND_Y, CrossPlayFixture.Z,
                        true))
                : in;
    }
}
