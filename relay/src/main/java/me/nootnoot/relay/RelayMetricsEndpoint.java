package me.nootnoot.relay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.IntSupplier;
import me.nootnoot.sim.net.InputCodec;
import me.nootnoot.sim.net.Protocol;

public final class RelayMetricsEndpoint {

    public static final int DEFAULT_PORT = 7779;

    private static final String PROMETHEUS_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private final HttpServer server;
    private final RelayServer.Metrics metrics;
    private final IntSupplier sessionCount;
    private final String relayId;

    private RelayMetricsEndpoint(HttpServer server, RelayServer.Metrics metrics,
                                 IntSupplier sessionCount, String relayId) {
        this.server = server;
        this.metrics = metrics;
        this.sessionCount = sessionCount;
        this.relayId = relayId;
    }

    public static RelayMetricsEndpoint start(int port, RelayServer.Metrics metrics,
                                             IntSupplier sessionCount, String relayId)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 8);
        RelayMetricsEndpoint endpoint =
                new RelayMetricsEndpoint(server, metrics, sessionCount, relayId);
        server.createContext("/metrics", endpoint::serveMetrics);
        server.createContext("/", endpoint::serveMetrics);
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "relay-metrics");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        return endpoint;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }

    private void serveMetrics(HttpExchange exchange) throws IOException {
        byte[] payload = render().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().put("Content-Type", List.of(PROMETHEUS_TYPE));
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private String render() {
        String labels = "{relay=\"" + relayId + "\"}";
        StringBuilder b = new StringBuilder(2048);
        b.append("# HELP rollback_relay_build_info The version fence this relay was built"
                + " against. It only forwards, but a HELLO whose protocol version differs from its"
                + " peer's is aborted here and counted as rollback_relay_version_mismatch_total.\n");
        b.append("# TYPE rollback_relay_build_info gauge\n");
        b.append("rollback_relay_build_info{relay=\"").append(relayId)
                .append("\",input_bytes=\"").append(InputCodec.BYTES)
                .append("\",checksum_rev=\"").append(Protocol.CHECKSUM_REV)
                .append("\",protocol_version=\"").append(Protocol.VERSION)
                .append("\"} 1\n");
        gauge(b, "rollback_relay_sessions", labels, sessionCount.getAsInt());
        counter(b, "rollback_relay_forwarded_total", labels, metrics.forwarded.get());
        counter(b, "rollback_relay_hello_total", labels, metrics.hello.get());
        counter(b, "rollback_relay_rate_limited_total", labels, metrics.rateLimited.get());
        counter(b, "rollback_relay_invalid_total", labels, metrics.invalid.get());
        counter(b, "rollback_relay_unknown_source_total", labels, metrics.unknownSource.get());
        counter(b, "rollback_relay_unauthorized_total", labels, metrics.unauthorized.get());
        counter(b, "rollback_relay_no_peer_total", labels, metrics.noPeer.get());
        counter(b, "rollback_relay_session_cap_total", labels, metrics.sessionCap.get());
        counter(b, "rollback_relay_evicted_total", labels, metrics.evicted.get());
        counter(b, "rollback_relay_referee_shed_total", labels, metrics.refereeShed.get());
        counter(b, "rollback_relay_reclaimed_total", labels, metrics.reclaimed.get());
        counter(b, "rollback_relay_orphan_pins_total", labels, metrics.orphanPins.get());
        counter(b, "rollback_relay_handler_error_total", labels, metrics.handlerError.get());
        counter(b, "rollback_relay_version_mismatch_total", labels,
                metrics.versionMismatch.get());
        return b.toString();
    }

    private static void gauge(StringBuilder b, String name, String labels, long value) {
        b.append("# TYPE ").append(name).append(" gauge\n");
        b.append(name).append(labels).append(' ').append(value).append('\n');
    }

    private static void counter(StringBuilder b, String name, String labels, long value) {
        b.append("# TYPE ").append(name).append(" counter\n");
        b.append(name).append(labels).append(' ').append(value).append('\n');
    }
}
