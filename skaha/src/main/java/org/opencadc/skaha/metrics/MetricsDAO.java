package org.opencadc.skaha.metrics;

import org.apache.log4j.Logger;

/**
 * Central data access for all Skaha metrics: platform stats from the Metrics backend and session pod usage (Kubernetes
 * metrics API today; Metrics backend in future).
 */
public class MetricsDAO {

    private static final Logger log = Logger.getLogger(MetricsDAO.class);
    private static MetricsDAO defaultInstance;

    private final PlatformUsageProvider platformUsageProvider;
    private final PodUsageProvider podUsageProvider;

    /** Production wiring: Metrics backend for platform stats, environment-selected pod-usage provider. */
    public static MetricsDAO fromConfiguration(final MetricsConfiguration metricsConfiguration) {
        return new MetricsDAO(
                PlatformUsageProvider.fromConfiguration(metricsConfiguration),
                PodUsageProvider.fromConfiguration(metricsConfiguration));
    }

    MetricsDAO(final PlatformUsageProvider platformUsageProvider, final PodUsageProvider podUsageProvider) {
        this.platformUsageProvider = platformUsageProvider;
        this.podUsageProvider = podUsageProvider;
    }

    /** @visibleForTesting */
    static void resetDefaultForTests() {
        defaultInstance = null;
    }

    /** @visibleForTesting */
    static void setDefaultForTests(final MetricsDAO metricsDAO) {
        defaultInstance = metricsDAO;
    }

    /** Shared production instance for session handlers. */
    public static MetricsDAO getDefault() throws Exception {
        if (defaultInstance == null) {
            defaultInstance = MetricsDAO.fromConfiguration(MetricsConfiguration.fromEnv());
        }
        return defaultInstance;
    }

    /**
     * Fetch the current platform metrics snapshot from the Metrics backend.
     *
     * @return platform metrics from the Metrics backend
     * @throws Exception if the snapshot cannot be retrieved
     * @throws UnsupportedOperationException If no platform metrics are implemented
     */
    public PlatformMetrics getPlatformMetrics() throws Exception {
        return platformUsageProvider.getPlatformMetrics();
    }

    /**
     * Fetch per-pod CPU and memory usage for session workloads, formatted for session listings.
     *
     * <p>Soft-fails: returns {@link PodResourceUsage#empty()} when pod metrics cannot be retrieved.
     *
     * @param userID constrain by user ID when non-null/non-blank; otherwise all users
     * @param omitHeadless when true, exclude headless session pods
     * @return session-list pod usage DTO
     */
    public PodResourceUsage getPodResourceUsage(final String userID, final boolean omitHeadless) {
        try {
            return PodMetrics.toPodResourceUsage(podUsageProvider.getPodMetrics(userID, omitHeadless));
        } catch (Exception e) {
            log.warn("Failed to fetch pod metrics for sessions: " + e.getMessage(), e);
            return PodResourceUsage.empty();
        }
    }
}
