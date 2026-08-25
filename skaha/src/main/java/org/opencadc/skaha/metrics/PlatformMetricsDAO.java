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
 * trailing slash) and optional {@value #SKAHA_METRICS_PLATFORM_NAME} (default {@code canfar}). When the backend URL is
 * unset, use {@link #fromEnvironmentOrNull()} and treat a {@code null} result as Metrics not deployed.
 */
class PlatformMetricsDAO {

    /** Environment variable holding the Metrics backend base URL (scheme, host, optional port). */
    public static final String SKAHA_METRICS_BACKEND_URL = "SKAHA_METRICS_BACKEND_URL";

    /**
     * Environment variable holding the Metrics platform path segment ({@code .../platform/{name}}). Defaults to {@code
     * canfar} when unset so GitOps can rename the ClusterQueue cohort without rebuilding Skaha.
     */
    public static final String SKAHA_METRICS_PLATFORM_NAME = "SKAHA_METRICS_PLATFORM_NAME";

    private static final String DEFAULT_PLATFORM_NAME = "canfar";

    private final String platformMetricsUrl;
    private final String platformName;

    /**
     * Returns a platform Metrics client when {@link #SKAHA_METRICS_BACKEND_URL} is set; otherwise {@code null} so
     * callers can run without a co-deployed Metrics backend.
     */
    static PlatformMetricsDAO fromEnvironmentOrNull() {
        final String metricsBackendBaseUrl = System.getenv(SKAHA_METRICS_BACKEND_URL);
        if (!StringUtil.hasText(metricsBackendBaseUrl)) {
            return null;
        }
        return new PlatformMetricsDAO(metricsBackendBaseUrl, platformNameFromEnvironment());
    }

    /** @param metricsBackendBaseUrl Metrics backend base URL (for example {@code http://skaha-metrics:8000}) */
    PlatformMetricsDAO(final String metricsBackendBaseUrl) {
        this(metricsBackendBaseUrl, DEFAULT_PLATFORM_NAME);
    }

    /**
     * @param metricsBackendBaseUrl Metrics backend base URL (for example {@code http://skaha-metrics:8000})
     * @param platformName Public platform subject matching Metrics {@code METRICS_PLATFORM_NAME}
     */
    PlatformMetricsDAO(final String metricsBackendBaseUrl, final String platformName) {
        this.platformName = requirePlatformName(platformName);
        this.platformMetricsUrl = platformMetricsUrl(requireBaseUrl(metricsBackendBaseUrl), this.platformName);
    }

    static String platformNameFromEnvironment() {
        final String configured = System.getenv(SKAHA_METRICS_PLATFORM_NAME);
        if (!StringUtil.hasText(configured)) {
            return DEFAULT_PLATFORM_NAME;
        }
        return requirePlatformName(configured);
    }

    static String requireBaseUrl(final String metricsBackendBaseUrl) {
        if (!StringUtil.hasText(metricsBackendBaseUrl)) {
            throw new IllegalStateException("missing configuration: " + SKAHA_METRICS_BACKEND_URL);
        }
        return metricsBackendBaseUrl.trim();
    }

    static String requirePlatformName(final String platformName) {
        if (!StringUtil.hasText(platformName)) {
            throw new IllegalStateException("missing configuration: " + SKAHA_METRICS_PLATFORM_NAME);
        }
        final String trimmed = platformName.trim();
        if (!trimmed.matches("^[A-Za-z0-9](?:[-A-Za-z0-9_.]{0,61}[A-Za-z0-9])?$")) {
            throw new IllegalStateException("invalid configuration: " + SKAHA_METRICS_PLATFORM_NAME);
        }
        return trimmed;
    }

    static String normalizeBaseUrl(final String metricsBackendBaseUrl) {
        final String trimmed = metricsBackendBaseUrl.trim();
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    static String platformMetricsPath(final String platformName) {
        return "/apis/canfar.net/v1alpha1/metrics/platform/" + requirePlatformName(platformName);
    }

    static String platformMetricsUrl(final String normalizedBaseUrl, final String platformName) {
        return normalizeBaseUrl(normalizedBaseUrl) + platformMetricsPath(platformName);
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
                || !platformName.equals(text(spec, "platform"))
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
