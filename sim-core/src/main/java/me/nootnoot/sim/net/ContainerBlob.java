package me.nootnoot.sim.net;

public final class ContainerBlob {

    public static final int MAGIC = 0x4A434E54;
    public static final byte VERSION = 1;

    public static final int HEADER_BYTES = 4 + 1 + 1;

    public static final byte OP_OPEN = 0;
    public static final byte OP_CLOSE = 1;
    public static final byte OP_CONTENTS = 2;
    public static final byte OP_BREAK = 3;
    public static final byte OP_DROP = 4;

    public static final byte OP_REFUSED = -1;

    public static final int CONTENTS_SLOTS = 27;

    public static final int CELL_LENGTH = HEADER_BYTES + 8;

    public static final int CONTENTS_MIN_LENGTH = HEADER_BYTES + 8 + CONTENTS_SLOTS;

    public static final int DROP_MIN_LENGTH = HEADER_BYTES + 4 + 1;

    public static final int MAX_LENGTH = Protocol.MAX_CONTAINER_BYTES;

    private ContainerBlob() {
    }

    public static byte[] header(byte opcode) {
        return new byte[]{
                (byte) (MAGIC >>> 24), (byte) (MAGIC >>> 16), (byte) (MAGIC >>> 8), (byte) MAGIC,
                VERSION, opcode};
    }

    public static int exactLength(byte opcode) {
        return switch (opcode) {
            case OP_OPEN, OP_CLOSE, OP_BREAK -> CELL_LENGTH;
            default -> -1;
        };
    }

    public static int minimumLength(byte opcode) {
        return switch (opcode) {
            case OP_OPEN, OP_CLOSE, OP_BREAK -> CELL_LENGTH;
            case OP_CONTENTS -> CONTENTS_MIN_LENGTH;
            case OP_DROP -> DROP_MIN_LENGTH;
            default -> -1;
        };
    }

    public static byte opcodeOf(byte[] blob) {
        if (blob == null || blob.length < HEADER_BYTES || blob.length > MAX_LENGTH) {
            return OP_REFUSED;
        }
        int magic = ((blob[0] & 0xFF) << 24) | ((blob[1] & 0xFF) << 16)
                | ((blob[2] & 0xFF) << 8) | (blob[3] & 0xFF);
        if (magic != MAGIC || blob[4] != VERSION) {
            return OP_REFUSED;
        }
        byte opcode = blob[5];
        int exact = exactLength(opcode);
        if (exact >= 0) {
            return blob.length == exact ? opcode : OP_REFUSED;
        }
        int minimum = minimumLength(opcode);
        if (minimum < 0 || blob.length < minimum) {
            return OP_REFUSED;
        }
        return opcode;
    }

    public static boolean accepts(byte[] blob) {
        return opcodeOf(blob) != OP_REFUSED;
    }
}
