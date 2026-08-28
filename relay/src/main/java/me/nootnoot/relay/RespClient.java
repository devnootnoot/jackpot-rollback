package me.nootnoot.relay;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

final class RespClient implements AutoCloseable {

    static final int CONNECT_TIMEOUT_MS = 2_000;
    static final int READ_TIMEOUT_MS = 2_000;

    private static final int MAX_LINE = 64 * 1024;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    private RespClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new BufferedInputStream(socket.getInputStream(), 1024);
        this.out = socket.getOutputStream();
    }

    static RespClient connect(String host, int port, String password) throws IOException {
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
        } catch (IOException unreachable) {
            closeQuietly(socket);
            throw unreachable;
        }
        RespClient client;
        try {
            client = new RespClient(socket);
        } catch (IOException broken) {
            closeQuietly(socket);
            throw broken;
        }
        if (password != null && !password.isBlank()) {
            try {
                client.call("AUTH", password);
            } catch (IOException rejected) {
                client.close();
                throw rejected;
            }
        }
        return client;
    }

    void hset(String key, String field, String value) throws IOException {
        call("HSET", key, field, value);
    }

    void expire(String key, long seconds) throws IOException {
        call("EXPIRE", key, Long.toString(seconds));
    }

    void hdel(String key, String field) throws IOException {
        call("HDEL", key, field);
    }

    private void call(String... args) throws IOException {
        out.write(encode(args));
        out.flush();
        readReply();
    }

    static byte[] encode(String... args) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        ascii(buf, "*" + args.length + "\r\n");
        for (String arg : args) {
            byte[] raw = arg.getBytes(StandardCharsets.UTF_8);
            ascii(buf, "$" + raw.length + "\r\n");
            buf.write(raw, 0, raw.length);
            ascii(buf, "\r\n");
        }
        return buf.toByteArray();
    }

    private static void ascii(ByteArrayOutputStream buf, String text) {
        byte[] raw = text.getBytes(StandardCharsets.US_ASCII);
        buf.write(raw, 0, raw.length);
    }

    private void readReply() throws IOException {
        int marker = in.read();
        if (marker < 0) {
            throw new EOFException("redis closed the connection");
        }
        String line = readLine();
        switch (marker) {
            case '+', ':' -> {
            }
            case '-' -> throw new IOException("redis replied " + line);
            case '$' -> {
                int length = parseLength(line);
                if (length >= 0) {
                    skip(length);
                    expectCrlf();
                }
            }
            case '*' -> {
                int count = parseLength(line);
                for (int i = 0; i < count; i++) {
                    readReply();
                }
            }
            default -> throw new IOException("unexpected redis reply type '" + (char) marker + "'");
        }
    }

    private static int parseLength(String line) throws IOException {
        try {
            return Integer.parseInt(line.trim());
        } catch (NumberFormatException malformed) {
            throw new IOException("malformed redis length '" + line + "'");
        }
    }

    private String readLine() throws IOException {
        StringBuilder b = new StringBuilder(32);
        while (b.length() <= MAX_LINE) {
            int c = in.read();
            if (c < 0) {
                throw new EOFException("redis closed the connection mid-reply");
            }
            if (c == '\r') {
                if (in.read() != '\n') {
                    throw new IOException("malformed redis reply line ending");
                }
                return b.toString();
            }
            b.append((char) c);
        }
        throw new IOException("redis reply line exceeded " + MAX_LINE + " bytes");
    }

    private void skip(int count) throws IOException {
        for (int i = 0; i < count; i++) {
            if (in.read() < 0) {
                throw new EOFException("redis closed the connection mid-reply");
            }
        }
    }

    private void expectCrlf() throws IOException {
        if (in.read() != '\r' || in.read() != '\n') {
            throw new IOException("malformed redis bulk terminator");
        }
    }

    @Override
    public void close() {
        closeQuietly(socket);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
