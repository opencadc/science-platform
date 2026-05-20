package org.opencadc.skaha.metrics;

import io.kubernetes.client.custom.ContainerMetrics;
import io.kubernetes.client.custom.PodMetricsList;
import io.kubernetes.client.custom.Quantity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Maps {@link PodMetrics} and Kubernetes pod metrics API responses to {@link PodResourceUsage}. */
public final class PodMetricsMapper {

    private PodMetricsMapper() {}

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
            final Quantity cpu = firstContainerQuantity(podMetrics.getContainers(), "cpu");
            final Quantity memory = firstContainerQuantity(podMetrics.getContainers(), "memory");
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

    private static Quantity firstContainerQuantity(final List<ContainerMetrics> containers, final String resource) {
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
