package org.opencadc.skaha.metrics;

import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.mockito.Mockito;

/** Stable platform metrics values for unit and integration tests. */
public final class PlatformMetricsFixtures {
    public static final Instant SNAPSHOT_CREATED = Instant.parse("2026-01-01T00:00:00Z");
    public static final Map<String, String> CAPACITY = Map.of("cpu", "64", "memory", "512Gi", "nvidia.com/gpu", "50");
    public static final Map<String, String> ALLOCATED =
            Map.of("cpu", "12.5", "memory", "128Gi", "nvidia.com/gpu", "48");

    private static final PlatformMetrics FIXED = new PlatformMetrics(
            new PlatformMetrics.Metadata(SNAPSHOT_CREATED), new PlatformMetrics.Data(CAPACITY, ALLOCATED));

    private PlatformMetricsFixtures() {}

    public static PlatformMetrics fixedPlatformMetrics() {
        return FIXED;
    }

    /** {@link MetricsDAO} with no Metrics backend configured (for 503 stats tests). */
    public static MetricsDAO unconfiguredPlatformMetricsDAO() {
        return new MetricsDAO(null, (userID, interactiveOnly, sessionJobs) -> PodMetrics.empty());
    }

    /** {@link MetricsDAO} that returns {@link #fixedPlatformMetrics()} and empty pod usage. */
    public static MetricsDAO metricsDAOWithFixedPlatformMetrics() throws Exception {
        final PlatformMetricsDAO platformDao = Mockito.mock(PlatformMetricsDAO.class);
        Mockito.when(platformDao.getPlatformMetrics()).thenReturn(fixedPlatformMetrics());
        return new MetricsDAO(platformDao, (userID, interactiveOnly, sessionJobs) -> PodMetrics.empty());
    }

    /**
     * {@link MetricsDAO} whose Metrics session reports are these, by session id; any other session's report is
     * unavailable. Pod usage is empty.
     */
    public static MetricsDAO metricsDAOWithSessionReports(final Map<String, SessionMetrics> reports) {
        final SessionMetricsDAO sessionMetricsDAO = Mockito.mock(SessionMetricsDAO.class);
        Mockito.when(sessionMetricsDAO.getSessionMetrics(Mockito.<String>anyCollection()))
                .thenAnswer(invocation -> {
                    final Collection<String> sessionIds = invocation.getArgument(0);
                    final Map<String, SessionMetrics> found = new HashMap<>(reports);
                    found.keySet().retainAll(sessionIds);
                    return found;
                });
        return new MetricsDAO(null, (userID, interactiveOnly, sessionJobs) -> PodMetrics.empty(), sessionMetricsDAO);
    }

    /** Serve {@link MetricsDAO#getDefault()} from this DAO; null restores the production instance. */
    public static void useAsDefault(final MetricsDAO metricsDAO) {
        MetricsDAO.setDefaultForTests(metricsDAO);
    }

    /** {@link MetricsDAO} whose platform metrics call fails (for 503 stats tests). */
    public static MetricsDAO failingPlatformMetricsDAO() throws Exception {
        return failingPlatformMetricsDAO(new IOException("metrics backend unreachable"));
    }

    /** {@link MetricsDAO} whose platform metrics call fails with a parser or transport error. */
    public static MetricsDAO failingPlatformMetricsDAO(final Exception failure) throws Exception {
        final PlatformMetricsDAO platformDao = Mockito.mock(PlatformMetricsDAO.class);
        Mockito.when(platformDao.getPlatformMetrics()).thenThrow(failure);
        return new MetricsDAO(platformDao, (userID, interactiveOnly, sessionJobs) -> PodMetrics.empty());
    }
}
