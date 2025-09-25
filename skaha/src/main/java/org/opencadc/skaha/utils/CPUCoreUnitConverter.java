package org.opencadc.skaha.utils;

import ca.nrc.cadc.util.StringUtil;

public class CPUCoreUnitConverter {

    /**
     * Parse the given cpu value to a unit-less double representing the number of CPU cores.
     *
     * @param cpu String representing CPU, e.g. "500m", "2", "0.5", "1000m", "250u", "1000000n"
     * @return double number of CPU cores
     */
    public static double toCoreUnit(final String cpu) {
        if (!StringUtil.hasText(cpu)) {
            throw new IllegalArgumentException("CPU string cannot be null or empty");
        }

        final String unit = cpu.replaceAll("[^a-zA-Z]", "");
        final String numberStr = cpu.replaceAll("[^0-9.]", "");

        if (!StringUtil.hasLength(numberStr)) {
            throw new NumberFormatException("Invalid CPU format: " + cpu);
        }

        // It's just cores.
        if (unit.isEmpty()) {
            return Double.parseDouble(numberStr);
        }

        final double multiplier = CPUCoreUnit.valueOf(unit).multiplier;
        final double number = Double.parseDouble(numberStr);
        return number * multiplier;
    }

    public enum CPUCoreUnit {
        n(1e-9),
        u(1e-6),
        m(1e-3),
        NONE(1.0);

        final double multiplier;

        CPUCoreUnit(double multiplier) {
            this.multiplier = multiplier;
        }
    }
}
