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
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Fetches per-session usage from the co-deployed Metrics HTTP API.
 *
 * <p>Configured via {@link PlatformMetricsDAO#SKAHA_METRICS_BACKEND_URL}. When unset, use
 * {@link #fromEnvironmentOrNull()} and treat a {@code null} result as Metrics not deployed.
 */
class SessionMetricsDAO {

    private static final Pattern SESSION_ID = Pattern.compile("^[A-Za-z0-9](?:[-A-Za-z0-9_.]{0,61}[A-Za-z0-9])?$");
    private static final int CONNECTION_TIMEOUT_MILLIS = 1_000;
    private static final int READ_TIMEOUT_MILLIS = 2_000;
    private static final int MAX_RETRIES = 0;
    private static final int MAX_RESPONSE_BYTES = 1_048_576;

    private final String metricsBackendBaseUrl;

    static SessionMetricsDAO fromEnvironmentOrNull() {
        final String metricsBackendBaseUrl = System.getenv(PlatformMetricsDAO.SKAHA_METRICS_BACKEND_URL);
        if (!StringUtil.hasText(metricsBackendBaseUrl)) {
            return null;
        }
        return new SessionMetricsDAO(metricsBackendBaseUrl);
    }

    SessionMetricsDAO(final String metricsBackendBaseUrl) {
        this.metricsBackendBaseUrl = PlatformMetricsDAO.requireBaseUrl(metricsBackendBaseUrl);
    }

    static String sessionMetricsPath(final String sessionId) {
        return "/apis/canfar.net/v1alpha1/metrics/session/" + requireSessionId(sessionId);
    }

    static String sessionMetricsUrl(final String normalizedBaseUrl, final String sessionId) {
        return PlatformMetricsDAO.normalizeBaseUrl(normalizedBaseUrl) + sessionMetricsPath(sessionId);
    }

    static String requireSessionId(final String sessionId) {
        if (!StringUtil.hasText(sessionId)) {
            throw new IllegalArgumentException("session id is required");
        }
        final String trimmed = sessionId.trim();
        if (!SESSION_ID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("invalid session id");
        }
        return trimmed;
    }

    SessionMetrics getSessionMetrics(final String sessionId) throws Exception {
        final String normalizedSessionId = requireSessionId(sessionId);
        final String sessionMetricsUrl = sessionMetricsUrl(metricsBackendBaseUrl, normalizedSessionId);
        final BoundedOutputStream responseBody = new BoundedOutputStream(MAX_RESPONSE_BYTES);
        final HttpGet get = new HttpGet(URI.create(sessionMetricsUrl).toURL(), responseBody);
        PlatformMetricsDAO.configureHttpGet(get);
        get.run();
        if (get.getThrowable() != null) {
            throw new IOException(
                    "failed to fetch session metrics from " + sessionMetricsUrl, get.getThrowable());
        }
        return parseEnvelope(responseBody.asUtf8(), normalizedSessionId);
    }

    private SessionMetrics parseEnvelope(final String json, final String expectedSessionId) {
        final JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        final JsonObject spec = root.getAsJsonObject("spec");
        final JsonObject status = root.getAsJsonObject("status");
        if (!"canfar.net/v1alpha1".equals(text(root, "apiVersion"))
                || !"Metrics".equals(text(root, "kind"))
                || spec == null
                || !expectedSessionId.equals(text(spec, "session"))
                || status == null) {
            throw new IllegalArgumentException("invalid Metrics envelope");
        }
        final Map<String, String> usageByResource = new HashMap<>();
        final JsonElement resourcesElement = status.get("resources");
        if (resourcesElement != null && resourcesElement.isJsonArray()) {
            for (JsonElement element : resourcesElement.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                final JsonObject resource = element.getAsJsonObject();
                if (!resource.has("usage") || resource.get("usage").isJsonNull()) {
                    continue;
                }
                usageByResource.put(text(resource, "name"), text(resource, "usage"));
            }
        }
        return new SessionMetrics(expectedSessionId, Map.copyOf(usageByResource));
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

    private static String decodeUtf8(final byte[] bytes) {
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
}
