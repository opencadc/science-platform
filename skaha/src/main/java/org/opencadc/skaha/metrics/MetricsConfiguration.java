package org.opencadc.skaha.metrics;

import ca.nrc.cadc.util.StringUtil;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.Map;

public class MetricsConfiguration {
    /** Environment variable holding the Metrics backend base URL (scheme, host, optional port). */
    static final String SKAHA_METRICS_BACKEND_URL = "SKAHA_METRICS_BACKEND_URL";

    final URL metricsBackEndUrl;

    /**
     * Default instance creation using JVM environment.
     *
     * @return MetricsConfiguration instance.
     * @throws IOException If properties cannot be converted/used.
     */
    public static MetricsConfiguration fromEnv() throws IOException {
        return MetricsConfiguration.fromEnv(System.getenv());
    }

    /**
     * Create a new instance from the given map of the environment.
     *
     * @param env The environment mapping.
     * @return MetricsConfiguration instance.
     * @throws IOException If properties cannot be converted/used.
     */
    public static MetricsConfiguration fromEnv(final Map<String, String> env) throws IOException {
        final String metricsBackendUrlString = env.get(SKAHA_METRICS_BACKEND_URL);
        final URL configuredMetricsBackendUrl = StringUtil.hasText(metricsBackendUrlString)
                ? URI.create(MetricsConfiguration.normalizeBaseUrl(metricsBackendUrlString))
                        .toURL()
                : null;
        return new MetricsConfiguration(configuredMetricsBackendUrl);
    }

    MetricsConfiguration(final URL metricsBackEndUrl) {
        this.metricsBackEndUrl = metricsBackEndUrl;
    }

    static String normalizeBaseUrl(final String metricsBackendBaseUrl) {
        final String trimmed = metricsBackendBaseUrl.trim();
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
