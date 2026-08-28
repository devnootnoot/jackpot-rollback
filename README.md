# jackpot-rollback

GGPO-style rollback netcode for 1v1 Minecraft duels. Two players run one deterministic simulation in
lockstep over UDP: a modded player hosts the sim on their own client, a vanilla player is hosted by an
edge Paper server that stamps their client-authoritative movement into the input stream.

| Module | What it is |
| --- | --- |
| `sim-core` | The deterministic simulation. Pure JVM, no Minecraft, no networking, no rendering. This is the thing that must be bit-identical everywhere. |
| `sim-host` | Drives `sim-core` over the wire: prediction, rollback, session lifecycle. |
| `edge` | Paper plugin that hosts a vanilla player's side of a duel and mirrors the sim back into the real world. |
| `relay` | Headless UDP relay between the two peers. Required for the modded client (a player's machine is behind NAT); the fallback for edge-to-edge, which can dial directly. |
| `limbo` | Minimal server that holds a player while an arena is prepared. |

| Document | What it covers |
| --- | --- |
| `ARCHITECTURE.md` | Start here. Both host kinds, the broker, the arena and cage flow, the authority model, the version fence |
| `NETCODE.md` | Runtime topology: server types, ports, the Redis keyspace, the lifetime of one duel |
| `DEPLOYMENT.md` | Compose files, `SERVER_TYPE`s, environment variables, Dockhand, Paper config, and the operational runbook |
| `DEPLOY.md` | Releases: the lockstep deploy order for mod/edge/core, what a player sees during a version skew, the kill switch, the cpu-architecture pin, the staged rollout with per-stage abort criteria, and the pre-deploy checklist |
| `RUNBOOK.md` | Operations: every metric, what a healthy value is, the desync-abort alert, the kill switch, and step-by-step for a desync storm, a stale mod jar, a wrong-architecture edge, a relay refusing binds, redis down, and a region that cannot reach another |
| `EDGE-PROTOCOL.md` | The brokering contract: the assignment format (section 2), the direct edge-to-edge link (section 4), the result path (section 5), what happens when a player, an edge, the wire or Redis goes away (section 6), and every flag (section 7) |

## Build and test

```
./gradlew build
```

`:sim-core:check` runs the unit suite plus the determinism guards described below, so a plain `build`
is enough to catch the usual ways determinism gets broken on one machine.

## Cross-machine determinism gate

### Why this gate exists

Every byte of divergence between the two peers is a desync, and a desync mid-duel is a lost ranked
match. The sim is written to avoid every construct whose result is allowed to differ between
platforms, but "written to avoid" is not evidence. The evidence is this: run the same 2000-tick
scenario on an x86_64 machine and on an aarch64 machine and get the same numbers out of both.

Until that comparison has actually run and passed, **edge servers must be pinned to one CPU
architecture**. Two edges on different architectures could desync every match between their players
and nothing in the codebase would tell you why. Pin the fleet, or keep the gate green.

### What a stream-digest is

`HarnessMain` runs a fixed scenario (`ticks=2000`, `seed=c0ffee`, a flat arena, two duelists, a
deterministic pseudo-random input log) and takes `Checksum.of(state)` after every tick. That list of
2000 checksums is the checksum stream. The stream-digest is an FNV-1a fold of the whole stream into one
64-bit value, so it is a single number that changes if any tick on any machine differs by one bit.

The stream is the diagnostic and the digest is the gate. If two machines disagree, the stream tells you
the exact tick where they first parted company, which is usually enough to find the offending
expression.

The digest is a function of sim behaviour, so it moves whenever the sim moves. That is why
`Protocol.CHECKSUM_REV` is recorded alongside it: a digest change with a rev bump is an intended sim
change, and a digest change without one is a bug.

### The committed reference

`sim-core/src/main/resources/me/nootnoot/sim/harness/reference-digest.txt` holds the numbers the sim is
expected to produce:

```
ticks=2000
seed=c0ffee
checksum-rev=110
final-checksum=64624bcd063d8524
stream-digest=a279ebf908b6dcc1
```

It is loaded from the classpath, so every local run, every test and every CI job is measured against
the same committed values.

### What to run

| Command | What it does |
| --- | --- |
| `./gradlew :sim-core:checkHarnessDigest` | Runs the harness and fails if this machine's digest differs from the committed reference. Wired into `check`, so `build` runs it. |
| `./gradlew :sim-core:runHarness` | Same, and also writes `sim-core/build/harness/checksums-<os>-<arch>.txt`, the full per-tick stream for diffing. |
| `./gradlew :sim-core:compareHarnessDigests -PharnessDir=<dir>` | Compares every `checksums-*.txt` under `<dir>` (path relative to the repo root) against each other and against the reference. Fails if any two streams differ, if fewer than two runs are present, if fewer than `-PharnessPlatforms` distinct `os/arch` pairs are present, or if fewer than `-PharnessArches` distinct architectures are present (both default to 2). On a difference it prints the first differing tick, the last agreeing tick, how many ticks differ, and which part of the state moved first. |
| `./gradlew :sim-core:updateHarnessDigest` | Re-records the reference file. Only correct after an intended sim change. |

To do the comparison by hand across two machines: run `runHarness` on each, copy both
`checksums-*.txt` into one directory, and point `compareHarnessDigests` at it. Add
`-PharnessArches=1` when both machines are the same architecture and you are only measuring the
operating system or the JDK; the tool then says out loud that it did not prove the architecture axis.

Each row of a stream is `<tick> <checksum>` followed by one column per entry in
`StateFacets.NAMES` - a 32-bit digest of one part of the state (`p0.motion`, `blocks`, `containers`,
and so on). The columns are written purely so a divergence can be attributed; they are not part of
`Checksum.of` and have no effect on `CHECKSUM_REV`. `StateFacetsCoverageTest` fails if a field is
added to `GameState` or `PlayerState` and no facet claims it, so the attribution cannot silently rot.

### CI

`.github/workflows/determinism.yml` runs on every push and pull request.

1. The `harness` job runs on `ubuntu-latest` and `windows-latest`. Each one builds, runs the full
   test suite, runs the harness, asserts that the stream it just wrote really carries the `os` and
   `arch` the matrix scheduled it for, and uploads the whole per-tick stream as an artifact.
2. The `cross-platform` job downloads both streams and runs `compareHarnessDigests`. This closes the
   operating-system axis, which matters because production edges are Linux containers while every
   digest recorded by hand so far came off Windows.
3. The `harness-arm64` job is defined but **needs a runner**. It is skipped until the repository
   variable `ARM64_RUNNER` names an aarch64 runner (GitHub-hosted `ubuntu-24.04-arm`, or a
   self-hosted label). It asserts `uname -m` is really `aarch64` before it measures anything.
4. The `cross-architecture` job is likewise skipped until then. It is the job that closes the
   architecture axis, and it requires two distinct architectures rather than passing vacuously.

Until `ARM64_RUNNER` is set, the workflow proves the operating system and the JVM agree. It does not
prove x64 and arm64 agree, and `compareHarnessDigests` prints that limitation on every run that was
allowed to pass with one architecture.

### What a mismatch means

| Symptom | What it means | What to do |
| --- | --- | --- |
| `checkHarnessDigest` fails locally and `CHECKSUM_REV` was bumped in your change | You changed sim behaviour on purpose | Run `updateHarnessDigest`, commit the new reference, and bump `RollbackModRegistry.EXPECTED_VERSION` in the same commit |
| `checkHarnessDigest` fails locally and `CHECKSUM_REV` is unchanged | You changed sim behaviour by accident, or you changed it deliberately and forgot the rev bump | Find the change. Shipping this desyncs every peer still on the old rev, because the version handshake will not catch it |
| The x64 job is green and the arm64 job is red | The sim is not architecture-independent | Do not ship. The compare step names the first differing tick and the facet that moved first; start there. The usual causes are a `java.lang.Math` transcendental, a floating-point expression the JIT is allowed to contract differently, iteration over a hash-ordered collection, or an identity hash leaking into state |
| The ubuntu job is green and the windows job is red | The sim depends on the host operating system | Do not ship. Same procedure: the compare step names the tick and the facet |
| Both architectures agree with each other but not with the reference | The reference is stale | Re-record it from a run whose `CHECKSUM_REV` matches |
| `compareHarnessDigests` fails with "the runs cover 1 architecture(s)" | The arm64 runner did not produce an artifact | Fix the runner. This is not a pass |
| `compareHarnessDigests` fails with "the runs cover 1 platform(s)" | Only one of the matrix jobs uploaded a stream | Fix the job. Two runs off the same os and architecture measure the same JVM twice |

### Local guards that catch the common causes without a second machine

These run as part of `:sim-core:test`, so they fail in seconds instead of waiting for CI.

- `TranscendentalBytecodeGateTest` reads the constant pool of every compiled `sim-core` class and fails
  on any `java.lang.Math` call whose method is not on a small allow list of operations the JLS requires
  to be bit-exact (`sqrt`, `abs`, `min`, `max`, `floor`, `ceil`, `round`, `fma`, the exact integer
  helpers, and so on). It also fails on `StrictMath.random`, refuses to let the allow list itself grow
  to cover a platform-dependent method, and asserts that the scan actually saw the core sim classes so
  a stale build cannot produce a false green. Because it reads bytecode rather than source, it catches
  a `Math.pow` reached through a lambda, a method reference or a nested class.
- `DeterminismGateTest.simRuntimeUsesNoForbiddenTranscendentals` keeps the source-level version of the
  same rule, which produces a file and line number.
- `HarnessDigestStabilityTest` runs the harness cold, hammers the sim with 24000 ticks of unrelated
  input to push it through the JIT compilation tiers, then runs the harness again and asserts the digest
  is unchanged. It repeats the run on a second thread with its own compilation history. A tick whose
  result depends on whether HotSpot has compiled it yet cannot be replicated on a peer, and this is the
  cheapest way to find that class of bug.
- `HarnessDigestStabilityTest` also asserts that the reference file was recorded at the current
  `CHECKSUM_REV`, so bumping the rev without re-recording fails immediately.

None of these replace the cross-architecture run. They catch the causes that are cheap to catch.

### Known limits of the gate

- The architecture axis is **not** covered until `ARM64_RUNNER` is set. Everything CI compares today
  is x86_64.
- Linux and Windows are compared. macOS is not in the matrix, and a modded client hosting the sim runs
  on whatever the player has.
- CI exercises only Temurin 21. Other JDK vendors and versions are not gated, though Oracle 21.0.6 on
  Windows, Temurin 21.0.12 on Linux and Corretto 21.0.12.1 on Linux have been compared by hand and
  agree on all 11740 ticks and on all three digests.
- The gate covers `sim-core`. Divergence introduced by a host feeding the sim different inputs is a
  different failure and is covered by the referee replay path, not by this digest.
