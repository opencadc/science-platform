package org.opencadc.skaha.utils;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import org.apache.log4j.Logger;

/** Simple memory unit converter to convert k8s memory units to bytes or GB. */
public final class MemoryUnitConverter {
    private static final Logger LOGGER = Logger.getLogger(MemoryUnitConverter.class.getName());

    private static final DecimalFormat DECIMAL_FORMAT =
            new DecimalFormat("#.###", DecimalFormatSymbols.getInstance(Locale.ENGLISH));

    /**
     * Given bytes, convert to specified unit.
     *
     * @param bytes full byte value
     * @param unit unit to convert to
     * @return double value in specified unit
     */
    public static double format(final long bytes, final MemoryUnit unit) {
        LOGGER.debug("Converting " + bytes + " bytes to " + unit.name());
        final long divider = unit.multiplier;
        return (double) bytes / divider;
    }

    /**
     * Format already converted memory count to human-readable string in specified unit.
     *
     * @param memoryCount double value in specified unit
     * @param unit unit to print out
     * @return String in format "<value><unit>", e.g. "1.5Gi"
     */
    public static String formatHumanReadable(final double memoryCount, final MemoryUnit unit) {
        LOGGER.debug("Converting already converted " + memoryCount + " mem to human readable format");
        return String.format("%s%s", DECIMAL_FORMAT.format(memoryCount), unit.name());
    }

    /**
     * Format bytes to human-readable string in specified unit.
     *
     * @param bytes full byte value
     * @param unit unit to convert to
     * @return String in format "<value><unit>", e.g. "1.5Gi"
     */
    public static String formatHumanReadable(final long bytes, final MemoryUnit unit) {
        LOGGER.debug("Converting " + bytes + " bytes to human readable format");
        return MemoryUnitConverter.formatHumanReadable(MemoryUnitConverter.format(bytes, unit), unit);
    }

    /**
     * Convert memory string with unit to bytes.
     *
     * @param memory Common memory string, e.g. "512Mi", "2Gi", "6420K", "1536M", etc.
     * @return long byte value
     */
    public static long toBytes(final String memory) {
        if (memory == null || memory.isEmpty()) {
            throw new IllegalArgumentException("Memory string cannot be null or empty");
        }

        LOGGER.debug("Converting memory string: " + memory);
        final String unit = memory.replaceAll("[^a-zA-Z]", "");
        final String numberStr = memory.replaceAll("[^0-9.]", "");

        // It's just bytes.
        if (unit.isEmpty()) {
            return Long.parseLong(numberStr);
        }

        try {
            final long multiplier = MemoryUnit.valueOf(unit).multiplier;

            final double number = Double.parseDouble(numberStr);
            return (long) (number * multiplier);
        } catch (IllegalArgumentException e) {
            LOGGER.error("Unknown memory unit: " + unit);
            try {
                return Long.parseLong(numberStr);
            } catch (NumberFormatException ex) {
                return 0L;
            }
        }
    }

    /**
     * Convenience method to convert bytes to gigabytes.
     *
     * @param bytes byte value
     * @return double gigabyte value
     */
    public static double toGigabytes(final long bytes) {
        final long divider = MemoryUnit.G.multiplier;
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
