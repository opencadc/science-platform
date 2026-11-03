package org.opencadc.skaha.metrics;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import java.util.List;

/** Pod usage from the Kubernetes metrics API ({@code metrics.k8s.io}). */
final class KubernetesPodUsageProvider implements PodUsageProvider {

    private final PodMetricsDAO podMetricsDAO;

    KubernetesPodUsageProvider(final PodMetricsDAO podMetricsDAO) {
        this.podMetricsDAO = podMetricsDAO;
    }

    @Override
    public PodMetrics getPodMetrics(final String userID, final boolean omitHeadless, final List<Job> sessionJobs)
            throws Exception {
        return podMetricsDAO.getPodMetrics(userID, omitHeadless);
    }
}
