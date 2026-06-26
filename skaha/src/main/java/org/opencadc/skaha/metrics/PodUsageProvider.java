package org.opencadc.skaha.metrics;

/**
 * Internal seam for per-pod session usage metrics.
 *
 * <p>Today the default implementation reads the Kubernetes metrics API; a future implementation will read the Metrics
 * backend when that API is available.
 */
interface PodUsageProvider {
    PodMetrics getPodMetrics(String userID, boolean omitHeadless) throws Exception;

    static PodUsageProvider fromConfiguration(final MetricsConfiguration metricsConfiguration) {
        return metricsConfiguration.metricsBackEndUrl == null
                ? KubernetesPodUsageProvider.fromConfiguration(metricsConfiguration)
                : MetricsBackendPodUsageProvider.fromConfiguration(metricsConfiguration);
    }
}
