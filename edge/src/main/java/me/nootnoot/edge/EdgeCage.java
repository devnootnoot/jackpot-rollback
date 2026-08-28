package me.nootnoot.edge;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class EdgeCage {

    public static final int SLOTS = 2;

    public static final int MAX_BLOCKS_PER_SLOT = 16384;

    public static final int MAX_OFFSET = 64;

    public static final int MAGIC_V2 = 0x43414732;
    public static final int VERSION_V2 = 2;
    public static final int VERSION_V3 = 3;

    public static final String BARRIER = "minecraft:barrier";

    public static final int SHELL_FLOOR_Y = -1;
    public static final int SHELL_WALL_RADIUS = 2;
    public static final int SHELL_ROOF_Y = 4;

    private static final String BUILT_IN_GATE = "minecraft:smooth_basalt";

    private static final int BUILT_IN_FLOOR_RADIUS = 1;
    private static final int BUILT_IN_WALL_RADIUS = 2;
    private static final int BUILT_IN_FLOOR_Y = -1;
    private static final int BUILT_IN_ROOF_Y = 4;

    public record Block(int rx, int ry, int rz, String data) {
    }

    public record Extent(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
    }

    private final List<List<Block>> floorBySlot;
    private final List<List<Block>> wallsBySlot;
    private final List<String> gate;
    private final String source;
    private final int skipped;
    private final double dropHeight;

    private EdgeCage(List<List<Block>> floorBySlot, List<List<Block>> wallsBySlot, List<String> gate,
                     String source, int skipped, double dropHeight) {
        this.floorBySlot = floorBySlot;
        this.wallsBySlot = wallsBySlot;
        this.gate = gate;
        this.source = source;
        this.skipped = skipped;
        this.dropHeight = dropHeight;
    }

    private static void fillEmptySlots(List<List<Block>> floors, List<List<Block>> walls) {
        EdgeCage built = fallback();
        for (int slot = 0; slot < SLOTS; slot++) {
            if (!floors.get(slot).isEmpty() || !walls.get(slot).isEmpty()) {
                continue;
            }
            floors.set(slot, built.floor(slot));
            walls.set(slot, built.walls(slot));
        }
    }

    public static EdgeCage fallback() {
        List<Block> floor = new ArrayList<>();
        List<Block> walls = new ArrayList<>();
        for (int rx = -BUILT_IN_FLOOR_RADIUS; rx <= BUILT_IN_FLOOR_RADIUS; rx++) {
            for (int rz = -BUILT_IN_FLOOR_RADIUS; rz <= BUILT_IN_FLOOR_RADIUS; rz++) {
                floor.add(new Block(rx, BUILT_IN_FLOOR_Y, rz, BARRIER));
            }
        }
        for (int ry = BUILT_IN_FLOOR_Y; ry < BUILT_IN_ROOF_Y; ry++) {
            for (int rx = -BUILT_IN_WALL_RADIUS; rx <= BUILT_IN_WALL_RADIUS; rx++) {
                for (int rz = -BUILT_IN_WALL_RADIUS; rz <= BUILT_IN_WALL_RADIUS; rz++) {
                    if (Math.abs(rx) != BUILT_IN_WALL_RADIUS && Math.abs(rz) != BUILT_IN_WALL_RADIUS) {
                        continue;
                    }
                    walls.add(new Block(rx, ry, rz, BARRIER));
                }
            }
        }
        for (int rx = -BUILT_IN_WALL_RADIUS; rx <= BUILT_IN_WALL_RADIUS; rx++) {
            for (int rz = -BUILT_IN_WALL_RADIUS; rz <= BUILT_IN_WALL_RADIUS; rz++) {
                walls.add(new Block(rx, BUILT_IN_ROOF_Y, rz, BARRIER));
            }
        }
        List<Block> sharedFloor = List.copyOf(floor);
        List<Block> sharedWalls = List.copyOf(walls);
        return new EdgeCage(List.of(sharedFloor, sharedFloor), List.of(sharedWalls, sharedWalls),
                List.of(BUILT_IN_GATE, BUILT_IN_GATE), "built-in", 0, 0.0);
    }

    public static EdgeCage decode(byte[] payload) throws IOException {
        if (payload == null || payload.length == 0) {
            throw new IOException("empty cage payload");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            in.mark(payload.length);
            int lead = in.readInt();
            if (lead == MAGIC_V2) {
                int version = in.readUnsignedByte();
                if (version != VERSION_V2 && version != VERSION_V3) {
                    throw new IOException("cage payload version " + version + " is not supported");
                }
                in.readLong();
                double drop = version >= VERSION_V3 ? in.readDouble() : 0.0;
                return decodeSplit(in, drop);
            }
            in.reset();
            in.readLong();
            return decodeFlat(in);
        }
    }

    private static EdgeCage decodeSplit(DataInputStream in, double dropHeight) throws IOException {
        List<List<Block>> floors = new ArrayList<>(SLOTS);
        List<List<Block>> walls = new ArrayList<>(SLOTS);
        List<String> gate = new ArrayList<>(SLOTS);
        int[] dropped = {0};
        for (int slot = 0; slot < SLOTS; slot++) {
            gate.add(in.readUTF());
            floors.add(readBlocks(in, slot, dropped));
            walls.add(readBlocks(in, slot, dropped));
        }
        fillEmptySlots(floors, walls);
        return new EdgeCage(List.copyOf(floors), List.copyOf(walls), List.copyOf(gate),
                "assignment", dropped[0], dropHeight);
    }

    private static EdgeCage decodeFlat(DataInputStream in) throws IOException {
        List<List<Block>> walls = new ArrayList<>(SLOTS);
        List<List<Block>> floors = new ArrayList<>(SLOTS);
        List<String> gate = new ArrayList<>(SLOTS);
        int[] dropped = {0};
        for (int slot = 0; slot < SLOTS; slot++) {
            gate.add(in.readUTF());
            List<Block> all = readBlocks(in, slot, dropped);
            List<Block> slotFloor = new ArrayList<>();
            List<Block> slotWalls = new ArrayList<>();
            for (Block block : all) {
                if (isFloorCell(block)) {
                    slotFloor.add(block);
                } else {
                    slotWalls.add(block);
                }
            }
            floors.add(List.copyOf(slotFloor));
            walls.add(List.copyOf(slotWalls));
        }
        fillEmptySlots(floors, walls);
        return new EdgeCage(List.copyOf(floors), List.copyOf(walls), List.copyOf(gate),
                "assignment (flat)", dropped[0], 0.0);
    }

    private static boolean isFloorCell(Block block) {
        return block.ry() == SHELL_FLOOR_Y
                && Math.abs(block.rx()) <= SHELL_WALL_RADIUS
                && Math.abs(block.rz()) <= SHELL_WALL_RADIUS
                && isBarrier(block.data());
    }

    public static boolean isBarrier(String data) {
        return data != null && data.startsWith(BARRIER);
    }

    private static List<Block> readBlocks(DataInputStream in, int slot, int[] dropped)
            throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_BLOCKS_PER_SLOT) {
            throw new IOException("slot " + slot + " declares " + count
                    + " cage blocks (limit " + MAX_BLOCKS_PER_SLOT + ")");
        }
        List<Block> blocks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int rx = in.readShort();
            int ry = in.readShort();
            int rz = in.readShort();
            String data = in.readUTF();
            if (outOfRange(rx) || outOfRange(ry) || outOfRange(rz)) {
                dropped[0]++;
                continue;
            }
            blocks.add(new Block(rx, ry, rz, data));
        }
        return List.copyOf(blocks);
    }

    private static boolean outOfRange(int offset) {
        return offset < -MAX_OFFSET || offset > MAX_OFFSET;
    }

    public List<Block> floor(int slot) {
        return slot >= 0 && slot < floorBySlot.size() ? floorBySlot.get(slot) : List.of();
    }

    public List<Block> walls(int slot) {
        return slot >= 0 && slot < wallsBySlot.size() ? wallsBySlot.get(slot) : List.of();
    }

    public List<Block> blocks(int slot) {
        List<Block> all = new ArrayList<>(floor(slot).size() + walls(slot).size());
        all.addAll(floor(slot));
        all.addAll(walls(slot));
        return all;
    }

    public Extent extent(int slot) {
        return extentOf(blocks(slot));
    }

    private static Extent extentOf(List<Block> blocks) {
        if (blocks.isEmpty()) {
            return null;
        }
        int minX = -SHELL_WALL_RADIUS;
        int maxX = SHELL_WALL_RADIUS;
        int minY = SHELL_FLOOR_Y;
        int maxY = SHELL_ROOF_Y;
        int minZ = -SHELL_WALL_RADIUS;
        int maxZ = SHELL_WALL_RADIUS;
        for (Block block : blocks) {
            minX = Math.min(minX, block.rx());
            maxX = Math.max(maxX, block.rx());
            minY = Math.min(minY, block.ry());
            maxY = Math.max(maxY, block.ry());
            minZ = Math.min(minZ, block.rz());
            maxZ = Math.max(maxZ, block.rz());
        }
        return new Extent(minX, maxX, minY, maxY, minZ, maxZ);
    }

    public String gate(int slot) {
        return slot >= 0 && slot < gate.size() ? gate.get(slot) : BUILT_IN_GATE;
    }

    public String source() {
        return source;
    }

    public double dropHeight() {
        return dropHeight;
    }

    public int skipped() {
        return skipped;
    }

    public int total() {
        int sum = 0;
        for (int slot = 0; slot < SLOTS; slot++) {
            sum += floor(slot).size() + walls(slot).size();
        }
        return sum;
    }

    public boolean empty() {
        return total() == 0;
    }

    public String describe() {
        return source + " slot0=" + (floor(0).size() + walls(0).size())
                + " (floor " + floor(0).size() + ") slot1=" + (floor(1).size() + walls(1).size())
                + " (floor " + floor(1).size() + ") blocks gate0=" + gate(0)
                + " drop=" + dropHeight
                + (skipped > 0 ? " skipped=" + skipped : "");
    }
}
