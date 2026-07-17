package org.opencadc.skaha.metrics;

import ca.nrc.cadc.net.HttpGet;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.URL;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Fetches platform metrics from the co-deployed Metrics HTTP API.
 *
 * <p>Configured via the {@value org.opencadc.skaha.metrics.MetricsConfiguration} environment variable (in-cluster base
 * URL, without a trailing slash).
 */
class PlatformMetricsDAO implements PlatformUsageProvider {
    private static final Type STRING_MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private final Gson gson = new Gson();
    private final URL platformMetricsUrl;

    /**
     * New instance using configuration.
     *
     * @param metricsConfiguration MetricsConfiguration instance.
     * @return PlatformMetricsDAO instance. Never null.
     */
    public static PlatformMetricsDAO fromConfiguration(final MetricsConfiguration metricsConfiguration) {
        Objects.requireNonNull(metricsConfiguration, "metricsConfiguration cannot be null");
        return new PlatformMetricsDAO(metricsConfiguration.metricsBackEndUrl);
    }

    PlatformMetricsDAO(final URL platformMetricsUrl) {
        if (platformMetricsUrl == null) {
            throw new IllegalArgumentException("platformMetricsUrl cannot be null");
        }
        this.platformMetricsUrl = platformMetricsUrl;
    }

    void getFromMetricsService(final OutputStream responseBody) throws Exception {
        final HttpGet get = new HttpGet(this.platformMetricsUrl, responseBody);
        get.run();
        if (get.getThrowable() != null) {
            throw new IOException(
                    "failed to fetch platform metrics from " + this.platformMetricsUrl, get.getThrowable());
        }
    }

    public PlatformMetrics getPlatformMetrics() throws Exception {
        final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        getFromMetricsService(responseBody);
        return parseEnvelope(responseBody.toString());
    }

    private PlatformMetrics parseEnvelope(final String json) {
        final JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        final JsonObject metadata = root.getAsJsonObject("metadata");
        final JsonObject data = root.getAsJsonObject("data");
        if (metadata == null || data == null) {
            throw new IllegalArgumentException("invalid PlatformMetrics envelope");
        }
        final Instant created = Instant.parse(metadata.get("created").getAsString());
        final Map<String, String> capacity = parseResourceMap(data, "capacity");
        final Map<String, String> allocated = parseResourceMap(data, "allocated");
        return new PlatformMetrics(
                new PlatformMetrics.Metadata(created), new PlatformMetrics.Data(capacity, allocated));
    }

    private Map<String, String> parseResourceMap(final JsonObject data, final String field) {
        if (!data.has(field) || data.get(field).isJsonNull()) {
            return Collections.emptyMap();
        }
        final Map<String, String> values = gson.fromJson(data.get(field), STRING_MAP_TYPE);
        return values == null ? Collections.emptyMap() : values;
    }
}
