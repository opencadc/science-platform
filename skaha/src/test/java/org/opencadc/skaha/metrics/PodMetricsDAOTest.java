package org.opencadc.skaha.metrics;

import org.junit.Assert;
import org.junit.Test;
import org.opencadc.skaha.session.SessionAction;

public class PodMetricsDAOTest {

    @Test
    public void buildLabelSelectorForUserAndOmitHeadless() {
        Assert.assertEquals(
                "canfar-net-userid=alice,canfar-net-sessionType!=" + SessionAction.SESSION_TYPE_HEADLESS,
                PodMetricsDAO.buildLabelSelector("alice", true));
    }

    @Test
    public void buildLabelSelectorEmptyWhenNoFilters() {
        // Empty selector lists all pods in the namespace (client-java Metrics API; same as kubectl top without -l).
        Assert.assertEquals("", PodMetricsDAO.buildLabelSelector(null, false));
    }
}
