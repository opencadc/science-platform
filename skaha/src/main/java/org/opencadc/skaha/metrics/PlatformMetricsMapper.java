package org.opencadc.skaha.metrics;

import org.opencadc.skaha.utils.MemoryUnitConverter;

/** Maps {@link PlatformMetrics} to cluster-total fields used by legacy platform stats. */
public final class PlatformMetricsMapper {
    static final String CPU_RESOURCE = "cpu";
    static final String MEMORY_RESOURCE = "memory";

    private PlatformMetricsMapper() {}

    /**
     * Maps platform capacity and allocation from Metrics to cluster ResourceStats fields.
     *
     * @param metrics platform metrics snapshot
     * @return cluster CPU/RAM capacity and allocation (no session ceiling)
     */
    public static PlatformClusterResourceFields map(final PlatformMetrics metrics) {
        final PlatformMetricsData data = metrics.data();
        return new PlatformClusterResourceFields(
                parseCpuCores(data.allocated().get(CPU_RESOURCE)),
                toLegacyRamString(data.allocated().get(MEMORY_RESOURCE)),
                parseCpuCores(data.capacity().get(CPU_RESOURCE)),
                toLegacyRamString(data.capacity().get(MEMORY_RESOURCE)));
    }

    private static double parseCpuCores(final String cores) {
        if (cores == null || cores.isBlank()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(cores.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static String toLegacyRamString(final String metricsMemory) {
        if (metricsMemory == null || metricsMemory.isBlank()) {
            return MemoryUnitConverter.formatHumanReadable(0L, MemoryUnitConverter.MemoryUnit.G);
        }
        final long bytes = MemoryUnitConverter.toBytes(metricsMemory.trim());
        return MemoryUnitConverter.formatHumanReadable(bytes, MemoryUnitConverter.MemoryUnit.G);
    }
}
