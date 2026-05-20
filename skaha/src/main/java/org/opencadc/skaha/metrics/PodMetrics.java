package org.opencadc.skaha.metrics;

import io.kubernetes.client.custom.ContainerMetrics;
import io.kubernetes.client.custom.PodMetricsList;
import io.kubernetes.client.custom.Quantity;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-pod CPU and memory usage from the Kubernetes metrics API ({@code metrics.k8s.io}), keyed by pod name.
 *
 * <p>Values are raw Kubernetes quantity strings before {@link #toPodResourceUsage(PodMetrics)} applies legacy
 * formatting.
 */
public record PodMetrics(Map<String, String> cpuByPodName, Map<String, String> memoryByPodName) {

    public PodMetrics {
        cpuByPodName = Collections.unmodifiableMap(cpuByPodName);
        memoryByPodName = Collections.unmodifiableMap(memoryByPodName);
    }

    public static PodMetrics empty() {
        return new PodMetrics(Map.of(), Map.of());
    }

    public static PodMetrics fromKubernetes(final PodMetricsList podMetricsList) {
        final Map<String, String> cpuByPodName = new HashMap<>();
        final Map<String, String> memoryByPodName = new HashMap<>();
        if (podMetricsList == null || podMetricsList.getItems() == null) {
            return new PodMetrics(cpuByPodName, memoryByPodName);
        }
        for (final io.kubernetes.client.custom.PodMetrics podMetrics : podMetricsList.getItems()) {
            if (podMetrics.getMetadata() == null || podMetrics.getMetadata().getName() == null) {
                continue;
            }
            final String podName = podMetrics.getMetadata().getName();
            final Quantity cpu = primaryContainerQuantity(podMetrics.getContainers(), "cpu");
            final Quantity memory = primaryContainerQuantity(podMetrics.getContainers(), "memory");
            if (cpu != null) {
                cpuByPodName.put(podName, cpu.toSuffixedString());
            }
            if (memory != null) {
                memoryByPodName.put(podName, memory.toSuffixedString());
            }
        }
        return new PodMetrics(cpuByPodName, memoryByPodName);
    }

    public static PodResourceUsage toPodResourceUsage(final PodMetrics podMetrics) {
        final Map<String, String> cpu = new HashMap<>();
        final Map<String, String> memory = new HashMap<>();
        for (final String podName : podMetrics.cpuByPodName().keySet()) {
            cpu.put(
                    podName,
                    ResourceQuantityFormatter.toCoreUnit(
                            podMetrics.cpuByPodName().get(podName)));
        }
        for (final String podName : podMetrics.memoryByPodName().keySet()) {
            memory.put(
                    podName,
                    ResourceQuantityFormatter.toSessionMemoryGb(
                            podMetrics.memoryByPodName().get(podName)));
        }
        return new PodResourceUsage(cpu, memory);
    }

    /**
     * Usage from the primary workload container (index 0).
     *
     * <p>Session pods are modeled with a single main container today ({@code SessionBuilder} reads container index 0).
     * Sidecars are not summed; if multi-container pods become common, aggregate usage here to match {@code kubectl top
     * pod} totals.
     */
    private static Quantity primaryContainerQuantity(final List<ContainerMetrics> containers, final String resource) {
        if (containers == null || containers.isEmpty()) {
            return null;
        }
        final Map<String, Quantity> usage = containers.getFirst().getUsage();
        if (usage == null) {
            return null;
        }
        return usage.get(resource);
    }
}
