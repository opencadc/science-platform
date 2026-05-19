package org.opencadc.skaha.metrics;

import org.junit.Assert;
import org.junit.Test;
import org.opencadc.skaha.utils.MemoryUnitConverter;

public class PlatformMetricsMapperTest {

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
