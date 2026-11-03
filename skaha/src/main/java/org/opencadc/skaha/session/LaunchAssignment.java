package org.opencadc.skaha.session;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Launch routing for one session: workload namespace, owner, Community, ClusterQueue, and optional priority class.
 *
 * <p>The LocalQueue name is {@code <user>-<community>-lq}. Construction rejects names that are not a DNS-1123 label of
 * at most 63 characters.
 *
 * @param namespace workload namespace of the Job and its LocalQueue
 * @param user exact case-sensitive owner, a Kubernetes label value
 * @param community exact case-sensitive community, a Kubernetes label value
 * @param clusterQueue operator ClusterQueue for that community
 * @param workloadPriorityClass Kueue workload priority class, or null for Kueue's default
 */
record LaunchAssignment(
        String namespace, String user, String community, String clusterQueue, String workloadPriorityClass) {
    private static final Pattern DNS_1123_LABEL = Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?");
    private static final String PRIORITY_CLASS_LABEL = "kueue.x-k8s.io/priority-class";

    LaunchAssignment {
        namespace = requireText(namespace, "namespace");
        user = SessionLabels.labelValue(SessionLabels.Key.USERNAME, user);
        community = SessionLabels.labelValue(SessionLabels.Key.COMMUNITY, community);
        clusterQueue = requireText(clusterQueue, "clusterQueue");
        if (workloadPriorityClass != null) {
            workloadPriorityClass = SessionLabels.labelValue(PRIORITY_CLASS_LABEL, workloadPriorityClass);
        }
        final String localQueue = user + "-" + community + "-lq";
        if (localQueue.length() > 63 || !DNS_1123_LABEL.matcher(localQueue).matches()) {
            throw new IllegalArgumentException(
                    "LocalQueue name must be a DNS-1123 label of at most 63 characters: " + localQueue);
        }
    }

    /** LocalQueue name for this user and community: {@code <user>-<community>-lq}. */
    String localQueue() {
        return this.user + "-" + this.community + "-lq";
    }

    private static String requireText(final String value, final String field) {
        Objects.requireNonNull(value, field + " cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return value;
    }
}
