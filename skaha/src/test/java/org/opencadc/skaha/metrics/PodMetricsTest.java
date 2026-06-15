package org.opencadc.skaha.metrics;

import io.kubernetes.client.custom.ContainerMetrics;
import io.kubernetes.client.custom.PodMetricsList;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class PodMetricsTest {

    @Test
    public void fromKubernetesMapsPodUsageToRawQuantities() {
        final io.kubernetes.client.custom.PodMetrics k8sPod = new io.kubernetes.client.custom.PodMetrics();
        k8sPod.setMetadata(new V1ObjectMeta().name("skaha-notebook-alice-abc"));
        final ContainerMetrics container = new ContainerMetrics();
        container.setUsage(Map.of(
                "cpu", new Quantity("1367m"),
                "memory", new Quantity("1536Mi")));
        k8sPod.setContainers(List.of(container));

        final PodMetricsList list = new PodMetricsList();
        list.setItems(List.of(k8sPod));

        final PodMetrics podMetrics = PodMetrics.fromKubernetes(list);

        Assert.assertEquals("1367m", podMetrics.cpuByPodName().get("skaha-notebook-alice-abc"));
        Assert.assertEquals("1536Mi", podMetrics.memoryByPodName().get("skaha-notebook-alice-abc"));
    }

    @Test
    public void toPodResourceUsageFormatsLegacySessionFields() {
        final PodMetrics podMetrics = new PodMetrics(
                Map.of("skaha-notebook-alice-abc", "1367m"), Map.of("skaha-notebook-alice-abc", "1536Mi"));

        final PodResourceUsage usage = PodMetrics.toPodResourceUsage(podMetrics);

        Assert.assertEquals("1.367", usage.cpu().get("skaha-notebook-alice-abc"));
        Assert.assertEquals("1.61", usage.memory().get("skaha-notebook-alice-abc"));
    }

    @Test
    public void fromKubernetesReturnsEmptyWhenListMissing() {
        Assert.assertEquals(PodMetrics.empty(), PodMetrics.fromKubernetes(null));
        Assert.assertEquals(PodMetrics.empty(), PodMetrics.fromKubernetes(new PodMetricsList()));
    }
}
