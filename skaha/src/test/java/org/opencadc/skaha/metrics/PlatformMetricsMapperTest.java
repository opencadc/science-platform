package org.opencadc.skaha.metrics;

import java.time.Instant;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;
import org.opencadc.skaha.utils.MemoryUnitConverter;

public class PlatformMetricsMapperTest {

    @Test
    public void mapsInvalidCpuStringsToZeroCores() {
        final PlatformMetrics metrics = new PlatformMetrics(
                new PlatformMetricsMetadata(Instant.parse("2026-01-01T00:00:00Z")),
                new PlatformMetricsData(
                        Map.of("cpu", "not-a-number", "memory", "512Gi"), Map.of("cpu", "12.5", "memory", "128Gi")));

        final PlatformClusterResourceFields fields = PlatformMetricsMapper.map(metrics);

        Assert.assertEquals(0.0, fields.cpuCoresAvailable(), 0.0);
        Assert.assertEquals(12.5, fields.requestedCPUCores(), 0.0);
    }

    @Test
    public void mapsPlatformCapacityAndAllocationToClusterResourceFields() {
        final PlatformClusterResourceFields fields =
                PlatformMetricsMapper.map(PlatformMetricsFixtures.fixedPlatformMetrics());

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
