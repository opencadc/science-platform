package org.opencadc.skaha.metrics;

import ca.nrc.cadc.net.HttpGet;
import ca.nrc.cadc.util.StringUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

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
     * Environment variable holding the Metrics platform path segment ({@code .../platform/{name}}). Defaults to
     * {@code canfar} when unset so GitOps can configure the platform subject without rebuilding Skaha.
     */
    public static final String SKAHA_METRICS_PLATFORM_NAME = "SKAHA_METRICS_PLATFORM_NAME";

    private static final String DEFAULT_PLATFORM_NAME = "canfar";
    private static final Pattern PLATFORM_NAME = Pattern.compile("^[A-Za-z0-9](?:[-A-Za-z0-9_.]{0,61}[A-Za-z0-9])?$");
    private static final int CONNECTION_TIMEOUT_MILLIS = 1_000;
    private static final int READ_TIMEOUT_MILLIS = 2_000;
    private static final int MAX_RETRIES = 0;
    private static final int MAX_RESPONSE_BYTES = 1_048_576;

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
        final String trimmed = metricsBackendBaseUrl.trim();
        final URI baseUrl;
        try {
            baseUrl = URI.create(trimmed);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("invalid configuration: " + SKAHA_METRICS_BACKEND_URL, ex);
        }
        final String scheme = baseUrl.getScheme();
        if ((scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)))
                || baseUrl.getHost() == null
                || baseUrl.getUserInfo() != null
                || (baseUrl.getPort() != -1 && (baseUrl.getPort() < 1 || baseUrl.getPort() > 65535))
                || (baseUrl.getPath() != null && !baseUrl.getPath().isEmpty() && !"/".equals(baseUrl.getPath()))
                || baseUrl.getRawQuery() != null
                || baseUrl.getRawFragment() != null) {
            throw new IllegalStateException("invalid configuration: " + SKAHA_METRICS_BACKEND_URL);
        }
        return trimmed;
    }

    static String requirePlatformName(final String platformName) {
        if (!StringUtil.hasText(platformName)) {
            throw new IllegalStateException("missing configuration: " + SKAHA_METRICS_PLATFORM_NAME);
        }
        final String trimmed = platformName.trim();
        if (!PLATFORM_NAME.matcher(trimmed).matches()) {
            throw new IllegalStateException("invalid configuration: " + SKAHA_METRICS_PLATFORM_NAME);
        }
        return trimmed;
    }

    static String normalizeBaseUrl(final String metricsBackendBaseUrl) {
        String normalized = metricsBackendBaseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    static String platformMetricsPath(final String platformName) {
        return "/apis/canfar.net/v1alpha1/metrics/platform/" + requirePlatformName(platformName);
    }

    static String platformMetricsUrl(final String normalizedBaseUrl, final String platformName) {
        return normalizeBaseUrl(normalizedBaseUrl) + platformMetricsPath(platformName);
    }

    public PlatformMetrics getPlatformMetrics() throws Exception {
        final BoundedOutputStream responseBody = new BoundedOutputStream(MAX_RESPONSE_BYTES);
        final HttpGet get = new HttpGet(URI.create(platformMetricsUrl).toURL(), responseBody);
        configureHttpGet(get);
        get.run();
        if (get.getThrowable() != null) {
            throw new IOException("failed to fetch platform metrics from " + platformMetricsUrl, get.getThrowable());
        }
        return parseEnvelope(responseBody.asUtf8());
    }

    /** Apply a bounded, single-attempt policy to this synchronous optional dashboard read. */
    static void configureHttpGet(final HttpGet get) {
        get.setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);
        get.setReadTimeout(READ_TIMEOUT_MILLIS);
        get.setMaxRetries(MAX_RETRIES);
    }

    /** Output stream that rejects a response before it can exceed the parser's memory budget. */
    private static final class BoundedOutputStream extends OutputStream {

        private final int maximumBytes;
        private final ByteArrayOutputStream delegate;
        private int size;

        private BoundedOutputStream(final int maximumBytes) {
            this.maximumBytes = maximumBytes;
            this.delegate = new ByteArrayOutputStream(Math.min(maximumBytes, 8192));
        }

        @Override
        public void write(final int value) throws IOException {
            ensureCapacity(1);
            delegate.write(value);
        }

        @Override
        public void write(final byte[] bytes, final int offset, final int length) throws IOException {
            if (bytes == null) {
                throw new NullPointerException("bytes");
            }
            if (offset < 0 || length < 0 || offset > bytes.length - length) {
                throw new IndexOutOfBoundsException();
            }
            ensureCapacity(length);
            delegate.write(bytes, offset, length);
        }

        private void ensureCapacity(final int additionalBytes) throws IOException {
            if (additionalBytes > maximumBytes - size) {
                throw new IOException("Metrics response exceeded the maximum size");
            }
            size += additionalBytes;
        }

        private String asUtf8() {
            return decodeUtf8(delegate.toByteArray());
        }
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
        requireReadyConditions(status, created);
        final Map<String, String> capacity = new HashMap<>();
        final Map<String, String> allocated = new HashMap<>();
        final JsonElement resourcesElement = status.get("resources");
        if (resourcesElement == null || !resourcesElement.isJsonArray()) {
            throw new IllegalArgumentException("invalid Metrics resources");
        }
        final JsonArray resources = resourcesElement.getAsJsonArray();
        if (resources.isEmpty()) {
            throw new IllegalArgumentException("invalid Metrics resources");
        }
        resources.forEach(element -> {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("invalid Metrics resource");
            }
            final JsonObject resource = element.getAsJsonObject();
            final String name = text(resource, "name");
            if (capacity.containsKey(name)) {
                throw new IllegalArgumentException("duplicate Metrics resource");
            }
            capacity.put(name, text(resource, "capacity"));
            allocated.put(name, text(resource, "allocated"));
        });
        return new PlatformMetrics(
                new PlatformMetrics.Metadata(created), new PlatformMetrics.Data(capacity, allocated));
    }

    private static void requireReadyConditions(final JsonObject status, final Instant observedAt) {
        final JsonElement conditionsElement = status.get("conditions");
        if (conditionsElement == null || !conditionsElement.isJsonArray()) {
            throw new IllegalArgumentException("invalid Metrics conditions");
        }
        int readyCount = 0;
        int cachedCount = 0;
        JsonObject ready = null;
        for (JsonElement element : conditionsElement.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("invalid Metrics conditions");
            }
            final JsonObject condition = element.getAsJsonObject();
            final String type = text(condition, "type");
            final String statusValue = text(condition, "status");
            final String reason = text(condition, "reason");
            final Instant transition = Instant.parse(text(condition, "lastTransitionTime"));
            if (transition.isAfter(observedAt) || !validConditionPair(type, statusValue, reason)) {
                throw new IllegalArgumentException("invalid Metrics conditions");
            }
            if ("Ready".equals(type)) {
                readyCount++;
                ready = condition;
            } else if ("Cached".equals(type)) {
                cachedCount++;
            } else {
                throw new IllegalArgumentException("invalid Metrics conditions");
            }
        }
        if (readyCount != 1 || cachedCount != 1 || ready == null) {
            throw new IllegalArgumentException("invalid Metrics conditions");
        }
        if (!"True".equals(text(ready, "status")) || !"Available".equals(text(ready, "reason"))) {
            throw new IllegalArgumentException("platform Metrics are not ready");
        }
    }

    private static boolean validConditionPair(final String type, final String status, final String reason) {
        return switch (type) {
            case "Ready" ->
                ("True".equals(status) && "Available".equals(reason))
                        || ("False".equals(status)
                                && ("PartialData".equals(reason)
                                        || "StaleData".equals(reason)));
            case "Cached" ->
                ("True".equals(status) && ("FreshHit".equals(reason) || "StaleHit".equals(reason)))
                        || ("False".equals(status) && "Refreshed".equals(reason))
                        || ("Unknown".equals(status) && "RedisUnavailable".equals(reason));
            default -> false;
        };
    }

    static String decodeUtf8(final byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException ex) {
            throw new IllegalArgumentException("Metrics response was not valid UTF-8", ex);
        }
    }

    private static String text(final JsonObject object, final String field) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            throw new IllegalArgumentException("invalid Metrics envelope");
        }
        final JsonElement value = object.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("invalid Metrics envelope");
        }
        final String text = value.getAsString();
        if (text.isBlank()) {
            throw new IllegalArgumentException("invalid Metrics envelope");
        }
        return text;
    }
}
