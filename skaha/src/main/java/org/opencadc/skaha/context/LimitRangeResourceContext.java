package org.opencadc.skaha.context;

import ca.nrc.cadc.util.StringUtil;
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
public class LimitRangeResourceContext implements ResourceContext {
    private static final Logger LOGGER = Logger.getLogger(LimitRangeResourceContext.class.getName());
    private static final String LIMIT_RANGE_CPU_KEY = "cpu";
    private static final String LIMIT_RANGE_MEMORY_KEY = "memory";
    private static final String LIMIT_RANGE_GPU_KEY = ResourceContexts.NVIDIA_GPU_LABEL;
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
     * @param sessionLimitRangeName Name of the LimitRange in the Kubernetes cluster.
     * @throws Exception If there is an error retrieving the LimitRange from the cluster.
     */
    public LimitRangeResourceContext(@NotNull final String sessionLimitRangeName) throws Exception {
        this(ResourceContextDAO.getLimitRange(sessionLimitRangeName));
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

    @Override
    public void write(final OutputStream outputStream) throws IOException {
        final Writer writer = new BufferedWriter(new OutputStreamWriter(outputStream));
        final JSONWriter jsonWriter = new JSONWriter(writer);

        try {
            jsonWriter.object();

            final IntegerRange defaultCoreCounts = getDefaultCoreCounts();
            jsonWriter
                    .key(LimitRangeResourceContext.RESOURCE_KEY_MAP.get(LimitRangeResourceContext.LIMIT_RANGE_CPU_KEY))
                    .object();
            jsonWriter.key("defaultRequest").value(defaultCoreCounts.minimum);
            jsonWriter.key("defaultLimit").value(defaultCoreCounts.maximum);
            jsonWriter.key("options").array();
            defaultCoreCounts.iterator().forEachRemaining(jsonWriter::value);
            jsonWriter.endArray();
            jsonWriter.endObject();

            final IntegerRange defaultMemoryCounts = getDefaultMemoryCounts();
            jsonWriter
                    .key(LimitRangeResourceContext.RESOURCE_KEY_MAP.get(
                            LimitRangeResourceContext.LIMIT_RANGE_MEMORY_KEY))
                    .object();
            jsonWriter.key("defaultRequest").value(defaultMemoryCounts.minimum);
            jsonWriter.key("defaultLimit").value(defaultMemoryCounts.maximum);
            jsonWriter.key("options").array();
            defaultMemoryCounts.iterator().forEachRemaining(jsonWriter::value);
            jsonWriter.endArray();
            jsonWriter.endObject();

            final IntegerRange defaultGPUCounts = getDefaultCoreCounts();
            jsonWriter
                    .key(LimitRangeResourceContext.RESOURCE_KEY_MAP.get(LimitRangeResourceContext.LIMIT_RANGE_GPU_KEY))
                    .object();
            jsonWriter.key("options").array();
            defaultGPUCounts.iterator().forEachRemaining(jsonWriter::value);
            jsonWriter.endArray();
            jsonWriter.endObject();

            jsonWriter.key("maxInteractiveSessions").value(K8SUtil.getMaxUserSessions());
            jsonWriter.endObject();
            writer.flush();
        } catch (Exception e) {
            throw new IOException("Failed to get Pod limit range item for limit range.", e);
        }
    }

    @Override
    public IntegerRange getCoreCounts() {
        final String limitRangeKey = LimitRangeResourceContext.LIMIT_RANGE_CPU_KEY;
        return new IntegerRange(
                LimitRangeResourceContext.quantityAsInt(this.minimumValues, limitRangeKey),
                LimitRangeResourceContext.quantityAsInt(this.maximumValues, limitRangeKey));
    }

    @Override
    public IntegerRange getMemoryCounts() {
        final String limitRangeKey = LimitRangeResourceContext.LIMIT_RANGE_MEMORY_KEY;
        return new IntegerRange(
                LimitRangeResourceContext.quantityAsInt(this.minimumValues, limitRangeKey),
                LimitRangeResourceContext.quantityAsInt(this.maximumValues, limitRangeKey));
    }

    @Override
    public IntegerRange getGPUCounts() {
        final String limitRangeKey = LimitRangeResourceContext.LIMIT_RANGE_GPU_KEY;
        return new IntegerRange(
                LimitRangeResourceContext.quantityAsInt(this.minimumValues, limitRangeKey),
                LimitRangeResourceContext.quantityAsInt(this.maximumValues, limitRangeKey));
    }

    @Override
    public IntegerRange getDefaultCoreCounts() {
        final String limitRangeKey = LimitRangeResourceContext.LIMIT_RANGE_CPU_KEY;
        return new IntegerRange(
                LimitRangeResourceContext.quantityAsInt(this.defaultRequestValues, limitRangeKey),
                LimitRangeResourceContext.quantityAsInt(this.defaultLimitValues, limitRangeKey));
    }

    @Override
    public IntegerRange getDefaultMemoryCounts() {
        final String limitRangeKey = LimitRangeResourceContext.LIMIT_RANGE_MEMORY_KEY;
        return new IntegerRange(
                LimitRangeResourceContext.quantityAsInt(this.defaultRequestValues, limitRangeKey),
                LimitRangeResourceContext.quantityAsInt(this.defaultLimitValues, limitRangeKey));
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

    private void writeObjectFromKey(final String limitRangeKey, final JSONWriter jsonWriter) {
        final String outputKey = LimitRangeResourceContext.RESOURCE_KEY_MAP.get(limitRangeKey);
        LOGGER.debug("Writing limit range key: " + limitRangeKey + " to output key: " + outputKey);

        if (StringUtil.hasText(outputKey) && StringUtil.hasText(limitRangeKey) && hasDeclaredValue(limitRangeKey)) {
            jsonWriter.key(outputKey).object();
            if (this.defaultRequestValues.containsKey(limitRangeKey)) {
                jsonWriter
                        .key("defaultRequest")
                        .value(this.defaultRequestValues
                                .get(limitRangeKey)
                                .getNumber()
                                .intValue());
            } else {
                LOGGER.info("No default request value found for limit range key: " + limitRangeKey);
            }

            if (this.defaultLimitValues.containsKey(limitRangeKey)) {
                jsonWriter
                        .key("defaultLimit")
                        .value(this.defaultLimitValues
                                .get(limitRangeKey)
                                .getNumber()
                                .intValue());
            } else {
                LOGGER.info("No default limit value found for limit range key: " + limitRangeKey);
            }

            if (this.minimumValues.containsKey(limitRangeKey) && this.maximumValues.containsKey(limitRangeKey)) {
                jsonWriter.key("options").array();
                final int minValue =
                        this.minimumValues.get(limitRangeKey).getNumber().intValue();
                final int maxValue =
                        this.maximumValues.get(limitRangeKey).getNumber().intValue();
                for (int currVal = minValue; currVal <= maxValue; currVal++) {
                    jsonWriter.value(currVal);
                }
                jsonWriter.endArray();
            } else {
                LOGGER.warn("No min/max values found for limit range key: " + limitRangeKey);
            }

            jsonWriter.endObject();
        } else {
            LOGGER.info("Limit range key " + limitRangeKey + " not found.");
        }
    }

    private boolean hasDeclaredValue(final String limitRangeKey) {
        return this.minimumValues.containsKey(limitRangeKey)
                || this.maximumValues.containsKey(limitRangeKey)
                || this.defaultRequestValues.containsKey(limitRangeKey)
                || this.defaultLimitValues.containsKey(limitRangeKey);
    }
}
