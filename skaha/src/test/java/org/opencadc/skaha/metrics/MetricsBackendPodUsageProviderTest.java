package org.opencadc.skaha.metrics;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class MetricsBackendPodUsageProviderTest {

    @Test
    public void getPodMetricsMapsUsageToJobNames() throws Exception {
        final SessionMetricsDAO sessionMetricsDAO = Mockito.mock(SessionMetricsDAO.class);
        Mockito.when(sessionMetricsDAO.getSessionMetrics(Set.of("session-a", "session-b")))
                .thenReturn(Map.of(
                        "session-a", usage("session-a", "500m", "512Mi"),
                        "session-b", usage("session-b", "250m", "256Mi")));

        final MetricsBackendPodUsageProvider provider = new MetricsBackendPodUsageProvider(sessionMetricsDAO);
        final List<Job> jobs =
                List.of(job("job-a-main", "session-a"), job("job-a-app", "session-a"), job("job-b-main", "session-b"));
        final PodMetrics podMetrics = provider.getPodMetrics("alice", false, jobs);

        Assert.assertEquals(
                "0.500",
                ResourceQuantityFormatter.toCoreUnit(podMetrics.cpuByPodName().get("job-a-main")));
        Assert.assertEquals(
                "0.500",
                ResourceQuantityFormatter.toCoreUnit(podMetrics.cpuByPodName().get("job-a-app")));
        Assert.assertEquals(
                "0.250",
                ResourceQuantityFormatter.toCoreUnit(podMetrics.cpuByPodName().get("job-b-main")));
        Assert.assertEquals(
                "0.27",
                ResourceQuantityFormatter.toSessionMemoryGb(
                        podMetrics.memoryByPodName().get("job-b-main")));
    }

    @Test
    public void getPodMetricsSkipsSessionsWithoutAReport() throws Exception {
        final SessionMetricsDAO sessionMetricsDAO = Mockito.mock(SessionMetricsDAO.class);
        Mockito.when(sessionMetricsDAO.getSessionMetrics(Set.of("session-a", "session-b")))
                .thenReturn(Map.of("session-a", usage("session-a", "500m", null)));

        final MetricsBackendPodUsageProvider provider = new MetricsBackendPodUsageProvider(sessionMetricsDAO);
        final PodMetrics podMetrics = provider.getPodMetrics(
                "alice", false, List.of(job("job-a-main", "session-a"), job("job-b-main", "session-b")));

        Assert.assertEquals("500m", podMetrics.cpuByPodName().get("job-a-main"));
        Assert.assertFalse(podMetrics.memoryByPodName().containsKey("job-a-main"));
        Assert.assertFalse(podMetrics.cpuByPodName().containsKey("job-b-main"));
    }

    @Test
    public void getPodMetricsReturnsEmptyWhenBackendUnset() throws Exception {
        final MetricsBackendPodUsageProvider provider = new MetricsBackendPodUsageProvider(null);

        Assert.assertEquals(
                PodMetrics.empty(), provider.getPodMetrics("alice", false, List.of(job("job-a-main", "session-a"))));
    }

    private static SessionMetrics usage(final String sessionId, final String cpu, final String memory) {
        return new SessionMetrics(
                sessionId,
                List.of(
                        new SessionMetrics.Resource("cpu", null, "1", cpu, null),
                        new SessionMetrics.Resource("memory", null, "1Gi", memory, null)));
    }

    private static Job job(final String jobName, final String sessionId) {
        return new JobBuilder()
                .withNewMetadata()
                .withName(jobName)
                .withLabels(Map.of("canfar.net/id", sessionId, "canfar.net/username", "alice"))
                .endMetadata()
                .build();
    }
}
