package org.opencadc.skaha.metrics;

import ca.nrc.cadc.util.StringUtil;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

public class PlatformMetricsDAOTest {

    private static final String FIXTURE_JSON =
            """
            {
              "version": "metrics.canfar.net/v1",
              "kind": "PlatformMetrics",
              "metadata": {
                "created": "2026-03-15T12:30:00Z"
              },
              "status": "Success",
              "data": {
                "scope": "platform",
                "cluster": "test-cluster",
                "capacity": {
                  "cpu": "100",
                  "memory": "200Gi"
                },
                "allocated": {
                  "cpu": "25",
                  "memory": "50Gi"
                }
              }
            }
            """;

    private HttpServer server;
    private int port;

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/api/v1/metrics/platform", exchange -> {
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
    public void fromEnvironmentOrNullReturnsNullWhenUrlUnset() {
        Assume.assumeFalse(
                "SKAHA_METRICS_BACKEND_URL must be unset for this test",
                StringUtil.hasText(System.getenv(PlatformMetricsDAO.SKAHA_METRICS_BACKEND_URL)));

        Assert.assertNull(PlatformMetricsDAO.fromEnvironmentOrNull());
    }

    @Test
    public void requiresNonBlankMetricsBackendUrl() {
        Assert.assertThrows(IllegalStateException.class, () -> new PlatformMetricsDAO("  "));
        Assert.assertThrows(IllegalStateException.class, () -> new PlatformMetricsDAO(null));
    }
}
