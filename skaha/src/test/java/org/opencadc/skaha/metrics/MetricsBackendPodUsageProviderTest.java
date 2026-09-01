package org.opencadc.skaha.metrics;

import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class MetricsBackendPodUsageProviderTest {

    @Test
    public void getPodMetricsForJobsMapsUsageToJobNames() throws Exception {
        final SessionMetricsDAO sessionMetricsDAO = Mockito.mock(SessionMetricsDAO.class);
        Mockito.when(sessionMetricsDAO.getSessionMetrics("session-a"))
                .thenReturn(new SessionMetrics("session-a", Map.of("cpu", "500m", "memory", "512Mi")));
        Mockito.when(sessionMetricsDAO.getSessionMetrics("session-b"))
                .thenReturn(new SessionMetrics("session-b", Map.of("cpu", "250m", "memory", "256Mi")));

        final MetricsBackendPodUsageProvider provider = new MetricsBackendPodUsageProvider(sessionMetricsDAO);
        final PodMetrics podMetrics = provider.getPodMetricsForJobs(List.of(
                job("job-a-main", "session-a"),
                job("job-a-app", "session-a"),
                job("job-b-main", "session-b")));

        Assert.assertEquals("0.500", ResourceQuantityFormatter.toCoreUnit(podMetrics.cpuByPodName().get("job-a-main")));
        Assert.assertEquals("0.500", ResourceQuantityFormatter.toCoreUnit(podMetrics.cpuByPodName().get("job-a-app")));
        Assert.assertEquals("0.250", ResourceQuantityFormatter.toCoreUnit(podMetrics.cpuByPodName().get("job-b-main")));
        Assert.assertEquals(
                "0.27",
                ResourceQuantityFormatter.toSessionMemoryGb(podMetrics.memoryByPodName().get("job-b-main")));
    }

    @Test
    public void getPodMetricsForJobsSoftFailsPerSession() throws Exception {
        final SessionMetricsDAO sessionMetricsDAO = Mockito.mock(SessionMetricsDAO.class);
        Mockito.when(sessionMetricsDAO.getSessionMetrics("session-a"))
                .thenReturn(new SessionMetrics("session-a", Map.of("cpu", "500m")));
        Mockito.when(sessionMetricsDAO.getSessionMetrics("session-b"))
                .thenThrow(new RuntimeException("backend unavailable"));

        final MetricsBackendPodUsageProvider provider = new MetricsBackendPodUsageProvider(sessionMetricsDAO);
        final PodMetrics podMetrics = provider.getPodMetricsForJobs(List.of(
                job("job-a-main", "session-a"), job("job-b-main", "session-b")));

        Assert.assertEquals("500m", podMetrics.cpuByPodName().get("job-a-main"));
        Assert.assertFalse(podMetrics.cpuByPodName().containsKey("job-b-main"));
    }

    @Test
    public void getPodMetricsForJobsReturnsEmptyWhenBackendUnset() {
        final MetricsBackendPodUsageProvider provider = new MetricsBackendPodUsageProvider(null);

        Assert.assertEquals(PodMetrics.empty(), provider.getPodMetricsForJobs(List.of(job("job-a-main", "session-a"))));
    }

    private static V1Job job(final String jobName, final String sessionId) {
        return new V1Job()
                .metadata(new V1ObjectMeta()
                        .name(jobName)
                        .labels(Map.of("canfar.net/id", sessionId, "canfar.net/username", "alice")));
    }
}
