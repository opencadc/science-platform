package org.opencadc.skaha.session;

import static org.opencadc.skaha.KubernetesApi.NAMESPACE;
import static org.opencadc.skaha.session.SessionFixtures.USER;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.EventListBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetricsListBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
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
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.Mockito;
import org.opencadc.skaha.KubernetesApi;
import org.opencadc.skaha.session.SessionRequests.Request;

/**
 * One session's life, per kind, through the session actions against a mock Kubernetes API, with the chart's templates:
 * launch a private image, list, events, logs, renew, attach and delete a desktop app, delete.
 */
@RunWith(Parameterized.class)
public class SessionLifecycleTest {
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

    private final KubernetesApi api = new KubernetesApi();
    private final SessionRequests requests = new SessionRequests();

    @Rule
    public final RuleChain rules = RuleChain.outerRule(this.api).around(this.requests);

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
        this.cache = new SessionJobCache(this.api.client());
        this.cache.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
        SessionDAO.useCache(this.cache);
    }

    @After
    public void stopCache() {
        SessionDAO.useCache(null);
        this.cache.close();
    }

    @Test
    public void lifecycle() throws Exception {
        final KubernetesClient client = this.api.client();
        final String kind = this.type.name().toLowerCase();

        // Launch a private image: the pull Secret is applied, then the Job, then the Service and Traefik resources it
        // owns.
        this.api
                .server()
                .expect()
                .patch()
                .withPath("/api/v1/namespaces/" + NAMESPACE
                        + "/secrets/registry-auth-alice?fieldManager=skaha&force=true")
                .andReturn(
                        200,
                        new SecretBuilder()
                                .withNewMetadata()
                                .withName("registry-auth-alice")
                                .endMetadata()
                                .build())
                .once();
        final Request launch = new Request(null)
                .param("type", kind)
                .param("image", SessionFixtures.image(kind))
                .param("name", "analysis")
                .registryAuth("alice", "s3cret");
        if (this.type == SessionType.HEADLESS) {
            // Request values are typed fields, so YAML in them cannot add objects or fields, and even the image
            // placeholder stays as given.
            launch.param("cmd", HEADLESS_COMMAND).param("args", HEADLESS_ARGS);
            HEADLESS_ENV.forEach(env -> launch.param("env", env));
        }
        final String sessionID = this.requests.post(launch).text().trim();

        final Job job = onlyJob(client, sessionID);
        Assert.assertEquals(kind, job.getMetadata().getLabels().get("canfar.net/kind"));
        Assert.assertEquals(USER, job.getMetadata().getLabels().get("canfar.net/username"));
        final var container =
                job.getSpec().getTemplate().getSpec().getContainers().get(0);
        Assert.assertEquals(SessionFixtures.image(kind), container.getImage());
        Assert.assertEquals(
                "registry-auth-alice",
                job.getSpec()
                        .getTemplate()
                        .getSpec()
                        .getImagePullSecrets()
                        .get(0)
                        .getName());
        if (this.type == SessionType.HEADLESS) {
            Assert.assertEquals(List.of(HEADLESS_COMMAND), container.getCommand());
            Assert.assertEquals(List.of(HEADLESS_ARGS.split(" ")), container.getArgs());
            Assert.assertEquals("\"x\": y", envValue(container, "QUOTE"));
            Assert.assertEquals("line1\nline2", envValue(container, "MULTI"));
            Assert.assertEquals("a=b", envValue(container, "EQ"));
            Assert.assertTrue(
                    container.getEnv().stream().noneMatch(env -> env.getName().isEmpty()));
            Assert.assertEquals(
                    1, job.getSpec().getTemplate().getSpec().getContainers().size());
        }
        Assert.assertEquals(1, this.requests.posixWrites);
        Mockito.verify(this.requests.userStorage).ensureUserBase(USER);
        Mockito.verify(this.requests.userStorage).injectProxyCertificate(USER);

        final List<Service> createdServices = client.services().list().getItems();
        Assert.assertEquals(this.services, createdServices.size());
        for (final Service service : createdServices) {
            assertOwnedBy(service, job);
            assertSessionLabels(service, sessionID, kind);
            Assert.assertEquals(
                    Map.of("canfar.net/id", sessionID, "canfar.net/kind", kind),
                    service.getSpec().getSelector());
        }
        final List<GenericKubernetesResource> traefik = new ArrayList<>();
        for (final String traefikKind : List.of("Middleware", "IngressRoute")) {
            traefik.addAll(client.genericKubernetesResources("traefik.io/v1alpha1", traefikKind)
                    .list()
                    .getItems());
        }
        Assert.assertEquals(this.traefikResources, traefik.size());
        traefik.forEach(resource -> {
            assertOwnedBy(resource, job);
            assertSessionLabels(resource, sessionID, kind);
        });

        // List: the new session shows at once (read-after-write), with Job status and pod usage.
        final String podName = job.getMetadata().getName() + "-x1y2z";
        expectPodMetrics(podName);
        final Map<String, Object> listed =
                onlySession(this.requests.get(new Request(null)).text());
        Assert.assertEquals(sessionID, listed.get("id"));
        Assert.assertEquals(kind, listed.get("type"));
        Assert.assertEquals(Session.STATUS_PENDING, listed.get("status"));
        Assert.assertEquals("1.367", listed.get("cpuCoresInUse"));

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
        expectEvents(job.getMetadata().getName(), "Normal", "SuccessfulCreate", "Created pod: " + podName);
        expectEvents(podName, "Warning", "FailedMount", "MountVolume.SetUp failed");
        final String[] events = this.requests
                .get(new Request(sessionID).param("view", "events"))
                .text()
                .split("\n");
        Assert.assertEquals(3, events.length);
        Assert.assertTrue(events[0], events[0].matches("TYPE +REASON +MESSAGE +FIRST-TIME +LAST-TIME"));
        // Sorted by event name: the Pod's ("<job>-x1y2z.1") before the Job's ("<job>.1").
        Assert.assertTrue(events[1], events[1].startsWith("Warning   FailedMount"));
        Assert.assertTrue(
                events[2], events[2].matches("Normal +SuccessfulCreate +Created pod: \\S+ +2026\\S+ +<none>"));

        this.api
                .server()
                .expect()
                .get()
                .withPath("/api/v1/namespaces/" + NAMESPACE + "/pods/" + podName + "/log?container="
                        + container.getName())
                .andReturn(200, "session log\n")
                .once();
        Assert.assertEquals(
                "session log\n",
                this.requests.get(new Request(sessionID).param("view", "logs")).text());

        // Renew 2h in: a deadline with less than the configured expiry left moves to 2h + expiry (interactive kinds);
        // one with more is left alone (headless).
        final Instant start = Instant.now().minus(Duration.ofHours(2));
        final long deadline = job.getSpec().getActiveDeadlineSeconds();
        final long expiry = Long.parseLong(System.getenv("skaha.sessionexpiry"));
        client.batch().v1().jobs().withName(job.getMetadata().getName()).edit(current -> new JobBuilder(current)
                .editOrNewStatus()
                .withActive(1)
                .withStartTime(start.toString())
                .endStatus()
                .build());
        this.requests.post(new Request(sessionID).param("action", "renew"));
        final long renewed = onlyJob(client, sessionID).getSpec().getActiveDeadlineSeconds();
        if (deadline - Duration.ofHours(2).toSeconds() < expiry) {
            Assert.assertEquals(Duration.ofHours(2).toSeconds() + expiry, renewed, 5);
        } else {
            Assert.assertEquals(deadline, renewed);
        }

        if (this.type == SessionType.DESKTOP) {
            // Attach a desktop app to the running desktop; the desktop's Job owns it. Then delete it.
            final String appID = this.requests
                    .post(new Request(sessionID + "/app").param("image", REGISTRY_TERMINAL))
                    .text()
                    .trim();
            final Job app = client.batch()
                    .v1()
                    .jobs()
                    .withLabel("canfar.net/app-id", appID)
                    .list()
                    .getItems()
                    .get(0);
            assertOwnedBy(app, job);
            Assert.assertEquals(
                    REGISTRY_TERMINAL,
                    app.getSpec().getTemplate().getSpec().getContainers().get(0).getImage());
            this.requests.delete(new Request(sessionID + "/app/" + appID));
            Assert.assertTrue(client.batch()
                    .v1()
                    .jobs()
                    .withLabel("canfar.net/app-id", appID)
                    .list()
                    .getItems()
                    .isEmpty());
        }

        // Delete: the listing drops the session once the watch delivers the deletion.
        this.requests.delete(new Request(sessionID));
        Assert.assertTrue(client.batch().v1().jobs().list().getItems().isEmpty());
        KubernetesApi.await("the listing to drop the session", () -> {
            try {
                return this.requests.get(new Request(null)).text().trim().equals("[]");
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private static final String REGISTRY_TERMINAL = SessionRequests.REGISTRY + "/skaha/terminal:1.0";
    private static final String HEADLESS_COMMAND = "/bin/sh -c \"echo: hi\"";
    private static final String HEADLESS_ARGS = "-c print(\"a: b\")\n---\nkind: Secret --tag skaha-session-image";
    private static final List<String> HEADLESS_ENV =
            List.of("QUOTE=\"x\": y", "MULTI=line1\nline2", "EQ=a=b", "=noname");

    private static Job onlyJob(final KubernetesClient client, final String sessionID) {
        final List<Job> jobs = client.batch()
                .v1()
                .jobs()
                .withLabel("canfar.net/id", sessionID)
                .withoutLabel("canfar.net/app-id")
                .list()
                .getItems();
        Assert.assertEquals(1, jobs.size());
        return jobs.get(0);
    }

    private static Map<String, Object> onlySession(final String json) {
        final List<Map<String, Object>> sessions =
                new Gson().fromJson(json, new TypeToken<List<Map<String, Object>>>() {}.getType());
        Assert.assertEquals(json, 1, sessions.size());
        return sessions.get(0);
    }

    private static String envValue(final io.fabric8.kubernetes.api.model.Container container, final String name) {
        return container.getEnv().stream()
                .filter(env -> name.equals(env.getName()))
                .findFirst()
                .orElseThrow()
                .getValue();
    }

    private void expectPodMetrics(final String podName) {
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
                                .addToItems(new EventBuilder()
                                        .withNewMetadata()
                                        .withName(objectName + ".1")
                                        .endMetadata()
                                        .withType(type)
                                        .withReason(reason)
                                        .withMessage(message)
                                        .withFirstTimestamp("2026-10-23T12:00:00Z")
                                        .build())
                                .build())
                .once();
    }

    /** Owned resources carry the launch's session labels and none of Kueue's. */
    private static void assertSessionLabels(final HasMetadata resource, final String sessionID, final String kind) {
        final Map<String, String> labels = resource.getMetadata().getLabels();
        Assert.assertEquals(sessionID, labels.get("canfar.net/id"));
        Assert.assertEquals(kind, labels.get("canfar.net/kind"));
        Assert.assertEquals(USER, labels.get("canfar.net/username"));
        Assert.assertEquals("analysis", labels.get("canfar.net/name"));
        Assert.assertTrue(labels.keySet().stream().noneMatch(key -> key.startsWith("kueue.x-k8s.io/")));
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
