package org.opencadc.skaha.session;

import ca.nrc.cadc.util.StringUtil;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.*;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.json.JSONWriter;
import org.opencadc.skaha.K8SUtil;
import org.opencadc.skaha.utils.CommandExecutioner;
import org.opencadc.skaha.utils.KubectlCommandBuilder;
import org.opencadc.skaha.utils.MemoryUnitConverter;

public record PodResourceUsage(Map<String, String> cpu, Map<String, String> memory) {
    private static final Logger LOGGER = Logger.getLogger(PodResourceUsage.class);

    private static final Map<String, Integer> CORE_DIVIDENDS = new HashMap<>();

    static {
        PodResourceUsage.CORE_DIVIDENDS.put("m", 3);
        PodResourceUsage.CORE_DIVIDENDS.put("n", 9);
    }

    public PodResourceUsage(final Map<String, String> cpu, final Map<String, String> memory) {
        this.cpu = Collections.unmodifiableMap(cpu);
        this.memory = Collections.unmodifiableMap(memory);
    }

    /**
     * Convenience method to return pod resource usage for all pods.
     *
     * @return PodResourceUsage object containing CPU and memory usage. Never null.
     * @throws Exception For any execution or parsing errors.
     */
    public static PodResourceUsage getAll() throws Exception {
        return PodResourceUsage.get(null, false);
    }

    /**
     * Return a pod resource usage object containing CPU and memory usage, mapped by Pod name.
     *
     * @param userID Constrain by user ID if given, otherwise return for all users.
     * @param omitHeadless Constrain to omit headless sessions if true, otherwise include all session types.
     * @return PodResourceUsage object containing CPU (Cores) and memory (GB) usage. Never null.
     * @throws Exception For any execution or parsing errors.
     */
    public static PodResourceUsage get(final String userID, final boolean omitHeadless) throws Exception {
        final Map<String, String> cpuMetrics = new HashMap<>();
        final Map<String, String> memoryMetrics = new HashMap<>();
        final List<String> labelSelectors = new ArrayList<>();

        if (StringUtil.hasLength(userID)) {
            labelSelectors.add("canfar-net-userid=" + userID);
        }

        if (omitHeadless) {
            labelSelectors.add("canfar-net-sessionType!=" + SessionAction.SESSION_TYPE_HEADLESS);
        }

        final String[] topCommand = KubectlCommandBuilder.command("top")
                .namespace(K8SUtil.getWorkloadNamespace())
                .noHeaders()
                .pod()
                .label(String.join(",", labelSelectors))
                .build();

        SessionDAO.LOGGER.debug("Resource usage command: " + String.join(" ", topCommand));
        final String sessionResourceUsageMap = CommandExecutioner.execute(topCommand);
        SessionDAO.LOGGER.debug("Resource usage command output: " + sessionResourceUsageMap);
        if (StringUtil.hasLength(sessionResourceUsageMap)) {
            final String[] lines = sessionResourceUsageMap.split("\n");
            for (final String line : lines) {
                final String[] resourceUsage =
                        line.trim().replaceAll("\\s+", " ").split(" ");
                final String fullName = resourceUsage[0];

                cpuMetrics.put(fullName, PodResourceUsage.toCoreUnit(resourceUsage[1]));
                memoryMetrics.put(fullName, PodResourceUsage.toCommonMemoryUnit(resourceUsage[2]));
            }
        }

        return new PodResourceUsage(cpuMetrics, memoryMetrics);
    }

    @NotNull @Override
    public String toString() {
        final Writer stringWriter = new StringWriter();
        final JSONWriter jsonWriter = new JSONWriter(stringWriter);
        jsonWriter.array();
        cpu.keySet().forEach(podName -> {
            jsonWriter.object();
            jsonWriter.key("podName").value(podName);
            jsonWriter.key("cpuCores").value(cpu.get(podName));
            jsonWriter.key("memoryBytes").value(memory.get(podName));
            jsonWriter.endObject();
        });
        jsonWriter.endArray();

        try {
            stringWriter.flush();
        } catch (IOException e) {
            LOGGER.error("failed to flush string writer: " + e.getMessage(), e);
        }
        return stringWriter.toString();
    }

    static String toCoreUnit(String cores) {
        final String ret;
        if (StringUtil.hasLength(cores)) {
            final String coreUnit = cores.substring(cores.length() - 1);
            final Integer dividend = PodResourceUsage.CORE_DIVIDENDS.get(coreUnit);
            if (dividend == null) {
                // use value as is, can be '<none>' or some value
                ret = cores;
            } else {
                // in specified unit, covert to cores
                int coreValueWithoutUnit = Integer.parseInt(cores.substring(0, cores.length() - 1));
                final double coreValue = coreValueWithoutUnit / Math.pow(10, dividend);
                ret = String.format("%.3f", coreValue);
            }
        } else {
            ret = SessionDAO.NONE;
        }

        return ret;
    }

    /**
     * Ensure the given unit is converted to GB to keep the output consistent.
     *
     * @param inK8sUnit The memory unit as reported by k8s, e.g. 512Mi, 2Gi, etc.
     * @return String representation of the memory in GB, or <none> if the input is null or empty.
     */
    static String toCommonMemoryUnit(String inK8sUnit) {
        final String ret;
        if (StringUtil.hasLength(inK8sUnit)) {
            final long bytes = MemoryUnitConverter.toBytes(inK8sUnit);
            return String.format("%.2f", MemoryUnitConverter.toGigabytes(bytes));
        } else {
            ret = SessionDAO.NONE;
        }

        return ret;
    }
}
