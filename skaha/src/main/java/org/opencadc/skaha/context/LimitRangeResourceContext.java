package org.opencadc.skaha.context;

import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.models.V1LimitRangeItem;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.json.JSONWriter;
import org.opencadc.skaha.K8SUtil;
import org.opencadc.skaha.utils.MemoryUnitConverter;

/** Writes the resource limits from a Kubernetes LimitRange to an output stream in JSON format. */
public class LimitRangeResourceContext {
    private static final Logger LOGGER = Logger.getLogger(LimitRangeResourceContext.class.getName());
    private static final String LIMIT_RANGE_CPU_KEY = "cpu";
    private static final String LIMIT_RANGE_MEMORY_KEY = "memory";
    private static final String LIMIT_RANGE_GPU_KEY = "nvidia.com/gpu";
    private static final Map<String, String> RESOURCE_KEY_MAP = Map.of(
            LimitRangeResourceContext.LIMIT_RANGE_CPU_KEY,
            "cores",
            LimitRangeResourceContext.LIMIT_RANGE_MEMORY_KEY,
            "memoryGB",
            LimitRangeResourceContext.LIMIT_RANGE_GPU_KEY,
            "gpus");

    private final Map<String, Quantity> maximumValues = new HashMap<>();
    private final Map<String, Quantity> defaultRequestValues = new HashMap<>();
    private final Map<String, Quantity> defaultLimitValues = new HashMap<>();

    /**
     * Constructor that initializes the resource context from a Kubernetes LimitRange object.
     *
     * @throws Exception If there is an error retrieving the LimitRange from the cluster.
     */
    public LimitRangeResourceContext() throws Exception {
        this(ResourceContextDAO.lookupLimitRange());
    }

    /**
     * Constructor to accept a V1LimitRangeItem. Mainly for testing.
     *
     * @param containerLimitRange A configured LimitRangeItem from Kubernetes.
     */
    LimitRangeResourceContext(@NotNull final V1LimitRangeItem containerLimitRange) {
        LOGGER.debug("Initializing LimitRangeResourceContext with " + containerLimitRange);
        final Map<String, Quantity> configuredMax =
                Objects.requireNonNullElse(containerLimitRange.getMax(), new HashMap<>());
        if (configuredMax.isEmpty()) {
            throw new IllegalArgumentException("Maximum values for "
                    + LimitRangeResourceContext.RESOURCE_KEY_MAP.keySet() + " must be specified in the LimitRange.");
        } else {
            this.maximumValues.putAll(configuredMax);
        }

        final Map<String, Quantity> configuredDefaultRequest =
                Objects.requireNonNullElse(containerLimitRange.getDefaultRequest(), new HashMap<>());
        if (configuredDefaultRequest.isEmpty()) {
            throw new IllegalArgumentException("Default request values must be specified in the LimitRange.");
        } else {
            this.defaultRequestValues.putAll(configuredDefaultRequest);
        }

        final Map<String, Quantity> configuredDefaultLimit =
                Objects.requireNonNullElse(containerLimitRange.getDefault(), new HashMap<>());
        if (configuredDefaultLimit.isEmpty()) {
            throw new IllegalArgumentException("Default limit values must be specified in the LimitRange.");
        } else {
            this.defaultLimitValues.putAll(configuredDefaultLimit);
        }
    }

    private void writeResourceValues(
            final JSONWriter jsonWriter,
            final String key,
            final IntegerRange resourceRange,
            final IntegerRange totalCounts) {
        LOGGER.debug("Writing LimitRangeResourceContext with " + key + " to " + resourceRange);
        jsonWriter.key(LimitRangeResourceContext.RESOURCE_KEY_MAP.get(key)).object();
        jsonWriter.key("default").value(resourceRange.minimum);
        jsonWriter.key("defaultRequest").value(resourceRange.minimum);
        jsonWriter.key("defaultLimit").value(resourceRange.maximum);

        jsonWriter.key("options").array();
        totalCounts.iterator().forEachRemaining(jsonWriter::value);
        jsonWriter.endArray();
        jsonWriter.endObject();
    }

