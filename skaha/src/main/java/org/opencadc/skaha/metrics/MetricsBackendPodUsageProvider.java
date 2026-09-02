package org.opencadc.skaha.metrics;

import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.log4j.Logger;

/**
 * Pod usage from the Metrics backend session HTTP API.
 *
 * <p>Fetches {@code GET /apis/canfar.net/v1alpha1/metrics/session/{id}} per distinct session after Jobs are listed.
 */
final class MetricsBackendPodUsageProvider implements PodUsageProvider {

    private static final Logger log = Logger.getLogger(MetricsBackendPodUsageProvider.class);
    private static final int MAX_CONCURRENT_REQUESTS = 8;
    private static final int AGGREGATE_TIMEOUT_SECONDS = 30;
    private static final ExecutorService SESSION_METRICS_EXECUTOR =
            Executors.newFixedThreadPool(MAX_CONCURRENT_REQUESTS);

    private final SessionMetricsDAO sessionMetricsDAO;
    private final int maxConcurrentRequests;
    private final int aggregateTimeoutSeconds;
    private final ExecutorService sessionMetricsExecutor;

    MetricsBackendPodUsageProvider(final SessionMetricsDAO sessionMetricsDAO) {
        this(sessionMetricsDAO, MAX_CONCURRENT_REQUESTS, AGGREGATE_TIMEOUT_SECONDS, SESSION_METRICS_EXECUTOR);
    }

    MetricsBackendPodUsageProvider(
            final SessionMetricsDAO sessionMetricsDAO,
            final int maxConcurrentRequests,
            final int aggregateTimeoutSeconds,
            final ExecutorService sessionMetricsExecutor) {
        this.sessionMetricsDAO = sessionMetricsDAO;
        this.maxConcurrentRequests = maxConcurrentRequests;
        this.aggregateTimeoutSeconds = aggregateTimeoutSeconds;
        this.sessionMetricsExecutor = sessionMetricsExecutor;
    }

    @Override
    public PodMetrics getPodMetrics(final String userID, final boolean omitHeadless, final List<V1Job> sessionJobs) {
        if (sessionMetricsDAO == null || sessionJobs == null || sessionJobs.isEmpty()) {
            return PodMetrics.empty();
        }

        final Map<String, List<String>> sessionIdToJobNames = sessionIdsByJobName(sessionJobs);
        if (sessionIdToJobNames.isEmpty()) {
            return PodMetrics.empty();
        }

        final Map<String, SessionMetrics> metricsBySessionId = new ConcurrentHashMap<>();
        fetchSessionMetricsBounded(sessionIdToJobNames.keySet(), metricsBySessionId);

        final Map<String, String> cpuByJobName = new HashMap<>();
        final Map<String, String> memoryByJobName = new HashMap<>();
        for (final Map.Entry<String, List<String>> entry : sessionIdToJobNames.entrySet()) {
            final SessionMetrics sessionMetrics = metricsBySessionId.get(entry.getKey());
            if (sessionMetrics == null) {
                continue;
            }
            final String cpuUsage = sessionMetrics.cpuUsage();
            final String memoryUsage = sessionMetrics.memoryUsage();
            if (cpuUsage == null && memoryUsage == null) {
                continue;
            }
            for (final String jobName : entry.getValue()) {
                if (cpuUsage != null) {
                    cpuByJobName.put(jobName, cpuUsage);
                }
                if (memoryUsage != null) {
                    memoryByJobName.put(jobName, memoryUsage);
                }
            }
        }
        return new PodMetrics(cpuByJobName, memoryByJobName);
    }

    private void fetchSessionMetricsBounded(
            final Collection<String> sessionIds, final Map<String, SessionMetrics> metricsBySessionId) {
        if (sessionIds.isEmpty()) {
            return;
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
    }

    private void fetchSessionMetrics(final String sessionId, final Map<String, SessionMetrics> metricsBySessionId) {
        try {
            metricsBySessionId.put(sessionId, sessionMetricsDAO.getSessionMetrics(sessionId));
        } catch (Exception ex) {
            log.warn("Failed to fetch session metrics for " + sessionId + ": " + ex.getMessage(), ex);
        }
    }

    private static Map<String, List<String>> sessionIdsByJobName(final List<V1Job> jobs) {
        final Map<String, List<String>> sessionIdToJobNames = new LinkedHashMap<>();
        for (final V1Job job : jobs) {
            if (job == null || job.getMetadata() == null) {
                continue;
            }
            final V1ObjectMeta metadata = job.getMetadata();
            final Map<String, String> labels = metadata.getLabels();
            if (labels == null || metadata.getName() == null) {
                continue;
            }
            final String sessionId = labels.get("canfar.net/id");
            if (sessionId == null || sessionId.isBlank()) {
                continue;
            }
            sessionIdToJobNames
                    .computeIfAbsent(sessionId, ignored -> new ArrayList<>())
                    .add(metadata.getName());
        }
        return sessionIdToJobNames;
    }
}
