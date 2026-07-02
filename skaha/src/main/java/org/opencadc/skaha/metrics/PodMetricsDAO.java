package org.opencadc.skaha.metrics;

import io.kubernetes.client.Metrics;
import io.kubernetes.client.custom.PodMetricsList;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import org.opencadc.skaha.K8SUtil;
import org.opencadc.skaha.session.SessionLabels;

/**
 * Fetches per-pod resource usage from the Kubernetes metrics API ({@code metrics.k8s.io}).
 *
 * <p>Uses {@link Configuration#getDefaultApiClient()} (in-cluster service account). Does not call
 * {@link io.kubernetes.client.util.Config#fromCluster()} here.
 */
class PodMetricsDAO {

    private final Metrics metricsClient;

    public PodMetricsDAO() {
        this(new Metrics(Configuration.getDefaultApiClient()));
    }

    PodMetricsDAO(final Metrics metricsClient) {
        this.metricsClient = metricsClient;
    }

    public PodMetrics getPodMetrics(final String userID, final boolean omitHeadless) throws ApiException {
        final String labelSelector = buildLabelSelector(userID, omitHeadless);
        final PodMetricsList podMetricsList =
                metricsClient.getPodMetrics(K8SUtil.getWorkloadNamespace(), labelSelector);
        return PodMetrics.fromKubernetes(podMetricsList);
    }

    /**
     * Build a Kubernetes label selector for session pods in the workload namespace.
     *
     * @return comma-separated selector, or {@code ""} when no filters apply (all pods in the namespace, same as
     *     historical {@code kubectl top pod} without {@code -l})
     */
    static String buildLabelSelector(final String userID, final boolean omitHeadless) {
        return SessionLabels.forUserSessions(userID, null, omitHeadless);
    }
}
