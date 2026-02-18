package org.opencadc.skaha.session;

import ca.nrc.cadc.util.StringUtil;
import java.util.Objects;

/** Configuration for flexible resource requests based on session type. */
public class FlexResourceRequestConfiguration {
    public static final String FLEX_RESOURCE_REQUEST_MEMORY_ENV_VAR_TEMPLATE = "SKAHA_FLEX_RESOURCE_REQUEST_%s_MEMORY";
    public static final String FLEX_RESOURCE_REQUEST_CPU_ENV_VAR = "SKAHA_FLEX_RESOURCE_REQUEST_%s_CPU";

    private final String cpu;
    private final String memory;

    static FlexResourceRequestConfiguration fromSessionType(final String sessionType) {
        return FlexResourceRequestConfiguration.fromSessionType(sessionType, System.getenv());
    }

    static FlexResourceRequestConfiguration fromSessionType(
            final String sessionType, final java.util.Map<String, String> env) {

        Objects.requireNonNull(env, "Environment containing configuration keys must be provided.");

        if (!StringUtil.hasText(sessionType)) {
            throw new IllegalArgumentException("Session type must be provided.");
        }

        final String expectedTypeCase = sessionType.toUpperCase();

        final String cpuEnvVar = String.format(FLEX_RESOURCE_REQUEST_CPU_ENV_VAR, expectedTypeCase);
        final String memoryEnvVar = String.format(FLEX_RESOURCE_REQUEST_MEMORY_ENV_VAR_TEMPLATE, expectedTypeCase);

        final String cpu = env.get(cpuEnvVar);
        final String memory = env.get(memoryEnvVar);
        return new FlexResourceRequestConfiguration(cpu, memory);
    }

    FlexResourceRequestConfiguration(final String cpu, final String memory) {
        this.cpu = cpu;
        this.memory = memory;
    }

    /**
     * Get the CPU value, falling back to the default if not set.
     *
     * @param defaultCPU The default CPU value to use if none is set.
     * @return double representing the CPU Core count value.
     */
    public double getCPU(final double defaultCPU) {
        return StringUtil.hasText(this.cpu) ? Double.parseDouble(this.cpu) : defaultCPU;
    }

    /**
     * Get the Memory value, falling back to the default if not set.
     *
     * @param defaultMemory The default Memory value to use if none is set.
     * @return double representing the Memory value in GiB.
     */
    public double getMemory(final double defaultMemory) {
        return StringUtil.hasText(this.memory) ? Double.parseDouble(this.memory) : defaultMemory;
    }
}
