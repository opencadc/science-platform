package org.opencadc.skaha.metrics;

import java.util.List;

/**
 * One session's report from the Metrics backend session API: its {@code status.resources} rows, as Metrics wrote them.
 *
 * @param sessionId The session the report describes.
 * @param resources One row per resource, in report order.
 */
public record SessionMetrics(String sessionId, List<Resource> resources) {

    public SessionMetrics {
        resources = List.copyOf(resources);
    }

    /**
     * One resource row. Values are Kubernetes quantity strings; a value the report leaves out is null.
     *
     * @param name The resource, e.g. {@code cpu}, {@code memory} or {@code nvidia.com/gpu}.
     * @param total One Pod's request times the replicas; batch sessions only.
     * @param requests What the session holds now.
     * @param usage The sum over its running Pods.
     * @param efficiency The session-window usage ratio.
     */
    public record Resource(String name, String total, String requests, String usage, String efficiency) {}

    /** The named row, or null when the report has none. */
    public Resource resource(final String name) {
        return this.resources.stream()
                .filter(resource -> resource.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    String cpuUsage() {
        return usage("cpu");
    }

    String memoryUsage() {
        return usage("memory");
    }

    private String usage(final String name) {
        final Resource resource = resource(name);
        return resource == null ? null : resource.usage();
    }
}
