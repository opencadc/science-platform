package org.opencadc.skaha;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class SkahaKubernetesClientTest {
    private final KubernetesMockServer server = new KubernetesMockServer(false);

    @After
    public void tearDown() {
        SkahaKubernetesClient.close();
        this.server.destroy();
        System.clearProperty(Config.KUBERNETES_MASTER_SYSTEM_PROPERTY);
        System.clearProperty(Config.KUBERNETES_AUTH_TRYKUBECONFIG_SYSTEM_PROPERTY);
        System.clearProperty(Config.KUBERNETES_AUTH_TRYSERVICEACCOUNT_SYSTEM_PROPERTY);
    }

    @Test
    public void testWorkloadNamespaceAndNoHttpRetry() {
        this.server.init();
        System.setProperty(Config.KUBERNETES_MASTER_SYSTEM_PROPERTY, this.server.url("/"));
        System.setProperty(Config.KUBERNETES_AUTH_TRYKUBECONFIG_SYSTEM_PROPERTY, "false");
        System.setProperty(Config.KUBERNETES_AUTH_TRYSERVICEACCOUNT_SYSTEM_PROPERTY, "false");
        this.server
                .expect()
                .get()
                .withPath("/api/v1/namespaces/skaha-workload/pods/p1")
                .andReturn(503, "")
                .once();

        SkahaKubernetesClient.initialize("skaha-workload");

        Assert.assertThrows(IllegalStateException.class, () -> SkahaKubernetesClient.initialize("again"));
        final KubernetesClientException exception = Assert.assertThrows(
                KubernetesClientException.class,
                () -> SkahaKubernetesClient.get().pods().withName("p1").get());
        Assert.assertEquals(503, exception.getCode());
        Assert.assertEquals("A request is never resent", 1, this.server.getRequestCount());

        SkahaKubernetesClient.close();
        Assert.assertThrows(IllegalStateException.class, SkahaKubernetesClient::get);
    }
}
