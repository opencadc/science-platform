package org.opencadc.skaha.metrics;

import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.log4j.Logger;

/**
 * Pod usage from the Metrics backend session HTTP API.
 *
 * <p>Fetches {@code GET /apis/canfar.net/v1alpha1/metrics/session/{id}} per distinct session after Jobs are listed.
 */
final class MetricsBackendPodUsageProvider implements PodUsageProvider {

    private static final Logger log = Logger.getLogger(MetricsBackendPodUsageProvider.class);
    private static final int MAX_CONCURRENT_REQUESTS = 8;
    private static final ExecutorService SESSION_METRICS_EXECUTOR =
            Executors.newFixedThreadPool(MAX_CONCURRENT_REQUESTS);

    private final SessionMetricsDAO sessionMetricsDAO;

    MetricsBackendPodUsageProvider() {
        this(SessionMetricsDAO.fromEnvironmentOrNull());
    }

    MetricsBackendPodUsageProvider(final SessionMetricsDAO sessionMetricsDAO) {
        this.sessionMetricsDAO = sessionMetricsDAO;
    }

    @Override
    public PodMetrics getPodMetrics(final String userID, final boolean omitHeadless) {
        return PodMetrics.empty();
    }

    PodMetrics getPodMetricsForJobs(final List<V1Job> jobs) {
        if (sessionMetricsDAO == null || jobs == null || jobs.isEmpty()) {
            return PodMetrics.empty();
        }

        final Map<String, List<String>> sessionIdToJobNames = sessionIdsByJobName(jobs);
        if (sessionIdToJobNames.isEmpty()) {
            return PodMetrics.empty();
        }

        final Map<String, SessionMetrics> metricsBySessionId = new ConcurrentHashMap<>();
        final CompletableFuture<?>[] requests = sessionIdToJobNames.keySet().stream()
                .map(sessionId -> CompletableFuture.runAsync(
                        () -> fetchSessionMetrics(sessionId, metricsBySessionId), SESSION_METRICS_EXECUTOR))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(requests).join();

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
