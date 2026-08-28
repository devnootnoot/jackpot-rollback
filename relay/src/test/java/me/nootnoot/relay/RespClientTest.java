package me.nootnoot.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RespClientTest {

    @Test
    void hsetEncodesAsARespArrayOfBulkStrings() {
        byte[] wire = RespClient.encode("HSET", RelayRegistry.KEY_SERVERS, "eu-relay-1", "{}");

        assertEquals("*4\r\n$4\r\nHSET\r\n$22\r\nrollback:relay:servers\r\n"
                        + "$10\r\neu-relay-1\r\n$2\r\n{}\r\n",
                new String(wire, StandardCharsets.UTF_8),
                "a relay that speaks RESP by hand has to get the framing exactly right or every"
                        + " later command lands on a desynced socket");
    }

    @Test
    void expireSendsTheSecondsAsABulkString() {
        byte[] wire = RespClient.encode("EXPIRE", "k", "120");

        assertEquals("*3\r\n$6\r\nEXPIRE\r\n$1\r\nk\r\n$3\r\n120\r\n",
                new String(wire, StandardCharsets.UTF_8),
                "RESP has no integer argument type - numbers go on the wire as bulk strings");
    }

    @Test
    void hdelIsTheDeregistrationCommand() {
        byte[] wire = RespClient.encode("HDEL", RelayRegistry.KEY_SERVERS, "eu-relay-1");

        assertEquals("*3\r\n$4\r\nHDEL\r\n$22\r\nrollback:relay:servers\r\n$10\r\neu-relay-1\r\n",
                new String(wire, StandardCharsets.UTF_8));
    }

    @Test
    void lengthPrefixesCountBytesNotCharacters() {
        byte[] wire = RespClient.encode("AUTH", "päss");

        assertEquals("*2\r\n$4\r\nAUTH\r\n$5\r\npäss\r\n",
                new String(wire, StandardCharsets.UTF_8),
                "a non-ascii redis password must be length-prefixed in utf-8 bytes, or AUTH is"
                        + " framed short and the relay never registers");
    }
}
