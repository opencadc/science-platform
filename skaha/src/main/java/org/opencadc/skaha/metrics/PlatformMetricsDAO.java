package org.opencadc.skaha.metrics;

import ca.nrc.cadc.net.HttpGet;
import ca.nrc.cadc.util.StringUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Fetches platform metrics from the co-deployed Metrics HTTP API.
 *
 * <p>Configured via the {@value #SKAHA_METRICS_BACKEND_URL} environment variable (in-cluster base URL, without a
 * trailing slash). When that variable is unset, use {@link #fromEnvironmentOrNull()} and treat a {@code null} result as
 * Metrics not deployed.
 */
class PlatformMetricsDAO {

    /** Environment variable holding the Metrics backend base URL (scheme, host, optional port). */
    public static final String SKAHA_METRICS_BACKEND_URL = "SKAHA_METRICS_BACKEND_URL";

    private static final String PLATFORM_METRICS_PATH = "/apis/canfar.net/v1alpha1/metrics/platform/canfar";

    private final String platformMetricsUrl;

    /**
     * Returns a platform Metrics client when {@link #SKAHA_METRICS_BACKEND_URL} is set; otherwise {@code null} so
     * callers can run without a co-deployed Metrics backend.
     */
    static PlatformMetricsDAO fromEnvironmentOrNull() {
        final String metricsBackendBaseUrl = System.getenv(SKAHA_METRICS_BACKEND_URL);
        if (!StringUtil.hasText(metricsBackendBaseUrl)) {
            return null;
        }
        return new PlatformMetricsDAO(metricsBackendBaseUrl);
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
        final JsonObject spec = root.getAsJsonObject("spec");
        final JsonObject status = root.getAsJsonObject("status");
        if (!"canfar.net/v1alpha1".equals(text(root, "apiVersion"))
                || !"Metrics".equals(text(root, "kind"))
                || spec == null
                || !"canfar".equals(text(spec, "platform"))
                || status == null) {
            throw new IllegalArgumentException("invalid Metrics envelope");
        }
        final Instant created = Instant.parse(text(status, "observedAt"));
        final Map<String, String> capacity = new HashMap<>();
        final Map<String, String> allocated = new HashMap<>();
        final JsonArray resources = status.getAsJsonArray("resources");
        if (resources == null) {
            throw new IllegalArgumentException("invalid Metrics resources");
        }
        resources.forEach(element -> {
            final JsonObject resource = element.getAsJsonObject();
            final String name = text(resource, "name");
            if (capacity.put(name, text(resource, "capacity")) != null
                    || allocated.put(name, text(resource, "allocated")) != null) {
                throw new IllegalArgumentException("duplicate Metrics resource");
            }
        });
        return new PlatformMetrics(
                new PlatformMetrics.Metadata(created), new PlatformMetrics.Data(capacity, allocated));
    }

    private static String text(final JsonObject object, final String field) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            throw new IllegalArgumentException("invalid Metrics envelope");
        }
        return object.get(field).getAsString();
    }
}
