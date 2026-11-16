package org.opencadc.skaha.metrics;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

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
                  {"name": "cpu", "total": "50", "requests": "10", "usage": "6.3", "efficiency": "0.68"},
                  {"name": "memory", "total": "200Gi", "requests": "40Gi", "usage": "21.6Gi", "efficiency": "0.55"},
                  {"name": "nvidia.com/gpu", "total": "0", "requests": "0"}
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
    public void getSessionMetricsReadsEveryResourceRow() throws Exception {
        final SessionMetrics metrics = new SessionMetricsDAO("http://127.0.0.1:" + port).getSessionMetrics(SESSION_ID);

        Assert.assertEquals(SESSION_ID, metrics.sessionId());
        Assert.assertEquals(
                List.of(
                        new SessionMetrics.Resource("cpu", "50", "10", "6.3", "0.68"),
                        new SessionMetrics.Resource("memory", "200Gi", "40Gi", "21.6Gi", "0.55"),
                        new SessionMetrics.Resource("nvidia.com/gpu", "0", "0", null, null)),
                metrics.resources());
        Assert.assertEquals("6.3", metrics.cpuUsage());
        Assert.assertEquals("21.6Gi", metrics.memoryUsage());
    }

    @Test
    public void keepsRowsWithoutUsage() throws Exception {
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

        Assert.assertEquals(new SessionMetrics.Resource("cpu", null, "2", null, null), metrics.resource("cpu"));
        Assert.assertNull(metrics.cpuUsage());
        Assert.assertEquals("1536Mi", metrics.memoryUsage());
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

    @Test
    public void manyReportsSoftFailPerSession() throws Exception {
        final ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            final SessionMetricsDAO dao = Mockito.spy(new SessionMetricsDAO("http://unused", 8, 30, executor));
            final SessionMetrics report = new SessionMetrics("session-a", List.of());
            Mockito.doReturn(report).when(dao).getSessionMetrics("session-a");
            Mockito.doThrow(new RuntimeException("backend unavailable"))
                    .when(dao)
                    .getSessionMetrics("session-b");

            Assert.assertEquals(Map.of("session-a", report), dao.getSessionMetrics(List.of("session-a", "session-b")));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void manyReportsLimitConcurrentRequests() throws Exception {
        final AtomicInteger activeRequests = new AtomicInteger();
        final AtomicInteger maxActiveRequests = new AtomicInteger();
        final ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            final SessionMetricsDAO dao = Mockito.spy(new SessionMetricsDAO("http://unused", 8, 30, executor));
            Mockito.doAnswer(invocation -> {
                        final int active = activeRequests.incrementAndGet();
                        maxActiveRequests.updateAndGet(current -> Math.max(current, active));
                        try {
                            Thread.sleep(25);
                            return new SessionMetrics(invocation.getArgument(0), List.of());
                        } finally {
                            activeRequests.decrementAndGet();
                        }
                    })
                    .when(dao)
                    .getSessionMetrics(Mockito.anyString());
            final List<String> sessionIds = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                sessionIds.add("session-" + index);
            }

            Assert.assertEquals(20, dao.getSessionMetrics(sessionIds).size());
        } finally {
            executor.shutdownNow();
        }

        Assert.assertTrue("max concurrent requests was " + maxActiveRequests.get(), maxActiveRequests.get() <= 8);
    }

    @Test
    public void manyReportsStopAtTheAggregateDeadline() throws Exception {
        final AtomicBoolean slowCompleted = new AtomicBoolean(false);
        final ExecutorService executor = Executors.newFixedThreadPool(1);
        try {
            final SessionMetricsDAO dao = Mockito.spy(new SessionMetricsDAO("http://unused", 1, 1, executor));
            Mockito.doAnswer(invocation -> {
                        try {
                            Thread.sleep(120_000);
                            slowCompleted.set(true);
                            return new SessionMetrics(invocation.getArgument(0), List.of());
                        } catch (final InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(ex);
                        }
                    })
                    .when(dao)
                    .getSessionMetrics(Mockito.startsWith("session-slow"));
            Mockito.doReturn(new SessionMetrics("session-fast", List.of()))
                    .when(dao)
                    .getSessionMetrics("session-fast");

            final long startedAt = System.currentTimeMillis();
            final Map<String, SessionMetrics> reports =
                    dao.getSessionMetrics(List.of("session-slow-1", "session-slow-2", "session-fast"));
            final long elapsedMillis = System.currentTimeMillis() - startedAt;

            Assert.assertTrue("fan-out should stop at aggregate deadline", elapsedMillis < 5_000L);
            Assert.assertFalse("slow session fetch should be cancelled before completion", slowCompleted.get());
            Mockito.verify(dao, Mockito.never()).getSessionMetrics("session-fast");
            Assert.assertTrue(reports.isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }
}
