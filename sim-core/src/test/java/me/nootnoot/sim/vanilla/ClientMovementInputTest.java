package me.nootnoot.sim.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClientMovementInputTest {
    private static float magnitude(ClientMovementInput.Impulse impulse) {
        return (float) Math.sqrt(impulse.sideways() * impulse.sideways()
                + impulse.forward() * impulse.forward());
    }

    @Test
    void noInputPassesThroughUntouched() {
        ClientMovementInput.Impulse none = ClientMovementInput.apply(0f, 0f, 1f, 1f);
        assertEquals(0f, none.sideways(), 0f);
        assertEquals(0f, none.forward(), 0f);
    }

    @Test
    void walkingForwardKeepsTheRawZeroPointNineEight() {
        ClientMovementInput.Impulse walk = ClientMovementInput.apply(0f, 1f, 1f, 1f);
        assertEquals(0.98f, walk.forward(), 0f);
        assertEquals(0f, walk.sideways(), 0f);
    }

    @Test
    void plainDiagonalClampsToOne() {
        ClientMovementInput.Impulse diagonal = ClientMovementInput.apply(1f, 1f, 1f, 1f);
        assertEquals(1.0f, magnitude(diagonal), 1.0E-6f);
    }

    @Test
    void sneakingDiagonalKeepsTheNormalisedLength() {
        ClientMovementInput.Impulse sneak = ClientMovementInput.apply(1f, 1f, 1f, 0.3f);
        assertEquals(0.41577885f, magnitude(sneak), 1.0E-6f);
        assertTrue(magnitude(sneak) < 0.5f, "scaling the un-normalised vector produces 0.58800006");
    }

    @Test
    void sneakingStraightIsUnaffectedByTheRemap() {
        ClientMovementInput.Impulse sneak = ClientMovementInput.apply(0f, 1f, 1f, 0.3f);
        assertEquals(0.294f, magnitude(sneak), 1.0E-6f);
    }

    @Test
    void usingAnItemDiagonallyAlsoKeepsTheDiagonalBonus() {
        ClientMovementInput.Impulse eating = ClientMovementInput.apply(1f, 1f, 0.2f, 1f);
        assertEquals(0.27718589f, magnitude(eating), 1.0E-6f);
    }

    @Test
    void factorsCompose() {
        ClientMovementInput.Impulse both = ClientMovementInput.apply(1f, 1f, 0.2f, 0.3f);
        assertEquals(0.08315576f, magnitude(both), 1.0E-6f);
    }

    @Test
    void backAndLeftMirrorForwardAndRight() {
        float forwardRight = magnitude(ClientMovementInput.apply(-1f, 1f, 1f, 0.3f));
        float backLeft = magnitude(ClientMovementInput.apply(1f, -1f, 1f, 0.3f));
        assertEquals(forwardRight, backLeft, 0f);
    }
}
