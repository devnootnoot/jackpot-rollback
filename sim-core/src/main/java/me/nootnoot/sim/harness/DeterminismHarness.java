package me.nootnoot.sim.harness;

import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.ArenaHash;
import me.nootnoot.sim.Checksum;
import me.nootnoot.sim.Simulation;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;

public final class DeterminismHarness {
    public record Result(
            boolean converged,
            int divergedAtTick,
            long finalChecksum,
            long arenaHash,
            boolean independentArenas,
            boolean rollbackAgreed,
            int rollbackDivergedAtFrame,
            long rollbackDigest,
            RollbackAudit.Stats rollback,
            String disagreement,
            List<Long> checksums,
            List<int[]> facets) {
    }

    public static Result run(InputLog log, GameState a, GameState b, Arena arena) {
        return run(log, a, b, arena, arena, null);
    }

    public static Result run(InputLog log, GameState a, GameState b, Arena arenaA, Arena arenaB) {
        return run(log, a, b, arenaA, arenaB, null);
    }

    public static Result run(InputLog log, GameState a, GameState b, Arena arenaA, Arena arenaB,
                             HarnessCoverage coverage) {
        long hashA = ArenaHash.of(arenaA);
        long hashB = ArenaHash.of(arenaB);
        boolean independent = arenaA != arenaB && arenaA.solids != arenaB.solids;
        if (hashA != hashB) {
            return new Result(false, 0, 0L, hashA, independent, false, 0, 0L,
                    RollbackAudit.notRun(),
                    "the two independently extracted arenas disagree: "
                            + Long.toHexString(hashA) + " vs " + Long.toHexString(hashB),
                    List.of(), List.of());
        }

        if (coverage != null) {
            coverage.observeArena(arenaA);
            coverage.observeArena(arenaB);
        }

        GameState rollbackInitial = a.copy();
        RollbackAudit.Landmarks marks = new RollbackAudit.Landmarks();

        List<Long> checksums = new ArrayList<>(log.frames.size());
        List<int[]> facets = new ArrayList<>(log.frames.size());
        for (Input[] frame : log.frames) {
            Simulation.tick(a, arenaA, frame[0], frame[1]);
            Simulation.tick(b, arenaB, frame[0], frame[1]);
            if (coverage != null) {
                coverage.observe(a, frame[0], frame[1]);
            }
            marks.observe(a);
            long ca = Checksum.of(a);
            long cb = Checksum.of(b);
            checksums.add(ca);
            facets.add(StateFacets.of(a));
            if (ca != cb) {
                return new Result(false, a.tick, ca, hashA, independent, false, 0, 0L,
                        RollbackAudit.notRun(),
                        "the two simulation instances diverged at tick " + a.tick + " in "
                                + namedFacets(StateFacets.of(a), StateFacets.of(b)), checksums,
                        facets);
            }
        }

        RollbackAudit.Result rb = RollbackAudit.run(log, rollbackInitial, arenaB, checksums, marks);

        return new Result(rb.agreed(), rb.agreed() ? -1 : rb.divergedAtFrame(), Checksum.of(a),
                hashA, independent, rb.agreed(), rb.divergedAtFrame(), rb.digest(), rb.stats(),
                rb.disagreement(), checksums, facets);
    }

    public static String namedFacets(int[] left, int[] right) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < StateFacets.COUNT; i++) {
            if (left[i] == right[i]) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(StateFacets.name(i)).append(" (")
                    .append(Integer.toHexString(left[i])).append(" vs ")
                    .append(Integer.toHexString(right[i])).append(')');
        }
        return sb.length() == 0
                ? "no facet: the checksum moved but every facet agrees, so a field the facets do"
                        + " not cover is what changed"
                : sb.toString();
    }
}
