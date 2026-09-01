package org.opencadc.skaha.metrics;

import java.util.Collections;
import java.util.Map;

/**
 * Per-session CPU and memory usage from the Metrics backend session API, before legacy formatting.
 *
 * <p>Values are raw Kubernetes quantity strings from {@code status.resources[].usage}.
 */
record SessionMetrics(String sessionId, Map<String, String> usageByResource) {

    SessionMetrics {
        usageByResource = Collections.unmodifiableMap(usageByResource);
    }

    static SessionMetrics empty(final String sessionId) {
        return new SessionMetrics(sessionId, Map.of());
    }

    String cpuUsage() {
        return usageByResource.get("cpu");
    }

    String memoryUsage() {
        return usageByResource.get("memory");
    }
}
