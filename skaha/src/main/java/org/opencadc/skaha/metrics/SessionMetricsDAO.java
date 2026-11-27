package org.opencadc.skaha.metrics;

import ca.nrc.cadc.util.StringUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.apache.log4j.Logger;

/**
 * Reads session reports from the co-deployed Metrics HTTP API ({@code GET
 * /apis/canfar.net/v1alpha1/metrics/session/{id}}), one at a time or many with bounded fan-out.
 *
 * <p>Configured via {@link PlatformMetricsDAO#SKAHA_METRICS_BACKEND_URL}. When unset, use
 * {@link #fromEnvironmentOrNull()} and treat a {@code null} result as Metrics not deployed.
 */
class SessionMetricsDAO {

    private static final Logger log = Logger.getLogger(SessionMetricsDAO.class);
    private static final Pattern SESSION_ID = Pattern.compile("^[A-Za-z0-9](?:[-A-Za-z0-9_.]{0,61}[A-Za-z0-9])?$");
    private static final int MAX_CONCURRENT_REQUESTS = 8;
    private static final int AGGREGATE_TIMEOUT_SECONDS = 30;
    private static final ExecutorService SESSION_METRICS_EXECUTOR =
            Executors.newFixedThreadPool(MAX_CONCURRENT_REQUESTS);

    private final String metricsBackendBaseUrl;
    private final int maxConcurrentRequests;
    private final int aggregateTimeoutSeconds;
    private final ExecutorService sessionMetricsExecutor;

    static SessionMetricsDAO fromEnvironmentOrNull() {
        final String metricsBackendBaseUrl = System.getenv(PlatformMetricsDAO.SKAHA_METRICS_BACKEND_URL);
        if (!StringUtil.hasText(metricsBackendBaseUrl)) {
            return null;
        }
        return new SessionMetricsDAO(metricsBackendBaseUrl);
    }

    SessionMetricsDAO(final String metricsBackendBaseUrl) {
        this(metricsBackendBaseUrl, MAX_CONCURRENT_REQUESTS, AGGREGATE_TIMEOUT_SECONDS, SESSION_METRICS_EXECUTOR);
    }

    SessionMetricsDAO(
            final String metricsBackendBaseUrl,
            final int maxConcurrentRequests,
            final int aggregateTimeoutSeconds,
            final ExecutorService sessionMetricsExecutor) {
        this.metricsBackendBaseUrl = PlatformMetricsDAO.requireBaseUrl(metricsBackendBaseUrl);
        this.maxConcurrentRequests = maxConcurrentRequests;
        this.aggregateTimeoutSeconds = aggregateTimeoutSeconds;
        this.sessionMetricsExecutor = sessionMetricsExecutor;
    }

    static String sessionMetricsPath(final String sessionId) {
        return "/apis/canfar.net/v1alpha1/metrics/session/" + requireSessionId(sessionId);
    }

    static String sessionMetricsUrl(final String normalizedBaseUrl, final String sessionId) {
        return PlatformMetricsDAO.normalizeBaseUrl(normalizedBaseUrl) + sessionMetricsPath(sessionId);
    }

    static String requireSessionId(final String sessionId) {
        if (!StringUtil.hasText(sessionId)) {
            throw new IllegalArgumentException("session id is required");
        }
        final String trimmed = sessionId.trim();
        if (!SESSION_ID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("invalid session id");
        }
        return trimmed;
    }

    SessionMetrics getSessionMetrics(final String sessionId) throws Exception {
        final String normalizedSessionId = requireSessionId(sessionId);
        final String sessionMetricsUrl = sessionMetricsUrl(metricsBackendBaseUrl, normalizedSessionId);
        return parseEnvelope(
                MetricsBackendHttp.fetchUtf8(URI.create(sessionMetricsUrl), "failed to fetch session metrics from"),
                normalizedSessionId);
    }

