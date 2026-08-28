package me.nootnoot.sim;

public final class RollbackOverrunException extends RuntimeException {
    public final int frame;
    public final int head;

    public RollbackOverrunException(int frame, int head) {
        super("needed saved state for frame " + frame + " but head=" + head + " is beyond ring capacity");
        this.frame = frame;
        this.head = head;
    }
}
