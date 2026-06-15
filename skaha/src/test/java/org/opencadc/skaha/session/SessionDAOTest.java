package org.opencadc.skaha.session;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.opencadc.skaha.metrics.MetricsDAO;
import org.opencadc.skaha.metrics.PodResourceUsage;

public class SessionDAOTest {

    @Test
    public void loadPodResourceUsageDelegatesToMetricsDao() {
        final MetricsDAO metricsDAO = Mockito.mock(MetricsDAO.class);
        final PodResourceUsage expected = PodResourceUsage.empty();
        Mockito.when(metricsDAO.getPodResourceUsage("alice", false)).thenReturn(expected);

        final PodResourceUsage usage = SessionDAO.loadPodResourceUsage(metricsDAO, "alice", false);

        Assert.assertSame(expected, usage);
        Mockito.verify(metricsDAO).getPodResourceUsage("alice", false);
    }
}
