package org.opencadc.skaha.session;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
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
        final List<Job> jobs = List.of(new JobBuilder()
                .withNewMetadata()
                .withName("job-a-main")
                .withLabels(Map.of("canfar.net/id", "session-a"))
                .endMetadata()
                .build());
        Mockito.when(metricsDAO.getPodResourceUsage("alice", false, jobs)).thenReturn(expected);

        final PodResourceUsage usage = SessionDAO.loadPodResourceUsage(metricsDAO, "alice", false, jobs);

        Assert.assertSame(expected, usage);
        Mockito.verify(metricsDAO).getPodResourceUsage("alice", false, jobs);
    }
}
