package org.opencadc.skaha.metrics;

/**
 * Internal seam for per-pod session usage metrics.
 *
 * <p>Today the default implementation reads the Kubernetes metrics API; a future implementation will read the Metrics
 * backend when that API is available.
 */
public interface PodUsageProvider {

    /** Environment variable selecting the pod-usage source ({@code kubernetes} or {@code backend}). */
    String SKAHA_POD_METRICS_SOURCE = "SKAHA_POD_METRICS_SOURCE";

    String SOURCE_KUBERNETES = "kubernetes";
    String SOURCE_BACKEND = "backend";

    PodMetrics getPodMetrics(String userID, boolean omitHeadless) throws Exception;

    static PodUsageProvider fromEnvironment() {
        final String source = System.getenv(SKAHA_POD_METRICS_SOURCE);
        if (SOURCE_BACKEND.equalsIgnoreCase(source)) {
            return new MetricsBackendPodUsageProvider();
        }
        return new KubernetesPodUsageProvider(new PodMetricsDAO());
    }
}
