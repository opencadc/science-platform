package org.opencadc.skaha.metrics;

/**
 * Default {@link MetricsDAO} delegating platform metrics to {@link PlatformMetricsDAO} and pod metrics to
 * {@link PodMetricsDAO}.
 */
public class SkahaMetricsDAO implements MetricsDAO {

    private final PlatformMetricsDAO platformMetricsDAO;
    private final PodMetricsDAO podMetricsDAO;

    public SkahaMetricsDAO() {
        this(new PlatformMetricsDAO(), new PodMetricsDAO());
    }

    SkahaMetricsDAO(final PlatformMetricsDAO platformMetricsDAO, final PodMetricsDAO podMetricsDAO) {
        this.platformMetricsDAO = platformMetricsDAO;
        this.podMetricsDAO = podMetricsDAO;
    }

    @Override
    public PlatformMetrics getPlatformMetrics() throws Exception {
        return platformMetricsDAO.getPlatformMetrics();
    }

    @Override
    public PodMetrics getPodMetrics(final String userID, final boolean omitHeadless) throws Exception {
        return podMetricsDAO.getPodMetrics(userID, omitHeadless);
    }
}
