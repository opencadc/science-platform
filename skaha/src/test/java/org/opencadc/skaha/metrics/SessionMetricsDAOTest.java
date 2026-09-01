package org.opencadc.skaha.metrics;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class SessionMetricsDAOTest {

    private static final String SESSION_ID = "abc123";
    private static final String FIXTURE_JSON =
            """
            {
              "apiVersion": "canfar.net/v1alpha1",
              "kind": "Metrics",
              "metadata": {
                "name": "session-abc123"
              },
              "spec": {
                "session": "abc123"
              },
              "status": {
                "observedAt": "2026-03-15T12:30:00Z",
                "reservingWorkloads": 1,
                "resources": [
                  {"name": "cpu", "requests": "2", "usage": "1367m", "efficiency": "0.68"},
                  {"name": "memory", "requests": "4Gi", "usage": "1536Mi", "efficiency": "0.38"}
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

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/apis/canfar.net/v1alpha1/metrics/session/" + SESSION_ID, exchange -> {
            final byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
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
    public void getSessionMetricsParsesUsageFields() throws Exception {
        final SessionMetrics metrics = new SessionMetricsDAO("http://127.0.0.1:" + port).getSessionMetrics(SESSION_ID);

        Assert.assertEquals(SESSION_ID, metrics.sessionId());
        Assert.assertEquals(
                Map.of("cpu", "1367m", "memory", "1536Mi"), metrics.usageByResource());
    }

    @Test
    public void ignoresEfficiencyAndMissingUsage() throws Exception {
        responseBody =
                """
                {
                  "apiVersion": "canfar.net/v1alpha1",
                  "kind": "Metrics",
                  "metadata": {"name": "session-abc123"},
                  "spec": {"session": "abc123"},
                  "status": {
                    "observedAt": "2026-03-15T12:30:00Z",
                    "reservingWorkloads": 1,
                    "resources": [
                      {"name": "cpu", "requests": "2"},
                      {"name": "memory", "requests": "4Gi", "usage": "1536Mi"}
                    ],
                    "conditions": []
                  }
                }
                """;

        final SessionMetrics metrics = new SessionMetricsDAO("http://127.0.0.1:" + port).getSessionMetrics(SESSION_ID);

        Assert.assertEquals(Map.of("memory", "1536Mi"), metrics.usageByResource());
    }

    @Test
    public void sessionMetricsUrlBuildsExpectedPath() {
        Assert.assertEquals(
                "http://metrics:8000/apis/canfar.net/v1alpha1/metrics/session/abc123",
                SessionMetricsDAO.sessionMetricsUrl("http://metrics:8000", "abc123"));
    }

    @Test
    public void rejectsInvalidSessionId() {
        Assert.assertThrows(IllegalArgumentException.class, () -> SessionMetricsDAO.requireSessionId("../escape"));
    }
}
