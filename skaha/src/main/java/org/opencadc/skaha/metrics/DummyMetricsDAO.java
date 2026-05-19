package org.opencadc.skaha.metrics;

/** {@link MetricsDAO} that returns a fixed platform metrics snapshot without calling the Metrics backend. */
public class DummyMetricsDAO implements MetricsDAO {

    @Override
    public PlatformMetrics getPlatformMetrics() {
        return PlatformMetricsFixtures.fixedPlatformMetrics();
    }
}
