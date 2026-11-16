package org.opencadc.skaha.metrics;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import java.util.List;

/**
 * Internal seam for per-pod session usage metrics.
 *
 * <p>Implementations read either the Kubernetes metrics API or the Metrics backend session HTTP API, selected via
 * {@link #SKAHA_POD_METRICS_SOURCE}. Backend session usage requires the listed session Jobs to map usage to job names.
 */
interface PodUsageProvider {

    /** Environment variable selecting the pod-usage source ({@code kubernetes} or {@code backend}). */
    String SKAHA_POD_METRICS_SOURCE = "SKAHA_POD_METRICS_SOURCE";

    String SOURCE_KUBERNETES = "kubernetes";
    String SOURCE_BACKEND = "backend";

    /**
     * Fetch per-pod CPU and memory usage for session workloads.
     *
     * @param userID constrain by user ID when non-null/non-blank; ignored by the backend provider
     * @param interactiveOnly when true, exclude headless and batch session pods; ignored by the backend provider
     * @param sessionJobs listed session Jobs; required for the backend provider to map session usage to job names
     */
    PodMetrics getPodMetrics(String userID, boolean interactiveOnly, List<Job> sessionJobs) throws Exception;

    /**
     * The provider {@link #SKAHA_POD_METRICS_SOURCE} selects.
     *
     * @param sessionMetricsDAO the Metrics session client, or null when Metrics is not configured
     */
    static PodUsageProvider fromEnvironment(final SessionMetricsDAO sessionMetricsDAO) {
        final String source = System.getenv(SKAHA_POD_METRICS_SOURCE);
        if (SOURCE_BACKEND.equalsIgnoreCase(source)) {
            return new MetricsBackendPodUsageProvider(sessionMetricsDAO);
        }
        return new KubernetesPodUsageProvider(new PodMetricsDAO());
    }
}
