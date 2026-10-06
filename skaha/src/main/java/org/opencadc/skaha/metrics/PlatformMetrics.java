package org.opencadc.skaha.metrics;

import io.kubernetes.client.custom.Quantity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.opencadc.skaha.utils.MemoryUnitConverter;

/**
 * Platform metrics adapted from the Metrics backend {@code GET /apis/canfar.net/v1alpha1/metrics/platform/{platform}}.
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
    private static final String GPU_RESOURCE = "nvidia.com/gpu";
    private static final BigDecimal MAX_KUBERNETES_QUANTITY = new BigDecimal("9223372036854775807");

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
            capacity = validateResourceMap(capacity, "capacity");
            allocated = validateResourceMap(allocated, "allocated");
        }
    }

    /**
     * Cluster-wide CPU, RAM, and GPU figures mapped from {@link PlatformMetrics} for legacy
     * {@code org.opencadc.skaha.session.ResourceStats} fields.
     *
     * <p>{@code cpuCoresAvailable}, {@code ramAvailable}, and {@code gpuAvailable} carry
     * <strong>platform capacity</strong>; {@code requestedCPUCores}, {@code requestedRAM}, and {@code requestedGPU}
     * carry <strong>platform allocation</strong> (legacy names retained for API compatibility). Session ceilings
     * ({@code maxCPUCores}, {@code maxRAM}, {@code maxGPU}) are populated separately in
     * {@link org.opencadc.skaha.session.GetAction#getResourceStats()}.
     */
    public record ClusterResourceFields(
            Double requestedCPUCores,
            String requestedRAM,
            Double cpuCoresAvailable,
            String ramAvailable,
            Integer requestedGPU,
            Integer gpuAvailable) {}

    /**
     * Maps platform capacity and allocation from Metrics to cluster ResourceStats fields.
     *
     * @return cluster CPU/RAM/GPU capacity and allocation (no session ceiling)
     */
    public ClusterResourceFields toClusterResourceFields() {
        final Data metricsData = data();
        return new ClusterResourceFields(
                strictCpuCores(metricsData.allocated().get(CPU_RESOURCE)),
                strictLegacyMemory(metricsData.allocated().get(MEMORY_RESOURCE)),
                strictCpuCores(metricsData.capacity().get(CPU_RESOURCE)),
                strictLegacyMemory(metricsData.capacity().get(MEMORY_RESOURCE)),
                optionalGpuCount(metricsData.allocated().get(GPU_RESOURCE)),
                optionalGpuCount(metricsData.capacity().get(GPU_RESOURCE)));
    }

    private static Map<String, String> validateResourceMap(
            final Map<String, String> resources, final String fieldName) {
        if (resources == null || !resources.containsKey(CPU_RESOURCE) || !resources.containsKey(MEMORY_RESOURCE)) {
            throw new IllegalArgumentException("Metrics " + fieldName + " must contain cpu and memory");
        }
        for (Map.Entry<String, String> entry : resources.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("Metrics resource name is invalid");
            }
            if (MEMORY_RESOURCE.equals(entry.getKey())) {
                requireMemoryBytes(entry.getValue());
            } else {
                requireQuantity(entry.getValue());
            }
        }
        return Map.copyOf(resources);
    }

    private static BigDecimal requireQuantity(final String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Metrics resource quantity is invalid");
        }
        final BigDecimal number;
        try {
            number = Quantity.fromString(raw).getNumber();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Metrics resource quantity is invalid", ex);
        }
        if (number == null || number.signum() < 0 || number.compareTo(MAX_KUBERNETES_QUANTITY) > 0) {
            throw new IllegalArgumentException("Metrics resource quantity is invalid");
        }
        return number;
    }

    private static double strictCpuCores(final String raw) {
        final BigDecimal number = requireQuantity(raw);
        final double value = number.doubleValue();
        if (!Double.isFinite(value) || (number.signum() > 0 && value == 0.0d)) {
            throw new IllegalArgumentException("Metrics CPU quantity is not representable");
        }
        return value;
    }

    private static long requireMemoryBytes(final String raw) {
        try {
            return requireQuantity(raw).longValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Metrics memory quantity is not representable", ex);
        }
    }

    private static String strictLegacyMemory(final String raw) {
        return MemoryUnitConverter.formatHumanReadable(requireMemoryBytes(raw), MemoryUnitConverter.MemoryUnit.G);
    }

    /**
     * Parse a whole GPU device count for platform stats. Missing, blank, fractional, or unparsable values become
     * {@code 0} so clusters without GPU quota still return CPU/RAM stats.
     */
    private static Integer optionalGpuCount(final String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            final BigDecimal number = Quantity.fromString(raw.trim()).getNumber();
            if (number == null || number.signum() < 0 || number.stripTrailingZeros().scale() > 0) {
                return 0;
            }
            return number.intValueExact();
        } catch (RuntimeException ex) {
            return 0;
        }
    }
}
