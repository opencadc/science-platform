package org.opencadc.skaha.session;

import static org.opencadc.skaha.KubernetesApi.NAMESPACE;
import static org.opencadc.skaha.session.SessionFixtures.SESSION_ID;
import static org.opencadc.skaha.session.SessionFixtures.USER;

import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.EventListBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.opencadc.skaha.KubernetesApi;

/**
 * One session's life, per kind, through the real modules against a mock Kubernetes API: launch from the chart's
 * templates, events, desktop-app attach.
 */
@RunWith(Parameterized.class)
public class SessionLifecycleTest {
    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");

    @Parameterized.Parameters(name = "{0}")
    public static List<Object[]> kinds() {
        // Distinct shapes: single IngressRoute, Middleware + IngressRoute bundle, a leading empty YAML document,
        // and no Service or ingress at all.
        return List.of(
                new Object[] {SessionType.NOTEBOOK, 1, 1},
                new Object[] {SessionType.DESKTOP, 1, 2},
                new Object[] {SessionType.CARTA, 1, 1},
                new Object[] {SessionType.HEADLESS, 0, 0});
    }

    @Rule
    public final KubernetesApi api = new KubernetesApi();

    private final SessionType type;
    private final int services;
    private final int traefikResources;

    public SessionLifecycleTest(final SessionType type, final int services, final int traefikResources) {
        this.type = type;
        this.services = services;
        this.traefikResources = traefikResources;
    }

    @Test
    public void lifecycle() throws Exception {
        final KubernetesClient client = this.api.client();
        final String kind = this.type.name().toLowerCase();

        // Launch: the Job, then the Service and Traefik resources it owns.
        final SessionJobBuilder.LaunchArtifacts launch =
                SessionFixtures.builder(kind).buildLaunch();
        final Job launched = new SessionLauncher(client)
                .launch(launch.job(), SessionFixtures.ownedResources(this.type, launch.labels()));
        final Job job = client.batch()
                .v1()
                .jobs()
                .withName(launched.getMetadata().getName())
                .get();
        Assert.assertEquals(SESSION_ID, job.getMetadata().getLabels().get("canfar.net/id"));
        Assert.assertEquals(kind, job.getMetadata().getLabels().get("canfar.net/kind"));
        Assert.assertEquals(
                SessionFixtures.image(kind),
                job.getSpec().getTemplate().getSpec().getContainers().get(0).getImage());

        final List<Service> createdServices = client.services().list().getItems();
        Assert.assertEquals(this.services, createdServices.size());
        for (final Service service : createdServices) {
            assertOwnedBy(service, launched);
            Assert.assertEquals(
                    Map.of("canfar.net/id", SESSION_ID, "canfar.net/kind", kind),
                    service.getSpec().getSelector());
        }
        final List<GenericKubernetesResource> traefik = new ArrayList<>();
        for (final String traefikKind : List.of("Middleware", "IngressRoute")) {
            traefik.addAll(client.genericKubernetesResources("traefik.io/v1alpha1", traefikKind)
                    .list()
                    .getItems());
        }
        Assert.assertEquals(this.traefikResources, traefik.size());
        traefik.forEach(resource -> assertOwnedBy(resource, launched));

        final String podName = launched.getMetadata().getName() + "-x1y2z";

        // A running Pod: events on the Job and the Pod.
        client.pods()
                .resource(new PodBuilder()
                        .withNewMetadata()
                        .withName(podName)
                        .withLabels(job.getSpec().getTemplate().getMetadata().getLabels())
                        .endMetadata()
                        .withSpec(job.getSpec().getTemplate().getSpec())
                        .withNewStatus()
                        .withPodIP("10.0.0.5")
                        .endStatus()
                        .build())
                .create();
        expectEvents(launched.getMetadata().getName(), "Normal", "SuccessfulCreate", "Created pod: " + podName);
        expectEvents(podName, "Warning", "FailedMount", "MountVolume.SetUp failed");
        final String[] events = SessionPods.events(USER, SESSION_ID).split("\n");
        Assert.assertEquals(3, events.length);
        Assert.assertTrue(events[0], events[0].matches("TYPE +REASON +MESSAGE +FIRST-TIME +LAST-TIME"));
        // Sorted by event name: the Pod's ("<job>-x1y2z.1") before the Job's ("<job>.1").
        Assert.assertTrue(events[1], events[1].startsWith("Warning   FailedMount"));
        Assert.assertTrue(
                events[2], events[2].matches("Normal +SuccessfulCreate +Created pod: \\S+ +2026\\S+ +<none>"));
        assertKubectlColumns();

        if (this.type == SessionType.DESKTOP) {
            // Attach a desktop app to the running desktop; it is owned by the desktop's Job.
            Assert.assertEquals("10.0.0.5", SessionPods.desktopIP(USER, SESSION_ID));
            final Job app = new SessionLauncher(client)
                    .launch(
                            SessionFixtures.builder("desktop-app")
                                    .withParameter(
                                            PostAction.SKAHA_JOBUID,
                                            launched.getMetadata().getUid())
                                    .buildLaunch()
                                    .job(),
                            List.of());
            Assert.assertEquals(
                    launched.getMetadata().getUid(),
                    app.getMetadata().getOwnerReferences().get(0).getUid());
            Assert.assertEquals("app1", app.getMetadata().getLabels().get("canfar.net/app-id"));
        }
    }

    /** The table keeps the columns and padding of the former kubectl custom-columns output. */
    private static void assertKubectlColumns() {
        Assert.assertEquals(
                "TYPE      REASON         MESSAGE                 FIRST-TIME             LAST-TIME\n"
                        + "Warning   FailedCreate   pods \"x\" is forbidden   2026-10-23T17:00:00Z   <none>\n"
                        + "Normal    Pulled         ok                      2026-10-23T17:01:00Z   2026-10-23T17:02:00Z",
                SessionPods.format(List.of(
                        event(
                                "a.1",
                                "Warning",
                                "FailedCreate",
                                "pods \"x\" is forbidden",
                                "2026-10-23T17:00:00Z",
                                null),
                        event("a.2", "Normal", "Pulled", "ok", "2026-10-23T17:01:00Z", "2026-10-23T17:02:00Z"))));
    }

    private void expectEvents(final String objectName, final String type, final String reason, final String message) {
        this.api
                .server()
                .expect()
                .get()
                .withPath(
                        "/api/v1/namespaces/" + NAMESPACE + "/events?fieldSelector=involvedObject.name%3D" + objectName)
                .andReturn(
                        200,
                        new EventListBuilder()
                                .withItems(event(objectName + ".1", type, reason, message, NOW.toString(), null))
                                .build())
                .once();
    }

    private static io.fabric8.kubernetes.api.model.Event event(
            final String name,
            final String type,
            final String reason,
            final String message,
            final String firstTimestamp,
            final String lastTimestamp) {
        return new EventBuilder()
                .withNewMetadata()
                .withName(name)
                .endMetadata()
                .withType(type)
                .withReason(reason)
                .withMessage(message)
                .withFirstTimestamp(firstTimestamp)
                .withLastTimestamp(lastTimestamp)
                .build();
    }

    private static void assertOwnedBy(final HasMetadata resource, final Job job) {
        Assert.assertEquals(1, resource.getMetadata().getOwnerReferences().size());
        Assert.assertEquals(
                job.getMetadata().getUid(),
                resource.getMetadata().getOwnerReferences().get(0).getUid());
        Assert.assertEquals(
                job.getMetadata().getName(),
                resource.getMetadata().getOwnerReferences().get(0).getName());
    }
}
