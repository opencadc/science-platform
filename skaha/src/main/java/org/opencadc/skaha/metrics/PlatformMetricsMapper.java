package org.opencadc.skaha.metrics;

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
                ResourceQuantityFormatter.parseCpuCores(data.allocated().get(CPU_RESOURCE)),
                ResourceQuantityFormatter.toPlatformRamString(data.allocated().get(MEMORY_RESOURCE)),
                ResourceQuantityFormatter.parseCpuCores(data.capacity().get(CPU_RESOURCE)),
                ResourceQuantityFormatter.toPlatformRamString(data.capacity().get(MEMORY_RESOURCE)));
    }
}
