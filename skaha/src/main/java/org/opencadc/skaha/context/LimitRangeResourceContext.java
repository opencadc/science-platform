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

    private final Map<String, Quantity> minimumValues = new HashMap<>();
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
        final Map<String, Quantity> configuredMin =
                Objects.requireNonNullElse(containerLimitRange.getMin(), new HashMap<>());
        if (configuredMin.isEmpty()) {
            throw new IllegalArgumentException("Minimum values for "
                    + LimitRangeResourceContext.RESOURCE_KEY_MAP.keySet() + " must be specified in the LimitRange.");
        } else {
            this.minimumValues.putAll(configuredMin);
        }

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

    public void write(final OutputStream outputStream) throws IOException {
        final Writer writer = new BufferedWriter(new OutputStreamWriter(outputStream));
        final JSONWriter jsonWriter = new JSONWriter(writer);

        try {
            jsonWriter.object();

            final IntegerRange defaultCoreCounts = getDefaultCoreCounts();
            final IntegerRange totalCoreCounts = getTotalCoreCounts();
            jsonWriter
                    .key(LimitRangeResourceContext.RESOURCE_KEY_MAP.get(LimitRangeResourceContext.LIMIT_RANGE_CPU_KEY))
                    .object();
            jsonWriter.key("defaultRequest").value(defaultCoreCounts.minimum);
            jsonWriter.key("defaultLimit").value(defaultCoreCounts.maximum);
            jsonWriter.key("options").array();
            totalCoreCounts.iterator().forEachRemaining(jsonWriter::value);
            jsonWriter.endArray();
            jsonWriter.endObject();

            final IntegerRange defaultMemoryCounts = getDefaultMemoryCounts();
            final IntegerRange totalMemoryCounts = getTotalMemoryCounts();
            jsonWriter
                    .key(LimitRangeResourceContext.RESOURCE_KEY_MAP.get(
                            LimitRangeResourceContext.LIMIT_RANGE_MEMORY_KEY))
                    .object();
            jsonWriter.key("defaultRequest").value(defaultMemoryCounts.minimum);
            jsonWriter.key("defaultLimit").value(defaultMemoryCounts.maximum);
            jsonWriter.key("options").array();
            totalMemoryCounts.iterator().forEachRemaining(jsonWriter::value);
            jsonWriter.endArray();
            jsonWriter.endObject();

            if (hasGPULimits()) {
                jsonWriter
                        .key(LimitRangeResourceContext.RESOURCE_KEY_MAP.get(
                                LimitRangeResourceContext.LIMIT_RANGE_GPU_KEY))
                        .object();
                final IntegerRange totalGPUCounts = getTotalGPUCounts();
                jsonWriter.key("options").array();
                totalGPUCounts.iterator().forEachRemaining(jsonWriter::value);
                jsonWriter.endArray();
                jsonWriter.endObject();
            }

            jsonWriter.key("maxInteractiveSessions").value(K8SUtil.getMaxUserSessions());
            jsonWriter.endObject();
            writer.flush();
        } catch (Exception e) {
            throw new IOException("Failed to get Pod limit range item for limit range.", e);
        }
    }

    IntegerRange getTotalCoreCounts() {
        final String limitRangeKey = LimitRangeResourceContext.LIMIT_RANGE_CPU_KEY;
        return new IntegerRange(
                LimitRangeResourceContext.quantityAsInt(this.minimumValues, limitRangeKey),
                LimitRangeResourceContext.quantityAsInt(this.maximumValues, limitRangeKey));
    }

    IntegerRange getDefaultCoreCounts() {
        final String limitRangeKey = LimitRangeResourceContext.LIMIT_RANGE_CPU_KEY;
        return new IntegerRange(
                LimitRangeResourceContext.quantityAsInt(this.defaultRequestValues, limitRangeKey),
                LimitRangeResourceContext.quantityAsInt(this.defaultLimitValues, limitRangeKey));
    }

    IntegerRange getTotalMemoryCounts() {
        final String limitRangeKey = LimitRangeResourceContext.LIMIT_RANGE_MEMORY_KEY;
        return new IntegerRange(
                LimitRangeResourceContext.quantityAsInt(this.minimumValues, limitRangeKey),
                LimitRangeResourceContext.quantityAsInt(this.maximumValues, limitRangeKey));
    }

    IntegerRange getDefaultMemoryCounts() {
        final String limitRangeKey = LimitRangeResourceContext.LIMIT_RANGE_MEMORY_KEY;
        return new IntegerRange(
                LimitRangeResourceContext.quantityAsInt(this.defaultRequestValues, limitRangeKey),
                LimitRangeResourceContext.quantityAsInt(this.defaultLimitValues, limitRangeKey));
    }

    private IntegerRange getTotalGPUCounts() {
        final String limitRangeKey = LimitRangeResourceContext.LIMIT_RANGE_GPU_KEY;
        return new IntegerRange(
                LimitRangeResourceContext.quantityAsInt(this.minimumValues, limitRangeKey),
                LimitRangeResourceContext.quantityAsInt(this.maximumValues, limitRangeKey));
    }

    private boolean hasGPULimits() {
        return this.minimumValues.containsKey(LimitRangeResourceContext.LIMIT_RANGE_GPU_KEY)
                && this.maximumValues.containsKey(LimitRangeResourceContext.LIMIT_RANGE_GPU_KEY);
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
