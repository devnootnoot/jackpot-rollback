package me.nootnoot.sim.state;

public record Authority(boolean present, double x, double y, double z, boolean onGround) {

    public static final Authority NONE = new Authority(false, 0.0, 0.0, 0.0, false);

    public static Authority at(double x, double y, double z, boolean onGround) {
        return new Authority(true, x, y, z, onGround);
    }
}
