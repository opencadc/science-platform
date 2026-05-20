package org.opencadc.skaha.metrics;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Collections;
import java.util.Map;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.json.JSONWriter;

/**
 * Legacy per-pod CPU and memory usage for session listings, keyed by pod name.
 *
 * <p>Produced by {@link PodMetricsMapper} from {@link PodMetrics}; field values match the historical session API.
 */
public record PodResourceUsage(Map<String, String> cpu, Map<String, String> memory) {
    private static final Logger LOGGER = Logger.getLogger(PodResourceUsage.class);

    public PodResourceUsage(final Map<String, String> cpu, final Map<String, String> memory) {
        this.cpu = Collections.unmodifiableMap(cpu);
        this.memory = Collections.unmodifiableMap(memory);
    }

    public static PodResourceUsage empty() {
        return new PodResourceUsage(Map.of(), Map.of());
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
}
