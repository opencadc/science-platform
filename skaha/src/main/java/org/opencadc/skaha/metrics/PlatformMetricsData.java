package org.opencadc.skaha.metrics;

import java.util.Map;

/**
 * Platform metrics payload from the Metrics API {@code data} object.
 *
 * @param capacity platform capacity from Metrics {@code data.capacity}
 * @param allocated platform allocation from Metrics {@code data.allocated}
 */
public record PlatformMetricsData(Map<String, String> capacity, Map<String, String> allocated) {

    /** Platform capacity: cluster-wide nominal quota keyed by Kubernetes resource name. */
    public PlatformMetricsData {
        capacity = Map.copyOf(capacity);
        allocated = Map.copyOf(allocated);
    }
}
