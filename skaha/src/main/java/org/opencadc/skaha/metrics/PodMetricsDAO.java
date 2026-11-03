package org.opencadc.skaha.metrics;

import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetricsList;
import org.opencadc.skaha.SkahaKubernetesClient;
import org.opencadc.skaha.session.SessionLabels;

/**
 * Fetches per-pod resource usage from the Kubernetes metrics API ({@code metrics.k8s.io}) with Skaha's Fabric8 client.
 */
class PodMetricsDAO {
    public PodMetrics getPodMetrics(final String userID, final boolean omitHeadless) {
        final PodMetricsList podMetricsList = SkahaKubernetesClient.get()
                .resources(io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics.class, PodMetricsList.class)
                .withLabelSelector(buildLabelSelector(userID, omitHeadless))
                .list();
        return PodMetrics.fromKubernetes(podMetricsList);
    }

    /**
     * Build a Kubernetes label selector for session pods in the workload namespace.
     *
     * @param userID session owner, or blank to include all users
     * @param omitHeadless true to exclude headless session pods
     * @return comma-separated selector, or {@code ""} when no filters apply (all pods in the namespace, same as
     *     historical {@code kubectl top pod} without {@code -l})
     */
    static String buildLabelSelector(final String userID, final boolean omitHeadless) {
        return SessionLabels.forUserSessions(userID, null, omitHeadless);
    }
}
