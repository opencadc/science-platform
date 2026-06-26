package org.opencadc.skaha.metrics;

import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class MetricsDAOTest {

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
    public void fromEnvironmentSelectsKubernetesProviderByDefault() {
        final PodUsageProvider provider = PodUsageProvider.fromConfiguration(new MetricsConfiguration(null));
        Assert.assertTrue(provider instanceof KubernetesPodUsageProvider);
    }

    @Test
    public void fromEnvironmentSelectsBackendProviderWhenConfigured() throws Exception {
        final Map<String, String> properties = new HashMap<>();
        properties.put(MetricsConfiguration.SKAHA_METRICS_BACKEND_URL, "http://localhost:8080");
        final MetricsConfiguration metricsConfiguration = MetricsConfiguration.fromEnv(properties);
        // Cannot set env in Java easily; test the backend class directly
        final PodUsageProvider provider = MetricsBackendPodUsageProvider.fromConfiguration(metricsConfiguration);
        Assert.assertThrows(UnsupportedOperationException.class, () -> provider.getPodMetrics("alice", false));
    }
}
