package org.opencadc.skaha.metrics;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class PlatformMetricsDAOTest {

    private static final String ONE_BYTE_IN_GIB = "0.000000000931322574615478515625Gi";
    private static final String ONE_GIB_PLUS_ONE_BYTE = "1.000000000931322574615478515625Gi";

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
    private String responseBody = FIXTURE_JSON;
    private boolean stallResponse;
    private final AtomicInteger requestCount = new AtomicInteger();

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/apis/canfar.net/v1alpha1/metrics/platform/canfar", exchange -> {
            requestCount.incrementAndGet();
            if (stallResponse) {
                exchange.sendResponseHeaders(200, 1);
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            final byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
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
    public void ignoresOptionalReservingWorkloadsAndEfficiencyFields() throws Exception {
        final var envelope = JsonParser.parseString(FIXTURE_JSON).getAsJsonObject();
        final var status = envelope.getAsJsonObject("status");
        status.addProperty("reservingWorkloads", 3);
        status.getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("efficiency", "0.75");
        status.getAsJsonArray("resources").get(1).getAsJsonObject().addProperty("efficiency", "0.50");
        responseBody = envelope.toString();

        final PlatformMetrics metrics = new PlatformMetricsDAO("http://127.0.0.1:" + port).getPlatformMetrics();

        Assert.assertEquals(
                Map.of("cpu", "100", "memory", "200Gi"), metrics.data().capacity());
        Assert.assertEquals(
                Map.of("cpu", "25", "memory", "50Gi"), metrics.data().allocated());
        Assert.assertEquals(100.0, metrics.toClusterResourceFields().cpuCoresAvailable(), 0.0);
        Assert.assertEquals(25.0, metrics.toClusterResourceFields().requestedCPUCores(), 0.0);
        Assert.assertEquals("214.748G", metrics.toClusterResourceFields().ramAvailable());
        Assert.assertEquals("53.687G", metrics.toClusterResourceFields().requestedRAM());
    }

    @Test
    public void rejectsPartialReadyCondition() {
        assertRejectsConditions(
                """
                [{"type":"Ready","status":"False","reason":"PartialData","lastTransitionTime":"2026-03-15T12:30:00Z"},
                 {"type":"Cached","status":"False","reason":"Refreshed","lastTransitionTime":"2026-03-15T12:30:00Z"}]
                """);
    }

    @Test
    public void rejectsStaleReadyCondition() {
        assertRejectsConditions(
                """
                [{"type":"Ready","status":"False","reason":"StaleData","lastTransitionTime":"2026-03-15T12:30:00Z"},
                 {"type":"Cached","status":"True","reason":"StaleHit","lastTransitionTime":"2026-03-15T12:30:00Z"}]
                """);
    }

    @Test
    public void rejectsMissingDuplicateAndMalformedConditions() {
        assertRejectsConditions("[]");
        assertRejectsConditions(
                """
                [{"type":"Ready","status":"True","reason":"Available","lastTransitionTime":"2026-03-15T12:30:00Z"},
                 {"type":"Ready","status":"True","reason":"Available","lastTransitionTime":"2026-03-15T12:30:00Z"}]
                """);
        assertRejectsConditions(
                """
                [{"type":"Ready","status":"True","lastTransitionTime":"2026-03-15T12:30:00Z"},
                 {"type":"Cached","status":"False","reason":"Refreshed","lastTransitionTime":"2026-03-15T12:30:00Z"}]
                """);
    }

    @Test
    public void rejectsInvalidCachedConditionPairsAndFutureTransitions() {
        assertRejectsConditions(
                """
                [{"type":"Ready","status":"True","reason":"Available","lastTransitionTime":"2026-03-15T12:30:00Z"},
                 {"type":"Cached","status":"True","reason":"Refreshed","lastTransitionTime":"2026-03-15T12:30:00Z"}]
                """);
        assertRejectsConditions(
                """
                [{"type":"Ready","status":"True","reason":"Available","lastTransitionTime":"2026-03-15T12:30:01Z"},
                 {"type":"Cached","status":"True","reason":"FreshHit","lastTransitionTime":"2026-03-15T12:30:00Z"}]
                """);
    }

    @Test
    public void rejectsMalformedUtf8ThroughDecoderSeam() {
        Assert.assertThrows(
                IllegalArgumentException.class,
                () -> PlatformMetricsDAO.decodeUtf8(new byte[] {(byte) 0xC3, (byte) 0x28}));
    }

    @Test
    public void normalizesTrailingSlashOnBaseUrl() throws Exception {
        final PlatformMetricsDAO dao = new PlatformMetricsDAO("http://127.0.0.1:" + port + "/");

        final PlatformMetrics metrics = dao.getPlatformMetrics();

        Assert.assertEquals(
                Map.of("cpu", "100", "memory", "200Gi"), metrics.data().capacity());
    }

    @Test
    public void usesConfiguredPlatformNameInMetricsPath() {
        Assert.assertEquals(
                "http://metrics:8000/apis/canfar.net/v1alpha1/metrics/platform/neutron",
                PlatformMetricsDAO.platformMetricsUrl("http://metrics:8000/", " neutron "));
    }

    @Test
    public void requiresNonBlankMetricsBackendUrl() {
        Assert.assertThrows(IllegalStateException.class, () -> new PlatformMetricsDAO("  "));
        Assert.assertThrows(IllegalStateException.class, () -> new PlatformMetricsDAO(null));
    }

    @Test
    public void requiresHttpBackendUrl() {
        Assert.assertThrows(IllegalStateException.class, () -> new PlatformMetricsDAO("file:///tmp/metrics"));
        Assert.assertThrows(IllegalStateException.class, () -> new PlatformMetricsDAO("metrics.internal:8000"));
        Assert.assertThrows(IllegalStateException.class, () -> new PlatformMetricsDAO("http://metrics:8000/?unsafe=1"));
        Assert.assertThrows(IllegalStateException.class, () -> new PlatformMetricsDAO("http://user:pass@metrics:8000"));
        Assert.assertThrows(IllegalStateException.class, () -> new PlatformMetricsDAO("http://metrics:0"));
        Assert.assertThrows(IllegalStateException.class, () -> new PlatformMetricsDAO("http://metrics:65536"));
        Assert.assertThrows(IllegalStateException.class, () -> new PlatformMetricsDAO("http://metrics:8000/metrics"));
    }

    @Test(timeout = 5_000)
    public void stalledResponseUsesBoundedTimeoutWithoutRetries() {
        stallResponse = true;
        final PlatformMetricsDAO dao = new PlatformMetricsDAO("http://127.0.0.1:" + port);

        Assert.assertThrows(IOException.class, dao::getPlatformMetrics);
        Assert.assertEquals(1, requestCount.get());
    }

    @Test
    public void rejectsEmptyResourceSnapshot() {
        responseBody =
                """
                {"apiVersion":"canfar.net/v1alpha1","kind":"Metrics","spec":{"platform":"canfar"},
                 "status":{"observedAt":"2026-03-15T12:30:00Z","resources":[]}}
                """;
        final PlatformMetricsDAO dao = new PlatformMetricsDAO("http://127.0.0.1:" + port);

        Assert.assertThrows(IllegalArgumentException.class, dao::getPlatformMetrics);
    }

    @Test
    public void rejectsNonStringResourceValues() {
        responseBody =
                """
                {"apiVersion":"canfar.net/v1alpha1","kind":"Metrics","spec":{"platform":"canfar"},
                 "status":{"observedAt":"2026-03-15T12:30:00Z","resources":[{"name":"cpu","capacity":100,"allocated":"25"}]}}
                """;
        final PlatformMetricsDAO dao = new PlatformMetricsDAO("http://127.0.0.1:" + port);

        Assert.assertThrows(IllegalArgumentException.class, dao::getPlatformMetrics);
    }

    @Test
    public void rejectsMissingCpuOrMemoryFromEitherResourceMap() {
        setResources("[{\"name\":\"memory\",\"capacity\":\"1Gi\",\"allocated\":\"1Gi\"}]");
        final PlatformMetricsDAO missingCpu = new PlatformMetricsDAO("http://127.0.0.1:" + port);
        Assert.assertThrows(IllegalArgumentException.class, missingCpu::getPlatformMetrics);

        setResources("[{\"name\":\"cpu\",\"capacity\":\"1\",\"allocated\":\"1\"}]");
        final PlatformMetricsDAO missingMemory = new PlatformMetricsDAO("http://127.0.0.1:" + port);
        Assert.assertThrows(IllegalArgumentException.class, missingMemory::getPlatformMetrics);
    }

    @Test
    public void rejectsInvalidMetricsResourceQuantities() {
        for (String value : new String[] {"NaN", "Inf", "-1", "not-a-quantity", "1e9999"}) {
            setResourceValues(value, "1", "1Gi", "1Gi");
            final PlatformMetricsDAO dao = new PlatformMetricsDAO("http://127.0.0.1:" + port);

            Assert.assertThrows(value, IllegalArgumentException.class, dao::getPlatformMetrics);
        }
    }

    @Test
    public void rejectsInvalidOrNonRepresentableMetricsMemoryQuantities() {
        for (String value :
                new String[] {"NaN", "Inf", "-1", "not-a-quantity", "9223372036854775808", "1n", "1.23456789Gi"}) {
            setResourceValues("1", "1", value, "1Gi");
            final PlatformMetricsDAO dao = new PlatformMetricsDAO("http://127.0.0.1:" + port);

            Assert.assertThrows(value, IllegalArgumentException.class, dao::getPlatformMetrics);
        }
    }

    @Test
    public void parsesValidTinyCpuAndHighPrecisionWholeByteMemoryQuantities() throws Exception {
        setResourceValues("1n", "1.23456789", ONE_BYTE_IN_GIB, ONE_GIB_PLUS_ONE_BYTE);
        final PlatformMetrics metrics = new PlatformMetricsDAO("http://127.0.0.1:" + port).getPlatformMetrics();

        Assert.assertEquals(1e-9, metrics.toClusterResourceFields().cpuCoresAvailable(), 0.0);
        Assert.assertEquals("0G", metrics.toClusterResourceFields().ramAvailable());
        Assert.assertEquals("1.074G", metrics.toClusterResourceFields().requestedRAM());
    }

    @Test
    public void rejectsResponsesAboveTheBoundedBodyLimitWithoutLeakingBody() {
        final String marker = "untrusted-response-marker";
        responseBody = marker + "x".repeat(1_048_576);
        final PlatformMetricsDAO dao = new PlatformMetricsDAO("http://127.0.0.1:" + port);

        final IOException error = Assert.assertThrows(IOException.class, dao::getPlatformMetrics);

        Assert.assertFalse(error.getMessage().contains(marker));
        Assert.assertFalse(error.toString().contains(responseBody));
    }

    private void assertRejectsConditions(final String conditions) {
        final var envelope = JsonParser.parseString(FIXTURE_JSON).getAsJsonObject();
        envelope.getAsJsonObject("status").add("conditions", JsonParser.parseString(conditions));
        responseBody = envelope.toString();
        final PlatformMetricsDAO dao = new PlatformMetricsDAO("http://127.0.0.1:" + port);

        Assert.assertThrows(IllegalArgumentException.class, dao::getPlatformMetrics);
    }

    private void setResources(final String resources) {
        final var envelope = JsonParser.parseString(FIXTURE_JSON).getAsJsonObject();
        envelope.getAsJsonObject("status").add("resources", JsonParser.parseString(resources));
        responseBody = envelope.toString();
    }

    private void setResourceValues(
            final String cpuCapacity,
            final String cpuAllocated,
            final String memoryCapacity,
            final String memoryAllocated) {
        final var envelope = JsonParser.parseString(FIXTURE_JSON).getAsJsonObject();
        final var resources = envelope.getAsJsonObject("status").getAsJsonArray("resources");
        resources.get(0).getAsJsonObject().addProperty("capacity", cpuCapacity);
        resources.get(0).getAsJsonObject().addProperty("allocated", cpuAllocated);
        resources.get(1).getAsJsonObject().addProperty("capacity", memoryCapacity);
        resources.get(1).getAsJsonObject().addProperty("allocated", memoryAllocated);
        responseBody = envelope.toString();
    }
}
