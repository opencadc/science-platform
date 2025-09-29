package org.opencadc.skaha.utils;

import ca.nrc.cadc.util.StringUtil;
import org.apache.log4j.Logger;

/** Simple memory unit converter to convert k8s memory units to bytes or GB. */
public final class MemoryUnitConverter {
    private static final Logger LOGGER = Logger.getLogger(MemoryUnitConverter.class.getName());

    public static long toBytes(final String memory) {
        if (memory == null || memory.isEmpty()) {
            throw new IllegalArgumentException("Memory string cannot be null or empty");
        }

        LOGGER.debug("Converting memory string: " + memory);
        final String unit = memory.replaceAll("[^a-zA-Z]", "");
        final String numberStr = memory.replaceAll("[^0-9.]", "");

        if (!StringUtil.hasLength(numberStr)) {
            throw new NumberFormatException("Invalid memory format: " + memory);
        }

        // It's just bytes.
        if (unit.isEmpty()) {
            return Long.parseLong(numberStr);
        }

        final long multiplier = MemoryUnit.valueOf(unit).multiplier;
        final double number = Double.parseDouble(numberStr);
        return (long) (number * multiplier);
    }

    public static double format(final long bytes, final MemoryUnit unit) {
        final long divider = unit.multiplier;
        return (double) bytes / divider;
    }

    public enum MemoryUnit {
        Ki(1024L),
        Mi(1024L * 1024L),
        Gi(1024L * 1024L * 1024L),
        Ti(1024L * 1024L * 1024L * 1024L),
        Pi(1024L * 1024L * 1024L * 1024L * 1024L),
        Ei(1024L * 1024L * 1024L * 1024L * 1024L * 1024L),
        k(1000L),
        K(1000L),
        M(1000L * 1000L),
        G(1000L * 1000L * 1000L),
        T(1000L * 1000L * 1000L * 1000L),
        P(1000L * 1000L * 1000L * 1000L * 1000L),
        E(1000L * 1000L * 1000L * 1000L * 1000L * 1000L);

        final long multiplier;

        MemoryUnit(long multiplier) {
            this.multiplier = multiplier;
        }
    }
}