    /**
     * Read each session's report, at most {@code maxConcurrentRequests} at a time and within the aggregate deadline.
     * Soft-fails: a session whose read fails, or is still running at the deadline, is absent from the result.
     *
     * @return Reports keyed by session id.
     */
    Map<String, SessionMetrics> getSessionMetrics(final Collection<String> sessionIds) {
        final Map<String, SessionMetrics> metricsBySessionId = new ConcurrentHashMap<>();
        if (sessionIds.isEmpty()) {
            return metricsBySessionId;
        }

        final List<String> pendingSessionIds = new ArrayList<>(sessionIds);
        final int totalSessions = pendingSessionIds.size();
        final ExecutorCompletionService<Void> completionService =
                new ExecutorCompletionService<>(sessionMetricsExecutor);
        final List<Future<?>> submittedFutures = new ArrayList<>();
        final long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(aggregateTimeoutSeconds);
        int nextSessionIndex = 0;
        int completedSessions = 0;
        int inFlightSessions = 0;

        try {
            while (completedSessions < totalSessions) {
                while (inFlightSessions < maxConcurrentRequests && nextSessionIndex < totalSessions) {
                    final String sessionId = pendingSessionIds.get(nextSessionIndex++);
                    final Future<Void> future = completionService.submit(() -> {
                        fetchSessionMetrics(sessionId, metricsBySessionId);
                        return null;
                    });
                    submittedFutures.add(future);
                    inFlightSessions++;
                }

                if (inFlightSessions == 0) {
                    break;
                }

                final long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) {
                    log.warn("Session metrics fan-out reached aggregate deadline after completing " + completedSessions
                            + " of " + totalSessions + " sessions");
                    break;
                }

                final Future<Void> completedFuture;
                try {
                    completedFuture = completionService.poll(remainingNanos, TimeUnit.NANOSECONDS);
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    log.warn("Session metrics fan-out interrupted after completing " + completedSessions + " of "
                            + totalSessions + " sessions");
                    break;
                }

                if (completedFuture == null) {
                    log.warn("Session metrics fan-out timed out after completing " + completedSessions + " of "
                            + totalSessions + " sessions");
                    break;
                }

                inFlightSessions--;
                completedSessions++;
                try {
                    completedFuture.get();
                } catch (final Exception ex) {
                    if (!(ex instanceof InterruptedException)) {
                        log.debug("Session metrics task completed with failure: " + ex.getMessage());
                    }
                }
            }
        } finally {
            int cancelledSessions = 0;
            for (final Future<?> future : submittedFutures) {
                if (!future.isDone() && future.cancel(true)) {
                    cancelledSessions++;
                }
            }
            if (cancelledSessions > 0) {
                log.warn(
                        "Cancelled " + cancelledSessions + " in-flight session metrics requests at aggregate deadline");
            }
        }
        return metricsBySessionId;
    }

    private void fetchSessionMetrics(final String sessionId, final Map<String, SessionMetrics> metricsBySessionId) {
        try {
            metricsBySessionId.put(sessionId, getSessionMetrics(sessionId));
        } catch (Exception ex) {
            log.warn("Failed to fetch session metrics for " + sessionId + ": " + ex.getMessage(), ex);
        }
    }

    private SessionMetrics parseEnvelope(final String json, final String expectedSessionId) {
        final JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        final JsonObject spec = root.getAsJsonObject("spec");
        final JsonObject status = root.getAsJsonObject("status");
        if (!"canfar.net/v1alpha1".equals(MetricsBackendHttp.text(root, "apiVersion"))
                || !"Metrics".equals(MetricsBackendHttp.text(root, "kind"))
                || spec == null
                || !expectedSessionId.equals(MetricsBackendHttp.text(spec, "session"))
                || status == null) {
            throw new IllegalArgumentException("invalid Metrics envelope");
        }
        final List<SessionMetrics.Resource> resources = new ArrayList<>();
        final JsonElement resourcesElement = status.get("resources");
        if (resourcesElement != null && resourcesElement.isJsonArray()) {
            for (JsonElement element : resourcesElement.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                final JsonObject resource = element.getAsJsonObject();
                resources.add(new SessionMetrics.Resource(
                        MetricsBackendHttp.text(resource, "name"),
                        optionalText(resource, "total"),
                        optionalText(resource, "requests"),
                        optionalText(resource, "usage"),
                        optionalText(resource, "efficiency")));
            }
        }
        return new SessionMetrics(expectedSessionId, resources);
    }

    private static String optionalText(final JsonObject object, final String field) {
        return object.has(field) && !object.get(field).isJsonNull() ? MetricsBackendHttp.text(object, field) : null;
    }
}
