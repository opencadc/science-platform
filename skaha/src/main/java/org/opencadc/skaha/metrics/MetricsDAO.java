package org.opencadc.skaha.metrics;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.log4j.Logger;

/**
 * Central data access for all Skaha metrics: platform stats and session reports from the Metrics backend, and session
 * pod usage (Kubernetes metrics API or Metrics backend session API, selected via
 * {@link PodUsageProvider#SKAHA_POD_METRICS_SOURCE}).
 *
 * <p>The Skaha Helm chart always co-deploys Metrics and injects {@link PlatformMetricsDAO#SKAHA_METRICS_BACKEND_URL}.
 * When that variable is unset outside the chart, session listing and pod usage still work;
 * {@link #getPlatformMetrics()} fails closed so {@code view=stats} can return 503.
 */
public class MetricsDAO {

    private static final Logger log = Logger.getLogger(MetricsDAO.class);
    private static MetricsDAO defaultInstance;

    private final PlatformMetricsDAO platformMetricsDAO;
    private final PodUsageProvider podUsageProvider;
    private final SessionMetricsDAO sessionMetricsDAO;

    /**
     * Production wiring: Metrics backend for platform stats and session reports when configured; environment-selected
     * pod-usage provider always.
     */
    public MetricsDAO() {
        this(SessionMetricsDAO.fromEnvironmentOrNull());
    }

    private MetricsDAO(final SessionMetricsDAO sessionMetricsDAO) {
        this(
                PlatformMetricsDAO.fromEnvironmentOrNull(),
                PodUsageProvider.fromEnvironment(sessionMetricsDAO),
                sessionMetricsDAO);
    }

    MetricsDAO(final PlatformMetricsDAO platformMetricsDAO, final PodUsageProvider podUsageProvider) {
        this(platformMetricsDAO, podUsageProvider, null);
    }

    MetricsDAO(
            final PlatformMetricsDAO platformMetricsDAO,
            final PodUsageProvider podUsageProvider,
            final SessionMetricsDAO sessionMetricsDAO) {
        this.platformMetricsDAO = platformMetricsDAO;
        this.podUsageProvider = podUsageProvider;
        this.sessionMetricsDAO = sessionMetricsDAO;
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
     * Fetch the sessions' reports from the Metrics backend with bounded fan-out, whatever the pod-usage source.
     *
     * <p>Soft-fails: a session whose report cannot be read is absent, and every session is absent when the backend is
     * not configured.
     *
     * @return Reports keyed by session id.
     */
    public Map<String, SessionMetrics> getSessionMetrics(final Collection<String> sessionIds) {
        if (sessionMetricsDAO == null) {
            return Map.of();
        }
        return sessionMetricsDAO.getSessionMetrics(sessionIds);
    }

    /**
     * Fetch per-pod CPU and memory usage for session workloads, formatted for session listings.
     *
     * <p>Soft-fails: returns {@link PodResourceUsage#empty()} when pod metrics cannot be retrieved. Does not require
     * the Metrics HTTP backend when the Kubernetes pod-usage source is selected.
     *
     * @param userID constrain by user ID when non-null/non-blank; otherwise all users
     * @param interactiveOnly when true, exclude headless and batch session pods
     * @return session-list pod usage DTO
     */
    public PodResourceUsage getPodResourceUsage(final String userID, final boolean interactiveOnly) {
        return getPodResourceUsage(userID, interactiveOnly, List.of());
    }

    /**
     * Fetch session-list pod usage via the configured pod-usage provider.
     *
     * @param userJobs listed session Jobs; required for the Metrics backend pod-usage source
     */
    public PodResourceUsage getPodResourceUsage(
            final String userID, final boolean interactiveOnly, final List<Job> userJobs) {
        try {
            return PodMetrics.toPodResourceUsage(podUsageProvider.getPodMetrics(userID, interactiveOnly, userJobs));
        } catch (Exception e) {
            log.warn("Failed to fetch pod metrics for sessions: " + e.getMessage(), e);
            return PodResourceUsage.empty();
        }
    }
}
