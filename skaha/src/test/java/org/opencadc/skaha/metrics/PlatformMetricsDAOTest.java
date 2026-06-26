package org.opencadc.skaha.metrics;

import ca.nrc.cadc.util.FileUtil;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.After;
import org.junit.Assert;
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
        final PlatformMetricsDAO dao =
                new PlatformMetricsDAO(URI.create("http://127.0.0.1:" + port).toURL()) {
                    @Override
                    void getFromMetricsService(OutputStream responseBody) throws Exception {
                        PlatformMetricsDAOTest.writeJSON("getPlatformMetricsParsesMetricsApiEnvelope", responseBody);
                    }
                };

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
        final PlatformMetricsDAO dao = new PlatformMetricsDAO(
                URI.create("http://127.0.0.1:" + port + "/").toURL()) {
            @Override
            void getFromMetricsService(OutputStream responseBody) throws Exception {
                PlatformMetricsDAOTest.writeJSON("normalizesTrailingSlashOnBaseUrl", responseBody);
            }
        };

        final PlatformMetrics metrics = dao.getPlatformMetrics();

        Assert.assertEquals(
                Map.of("cpu", "100", "memory", "200Gi"), metrics.data().capacity());
    }

    @Test
    public void requiresNonBlankMetricsBackendUrl() {
        Assert.assertThrows(IllegalArgumentException.class, () -> new PlatformMetricsDAO(null));
    }

    static void writeJSON(final String methodName, final OutputStream responseBody) throws IOException {
        final File file = FileUtil.getFileFromResource(
                PlatformMetricsDAOTest.class.getSimpleName() + "." + methodName + ".json",
                PlatformMetricsDAOTest.class);
        try (final FileReader reader = new FileReader(file)) {
            final JSONObject json = new JSONObject(new JSONTokener(reader));
            responseBody.write(json.toString().getBytes(StandardCharsets.UTF_8));
            responseBody.flush();
        }
    }
}
