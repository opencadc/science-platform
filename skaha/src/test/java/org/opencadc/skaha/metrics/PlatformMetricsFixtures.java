package org.opencadc.skaha.metrics;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.mockito.Mockito;

/** Stable platform metrics values for unit and integration tests. */
public final class PlatformMetricsFixtures {
    public static final Instant SNAPSHOT_CREATED = Instant.parse("2026-01-01T00:00:00Z");
    public static final Map<String, String> CAPACITY = Map.of("cpu", "64", "memory", "512Gi");
    public static final Map<String, String> ALLOCATED = Map.of("cpu", "12.5", "memory", "128Gi");

    private static final PlatformMetrics FIXED = new PlatformMetrics(
            new PlatformMetrics.Metadata(SNAPSHOT_CREATED), new PlatformMetrics.Data(CAPACITY, ALLOCATED));

    private PlatformMetricsFixtures() {}

    public static PlatformMetrics fixedPlatformMetrics() {
        return FIXED;
    }

    /** {@link MetricsDAO} with no Metrics backend configured (for 503 stats tests). */
    public static MetricsDAO unconfiguredPlatformMetricsDAO() {
        return new MetricsDAO(new NoOpPlatformUsageProvider(), (userID, omitHeadless) -> PodMetrics.empty());
    }

    /** {@link MetricsDAO} that returns {@link #fixedPlatformMetrics()} and empty pod usage. */
    public static MetricsDAO metricsDAOWithFixedPlatformMetrics() throws Exception {
        final PlatformMetricsDAO platformDao = Mockito.mock(PlatformMetricsDAO.class);
        Mockito.when(platformDao.getPlatformMetrics()).thenReturn(fixedPlatformMetrics());
        return new MetricsDAO(platformDao, (userID, omitHeadless) -> PodMetrics.empty());
    }

    /** {@link MetricsDAO} whose platform metrics call fails (for 503 stats tests). */
    public static MetricsDAO failingPlatformMetricsDAO() throws Exception {
        final PlatformMetricsDAO platformDao = Mockito.mock(PlatformMetricsDAO.class);
        Mockito.when(platformDao.getPlatformMetrics()).thenThrow(new IOException("metrics backend unreachable"));
        return new MetricsDAO(platformDao, (userID, omitHeadless) -> PodMetrics.empty());
    }
}
