package org.opencadc.skaha.session;

import java.io.IOException;
import org.junit.Assert;
import org.junit.Test;
import org.opencadc.skaha.metrics.MetricsDAO;
import org.opencadc.skaha.metrics.PlatformMetrics;
import org.opencadc.skaha.metrics.PlatformMetricsFixtures;
import org.opencadc.skaha.metrics.PodMetrics;
import org.opencadc.skaha.metrics.PodResourceUsage;

public class SessionDAOTest {

    @Test
    public void loadPodResourceUsageReturnsEmptyWhenMetricsDaoFails() {
        final MetricsDAO failingDao = new MetricsDAO() {
            @Override
            public PlatformMetrics getPlatformMetrics() throws Exception {
                throw new IOException("unreachable");
            }

            @Override
            public PodMetrics getPodMetrics(final String userID, final boolean omitHeadless) throws Exception {
                throw new IOException("metrics API unavailable");
            }
        };

        final PodResourceUsage usage = SessionDAO.loadPodResourceUsage(failingDao, "alice", false);

        Assert.assertEquals(PodResourceUsage.empty(), usage);
    }

    @Test
    public void loadPodResourceUsageMapsMetricsFromDao() throws Exception {
        final PodMetrics podMetrics =
                new PodMetrics(java.util.Map.of("pod-a", "100m"), java.util.Map.of("pod-a", "512Mi"));
        final MetricsDAO dao = new MetricsDAO() {
            @Override
            public PlatformMetrics getPlatformMetrics() {
                return PlatformMetricsFixtures.fixedPlatformMetrics();
            }

            @Override
            public PodMetrics getPodMetrics(final String userID, final boolean omitHeadless) {
                return podMetrics;
            }
        };

        final PodResourceUsage usage = SessionDAO.loadPodResourceUsage(dao, "alice", true);

        Assert.assertEquals("0.100", usage.cpu().get("pod-a"));
        Assert.assertEquals("0.54", usage.memory().get("pod-a"));
    }
}
