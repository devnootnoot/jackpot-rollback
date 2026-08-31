package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PeerEntityHasNoClientSideGravityTest {

    @Test
    void theOpponentEntityIsFullyServerDriven() throws IOException {
        String s = Files.readString(Path.of("src/main/java/me/nootnoot/edge/EdgePlayerEntity.java"),
                StandardCharsets.UTF_8);
        assertTrue(s.contains("DATA_NO_GRAVITY"),
                "every tick of the opponent's position comes from the sim, so the client must not"
                        + " also simulate gravity for that entity. Between our packets it applies"
                        + " its own fall and settles a few pixels into the ground, which is visible"
                        + " while standing still. The edge itself never rendered the peer below"
                        + " y=6.000, so this drift is entirely client-side");
    }
}
