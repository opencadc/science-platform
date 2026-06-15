package org.opencadc.skaha.metrics;

/** Pod usage from the Kubernetes metrics API ({@code metrics.k8s.io}). */
final class KubernetesPodUsageProvider implements PodUsageProvider {

    private final PodMetricsDAO podMetricsDAO;

    KubernetesPodUsageProvider(final PodMetricsDAO podMetricsDAO) {
        this.podMetricsDAO = podMetricsDAO;
    }

    @Override
    public PodMetrics getPodMetrics(final String userID, final boolean omitHeadless) throws Exception {
        return podMetricsDAO.getPodMetrics(userID, omitHeadless);
    }
}
