package org.opencadc.skaha.metrics;

import java.util.Collections;
import java.util.Map;

/**
 * Per-pod CPU and memory usage from the Kubernetes metrics API ({@code metrics.k8s.io}), keyed by pod name.
 *
 * <p>Values are raw Kubernetes quantity strings before {@link PodMetricsMapper} applies legacy formatting.
 */
public record PodMetrics(Map<String, String> cpuByPodName, Map<String, String> memoryByPodName) {

    public PodMetrics {
        cpuByPodName = Collections.unmodifiableMap(cpuByPodName);
        memoryByPodName = Collections.unmodifiableMap(memoryByPodName);
    }

    public static PodMetrics empty() {
        return new PodMetrics(Map.of(), Map.of());
    }
}
