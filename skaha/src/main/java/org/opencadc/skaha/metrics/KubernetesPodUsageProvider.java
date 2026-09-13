package org.opencadc.skaha.metrics;

import io.kubernetes.client.openapi.models.V1Job;
import java.util.List;

/** Pod usage from the Kubernetes metrics API ({@code metrics.k8s.io}). */
final class KubernetesPodUsageProvider implements PodUsageProvider {

    private final PodMetricsDAO podMetricsDAO;

    KubernetesPodUsageProvider(final PodMetricsDAO podMetricsDAO) {
        this.podMetricsDAO = podMetricsDAO;
    }

    @Override
    public PodMetrics getPodMetrics(final String userID, final boolean omitHeadless, final List<V1Job> sessionJobs)
            throws Exception {
        return podMetricsDAO.getPodMetrics(userID, omitHeadless);
    }
}
