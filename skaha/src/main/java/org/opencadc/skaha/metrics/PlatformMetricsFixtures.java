package org.opencadc.skaha.metrics;

import java.time.Instant;
import java.util.Map;

/**
 * Stable platform metrics values for {@link DummyMetricsDAO} and tests.
 *
 * <p>Documented in test resources at {@code dummy-platform-metrics-fixture.properties}.
 */
public final class PlatformMetricsFixtures {
    public static final Instant SNAPSHOT_CREATED = Instant.parse("2026-01-01T00:00:00Z");
    public static final Map<String, String> CAPACITY = Map.of("cpu", "64", "memory", "512Gi");
    public static final Map<String, String> ALLOCATED = Map.of("cpu", "12.5", "memory", "128Gi");

    private static final PlatformMetrics FIXED = new PlatformMetrics(
            new PlatformMetricsMetadata(SNAPSHOT_CREATED), new PlatformMetricsData(CAPACITY, ALLOCATED));

    private PlatformMetricsFixtures() {}

    public static PlatformMetrics fixedPlatformMetrics() {
        return FIXED;
    }
}
