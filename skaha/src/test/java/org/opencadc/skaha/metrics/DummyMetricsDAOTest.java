package org.opencadc.skaha.metrics;

import org.junit.Assert;
import org.junit.Test;

public class DummyMetricsDAOTest {

    @Test
    public void returnsFixedPlatformMetricsFixture() throws Exception {
        final MetricsDAO dao = new DummyMetricsDAO();

        final PlatformMetrics metrics = dao.getPlatformMetrics();

        Assert.assertEquals(
                PlatformMetricsFixtures.SNAPSHOT_CREATED, metrics.metadata().created());
        Assert.assertEquals(PlatformMetricsFixtures.CAPACITY, metrics.data().capacity());
        Assert.assertEquals(PlatformMetricsFixtures.ALLOCATED, metrics.data().allocated());
    }

    @Test
    public void returnsStableSnapshotOnRepeatedCalls() throws Exception {
        final DummyMetricsDAO dao = new DummyMetricsDAO();

        Assert.assertEquals(dao.getPlatformMetrics(), dao.getPlatformMetrics());
    }
}
