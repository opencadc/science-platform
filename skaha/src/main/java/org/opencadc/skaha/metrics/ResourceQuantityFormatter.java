package org.opencadc.skaha.metrics;

import ca.nrc.cadc.util.StringUtil;
import java.util.Map;
import org.opencadc.skaha.utils.MemoryUnitConverter;

/** Formats Kubernetes resource quantities for legacy session and platform stats JSON fields. */
public final class ResourceQuantityFormatter {

    public static final String NONE = "<none>";

    private static final Map<String, Integer> CORE_DIVIDENDS = Map.of("m", 3, "n", 9);

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
        try {
            final int coreValueWithoutUnit = Integer.parseInt(cores.substring(0, cores.length() - 1));
            final double coreValue = coreValueWithoutUnit / Math.pow(10, dividend);
            return String.format("%.3f", coreValue);
        } catch (NumberFormatException e) {
            return cores;
        }
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
}
