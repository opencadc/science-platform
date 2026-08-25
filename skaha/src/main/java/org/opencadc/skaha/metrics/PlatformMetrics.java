package org.opencadc.skaha.metrics;

import java.time.Instant;
import java.util.Map;

/**
 * Platform metrics adapted from the Metrics backend
 * {@code GET /apis/canfar.net/v1alpha1/metrics/platform/{platform}}.
 *
 * <p>Combines snapshot metadata with {@linkplain Data platform capacity} and {@linkplain Data platform allocation}
 * resource maps. The platform path segment is configured with {@link PlatformMetricsDAO#SKAHA_METRICS_PLATFORM_NAME}
 * (default {@code canfar}).
 *
 * @param metadata snapshot metadata adapted from {@code status.observedAt}
 * @param data platform capacity and allocation adapted from {@code status.resources}
 */
public record PlatformMetrics(Metadata metadata, Data data) {

    private static final String CPU_RESOURCE = "cpu";
    private static final String MEMORY_RESOURCE = "memory";

    /**
     * Metrics response metadata for a platform metrics snapshot.
     *
     * @param created snapshot time ({@code status.observedAt} from the Metrics API)
     */
    public record Metadata(Instant created) {}

    /**
     * Platform metrics payload adapted from the Metrics API resource list.
     *
     * @param capacity platform capacity keyed from Metrics {@code status.resources}
     * @param allocated platform allocation keyed from Metrics {@code status.resources}
     */
    public record Data(Map<String, String> capacity, Map<String, String> allocated) {

        /** Platform capacity: cluster-wide nominal quota keyed by Kubernetes resource name. */
        public Data {
            capacity = Map.copyOf(capacity);
            allocated = Map.copyOf(allocated);
        }
    }

    /**
     * Cluster-wide CPU and RAM figures mapped from {@link PlatformMetrics} for legacy
     * {@code org.opencadc.skaha.session.ResourceStats} fields.
     *
     * <p>{@code cpuCoresAvailable} and {@code ramAvailable} carry <strong>platform capacity</strong>;
     * {@code requestedCPUCores} and {@code requestedRAM} carry <strong>platform allocation</strong> (legacy names
     * retained for API compatibility). Session ceiling ({@code maxCPUCores}, {@code maxRAM}) is populated separately in
     * {@link org.opencadc.skaha.session.GetAction#getResourceStats()}.
     */
    public record ClusterResourceFields(
            Double requestedCPUCores, String requestedRAM, Double cpuCoresAvailable, String ramAvailable) {}

    /**
     * Maps platform capacity and allocation from Metrics to cluster ResourceStats fields.
     *
     * @return cluster CPU/RAM capacity and allocation (no session ceiling)
     */
    public ClusterResourceFields toClusterResourceFields() {
        final Data metricsData = data();
        return new ClusterResourceFields(
                ResourceQuantityFormatter.parseCpuCores(metricsData.allocated().get(CPU_RESOURCE)),
                ResourceQuantityFormatter.toPlatformRamString(
                        metricsData.allocated().get(MEMORY_RESOURCE)),
                ResourceQuantityFormatter.parseCpuCores(metricsData.capacity().get(CPU_RESOURCE)),
                ResourceQuantityFormatter.toPlatformRamString(
                        metricsData.capacity().get(MEMORY_RESOURCE)));
    }
}
