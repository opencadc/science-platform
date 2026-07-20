package org.opencadc.skaha.metrics;

import ca.nrc.cadc.util.StringUtil;
import java.net.URI;
import java.util.Map;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.mockito.Mockito;

public class MetricsDAOTest {

    @Test
    public void fromConfigurationSucceedsWhenMetricsBackendUrlUnset() {
        MetricsDAO.fromConfiguration(new MetricsConfiguration(null));
    }

    @Test
    public void getDefaultSucceedsWhenMetricsBackendUrlUnset() throws Exception {
        Assume.assumeFalse(
                "SKAHA_METRICS_BACKEND_URL must be unset for this test",
                StringUtil.hasText(System.getenv(MetricsConfiguration.SKAHA_METRICS_BACKEND_URL)));

        MetricsDAO.resetDefaultForTests();
        try {
            MetricsDAO.getDefault();
        } finally {
            MetricsDAO.resetDefaultForTests();
        }
    }

    @Test
    public void getPlatformMetricsFailsWhenBackendNotConfigured() {
        final PodUsageProvider podProvider = Mockito.mock(PodUsageProvider.class);
        final MetricsDAO dao = new MetricsDAO(new NoOpPlatformUsageProvider(), podProvider);

        Assert.assertThrows(UnsupportedOperationException.class, dao::getPlatformMetrics);
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
    public void fromEnvironmentSelectsKubernetesProviderByDefault() {
        final PodUsageProvider provider = PodUsageProvider.fromConfiguration(new MetricsConfiguration(null));
        Assert.assertTrue(provider instanceof KubernetesPodUsageProvider);
    }

    @Test
    public void fromConfigurationUsesKubernetesPodProviderWhenMetricsBackendConfigured() throws Exception {
        final MetricsConfiguration config =
                new MetricsConfiguration(URI.create("http://metrics:8000").toURL());
        final PodUsageProvider provider = PodUsageProvider.fromConfiguration(config);
        Assert.assertTrue(provider instanceof KubernetesPodUsageProvider);
    }

    @Test
    public void metricsBackendPodProviderIsNotYetImplemented() {
        final PodUsageProvider provider = MetricsBackendPodUsageProvider.fromConfiguration();
        Assert.assertThrows(UnsupportedOperationException.class, () -> provider.getPodMetrics("alice", false));
    }
}
