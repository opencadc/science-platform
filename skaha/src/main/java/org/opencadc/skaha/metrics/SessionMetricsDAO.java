package org.opencadc.skaha.metrics;

import ca.nrc.cadc.util.StringUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
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
        return parseEnvelope(
                MetricsBackendHttp.fetchUtf8(URI.create(sessionMetricsUrl), "failed to fetch session metrics from"),
                normalizedSessionId);
    }

    private SessionMetrics parseEnvelope(final String json, final String expectedSessionId) {
        final JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        final JsonObject spec = root.getAsJsonObject("spec");
        final JsonObject status = root.getAsJsonObject("status");
        if (!"canfar.net/v1alpha1".equals(MetricsBackendHttp.text(root, "apiVersion"))
                || !"Metrics".equals(MetricsBackendHttp.text(root, "kind"))
                || spec == null
                || !expectedSessionId.equals(MetricsBackendHttp.text(spec, "session"))
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
                usageByResource.put(
                        MetricsBackendHttp.text(resource, "name"), MetricsBackendHttp.text(resource, "usage"));
            }
        }
        return new SessionMetrics(expectedSessionId, Map.copyOf(usageByResource));
    }
}