    /**
     * Write out a JSON representation of this LimitRange.
     *
     * @param outputStream The OutputStream to write to.
     * @throws IOException For any issues writing the output.
     */
    public void write(final OutputStream outputStream) throws IOException {
        final Writer writer = new BufferedWriter(new OutputStreamWriter(outputStream));
        final JSONWriter jsonWriter = new JSONWriter(writer);

        try {
            jsonWriter.object();

            final IntegerRange defaultCoreCounts = getDefaultCoreCounts();
            final IntegerRange totalCoreCounts = getTotalCoreCounts();
            writeResourceValues(
                    jsonWriter, LimitRangeResourceContext.LIMIT_RANGE_CPU_KEY, defaultCoreCounts, totalCoreCounts);

            final IntegerRange defaultMemoryCounts = getDefaultMemoryCounts();
            final IntegerRange totalMemoryCounts = getTotalMemoryCounts();
            writeResourceValues(
                    jsonWriter,
                    LimitRangeResourceContext.LIMIT_RANGE_MEMORY_KEY,
                    defaultMemoryCounts,
                    totalMemoryCounts);

            // GPUs will be an empty array if no GPU limits are defined in the LimitRange.
            jsonWriter
                    .key(LimitRangeResourceContext.RESOURCE_KEY_MAP.get(LimitRangeResourceContext.LIMIT_RANGE_GPU_KEY))
                    .object();
            jsonWriter.key("options").array();

            if (hasGPULimits()) {
                final IntegerRange totalGPUCounts = getTotalGPUCounts();
                totalGPUCounts.iterator().forEachRemaining(jsonWriter::value);
            }

            jsonWriter.endArray();
            jsonWriter.endObject();
            // End GPU resource output

            jsonWriter.key("maxInteractiveSessions").value(K8SUtil.getMaxUserSessions());
            jsonWriter.endObject();
            writer.flush();
        } catch (Exception e) {
            throw new IOException("Failed to get Pod limit range item for limit range.", e);
        }
    }

    IntegerRange getTotalCoreCounts() {
        return new IntegerRange(
                1,
                LimitRangeResourceContext.quantityAsInt(
                        this.maximumValues, LimitRangeResourceContext.LIMIT_RANGE_CPU_KEY));
    }

    IntegerRange getDefaultCoreCounts() {
        final String limitRangeKey = LimitRangeResourceContext.LIMIT_RANGE_CPU_KEY;
        return new IntegerRange(
                LimitRangeResourceContext.quantityAsInt(this.defaultRequestValues, limitRangeKey),
                LimitRangeResourceContext.quantityAsInt(this.defaultLimitValues, limitRangeKey));
    }

    IntegerRange getTotalMemoryCounts() {
        return new IntegerRange(
                1,
                LimitRangeResourceContext.quantityAsInt(
                        this.maximumValues, LimitRangeResourceContext.LIMIT_RANGE_MEMORY_KEY));
    }

    IntegerRange getDefaultMemoryCounts() {
        final String limitRangeKey = LimitRangeResourceContext.LIMIT_RANGE_MEMORY_KEY;
        return new IntegerRange(
                LimitRangeResourceContext.quantityAsInt(this.defaultRequestValues, limitRangeKey),
                LimitRangeResourceContext.quantityAsInt(this.defaultLimitValues, limitRangeKey));
    }

    private IntegerRange getTotalGPUCounts() {
        return new IntegerRange(
                1,
                LimitRangeResourceContext.quantityAsInt(
                        this.maximumValues, LimitRangeResourceContext.LIMIT_RANGE_GPU_KEY));
    }

    private boolean hasGPULimits() {
        return this.maximumValues.containsKey(LimitRangeResourceContext.LIMIT_RANGE_GPU_KEY);
    }

    private static int quantityAsInt(final Map<String, Quantity> map, final String limitRangeKey) {
        if (map.containsKey(limitRangeKey)) {
            final BigDecimal limitRangeValue = map.get(limitRangeKey).getNumber();
            if (limitRangeKey.equals(LimitRangeResourceContext.LIMIT_RANGE_MEMORY_KEY)) {
                return (int) MemoryUnitConverter.format(limitRangeValue.longValue(), MemoryUnitConverter.MemoryUnit.Gi);
            } else {
                return limitRangeValue.intValue();
            }
        }
        throw new IllegalArgumentException(limitRangeKey + " is not a valid limit range key.");
    }
}
