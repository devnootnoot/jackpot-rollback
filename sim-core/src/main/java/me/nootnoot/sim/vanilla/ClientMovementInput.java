package me.nootnoot.sim.vanilla;

public final class ClientMovementInput {
    public record Impulse(float sideways, float forward) {
    }

    private ClientMovementInput() {
    }

    public static Impulse apply(float sideways, float forward, float itemUse, float sneak) {
        float length = length(sideways, forward);

        if (length < 1.0E-4F) {
            return new Impulse(0.0F, 0.0F);
        }

        float nx = sideways / length;
        float ny = forward / length;

        if (nx * nx + ny * ny == 0.0F) {
            return new Impulse(nx, ny);
        }

        float x = nx * 0.98F;
        float y = ny * 0.98F;

        x *= itemUse;
        y *= itemUse;

        x *= sneak;
        y *= sneak;

        return directional(x, y);
    }

    private static Impulse directional(float x, float y) {
        float f = length(x, y);

        if (f <= 0.0F) {
            return new Impulse(x, y);
        }

        float ux = x * (1.0F / f);
        float uy = y * (1.0F / f);
        float g = directionalMultiplier(ux, uy);
        float h = Math.min(f * g, 1.0F);
        return new Impulse(ux * h, uy * h);
    }

    private static float directionalMultiplier(float x, float y) {
        float f = Math.abs(x);
        float g = Math.abs(y);
        float h = g > f ? f / g : g / f;
        return sqrt(1.0F + h * h);
    }

    private static float length(float x, float y) {
        return sqrt(x * x + y * y);
    }

    private static float sqrt(float value) {
        return (float) Math.sqrt(value);
    }
}
