package org.opencadc.skaha.session;

import ca.nrc.cadc.util.StringUtil;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns an authenticated owner and session type into a {@link LaunchAssignment}. Every launch routes to the operator's
 * Default Community and its ClusterQueue; the session type only selects the Kueue WorkloadPriorityClass.
 */
final class LaunchRouter {
    static final String NAMESPACE_ENV = "skaha.namespace";
    static final String DEFAULT_COMMUNITY_ENV = "SKAHA_DEFAULT_COMMUNITY";
    static final String DEFAULT_CLUSTER_QUEUE_ENV = "SKAHA_DEFAULT_CLUSTER_QUEUE";
    private static final String PRIORITY_CLASS_ENV = "SKAHA_QUEUE_%s_PRIORITY_CLASS";
    private static final Pattern LEGACY_QUEUE_NAME_ENV = Pattern.compile("SKAHA_QUEUE_.+_NAME");
    private static final Pattern DNS_1123_SUBDOMAIN =
            Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*");

    private final String namespace;
    private final String community;
    private final String clusterQueue;
    private final Map<SessionType, String> priorityClasses;

    private LaunchRouter(
            final String namespace,
            final String community,
            final String clusterQueue,
            final Map<SessionType, String> priorityClasses) {
        this.namespace = namespace;
        this.community = community;
        this.clusterQueue = clusterQueue;
        this.priorityClasses = priorityClasses;
    }

    static LaunchRouter fromEnv() {
        return fromEnv(System.getenv());
    }

    /**
     * Parse and validate the routing configuration.
     *
     * @throws IllegalStateException When the namespace, Default Community, or its ClusterQueue is missing or invalid,
     *     or a removed {@code SKAHA_QUEUE_<TYPE>_NAME} is still set.
     */
    static LaunchRouter fromEnv(final Map<String, String> env) {
        final List<String> legacy = env.keySet().stream()
                .filter(key -> LEGACY_QUEUE_NAME_ENV.matcher(key).matches())
                .sorted()
                .toList();
        if (!legacy.isEmpty()) {
            throw new IllegalStateException(legacy + " no longer supported: Skaha routes every session to a per-user "
                    + "LocalQueue; set deployment.skaha.sessions.communities.<name>.clusterQueue "
                    + "on the entry with default: true.");
        }
        final String namespace = require(env, NAMESPACE_ENV);
        final String community = require(env, DEFAULT_COMMUNITY_ENV);
        try {
            SessionLabels.labelValue(SessionLabels.Key.COMMUNITY, community);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(DEFAULT_COMMUNITY_ENV + " is invalid: " + invalid.getMessage(), invalid);
        }
        final String clusterQueue = require(env, DEFAULT_CLUSTER_QUEUE_ENV);
        if (clusterQueue.length() > 253
                || !DNS_1123_SUBDOMAIN.matcher(clusterQueue).matches()) {
            throw new IllegalStateException(DEFAULT_CLUSTER_QUEUE_ENV + " must be a Kubernetes resource name.");
        }

        final String defaultPriorityClass = env.get(String.format(PRIORITY_CLASS_ENV, "DEFAULT"));
        final Map<SessionType, String> priorityClasses = new EnumMap<>(SessionType.class);
        for (final SessionType type : SessionType.values()) {
            final String priorityClass = env.getOrDefault(
                    String.format(PRIORITY_CLASS_ENV, type.applicationName.toUpperCase()), defaultPriorityClass);
            if (StringUtil.hasText(priorityClass)) {
                priorityClasses.put(type, priorityClass);
            }
        }
        return new LaunchRouter(namespace, community, clusterQueue, priorityClasses);
    }

    /**
     * Route one launch. No I/O.
     *
     * @throws IllegalArgumentException When the user is not a valid Kubernetes label value, or
     *     {@code <user>-<community>-lq} is not a DNS-1123 label of at most 63 characters.
     */
    LaunchAssignment assign(final String user, final SessionType type) {
        return new LaunchAssignment(
                this.namespace, user, this.community, this.clusterQueue, this.priorityClasses.get(type));
    }

    private static String require(final Map<String, String> env, final String key) {
        final String value = env.get(key);
        if (!StringUtil.hasText(value)) {
            throw new IllegalStateException(key + " is required.");
        }
        return value;
    }
}
