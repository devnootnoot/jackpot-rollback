package me.nootnoot.sim.math;

public final class MathTables {
    private static final float[] SIN = new float[65536];

    static {
        for (int i = 0; i < SIN.length; i++) {
            SIN[i] = (float) StrictMath.sin((double) i * Math.PI * 2.0 / 65536.0);
        }
    }

    private MathTables() {
    }

    public static float sin(float radians) {
        return SIN[(int) (radians * 10430.378F) & 0xFFFF];
    }

    public static float cos(float radians) {
        return SIN[(int) (radians * 10430.378F + 16384.0F) & 0xFFFF];
    }
}
