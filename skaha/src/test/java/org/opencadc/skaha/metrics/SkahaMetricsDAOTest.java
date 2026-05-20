package org.opencadc.skaha.metrics;

import java.util.Map;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class SkahaMetricsDAOTest {

    @Test
    public void delegatesPlatformAndPodMetricsToAdapters() throws Exception {
        final PlatformMetricsDAO platformDao = Mockito.mock(PlatformMetricsDAO.class);
        final PodMetricsDAO podDao = Mockito.mock(PodMetricsDAO.class);
        final PlatformMetrics platformMetrics = PlatformMetricsFixtures.fixedPlatformMetrics();
        final PodMetrics podMetrics = new PodMetrics(Map.of("pod-1", "250m"), Map.of("pod-1", "1Gi"));

        Mockito.when(platformDao.getPlatformMetrics()).thenReturn(platformMetrics);
        Mockito.when(podDao.getPodMetrics("alice", true)).thenReturn(podMetrics);

        final SkahaMetricsDAO dao = new SkahaMetricsDAO(platformDao, podDao);

        Assert.assertSame(platformMetrics, dao.getPlatformMetrics());
        Assert.assertSame(podMetrics, dao.getPodMetrics("alice", true));
        Mockito.verify(platformDao).getPlatformMetrics();
        Mockito.verify(podDao).getPodMetrics("alice", true);
    }
}
