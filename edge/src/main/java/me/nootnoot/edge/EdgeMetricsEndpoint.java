package me.nootnoot.edge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public final class EdgeMetricsEndpoint {

    public static final int DEFAULT_PORT = 7788;

    private static final String PROMETHEUS_TYPE = "text/plain; version=0.0.4; charset=utf-8";
    private static final String PLAIN_TYPE = "text/plain; charset=utf-8";

    private final HttpServer server;
    private final Logger logger;

    private volatile String prometheus = "";
    private volatile String human = "no snapshot has been published yet";
    private volatile boolean alerting;

    private EdgeMetricsEndpoint(HttpServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    public static EdgeMetricsEndpoint start(Logger logger, String bind, int port) {
        try {
            InetSocketAddress address = bind == null || bind.isBlank()
                    ? new InetSocketAddress(port) : new InetSocketAddress(bind, port);
            HttpServer server = HttpServer.create(address, 8);
            EdgeMetricsEndpoint endpoint = new EdgeMetricsEndpoint(server, logger);
            server.createContext("/metrics", endpoint::serveMetrics);
            server.createContext("/healthz", endpoint::serveHealth);
            server.createContext("/", endpoint::serveHuman);
            server.setExecutor(Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "edge-metrics");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            logger.info("metrics endpoint listening on http://" + address.getHostString() + ":"
                    + server.getAddress().getPort() + "/metrics (prometheus text) and / (human)."
                    + " It serves a snapshot rebuilt by the async broker poll, so a scrape never"
                    + " touches the server thread.");
            return endpoint;
        } catch (IOException ex) {
            logger.warning("could not bind the metrics endpoint on port " + port + " (" + ex
                    + ") - metrics still reach core through redis, but nothing can scrape this"
                    + " process directly");
            return null;
        }
    }

    public void publish(EdgeMetrics.Snapshot snapshot, boolean desyncAlert) {
        this.alerting = desyncAlert;
        this.prometheus = EdgeMetrics.prometheus(snapshot)
                + "# TYPE rollback_edge_desync_alert_firing gauge\n"
                + "rollback_edge_desync_alert_firing{edge=\"" + snapshot.edgeId() + "\"} "
                + (desyncAlert ? 1 : 0) + "\n";
        StringBuilder b = new StringBuilder(2048);
        if (desyncAlert) {
            b.append("!!! DESYNC ALERT FIRING - two builds disagree. Version fence here: ")
                    .append(snapshot.versionFence()).append('\n');
        }
        for (String line : EdgeMetrics.human(snapshot)) {
            b.append(line).append('\n');
        }
        this.human = b.toString();
    }

    public void stop() {
        try {
            server.stop(0);
        } catch (RuntimeException ex) {
            logger.warning("metrics endpoint did not stop cleanly: " + ex);
        }
    }

    private void serveMetrics(HttpExchange exchange) throws IOException {
        respond(exchange, 200, PROMETHEUS_TYPE, prometheus);
    }

    private void serveHuman(HttpExchange exchange) throws IOException {
        respond(exchange, 200, PLAIN_TYPE, human);
    }

    private void serveHealth(HttpExchange exchange) throws IOException {
        respond(exchange, alerting ? 503 : 200, PLAIN_TYPE,
                alerting ? "DESYNC ALERT FIRING\n" : "ok\n");
    }

    private static void respond(HttpExchange exchange, int status, String type, String body)
            throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().put("Content-Type", List.of(type));
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
