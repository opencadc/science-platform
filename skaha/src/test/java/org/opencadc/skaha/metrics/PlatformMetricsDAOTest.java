package org.opencadc.skaha.metrics;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class PlatformMetricsDAOTest {

    private static final String FIXTURE_JSON =
            """
            {
              "apiVersion": "canfar.net/v1alpha1",
              "kind": "Metrics",
              "metadata": {
                "name": "platform-canfar"
              },
              "spec": {
                "platform": "canfar"
              },
              "status": {
                "observedAt": "2026-03-15T12:30:00Z",
                "resources": [
                  {"name": "cpu", "capacity": "100", "allocated": "25"},
                  {"name": "memory", "capacity": "200Gi", "allocated": "50Gi"}
                ],
                "conditions": [
                  {
                    "type": "Ready",
                    "status": "True",
                    "reason": "Available",
                    "lastTransitionTime": "2026-03-15T12:30:00Z"
                  },
                  {
                    "type": "Cached",
                    "status": "True",
                    "reason": "FreshHit",
                    "lastTransitionTime": "2026-03-15T12:30:00Z"
                  }
                ]
              }
            }
            """;

    private HttpServer server;
    private int port;

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/apis/canfar.net/v1alpha1/metrics/platform/canfar", exchange -> {
            final byte[] body = FIXTURE_JSON.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(body);
            }
        });
        server.start();
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void getPlatformMetricsParsesMetricsApiEnvelope() throws Exception {
        final PlatformMetricsDAO dao = new PlatformMetricsDAO("http://127.0.0.1:" + port);

        final PlatformMetrics metrics = dao.getPlatformMetrics();

        Assert.assertEquals(
                Instant.parse("2026-03-15T12:30:00Z"), metrics.metadata().created());
        Assert.assertEquals(
                Map.of("cpu", "100", "memory", "200Gi"), metrics.data().capacity());
        Assert.assertEquals(
                Map.of("cpu", "25", "memory", "50Gi"), metrics.data().allocated());
    }

    @Test
    public void normalizesTrailingSlashOnBaseUrl() throws Exception {
        final PlatformMetricsDAO dao = new PlatformMetricsDAO("http://127.0.0.1:" + port + "/");

        final PlatformMetrics metrics = dao.getPlatformMetrics();

        Assert.assertEquals(
                Map.of("cpu", "100", "memory", "200Gi"), metrics.data().capacity());
    }

    @Test
    public void requiresNonBlankMetricsBackendUrl() {
        Assert.assertThrows(IllegalStateException.class, () -> new PlatformMetricsDAO("  "));
        Assert.assertThrows(IllegalStateException.class, () -> new PlatformMetricsDAO(null));
    }
}
