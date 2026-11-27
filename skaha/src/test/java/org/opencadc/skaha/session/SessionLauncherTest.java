package org.opencadc.skaha.session;

import ca.nrc.cadc.net.ResourceNotFoundException;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import java.util.List;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.opencadc.skaha.KubernetesApi;

/** A launch ensures the user's LocalQueue before it creates the Job, and only for a Job stamped with that queue. */
public class SessionLauncherTest {
    @Rule
    public final KubernetesApi api = new KubernetesApi();

    @Test
    public void launchCreatesTheLocalQueueThenTheJob() throws Exception {
        this.api.clusterQueue("cq", "default");
        this.api.requests();
        final Job launched = new SessionLauncher(this.api.client()).launch(launch(), List.of());

        Assert.assertNotNull(localQueue());
        Assert.assertEquals("alice-default-lq", SessionLabels.queueName(launched));
        Assert.assertEquals(
                List.of("POST localqueues", "POST jobs"),
                this.api.requests().stream()
                        .filter(request -> request.startsWith("POST"))
                        .map(request -> "POST " + request.substring(request.lastIndexOf('/') + 1))
                        .toList());
    }

    @Test
    public void unstampedJobIsRefusedBeforeAnyWrite() throws Exception {
        this.api.clusterQueue("cq", "default");
        final SessionJobBuilder.LaunchArtifacts stamped = launch();
        final Job unstamped = stamped.job();
        unstamped.getMetadata().getLabels().put("kueue.x-k8s.io/queue-name", "bob-default-lq");
        this.api.requests();

        Assert.assertThrows(IllegalStateException.class, () -> new SessionLauncher(this.api.client())
                .launch(
                        new SessionJobBuilder.LaunchArtifacts(unstamped, stamped.labels(), stamped.assignment()),
                        List.of()));

        Assert.assertTrue(this.api.requests().isEmpty());
    }

    @Test
    public void noJobWithoutAUsableLocalQueue() throws Exception {
        this.api
                .server()
                .expect()
                .get()
                .withPath("/apis/kueue.x-k8s.io/v1beta2")
                .andReturn(200, "{\"kind\":\"APIResourceList\",\"groupVersion\":\"kueue.x-k8s.io/v1beta2\"}")
                .always();

        Assert.assertThrows(ResourceNotFoundException.class, () -> new SessionLauncher(this.api.client())
                .launch(launch(), List.of()));

        Assert.assertNull(localQueue());
        Assert.assertTrue(
                this.api.client().batch().v1().jobs().list().getItems().isEmpty());
    }

    private static SessionJobBuilder.LaunchArtifacts launch() throws Exception {
        return SessionFixtures.builder("notebook").buildLaunch();
    }

    private Object localQueue() {
        return this.api
                .client()
                .genericKubernetesResources(new ResourceDefinitionContext.Builder()
                        .withGroup("kueue.x-k8s.io")
                        .withVersion("v1beta2")
                        .withKind("LocalQueue")
                        .withPlural("localqueues")
                        .withNamespaced(true)
                        .build())
                .withName("alice-default-lq")
                .get();
    }
}
