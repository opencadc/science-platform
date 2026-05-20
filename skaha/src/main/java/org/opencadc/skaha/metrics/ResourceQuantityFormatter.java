package org.opencadc.skaha.metrics;

import ca.nrc.cadc.util.StringUtil;
import java.util.HashMap;
import java.util.Map;
import org.opencadc.skaha.utils.MemoryUnitConverter;

/** Formats Kubernetes resource quantities for legacy session and platform stats JSON fields. */
public final class ResourceQuantityFormatter {

    public static final String NONE = "<none>";

    private static final Map<String, Integer> CORE_DIVIDENDS = new HashMap<>();

    static {
        ResourceQuantityFormatter.CORE_DIVIDENDS.put("m", 3);
        ResourceQuantityFormatter.CORE_DIVIDENDS.put("n", 9);
    }

    private ResourceQuantityFormatter() {}

    public static String toCoreUnit(final String cores) {
        if (!StringUtil.hasLength(cores)) {
            return ResourceQuantityFormatter.NONE;
        }
        final String coreUnit = cores.substring(cores.length() - 1);
        final Integer dividend = ResourceQuantityFormatter.CORE_DIVIDENDS.get(coreUnit);
        if (dividend == null) {
            return cores;
        }
        final int coreValueWithoutUnit = Integer.parseInt(cores.substring(0, cores.length() - 1));
        final double coreValue = coreValueWithoutUnit / Math.pow(10, dividend);
        return String.format("%.3f", coreValue);
    }

    /**
     * Format memory for session listing fields ({@code memoryInUse}) as a bare decimal GB value.
     *
     * @param inK8sUnit memory string from Kubernetes (for example {@code 512Mi})
     * @return decimal gigabytes or {@link #NONE}
     */
    public static String toSessionMemoryGb(final String inK8sUnit) {
        if (!StringUtil.hasLength(inK8sUnit)) {
            return ResourceQuantityFormatter.NONE;
        }
        final long bytes = MemoryUnitConverter.toBytes(inK8sUnit);
        return String.format("%.2f", MemoryUnitConverter.toGigabytes(bytes));
    }

    static String toPlatformRamString(final String metricsMemory) {
        if (metricsMemory == null || metricsMemory.isBlank()) {
            return MemoryUnitConverter.formatHumanReadable(0L, MemoryUnitConverter.MemoryUnit.G);
        }
        final long bytes = MemoryUnitConverter.toBytes(metricsMemory.trim());
        return MemoryUnitConverter.formatHumanReadable(bytes, MemoryUnitConverter.MemoryUnit.G);
    }

    static double parseCpuCores(final String cores) {
        if (cores == null || cores.isBlank()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(cores.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
