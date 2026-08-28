package me.nootnoot.limbo;

/**
 * Packet IDs and protocol constants for the connection states the limbo handles dynamically.
 *
 * <p>These are the <b>1.21.11 (protocol 774)</b> numbers, taken from that server's own generated
 * protocol report. They are version-fragile. The complex config/join packets are NOT here — those are
 * replayed from your capture files; only the few packets the limbo builds itself (login success,
 * keep-alive, transfer, status) use these IDs. Newer versions override the PLAY ids through
 * {@code play.id.*.alt} / {@code capture.N.id.*}; handshake, status, login and configuration are
 * identical on 774, 775 and 776.
 */
public final class Protocol {

    private Protocol() {
    }

    /** Set to your client's protocol version number; sent back in the status response. */
    public static int PROTOCOL_VERSION = 774;
    public static String VERSION_NAME = "1.21.11";

    // Handshake (serverbound)
    public static final int HS_HANDSHAKE = 0x00;

    // Status
    public static final int ST_REQUEST = 0x00;   // serverbound
    public static final int ST_PING = 0x01;      // serverbound
    public static final int ST_RESPONSE = 0x00;  // clientbound
    public static final int ST_PONG = 0x01;      // clientbound

    // Login
    public static final int LOGIN_START = 0x00;          // serverbound
    public static final int LOGIN_PLUGIN_RESPONSE = 0x02; // serverbound Login Plugin Response (Velocity forwarding)
    public static final int LOGIN_ACK = 0x03;            // serverbound (Login Acknowledged)
    public static final int LOGIN_DISCONNECT = 0x00;     // clientbound Login Disconnect (JSON string body)
    public static final int LOGIN_SUCCESS = 0x02;        // clientbound
    public static final int LOGIN_PLUGIN_REQUEST = 0x04; // clientbound Login Plugin Request (Velocity forwarding)

    // Configuration (serverbound we care about)
    public static final int CFG_FINISH_ACK = 0x03;     // serverbound Acknowledge Finish Configuration
    public static final int CFG_KNOWN_PACKS_IN = 0x07;  // serverbound Known Packs (ignored)
    public static final int CFG_KEEPALIVE_IN = 0x04;    // serverbound Keep Alive (ignored)

    // Play
    public static final int PLAY_KEEPALIVE_IN = 0x1B;   // serverbound Keep Alive
    public static final int PLAY_TELEPORT_CONFIRM = 0x00; // serverbound (ignored)
    public static final int PLAY_CUSTOM_PAYLOAD_IN = 0x15; // serverbound Plugin Message
    public static final int PLAY_KEEPALIVE_OUT = 0x2B;  // clientbound Keep Alive
    public static final int PLAY_TRANSFER_OUT = 0x7F;   // clientbound Transfer
    public static final int PLAY_DISCONNECT_OUT = 0x20; // clientbound Disconnect
}
