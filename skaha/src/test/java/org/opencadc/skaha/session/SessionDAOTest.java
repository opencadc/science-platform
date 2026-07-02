package org.opencadc.skaha.session;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.opencadc.skaha.metrics.MetricsDAO;
import org.opencadc.skaha.metrics.PodResourceUsage;

public class SessionDAOTest {

    @Test
    public void buildUserSessionLabelSelectorUsesCanonicalLabels() {
        Assert.assertEquals(
                "canfar.net/id=session-123,canfar.net/username=alice,canfar.net/kind!=headless",
                SessionDAO.buildUserSessionLabelSelector("alice", "session-123", true));
    }

    @Test
    public void buildDesktopApplicationLabelSelectorUsesCanonicalLabels() {
        Assert.assertEquals(
                "canfar.net/id=session-123,canfar.net/username=alice,canfar.net/app-id=app-123,canfar.net/kind=desktop-app",
                SessionDAO.buildDesktopApplicationLabelSelector("session-123", "alice", "app-123"));
    }

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
