package org.opencadc.skaha.session;

import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import java.util.List;
import java.util.Map;
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
        final List<V1Job> jobs = List.of(new V1Job()
                .metadata(new V1ObjectMeta()
                        .name("job-a-main")
                        .labels(Map.of("canfar.net/id", "session-a"))));
        Mockito.when(metricsDAO.getPodResourceUsage("alice", false, jobs)).thenReturn(expected);

        final PodResourceUsage usage = SessionDAO.loadPodResourceUsage(metricsDAO, "alice", false, jobs);

        Assert.assertSame(expected, usage);
        Mockito.verify(metricsDAO).getPodResourceUsage("alice", false, jobs);
    }
}
