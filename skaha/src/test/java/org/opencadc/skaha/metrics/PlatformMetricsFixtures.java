package org.opencadc.skaha.metrics;

import java.time.Instant;
import java.util.Map;

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

    /** {@link MetricsDAO} that returns {@link #fixedPlatformMetrics()} and empty pod metrics. */
    public static MetricsDAO metricsDAOWithFixedPlatformMetrics() {
        return new MetricsDAO() {
            @Override
            public PlatformMetrics getPlatformMetrics() {
                return PlatformMetricsFixtures.fixedPlatformMetrics();
            }

            @Override
            public PodMetrics getPodMetrics(final String userID, final boolean omitHeadless) {
                return PodMetrics.empty();
            }
        };
    }
}
