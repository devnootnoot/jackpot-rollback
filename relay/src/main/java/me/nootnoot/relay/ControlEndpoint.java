package me.nootnoot.relay;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import me.nootnoot.sim.net.ControlProtocol;

final class ControlEndpoint {
    private static final int MAX_FRAME = 1 << 24;

    private final ServerSocket server;
    private final ControlAuth auth;
    private final RefereeManager referees;
    private volatile boolean running;
    private Thread acceptThread;

    ControlEndpoint(int port, byte[] secret, RefereeManager referees) throws IOException {
        this.server = new ServerSocket(port);
        this.auth = new ControlAuth(secret);
        this.referees = referees;
    }

    int port() {
        return server.getLocalPort();
    }

    void start() {
        running = true;
        acceptThread = new Thread(this::acceptLoop, "control-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    void stop() {
        running = false;
        try {
            server.close();
        } catch (IOException ignored) {
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> handle(s), "control-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!running) {
                    return;
                }
            }
        }
    }

    private void handle(Socket socket) {
        String peer = String.valueOf(socket.getRemoteSocketAddress());
        try (Socket s = socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()))) {
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            while (running) {
                int len = in.readInt();
                if (len < 0 || len > MAX_FRAME) {
                    return;
                }
                byte[] msg = new byte[len];
                in.readFully(msg);
                byte[] tag = new byte[32];
                in.readFully(tag);
                if (!auth.verify(msg, tag)) {
                    System.out.println("[relay] referee: a control frame from " + peer + " failed"
                            + " its HMAC and was discarded - whatever sent it does not hold"
                            + " RELAY_CONTROL_SECRET");
                    continue;
                }
                dispatch(peer, msg, out);
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private void dispatch(String peer, byte[] msg, DataOutputStream out) {
        ControlProtocol.ControlMessage m;
        try {
            m = ControlProtocol.decode(msg);
        } catch (RuntimeException malformed) {
            System.out.println("[relay] referee: a control frame from " + peer + " did not decode ("
                    + malformed + ") - the frame is dropped and the link stays up");
            return;
        }
        long sessionId = sessionIdOf(m);
        try {
            if (m instanceof ControlProtocol.Authorize a) {
                referees.authorize(a, result -> sendResult(out, result));
            } else if (m instanceof ControlProtocol.AuthorizeSetup a) {
                referees.authorize(a, result -> sendResult(out, result));
            } else {
                return;
            }
        } catch (RuntimeException rejected) {
            System.out.println("[relay] #### THE REFEREE COULD NOT TAKE SESSION " + sessionId
                    + " #### " + peer + " authorized it with frame 0 or arena bytes this relay"
                    + " cannot rebuild (" + rejected + "). That duel has NO third witness and its"
                    + " result falls back to the two clients corroborating each other. The control"
                    + " link stays up so every other session keeps its referee.");
            return;
        }
        System.out.println("[relay] referee: watching session " + sessionId + " for " + peer
                + " - every forwarded input frame is re-simulated here");
    }

    private static long sessionIdOf(ControlProtocol.ControlMessage m) {
        if (m instanceof ControlProtocol.Authorize a) {
            return a.sessionId();
        }
        if (m instanceof ControlProtocol.AuthorizeSetup a) {
            return a.sessionId();
        }
        if (m instanceof ControlProtocol.Result r) {
            return r.sessionId();
        }
        return -1L;
    }

    static String describe(ControlProtocol.Result r) {
        if (r.violation()) {
            return "PROTOCOL VIOLATION at frame " + r.confirmedFrame() + " - the result is void";
        }
        if (!r.decided() || r.winnerSlot() < 0 || r.winnerSlot() > 1) {
            return "no decided end after re-simulating " + r.confirmedFrame() + " frames - the"
                    + " referee claims nothing and the two-witness rule decides";
        }
        return "slot " + r.winnerSlot() + " wins " + r.winsP0() + "-" + r.winsP1()
                + " after re-simulating " + r.confirmedFrame() + " frames";
    }

    private void sendResult(DataOutputStream out, ControlProtocol.Result result) {
        System.out.println("[relay] referee verdict: session " + result.sessionId() + " - "
                + describe(result));
        byte[] msg = ControlProtocol.encode(result);
        byte[] tag = auth.sign(msg);
        synchronized (out) {
            try {
                out.writeInt(msg.length);
                out.write(msg);
                out.write(tag);
                out.flush();
            } catch (IOException gone) {
                System.out.println("[relay] referee: the verdict above could not be delivered ("
                        + gone + ") - whoever authorized session " + result.sessionId()
                        + " is no longer connected, so it falls back to two-witness corroboration");
            }
        }
    }
}
