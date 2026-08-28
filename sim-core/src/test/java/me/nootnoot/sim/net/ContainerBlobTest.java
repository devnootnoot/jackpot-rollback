package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.ArenaAgreement;
import org.junit.jupiter.api.Test;

class ContainerBlobTest {

    private static byte[] framed(byte opcode, int payloadBytes) {
        byte[] out = new byte[ContainerBlob.HEADER_BYTES + payloadBytes];
        byte[] header = ContainerBlob.header(opcode);
        System.arraycopy(header, 0, out, 0, header.length);
        return out;
    }

    @Test
    void aWellFormedCellBlobIsAccepted() {
        for (byte op : new byte[]{ContainerBlob.OP_OPEN, ContainerBlob.OP_CLOSE, ContainerBlob.OP_BREAK}) {
            byte[] blob = framed(op, 8);
            assertEquals(ContainerBlob.CELL_LENGTH, blob.length);
            assertEquals(op, ContainerBlob.opcodeOf(blob));
            assertTrue(ContainerBlob.accepts(blob));
        }
    }

    @Test
    void aBlobWithNoHeaderIsRefused() {
        assertEquals(ContainerBlob.OP_REFUSED, ContainerBlob.opcodeOf(null));
        assertEquals(ContainerBlob.OP_REFUSED, ContainerBlob.opcodeOf(new byte[0]));
        assertEquals(ContainerBlob.OP_REFUSED, ContainerBlob.opcodeOf(new byte[]{ContainerBlob.OP_OPEN}));
        assertEquals(ContainerBlob.OP_REFUSED,
                ContainerBlob.opcodeOf(new byte[ContainerBlob.HEADER_BYTES - 1]));
    }

    @Test
    void aWrongMagicIsRefused() {
        byte[] blob = framed(ContainerBlob.OP_OPEN, 8);
        blob[0] ^= 0x01;
        assertEquals(ContainerBlob.OP_REFUSED, ContainerBlob.opcodeOf(blob));
        assertFalse(ContainerBlob.accepts(blob));
    }

    @Test
    void aWrongVersionIsRefused() {
        byte[] blob = framed(ContainerBlob.OP_OPEN, 8);
        blob[4] = (byte) (ContainerBlob.VERSION + 1);
        assertEquals(ContainerBlob.OP_REFUSED, ContainerBlob.opcodeOf(blob));
    }

    @Test
    void anUnknownOpcodeIsRefused() {
        assertEquals(ContainerBlob.OP_REFUSED, ContainerBlob.opcodeOf(framed((byte) 9, 8)));
        assertEquals(ContainerBlob.OP_REFUSED, ContainerBlob.opcodeOf(framed((byte) -7, 8)));
        assertEquals(ContainerBlob.OP_REFUSED,
                ContainerBlob.opcodeOf(framed(ContainerBlob.OP_REFUSED, 8)));
    }

    @Test
    void aCellBlobOfTheWrongLengthIsRefused() {
        assertEquals(ContainerBlob.OP_REFUSED, ContainerBlob.opcodeOf(framed(ContainerBlob.OP_OPEN, 7)));
        assertEquals(ContainerBlob.OP_REFUSED, ContainerBlob.opcodeOf(framed(ContainerBlob.OP_OPEN, 9)));
        assertEquals(ContainerBlob.OP_REFUSED, ContainerBlob.opcodeOf(framed(ContainerBlob.OP_CLOSE, 0)));
        assertEquals(ContainerBlob.OP_REFUSED, ContainerBlob.opcodeOf(framed(ContainerBlob.OP_BREAK, 64)));
    }

    @Test
    void aTruncatedContentsBlobIsRefused() {
        int payload = ContainerBlob.CONTENTS_MIN_LENGTH - ContainerBlob.HEADER_BYTES;
        assertEquals(ContainerBlob.OP_CONTENTS,
                ContainerBlob.opcodeOf(framed(ContainerBlob.OP_CONTENTS, payload)));
        assertEquals(ContainerBlob.OP_REFUSED,
                ContainerBlob.opcodeOf(framed(ContainerBlob.OP_CONTENTS, payload - 1)));
        assertEquals(ContainerBlob.OP_REFUSED,
                ContainerBlob.opcodeOf(framed(ContainerBlob.OP_CONTENTS, 8)));
    }

    @Test
    void aTruncatedDropBlobIsRefused() {
        int payload = ContainerBlob.DROP_MIN_LENGTH - ContainerBlob.HEADER_BYTES;
        assertEquals(ContainerBlob.OP_DROP, ContainerBlob.opcodeOf(framed(ContainerBlob.OP_DROP, payload)));
        assertEquals(ContainerBlob.OP_REFUSED,
                ContainerBlob.opcodeOf(framed(ContainerBlob.OP_DROP, payload - 1)));
    }

    @Test
    void anOversizedBlobIsRefused() {
        assertEquals(ContainerBlob.OP_REFUSED,
                ContainerBlob.opcodeOf(framed(ContainerBlob.OP_CONTENTS,
                        ContainerBlob.MAX_LENGTH + 1 - ContainerBlob.HEADER_BYTES)));
        assertEquals(Protocol.MAX_CONTAINER_BYTES, ContainerBlob.MAX_LENGTH);
    }

    @Test
    void anArenaAgreementBlobIsNotMistakenForAContainerOp() {
        byte[] agreement = new ArenaAgreement(99L, 64.0, 0, 0, 0, 1, 1, 1, 7L, "arena.bin").encode();
        assertEquals(ContainerBlob.OP_REFUSED, ContainerBlob.opcodeOf(agreement));
    }

    @Test
    void aContainerOpIsNotMistakenForAnArenaAgreement() {
        for (byte op : new byte[]{ContainerBlob.OP_OPEN, ContainerBlob.OP_CLOSE, ContainerBlob.OP_BREAK}) {
            assertNull(ArenaAgreement.decode(framed(op, 8)));
        }
    }
}
