package org.opencadc.skaha.metrics;

import ca.nrc.cadc.net.HttpGet;
import ca.nrc.cadc.util.StringUtil;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * Fetches platform metrics from the co-deployed Metrics HTTP API.
 *
 * <p>Configured via the {@value #SKAHA_METRICS_BACKEND_URL} environment variable (in-cluster base URL, without a
 * trailing slash).
 */
public class PlatformMetricsDAO {

    /** Environment variable holding the Metrics backend base URL (scheme, host, optional port). */
    public static final String SKAHA_METRICS_BACKEND_URL = "SKAHA_METRICS_BACKEND_URL";

    private static final String PLATFORM_METRICS_PATH = "/api/v1/metrics/platform";
    private static final Type STRING_MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private final Gson gson = new Gson();
    private final String platformMetricsUrl;

    /** Uses {@link #SKAHA_METRICS_BACKEND_URL} from the process environment. */
    public PlatformMetricsDAO() {
        this(System.getenv(SKAHA_METRICS_BACKEND_URL));
    }

    /** @param metricsBackendBaseUrl Metrics backend base URL (for example {@code http://skaha-metrics:8000}) */
    PlatformMetricsDAO(final String metricsBackendBaseUrl) {
        this.platformMetricsUrl = platformMetricsUrl(requireBaseUrl(metricsBackendBaseUrl));
    }

    static String requireBaseUrl(final String metricsBackendBaseUrl) {
        if (!StringUtil.hasText(metricsBackendBaseUrl)) {
            throw new IllegalStateException("missing configuration: " + SKAHA_METRICS_BACKEND_URL);
        }
        return metricsBackendBaseUrl.trim();
    }

    static String normalizeBaseUrl(final String metricsBackendBaseUrl) {
        final String trimmed = metricsBackendBaseUrl.trim();
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    static String platformMetricsUrl(final String normalizedBaseUrl) {
        return normalizeBaseUrl(normalizedBaseUrl) + PLATFORM_METRICS_PATH;
    }

    public PlatformMetrics getPlatformMetrics() throws Exception {
        final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        final HttpGet get = new HttpGet(URI.create(platformMetricsUrl).toURL(), responseBody);
        get.run();
        if (get.getThrowable() != null) {
            throw new IOException("failed to fetch platform metrics from " + platformMetricsUrl, get.getThrowable());
        }
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
        return new PlatformMetrics(new PlatformMetricsMetadata(created), new PlatformMetricsData(capacity, allocated));
    }

    private Map<String, String> parseResourceMap(final JsonObject data, final String field) {
        if (!data.has(field) || data.get(field).isJsonNull()) {
            return Collections.emptyMap();
        }
        final Map<String, String> values = gson.fromJson(data.get(field), STRING_MAP_TYPE);
        return values == null ? Collections.emptyMap() : values;
    }
}
