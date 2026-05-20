package org.opencadc.skaha.metrics;

import java.time.Instant;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;
import org.opencadc.skaha.utils.MemoryUnitConverter;

public class PlatformMetricsTest {
    private static final Instant SNAPSHOT_CREATED = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    public void holdsPlatformCapacityAndAllocationFromFixture() {
        final Map<String, String> capacity = Map.of("cpu", "64", "memory", "512Gi");
        final Map<String, String> allocated = Map.of("cpu", "12.5", "memory", "128Gi");

        final PlatformMetrics metrics = new PlatformMetrics(
                new PlatformMetrics.Metadata(SNAPSHOT_CREATED), new PlatformMetrics.Data(capacity, allocated));

        Assert.assertEquals(SNAPSHOT_CREATED, metrics.metadata().created());
        Assert.assertEquals(capacity, metrics.data().capacity());
        Assert.assertEquals(allocated, metrics.data().allocated());
    }

    @Test
    public void metricsDaoContractReturnsDomainModel() throws Exception {
        final PlatformMetrics expected = new PlatformMetrics(
                new PlatformMetrics.Metadata(SNAPSHOT_CREATED),
                new PlatformMetrics.Data(Map.of("cpu", "1"), Map.of("cpu", "0.5")));

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

    @Test
    public void toClusterResourceFieldsMapsInvalidCpuStringsToZeroCores() {
        final PlatformMetrics metrics = new PlatformMetrics(
                new PlatformMetrics.Metadata(Instant.parse("2026-01-01T00:00:00Z")),
                new PlatformMetrics.Data(
                        Map.of("cpu", "not-a-number", "memory", "512Gi"), Map.of("cpu", "12.5", "memory", "128Gi")));

        final PlatformMetrics.ClusterResourceFields fields = metrics.toClusterResourceFields();

        Assert.assertEquals(0.0, fields.cpuCoresAvailable(), 0.0);
        Assert.assertEquals(12.5, fields.requestedCPUCores(), 0.0);
    }

    @Test
    public void toClusterResourceFieldsMapsPlatformCapacityAndAllocation() {
        final PlatformMetrics.ClusterResourceFields fields =
                PlatformMetricsFixtures.fixedPlatformMetrics().toClusterResourceFields();

        Assert.assertEquals(64.0, fields.cpuCoresAvailable(), 0.0);
        Assert.assertEquals(12.5, fields.requestedCPUCores(), 0.0);
        Assert.assertEquals(expectedLegacyRam("512Gi"), fields.ramAvailable());
        Assert.assertEquals(expectedLegacyRam("128Gi"), fields.requestedRAM());
    }

    private static String expectedLegacyRam(final String metricsMemory) {
        return MemoryUnitConverter.formatHumanReadable(
                MemoryUnitConverter.toBytes(metricsMemory), MemoryUnitConverter.MemoryUnit.G);
    }
}
