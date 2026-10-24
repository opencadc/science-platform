package org.opencadc.skaha.session;

import static org.opencadc.skaha.KubernetesApi.NAMESPACE;
import static org.opencadc.skaha.session.SessionFixtures.SESSION_ID;
import static org.opencadc.skaha.session.SessionFixtures.USER;

import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.EventListBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetricsListBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.opencadc.skaha.KubernetesApi;

/**
 * One session's life, per kind, through the real modules against a mock Kubernetes API: launch from the chart's
 * templates, list (cache, pod metrics, status), events, logs, renew, desktop-app attach, delete.
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
    private SessionJobCache cache;

    public SessionLifecycleTest(final SessionType type, final int services, final int traefikResources) {
        this.type = type;
        this.services = services;
        this.traefikResources = traefikResources;
    }

    @Before
    public void startCache() throws Exception {
        TestUtil.setupUserStorageEnvironment();
        this.cache = new SessionJobCache(this.api.client());
        this.cache.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
        SessionDAO.useCache(this.cache);
    }

    @After
    public void stopCache() {
        SessionDAO.useCache(null);
        this.cache.close();
        TestUtil.tearDownUserStorageEnvironment();
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

        // List: the new session shows at once (read-after-write), with Job status and pod usage.
        SessionDAO.created(launched);
        final String podName = launched.getMetadata().getName() + "-x1y2z";
        this.api
                .server()
                .expect()
                .get()
                .withPath("/apis/metrics.k8s.io/v1beta1/namespaces/" + NAMESPACE + "/pods?labelSelector="
                        + "canfar.net%2Fusername%3Dalice")
                .andReturn(
                        200,
                        new PodMetricsListBuilder()
                                .addNewItem()
                                .withNewMetadata()
                                .withName(podName)
                                .endMetadata()
                                .addNewContainer()
                                .withName("main")
                                .addToUsage("cpu", new Quantity("1367m"))
                                .addToUsage("memory", new Quantity("1536Mi"))
                                .endContainer()
                                .endItem()
                                .build())
                .once();
        final Session listed = onlySession();
        Assert.assertEquals(SESSION_ID, listed.getId());
        Assert.assertEquals(kind, listed.getType());
        Assert.assertEquals(Session.STATUS_PENDING, listed.getStatus());
        Assert.assertEquals("1.367", listed.getCPUCoresInUse());
        Assert.assertEquals("1.61", listed.getRAMInUse());

        // A running Pod: events (Job and Pod), then logs.
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

        final String container =
                job.getSpec().getTemplate().getSpec().getContainers().get(0).getName();
        this.api
                .server()
                .expect()
                .get()
                .withPath("/api/v1/namespaces/" + NAMESPACE + "/pods/" + podName + "/log?container=" + container)
                .andReturn(200, "session log\n")
                .once();
        final ByteArrayOutputStream logs = new ByteArrayOutputStream();
        SessionPods.streamLogs(USER, SESSION_ID, logs);
        Assert.assertEquals("session log\n", logs.toString(StandardCharsets.UTF_8));

        // Renew 2h in, with a 96h expiry: a deadline with less than 96h left moves to 2h + 96h (interactive kinds,
        // 96h deadline); one with more is left alone (headless, 14 days).
        final long deadline = job.getSpec().getActiveDeadlineSeconds();
        final long expectedDeadline = deadline - Duration.ofHours(2).toSeconds()
                        < Duration.ofHours(96).toSeconds()
                ? Duration.ofHours(98).toSeconds()
                : deadline;
        client.batch().v1().jobs().withName(launched.getMetadata().getName()).edit(current -> new JobBuilder(current)
                .editOrNewStatus()
                .withActive(1)
                .withStartTime(NOW.minus(Duration.ofHours(2)).toString())
                .endStatus()
                .build());
        SessionDAO.renew(USER, SESSION_ID, Duration.ofHours(96), NOW);
        Assert.assertEquals(expectedDeadline, (long) client.batch()
                .v1()
                .jobs()
                .withName(launched.getMetadata().getName())
                .get()
                .getSpec()
                .getActiveDeadlineSeconds());

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

        // Delete: the mock has no garbage collector, so remove the desktop apps the owner references would; the
        // listing drops the session once the watch delivers the deletions.
        SessionDAO.deleteJob(SESSION_ID, USER);
        client.batch()
                .v1()
                .jobs()
                .withLabel(SessionLabels.Key.ID.label(), SESSION_ID)
                .delete();
        KubernetesApi.await("the listing to drop the session", () -> {
            try {
                return SessionDAO.getUserSessions(USER, null, false).isEmpty();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
        Assert.assertNull(client.batch()
                .v1()
                .jobs()
                .withName(launched.getMetadata().getName())
                .get());
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

    private Session onlySession() {
        final List<Session> sessions = SessionDAO.getUserSessions(USER, null, false);
        Assert.assertEquals(1, sessions.size());
        return sessions.get(0);
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
