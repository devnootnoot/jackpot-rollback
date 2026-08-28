package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import me.nootnoot.sim.state.GameState;
import org.junit.jupiter.api.Test;

class BlastSeedTest {
    @Test
    void twoBlastsInOneTickCanNeverShareARollEvenAtTheSameCellAndPower() {
        long first = Combat.blastSeed(10, 64, 20, 500, Combat.CRYSTAL_POWER, 0);
        long second = Combat.blastSeed(10, 64, 20, 500, Combat.CRYSTAL_POWER, 1);
        assertNotEquals(first, second,
                "cell, tick and power alone cannot separate two explosions that land on the same"
                        + " block in the same tick with the same power. The per-blast sequence"
                        + " number is the only term that always moves");

        assertNotEquals(new Random(first).nextFloat(), new Random(second).nextFloat(),
                "and the streams they seed have to part on the very first ray, because the first"
                        + " draw is the energy of ray zero");
    }

    @Test
    void aChainOfSimultaneousBlastsProducesNoRepeatedSeed() {
        Set<Long> seeds = new LinkedHashSet<>();
        int n = 0;
        for (int seq = 0; seq < 64; seq++) {
            for (int cell = 0; cell < 16; cell++) {
                seeds.add(Combat.blastSeed(cell, 64, cell * 3, 900, Combat.ANCHOR_POWER, seq));
                n++;
            }
        }
        assertEquals(n, seeds.size(), "every distinct blast must get its own stream");
    }

    @Test
    void neighbouringCellsDoNotProduceNeighbouringRolls() {
        float a = new Random(Combat.blastSeed(0, 0, 0, 1, Combat.CRYSTAL_POWER, 0)).nextFloat();
        float b = new Random(Combat.blastSeed(1, 0, 0, 1, Combat.CRYSTAL_POWER, 0)).nextFloat();
        float c = new Random(Combat.blastSeed(0, 1, 0, 1, Combat.CRYSTAL_POWER, 0)).nextFloat();
        float d = new Random(Combat.blastSeed(0, 0, 1, 1, Combat.CRYSTAL_POWER, 0)).nextFloat();
        Set<Float> distinct = new LinkedHashSet<>(List.of(a, b, c, d));
        assertEquals(4, distinct.size(),
                "a one block move must not land on the same ray energy");
    }

    @Test
    void theSeedIsAPureFunctionOfItsInputsSoBothPeersDeriveIt() {
        for (int i = 0; i < 1000; i++) {
            assertEquals(Combat.blastSeed(i, -i, i * 7, i * 3, Combat.ANCHOR_POWER, i % 5),
                    Combat.blastSeed(i, -i, i * 7, i * 3, Combat.ANCHOR_POWER, i % 5),
                    "the seed has to be reproducible or a rollback reseeds the same explosion"
                            + " differently");
        }
    }

    @Test
    void theSequenceNumberIsReplicatedStateNotALocalCounter() {
        GameState s = new GameState();
        s.blastSeq = 7;
        assertEquals(7, s.copy().blastSeq, "a rollback restores the counter with the world");
        assertTrue(Checksum.of(s) != Checksum.of(new GameState()),
                "and the checksum sees it, so a peer that skipped a blast is caught");
    }
}
