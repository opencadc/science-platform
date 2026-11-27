package org.opencadc.skaha.metrics;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pod usage from the Metrics backend session HTTP API.
 *
 * <p>Fetches {@code GET /apis/canfar.net/v1alpha1/metrics/session/{id}} per distinct session after Jobs are listed.
 */
final class MetricsBackendPodUsageProvider implements PodUsageProvider {

    private final SessionMetricsDAO sessionMetricsDAO;

    MetricsBackendPodUsageProvider(final SessionMetricsDAO sessionMetricsDAO) {
        this.sessionMetricsDAO = sessionMetricsDAO;
    }

    @Override
    public PodMetrics getPodMetrics(final String userID, final boolean interactiveOnly, final List<Job> sessionJobs) {
        if (sessionMetricsDAO == null || sessionJobs == null || sessionJobs.isEmpty()) {
            return PodMetrics.empty();
        }

        final Map<String, List<String>> sessionIdToJobNames = sessionIdsByJobName(sessionJobs);
        if (sessionIdToJobNames.isEmpty()) {
            return PodMetrics.empty();
        }

        final Map<String, SessionMetrics> metricsBySessionId =
                sessionMetricsDAO.getSessionMetrics(sessionIdToJobNames.keySet());

        final Map<String, String> cpuByJobName = new HashMap<>();
        final Map<String, String> memoryByJobName = new HashMap<>();
        for (final Map.Entry<String, List<String>> entry : sessionIdToJobNames.entrySet()) {
            final SessionMetrics sessionMetrics = metricsBySessionId.get(entry.getKey());
            if (sessionMetrics == null) {
                continue;
            }
            final String cpuUsage = sessionMetrics.cpuUsage();
            final String memoryUsage = sessionMetrics.memoryUsage();
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

    private static Map<String, List<String>> sessionIdsByJobName(final List<Job> jobs) {
        final Map<String, List<String>> sessionIdToJobNames = new LinkedHashMap<>();
        for (final Job job : jobs) {
            if (job == null || job.getMetadata() == null) {
                continue;
            }
            final ObjectMeta metadata = job.getMetadata();
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
