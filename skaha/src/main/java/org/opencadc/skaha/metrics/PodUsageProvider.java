package org.opencadc.skaha.metrics;

import io.kubernetes.client.openapi.models.V1Job;
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
     * @param omitHeadless when true, exclude headless session pods; ignored by the backend provider
     * @param sessionJobs listed session Jobs; required for the backend provider to map session usage to job names
     */
    PodMetrics getPodMetrics(String userID, boolean omitHeadless, List<V1Job> sessionJobs) throws Exception;

    static PodUsageProvider fromEnvironment() {
        final String source = System.getenv(SKAHA_POD_METRICS_SOURCE);
        if (SOURCE_BACKEND.equalsIgnoreCase(source)) {
            return new MetricsBackendPodUsageProvider(SessionMetricsDAO.fromEnvironmentOrNull());
        }
        return new KubernetesPodUsageProvider(new PodMetricsDAO());
    }
}
