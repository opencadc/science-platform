package org.opencadc.skaha.metrics;

import java.time.Instant;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class PlatformMetricsTest {
    private static final Instant SNAPSHOT_CREATED = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    public void holdsPlatformCapacityAndAllocationFromFixture() {
        final Map<String, String> capacity = Map.of("cpu", "64", "memory", "512Gi");
        final Map<String, String> allocated = Map.of("cpu", "12.5", "memory", "128Gi");

        final PlatformMetrics metrics = new PlatformMetrics(
                new PlatformMetricsMetadata(SNAPSHOT_CREATED), new PlatformMetricsData(capacity, allocated));

        Assert.assertEquals(SNAPSHOT_CREATED, metrics.metadata().created());
        Assert.assertEquals(capacity, metrics.data().capacity());
        Assert.assertEquals(allocated, metrics.data().allocated());
    }

    @Test
    public void metricsDaoContractReturnsDomainModel() throws Exception {
        final PlatformMetrics expected = new PlatformMetrics(
                new PlatformMetricsMetadata(SNAPSHOT_CREATED),
                new PlatformMetricsData(Map.of("cpu", "1"), Map.of("cpu", "0.5")));

        final MetricsDAO dao = new MetricsDAO() {
            @Override
            public PlatformMetrics getPlatformMetrics() {
                return expected;
            }

            @Override
            public PodMetrics getPodMetrics(final String userID, final boolean omitHeadless) {
                return PodMetrics.empty();
            }
        };

        Assert.assertSame(expected, dao.getPlatformMetrics());
    }
}
