package org.opencadc.skaha.metrics;

import org.apache.log4j.Logger;

/**
 * Central data access for all Skaha metrics: platform stats from the Metrics backend and session pod usage (Kubernetes
 * metrics API today; Metrics backend in future).
 *
 * <p>Platform Metrics is optional: when {@link PlatformMetricsDAO#SKAHA_METRICS_BACKEND_URL} is unset (Helm
 * {@code metricsBackend.enabled=false}), session listing and pod usage still work; {@link #getPlatformMetrics()} fails
 * closed so {@code view=stats} can return 503.
 */
public class MetricsDAO {

    private static final Logger log = Logger.getLogger(MetricsDAO.class);
    private static MetricsDAO defaultInstance;

    private final PlatformMetricsDAO platformMetricsDAO;
    private final PodUsageProvider podUsageProvider;

    /**
     * Production wiring: Metrics backend for platform stats when configured; environment-selected pod-usage provider
     * always.
     */
    public MetricsDAO() {
        this(PlatformMetricsDAO.fromEnvironmentOrNull(), PodUsageProvider.fromEnvironment());
    }

    MetricsDAO(final PlatformMetricsDAO platformMetricsDAO, final PodUsageProvider podUsageProvider) {
        this.platformMetricsDAO = platformMetricsDAO;
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
    public static MetricsDAO getDefault() {
        if (defaultInstance == null) {
            defaultInstance = new MetricsDAO();
        }
        return defaultInstance;
    }

    /**
     * Fetch the current platform metrics snapshot from the Metrics backend.
     *
     * @return platform metrics from the Metrics backend
     * @throws Exception if the backend is not configured or the snapshot cannot be retrieved
     */
    public PlatformMetrics getPlatformMetrics() throws Exception {
        if (platformMetricsDAO == null) {
            throw new IllegalStateException("missing configuration: " + PlatformMetricsDAO.SKAHA_METRICS_BACKEND_URL);
        }
        return platformMetricsDAO.getPlatformMetrics();
    }

    /**
     * Fetch per-pod CPU and memory usage for session workloads, formatted for session listings.
     *
     * <p>Soft-fails: returns {@link PodResourceUsage#empty()} when pod metrics cannot be retrieved. Does not require
     * the Metrics HTTP backend.
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
