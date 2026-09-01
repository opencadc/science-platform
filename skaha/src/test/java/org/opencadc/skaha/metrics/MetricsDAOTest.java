package org.opencadc.skaha.metrics;

import ca.nrc.cadc.util.StringUtil;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.mockito.Mockito;

public class MetricsDAOTest {

    @Test
    public void defaultConstructorSucceedsWhenMetricsBackendUrlUnset() {
        Assume.assumeFalse(
                "SKAHA_METRICS_BACKEND_URL must be unset for this test",
                StringUtil.hasText(System.getenv(PlatformMetricsDAO.SKAHA_METRICS_BACKEND_URL)));

        new MetricsDAO();
    }

    @Test
    public void getPlatformMetricsFailsWhenBackendNotConfigured() {
        final PodUsageProvider podProvider = Mockito.mock(PodUsageProvider.class);
        final MetricsDAO dao = new MetricsDAO(null, podProvider);

        final IllegalStateException thrown = Assert.assertThrows(IllegalStateException.class, dao::getPlatformMetrics);
        Assert.assertTrue(thrown.getMessage().contains(PlatformMetricsDAO.SKAHA_METRICS_BACKEND_URL));
    }

    @Test
    public void delegatesPlatformMetricsToPlatformClient() throws Exception {
        final PlatformMetricsDAO platformDao = Mockito.mock(PlatformMetricsDAO.class);
        final PodUsageProvider podProvider = Mockito.mock(PodUsageProvider.class);
        final PlatformMetrics platformMetrics = PlatformMetricsFixtures.fixedPlatformMetrics();

        Mockito.when(platformDao.getPlatformMetrics()).thenReturn(platformMetrics);

        final MetricsDAO dao = new MetricsDAO(platformDao, podProvider);

        Assert.assertSame(platformMetrics, dao.getPlatformMetrics());
        Mockito.verify(platformDao).getPlatformMetrics();
    }

    @Test
    public void getPodResourceUsageMapsPodMetricsFromProvider() throws Exception {
        final PlatformMetricsDAO platformDao = Mockito.mock(PlatformMetricsDAO.class);
        final PodUsageProvider podProvider = Mockito.mock(PodUsageProvider.class);
        final PodMetrics podMetrics = new PodMetrics(Map.of("pod-1", "250m"), Map.of("pod-1", "1Gi"));

        Mockito.when(podProvider.getPodMetrics("alice", true)).thenReturn(podMetrics);

        final MetricsDAO dao = new MetricsDAO(platformDao, podProvider);
        final PodResourceUsage usage = dao.getPodResourceUsage("alice", true);

        Assert.assertEquals("0.250", usage.cpu().get("pod-1"));
        Mockito.verify(podProvider).getPodMetrics("alice", true);
    }

    @Test
    public void getPodResourceUsageSoftFailsWhenProviderFails() throws Exception {
        final PlatformMetricsDAO platformDao = Mockito.mock(PlatformMetricsDAO.class);
        final PodUsageProvider podProvider = Mockito.mock(PodUsageProvider.class);

        Mockito.when(podProvider.getPodMetrics("alice", false))
                .thenThrow(new RuntimeException("metrics API unavailable"));

        final MetricsDAO dao = new MetricsDAO(platformDao, podProvider);

        Assert.assertEquals(PodResourceUsage.empty(), dao.getPodResourceUsage("alice", false));
    }

    @Test
    public void getDefaultReturnsSharedInstance() throws Exception {
        MetricsDAO.resetDefaultForTests();
        MetricsDAO.setDefaultForTests(PlatformMetricsFixtures.metricsDAOWithFixedPlatformMetrics());
        try {
            Assert.assertSame(MetricsDAO.getDefault(), MetricsDAO.getDefault());
        } finally {
            MetricsDAO.resetDefaultForTests();
        }
    }

    @Test
    public void fromEnvironmentSelectsKubernetesProviderByDefault() throws Exception {
        final PodUsageProvider provider = PodUsageProvider.fromEnvironment();
        Assert.assertTrue(provider instanceof KubernetesPodUsageProvider);
    }

    @Test
    public void getPodResourceUsageUsesBackendSessionApiForListedJobs() throws Exception {
        final SessionMetricsDAO sessionMetricsDAO = Mockito.mock(SessionMetricsDAO.class);
        Mockito.when(sessionMetricsDAO.getSessionMetrics("session-a"))
                .thenReturn(new SessionMetrics("session-a", Map.of("cpu", "500m", "memory", "512Mi")));

        final MetricsDAO dao = new MetricsDAO(null, new MetricsBackendPodUsageProvider(sessionMetricsDAO));
        final List<V1Job> jobs = List.of(new V1Job()
                .metadata(new V1ObjectMeta()
                        .name("job-a-main")
                        .labels(Map.of("canfar.net/id", "session-a"))));

        final PodResourceUsage usage = dao.getPodResourceUsage("alice", false, jobs);

        Assert.assertEquals("0.500", usage.cpu().get("job-a-main"));
        Assert.assertEquals("0.54", usage.memory().get("job-a-main"));
    }

    @Test
    public void backendProviderReturnsEmptyWithoutJobs() {
        final MetricsDAO dao = new MetricsDAO(null, new MetricsBackendPodUsageProvider(new SessionMetricsDAO("http://unused")));
        Assert.assertEquals(PodResourceUsage.empty(), dao.getPodResourceUsage("alice", false, List.of()));
    }

    @Test
    public void backendProviderGetPodMetricsReturnsEmpty() throws Exception {
        final PodUsageProvider provider = new MetricsBackendPodUsageProvider(new SessionMetricsDAO("http://unused"));
        Assert.assertEquals(PodMetrics.empty(), provider.getPodMetrics("alice", false));
    }
}
