package org.opencadc.skaha.session;

import ca.nrc.cadc.util.StringUtil;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.*;
import org.apache.log4j.Logger;
import org.json.JSONWriter;
import org.opencadc.skaha.K8SUtil;
import org.opencadc.skaha.utils.CPUCoreUnitConverter;
import org.opencadc.skaha.utils.CommandExecutioner;
import org.opencadc.skaha.utils.KubectlCommandBuilder;
import org.opencadc.skaha.utils.MemoryUnitConverter;

/** Represents the CPU and memory resource usage of Kubernetes pods, typically used to monitor user sessions. */
public class PodResourceUsage {
    private static final Logger LOGGER = Logger.getLogger(PodResourceUsage.class);

    final Map<String, String> cpu;
    final Map<String, String> memory;

    private PodResourceUsage(final Map<String, String> cpu, final Map<String, String> memory) {
        this.cpu = Collections.unmodifiableMap(cpu);
        this.memory = Collections.unmodifiableMap(memory);
    }

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

    @Override
    public String toString() {
        final Writer stringWriter = new StringWriter();
        final JSONWriter jsonWriter = new JSONWriter(stringWriter);
        jsonWriter.array();
        cpu.keySet().forEach(podName -> {
            jsonWriter.object();
            jsonWriter.key("podName").value(podName);
            jsonWriter.key("cpuCores").value(cpu.get(podName));
            jsonWriter.key("memoryGB").value(memory.get(podName));
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
        String ret;
        if (StringUtil.hasLength(cores)) {
            try {
                final double coreValue = CPUCoreUnitConverter.toCoreUnit(cores);
                ret = String.format("%.3f", coreValue);
            } catch (NumberFormatException e) {
                ret = SessionDAO.NONE;
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
            try {
                final long bytes = MemoryUnitConverter.toBytes(inK8sUnit);
                return String.format("%.2f", MemoryUnitConverter.format(bytes, MemoryUnitConverter.MemoryUnit.G));
            } catch (NumberFormatException e) {
                ret = SessionDAO.NONE;
            }
        } else {
            ret = SessionDAO.NONE;
        }

        return ret;
    }
}
