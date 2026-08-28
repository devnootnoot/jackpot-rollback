package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import org.junit.jupiter.api.Test;

class OutOfBandQueueCapTest {

    private static final double GROUND_Y = 64.0;
    private static final int FLOOD = NetSession.MAX_QUEUED_CHAT * 8;

    private static NetSession[] pair(LoopbackNetwork net) {
        Arena arena = Arena.flat(GROUND_Y);
        return new NetSession[]{
                new NetSession(net.endpoint(0), 0, arena, HarnessScenarios.duel(arena), 256),
                new NetSession(net.endpoint(1), 1, arena, HarnessScenarios.duel(arena), 256)
        };
    }

    @Test
    void aPeerThatNeverStopsChattingCannotGrowTheQueueWithoutBound() {
        LoopbackNetwork net = new LoopbackNetwork(0x1234L, 0, 0, 0.0);
        NetSession[] s = pair(net);

        for (int i = 0; i < FLOOD; i++) {
            s[0].sendChat("spam " + i);
            net.step();
            s[1].flush();
        }

        assertFalse(s[1].aborted(), "the flood must not abort the match: " + s[1].abortReason());
        assertTrue(s[1].retention().chatQueue() <= NetSession.MAX_QUEUED_CHAT,
                "an edge never drains chat, so an undrained queue is the peer's memory to grow;"
                        + " held " + s[1].retention().chatQueue());
        assertTrue(s[1].droppedChat() > 0, "the overflow has to be visible, not silent");

        List<String> drained = s[1].drainChat();
        assertEquals(NetSession.MAX_QUEUED_CHAT, drained.size());
        assertEquals("spam " + (FLOOD - 1), drained.get(drained.size() - 1),
                "the newest line is the one worth keeping");
        assertEquals(0, s[1].retention().chatQueue());
    }

    @Test
    void aPeerThatNeverStopsSendingContainerBlobsIsCappedTheSameWay() {
        LoopbackNetwork net = new LoopbackNetwork(0x1234L, 0, 0, 0.0);
        NetSession[] s = pair(net);

        for (int i = 0; i < NetSession.MAX_QUEUED_CONTAINER * 4; i++) {
            byte[] blob = ContainerBlob.header(ContainerBlob.OP_OPEN);
            s[0].sendContainer(Arrays.copyOf(blob, ContainerBlob.CELL_LENGTH));
            net.step();
            s[1].flush();
        }

        assertTrue(s[1].retention().containerQueue() <= NetSession.MAX_QUEUED_CONTAINER);
        assertTrue(s[1].droppedContainer() > 0);
    }

    @Test
    void aNormalConversationIsNeverDropped() {
        LoopbackNetwork net = new LoopbackNetwork(0x1234L, 0, 0, 0.0);
        NetSession[] s = pair(net);

        for (int i = 0; i < FLOOD; i++) {
            s[0].sendChat("gg " + i);
            net.step();
            s[1].flush();
            assertEquals(List.of("gg " + i), s[1].drainChat());
        }

        assertEquals(0, s[1].droppedChat(), "draining every tick must never hit the cap");
    }
}
