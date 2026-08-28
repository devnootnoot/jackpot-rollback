package me.nootnoot.sim.host;

import me.nootnoot.sim.ArenaAgreement;

public final class ArenaAgreementExchange {

    public static final int DEFAULT_INTERVAL_TICKS = 20;
    public static final int DEFAULT_MAX_SENDS = 40;
    public static final int DEFAULT_TAIL_SENDS = 3;
    public static final int DEFAULT_DEADLINE_TICKS = 400;

    private final ArenaAgreement local;
    private final byte[] blob;
    private final int intervalTicks;
    private final int maxSends;
    private final int tailSends;
    private final int deadlineTicks;

    private int tick;
    private int sends;
    private int tailSent;
    private boolean peerSeen;
    private boolean deadlineMissed;
    private ArenaAgreement peer;
    private String mismatch;

    public ArenaAgreementExchange(ArenaAgreement local) {
        this(local, DEFAULT_INTERVAL_TICKS, DEFAULT_MAX_SENDS, DEFAULT_TAIL_SENDS,
                DEFAULT_DEADLINE_TICKS);
    }

    public ArenaAgreementExchange(ArenaAgreement local, int intervalTicks, int maxSends,
                                  int tailSends) {
        this(local, intervalTicks, maxSends, tailSends, DEFAULT_DEADLINE_TICKS);
    }

    public ArenaAgreementExchange(ArenaAgreement local, int intervalTicks, int maxSends,
                                  int tailSends, int deadlineTicks) {
        this.local = local;
        this.blob = local.encode();
        this.intervalTicks = Math.max(1, intervalTicks);
        this.maxSends = Math.max(1, maxSends);
        this.tailSends = Math.max(0, tailSends);
        this.deadlineTicks = Math.max(1, deadlineTicks);
    }

    public void pump(MatchDriver driver) {
        int now = tick++;
        if (!peerSeen && now >= deadlineTicks) {
            deadlineMissed = true;
        }
        if (sends >= maxSends || (peerSeen && tailSent >= tailSends)) {
            return;
        }
        if (now % intervalTicks != 0) {
            return;
        }
        driver.sendContainer(blob);
        sends++;
        if (peerSeen) {
            tailSent++;
        }
    }

    public boolean offer(byte[] container) {
        ArenaAgreement remote = ArenaAgreement.decode(container);
        if (remote == null) {
            int claimed = ArenaAgreement.peerVersion(container);
            if (claimed >= 0 && claimed != ArenaAgreement.VERSION) {
                peerSeen = true;
                if (mismatch == null) {
                    mismatch = "the opponent speaks arena-agreement version " + claimed
                            + " and this build speaks " + ArenaAgreement.VERSION
                            + ", so the two jars are not the same build and no frame may be"
                            + " simulated between them";
                }
                return true;
            }
            return false;
        }
        peer = remote;
        peerSeen = true;
        if (mismatch != null) {
            return true;
        }
        String reason = local.disagreement(remote);
        if (reason != null) {
            mismatch = reason;
        }
        return true;
    }

    public ArenaAgreement local() {
        return local;
    }

    public ArenaAgreement peer() {
        return peer;
    }

    public boolean peerSeen() {
        return peerSeen;
    }

    public boolean agreementMissing() {
        return deadlineMissed && !peerSeen;
    }

    public String mismatch() {
        return mismatch;
    }

    public String abortReason() {
        if (mismatch != null) {
            return "arena mismatch: " + mismatch;
        }
        if (agreementMissing()) {
            return "arena agreement missing: the opponent sent none within " + deadlineTicks + " ticks";
        }
        return null;
    }
}
