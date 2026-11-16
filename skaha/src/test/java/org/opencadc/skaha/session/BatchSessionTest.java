package org.opencadc.skaha.session;

import static org.opencadc.skaha.KubernetesApi.NAMESPACE;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.PodFailurePolicyRule;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.opencadc.skaha.KubernetesApi;
import org.opencadc.skaha.metrics.PlatformMetricsFixtures;
import org.opencadc.skaha.metrics.SessionMetrics;
import org.opencadc.skaha.session.SessionRequests.Request;
import org.opencadc.skaha.session.SessionRequests.Response;

/**
 * A batch through the session actions against a mock Kubernetes API, with the chart's batch template: create, list,
 * info, replica logs, and the failure document batch requests return. The session cache is off, so reads are live.
 */
public class BatchSessionTest {
    private final KubernetesApi api = new KubernetesApi();
    private final SessionRequests requests = new SessionRequests();

    @Rule
    public final RuleChain rules = RuleChain.outerRule(this.api).around(this.requests);

    @Before
    public void cluster() {
        this.api.clusterQueue("cq", "default");
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
                .always();
    }

    @After
    public void productionMetrics() {
        PlatformMetricsFixtures.useAsDefault(null);
    }

    @Test
    public void createsOneIndexedJob() throws Exception {
        final String id = this.requests
                .post(batch("field-reduction")
                        .param("replicas", "50")
                        .param("cmd", "python")
                        .param("args", "/arc/projects/demo/worker.py")
                        .param("env", "FIELD=north")
                        .param("env", "MY_CANFAR_TOKEN=t0k3n"))
                .text()
                .trim();

        final Job job = job(id);
        Assert.assertEquals("batch-" + id, job.getMetadata().getName());
        Assert.assertEquals(
                1,
                this.api
                        .client()
                        .batch()
                        .v1()
                        .jobs()
                        .withLabel("canfar.net/id", id)
                        .list()
                        .getItems()
                        .size());
        Assert.assertEquals(Boolean.TRUE, job.getSpec().getSuspend());
        Assert.assertEquals("Indexed", job.getSpec().getCompletionMode());
        Assert.assertEquals(Integer.valueOf(50), job.getSpec().getCompletions());
        Assert.assertEquals(Integer.valueOf(50), job.getSpec().getParallelism());
        Assert.assertEquals("1", job.getMetadata().getAnnotations().get("kueue.x-k8s.io/job-min-parallelism"));
        Assert.assertEquals(Integer.valueOf(0), job.getSpec().getBackoffLimitPerIndex());
        Assert.assertNull(job.getSpec().getBackoffLimit());
        Assert.assertNull(job.getSpec().getMaxFailedIndexes());
        Assert.assertEquals(Long.valueOf(1209600), job.getSpec().getActiveDeadlineSeconds());
        Assert.assertEquals(Integer.valueOf(86400), job.getSpec().getTtlSecondsAfterFinished());
        final PodFailurePolicyRule rule =
                job.getSpec().getPodFailurePolicy().getRules().getFirst();
        Assert.assertEquals("Ignore", rule.getAction());
        Assert.assertEquals(
                "DisruptionTarget", rule.getOnPodConditions().getFirst().getType());
        Assert.assertEquals("Never", job.getSpec().getTemplate().getSpec().getRestartPolicy());

        final Map<String, String> jobLabels = job.getMetadata().getLabels();
        final Map<String, String> podLabels =
                job.getSpec().getTemplate().getMetadata().getLabels();
        for (final Map<String, String> labels : List.of(jobLabels, podLabels)) {
            Assert.assertEquals("batch", labels.get("canfar.net/kind"));
            Assert.assertEquals(id, labels.get("canfar.net/id"));
            Assert.assertEquals("alice", labels.get("canfar.net/username"));
            Assert.assertEquals("field-reduction", labels.get("canfar.net/name"));
        }
        Assert.assertEquals("alice-default-lq", jobLabels.get("kueue.x-k8s.io/queue-name"));
        Assert.assertTrue(podLabels.keySet().stream().noneMatch(key -> key.startsWith("kueue.x-k8s.io/")));

        final Container container =
                job.getSpec().getTemplate().getSpec().getContainers().getFirst();
        Assert.assertEquals(List.of("python"), container.getCommand());
        Assert.assertEquals(List.of("/arc/projects/demo/worker.py"), container.getArgs());
        Assert.assertEquals("north", env(container, "FIELD").getValue());
        Assert.assertEquals(
                "only a CANFAR_ prefix is reserved",
                "t0k3n",
                env(container, "MY_CANFAR_TOKEN").getValue());
        Assert.assertEquals("50", env(container, "CANFAR_REPLICA_COUNT").getValue());
        Assert.assertEquals(
                "metadata.annotations['batch.kubernetes.io/job-completion-index']",
                env(container, "CANFAR_REPLICA_INDEX")
                        .getValueFrom()
                        .getFieldRef()
                        .getFieldPath());
        Assert.assertEquals("batch", env(container, "CANFAR_SESSION_KIND").getValue());
        Assert.assertEquals(id, env(container, "CANFAR_SESSION_ID").getValue());
        Assert.assertEquals("alice", env(container, "CANFAR_USERNAME").getValue());
        Assert.assertTrue(container.getEnv().stream()
                .noneMatch(env ->
                        env.getName().equals("REPLICA_ID") || env.getName().equals("REPLICA_COUNT")));
    }

    @Test
    public void oneReplicaAsksNoMinimumParallelism() throws Exception {
        final Job job = job(this.requests.post(batch("single")).text().trim());

        Assert.assertEquals(Integer.valueOf(1), job.getSpec().getCompletions());
        Assert.assertEquals(Integer.valueOf(1), job.getSpec().getParallelism());
        Assert.assertFalse(job.getMetadata().getAnnotations().containsKey("kueue.x-k8s.io/job-min-parallelism"));
    }

    /** The K-th failed index stops the Job, K = max(1, ceil(maxfail * replicas)); maxFailedIndexes is K - 1. */
    @Test
    public void maxfailSetsMaxFailedIndexes() throws Exception {
        final Object[][] rows = {
            {null, 50, null},
            {"0", 50, 0},
            {"0.0", 50, 0},
            {"0.25", 50, 12},
            {"0.25", 4, 0},
            {"0.25", 3, 0},
            {"1", 50, null},
            {"1.0", 50, null}
        };
        for (final Object[] row : rows) {
            final Request create = batch("maxfail").param("replicas", row[1].toString());
            if (row[0] != null) {
                create.param("maxfail", (String) row[0]);
            }
            final Job job = job(this.requests.post(create).text().trim());
            Assert.assertEquals(Arrays.toString(row), row[2], job.getSpec().getMaxFailedIndexes());
        }
    }

    @Test
    public void createErrorsOfABatchRequestAreAStatusDocument() throws Exception {
        assertStatus(400, "BadRequest", this.requests.run("POST", batch("bad").param("maxfail", "1.1")));
        assertStatus(400, "BadRequest", this.requests.run("POST", batch("bad").param("replicas", "257")));
        assertStatus(400, "BadRequest", this.requests.run("POST", batch("bad").param("env", "CANFAR_FOO=x")));
        assertStatus(
                400,
                "BadRequest",
                this.requests.run("POST", launch("headless", "bad").param("maxfail", "0.5")));

        // Not batch requests: cadc-rest's text/plain error.
        for (final Request notBatch : List.of(
                launch("notebook", "bad").param("replicas", "2"),
                launch("notebook", "bad").param("env", "CANFAR_FOO=x"),
                launch("headless", "bad").param("env", "canfar_registry__secret=x"))) {
            assertPlain(400, this.requests.run("POST", notBatch));
        }
        Assert.assertTrue(
                "no Job for a rejected create",
                this.api.client().batch().v1().jobs().list().getItems().isEmpty());
    }

    /** A maxfail whose rounding would take seconds is refused before any work. */
    @Test(timeout = 10_000)
    public void maxfailWithAHugeScaleIsRefusedFast() throws Exception {
        assertStatus(400, "BadRequest", this.requests.run("POST", batch("bad").param("maxfail", "1e-30000000")));
        Assert.assertTrue(
                this.api.client().batch().v1().jobs().list().getItems().isEmpty());
    }

    /** Kubernetes sets completionTime only on success; a failed batch ends at its Failed condition. */
    @Test
    public void failedBatchEndsAtItsFailedCondition() throws Exception {
        final String id = this.requests
                .post(batch("failing").param("replicas", "2").param("maxfail", "0"))
                .text()
                .trim();
        this.api.client().batch().v1().jobs().withName("batch-" + id).edit(job -> new JobBuilder(job)
                .editSpec()
                .withSuspend(false)
                .endSpec()
                .editOrNewStatus()
                .withStartTime("2026-11-13T17:06:02Z")
                .withFailed(1)
                .addNewCondition()
                .withType("Failed")
                .withStatus("True")
                .withReason("MaxFailedIndexesExceeded")
                .withLastTransitionTime("2026-11-13T17:09:40Z")
                .endCondition()
                .endStatus()
                .build());

        final Map<String, Object> entry =
                sessions(this.requests.get(new Request(null))).get(id);
        Assert.assertEquals("Failed", entry.get("status"));
        Assert.assertEquals("2026-11-13T17:09:40Z", entry.get("completionTime"));
        final JsonNode status = new ObjectMapper()
                .readTree(this.requests.get(new Request(id)).text())
                .get("status");
        Assert.assertEquals("Failed", status.get("phase").asText());
        Assert.assertEquals("2026-11-13T17:09:40Z", status.get("completionTime").asText());
    }

    @Test
    public void listShowsBatchProgressAndHeldResources() throws Exception {
        final String notebook =
                this.requests.post(launch("notebook", "notebook")).text().trim();
        final String reported = this.requests
                .post(batch("reported").param("replicas", "4"))
                .text()
                .trim();
        final String unreported = this.requests.post(batch("unreported")).text().trim();
        admit(reported, 3, 2, 1);
        PlatformMetricsFixtures.useAsDefault(PlatformMetricsFixtures.metricsDAOWithSessionReports(Map.of(
                reported,
                new SessionMetrics(
                        reported,
                        List.of(
                                new SessionMetrics.Resource("cpu", "4", "3", "1500m", "0.5"),
                                new SessionMetrics.Resource("memory", "16Gi", "12Gi", "6Gi", "0.5"))))));

        final Map<String, Map<String, Object>> listed = sessions(this.requests.get(new Request(null)));
        Assert.assertEquals(Set.of(notebook, reported, unreported), listed.keySet());
        final Map<String, Object> entry = listed.get(reported);
        Assert.assertEquals("batch", entry.get("type"));
        Assert.assertEquals("Running", entry.get("status"));
        Assert.assertEquals("", entry.get("connectURL"));
        Assert.assertEquals(
                Map.of("total", 4.0, "succeeded", 1.0, "failed", 0.0, "active", 2.0, "incomplete", 1.0),
                entry.get("replicas"));
        Assert.assertEquals("3", entry.get("requestedCPUCores"));
        Assert.assertEquals("1.500", entry.get("cpuCoresInUse"));
        Assert.assertEquals("12.88", entry.get("requestedRAM"));
        Assert.assertEquals("6.44", entry.get("ramInUse"));
        Assert.assertEquals("0", entry.get("requestedGPUCores"));
        Assert.assertEquals("2026-11-13T17:06:02Z", entry.get("startTime"));
        Assert.assertEquals("2026-11-27T17:06:02Z", entry.get("expiryTime"));
        Assert.assertFalse("completionTime is left out until the Job finishes", entry.containsKey("completionTime"));

        final Map<String, Object> pending = listed.get(unreported);
        Assert.assertEquals("Pending", pending.get("status"));
        Assert.assertFalse("no report, no held resources", pending.containsKey("requestedCPUCores"));
        Assert.assertFalse(pending.containsKey("cpuCoresInUse"));
        Assert.assertNull(
                "a notebook has no replicas block", listed.get(notebook).get("replicas"));

        Assert.assertEquals(
                Set.of(reported, unreported),
                sessions(this.requests.get(new Request(null).param("type", "batch")))
                        .keySet());
        Assert.assertEquals(
                Set.of(notebook),
                sessions(this.requests.get(new Request(null).param("view", "interactive")))
                        .keySet());
    }

    @Test
    public void infoIsTheBatchDocument() throws Exception {
        final String id = this.requests
                .post(batch("field-reduction").param("replicas", "3").param("cmd", "python"))
                .text()
                .trim();
        admit(id, 2, 1, 1);
        replicaPod(id, 0, "Succeeded");
        replicaPod(id, 1, "Pending");

        final Response unreported = this.requests.get(new Request(id));
        Assert.assertEquals("application/json", unreported.contentType());
        JsonNode document = new ObjectMapper().readTree(unreported.text());
        Assert.assertEquals("canfar.net/v1alpha1", document.get("apiVersion").asText());
        Assert.assertEquals("Batch", document.get("kind").asText());
        Assert.assertEquals(id, document.get("metadata").get("name").asText());
        Assert.assertEquals(
                "batch",
                document.get("metadata").get("labels").get("canfar.net/kind").asText());
        Assert.assertEquals(3, document.get("spec").get("replicas").asInt());
        Assert.assertTrue(document.get("spec").get("maxfail").isNull());
        Assert.assertEquals("python", document.get("spec").get("command").get(0).asText());
        final JsonNode status = document.get("status");
        Assert.assertEquals("Running", status.get("phase").asText());
        Assert.assertEquals(3, status.get("replicas").size());
        Assert.assertEquals(
                "Succeeded", status.get("replicas").get(0).get("phase").asText());
        Assert.assertEquals(0, status.get("replicas").get(0).get("exitCode").asInt());
        Assert.assertEquals(
                "Pending", status.get("replicas").get(1).get("phase").asText());
        Assert.assertTrue(status.get("replicas").get(1).get("exitCode").isNull());
        Assert.assertEquals(
                "Pending", status.get("replicas").get(2).get("phase").asText());
        Assert.assertEquals(1, status.get("active").asInt());
        Assert.assertEquals(1, status.get("incomplete").asInt());
        Assert.assertEquals(0, status.get("resources").size());
        Assert.assertEquals(
                "Unavailable", status.get("conditions").get(3).get("reason").asText());

        PlatformMetricsFixtures.useAsDefault(PlatformMetricsFixtures.metricsDAOWithSessionReports(Map.of(
                id, new SessionMetrics(id, List.of(new SessionMetrics.Resource("cpu", "3", "2", "1.2", "0.6"))))));
        document =
                new ObjectMapper().readTree(this.requests.get(new Request(id)).text());
        Assert.assertEquals(
                "2",
                document.get("status").get("resources").get(0).get("requests").asText());
        Assert.assertEquals(
                "Available",
                document.get("status").get("conditions").get(3).get("reason").asText());
    }

    @Test
    public void logsNameAReplica() throws Exception {
        final String id =
                this.requests.post(batch("logs").param("replicas", "3")).text().trim();
        final String zero = replicaPod(id, 0, "Succeeded");
        final String one = replicaPod(id, 1, "Running");
        final String container = "batch-" + id;
        expectLog(zero, container, "replica zero\n");
        expectLog(one, container, "replica one\n");

        final Response log =
                this.requests.get(new Request(id).param("view", "logs").param("replica", "1"));
        Assert.assertEquals("replica one\n", log.text());
        Assert.assertEquals("text/plain", log.contentType());

        assertStatus(400, "BadRequest", this.requests.run("GET", new Request(id).param("view", "logs")));
        assertStatus(
                400,
                "BadRequest",
                this.requests.run("GET", new Request(id).param("view", "logs").param("replica", "3")));
        assertStatus(
                400,
                "BadRequest",
                this.requests.run("GET", new Request(id).param("view", "logs").param("replica", "one")));
        assertStatus(
                404,
                "NotFound",
                this.requests.run("GET", new Request(id).param("view", "logs").param("replica", "2")));

        // A replica does not make a request a batch request: these keep cadc-rest's text/plain error.
        assertPlain(
                404,
                this.requests.run(
                        "GET", new Request("nope1234").param("view", "logs").param("replica", "0")));
        final String notebook =
                this.requests.post(launch("notebook", "notebook")).text().trim();
        assertPlain(
                400,
                this.requests.run(
                        "GET", new Request(notebook).param("view", "logs").param("replica", "0")));
    }

    @Test
    public void batchesAreNotRenewedAndDeleteInTheBackground() throws Exception {
        final String id = this.requests.post(batch("short")).text().trim();
        admit(id, 1, 1, 0);
        final IllegalArgumentException renew = Assert.assertThrows(
                IllegalArgumentException.class, () -> this.requests.post(new Request(id).param("action", "renew")));
        Assert.assertTrue(renew.getMessage(), renew.getMessage().contains("batch"));

        this.requests.delete(new Request(id));
        Assert.assertTrue(
                this.api.client().batch().v1().jobs().list().getItems().isEmpty());
        Assert.assertTrue(sessions(this.requests.get(new Request(null))).isEmpty());
    }

    private static Request batch(final String name) {
        return launch("batch", name);
    }

    private static Request launch(final String kind, final String name) {
        return new Request(null)
                .param("type", kind)
                .param("image", SessionFixtures.image(kind))
                .param("name", name)
                .registryAuth("alice", "s3cret");
    }

    private Job job(final String id) {
        final Job job =
                this.api.client().batch().v1().jobs().withName("batch-" + id).get();
        Assert.assertNotNull("Job batch-" + id, job);
        return job;
    }

    /** Kueue admits the Job at {@code parallelism}; the controller reports its counters. */
    private void admit(final String id, final int parallelism, final int active, final int succeeded) {
        this.api.client().batch().v1().jobs().withName("batch-" + id).edit(job -> new JobBuilder(job)
                .editSpec()
                .withSuspend(false)
                .withParallelism(parallelism)
                .endSpec()
                .editOrNewStatus()
                .withStartTime("2026-11-13T17:06:02Z")
                .withActive(active)
                .withSucceeded(succeeded)
                .endStatus()
                .build());
    }

    /** A Pod of the batch's Job for one index; a Succeeded one exited 0, a Pending one is not scheduled. */
    private String replicaPod(final String id, final int index, final String phase) {
        final Job job = job(id);
        final String name = job.getMetadata().getName() + "-" + index + "-x1y2z";
        final PodBuilder pod = new PodBuilder()
                .withNewMetadata()
                .withName(name)
                .withLabels(job.getSpec().getTemplate().getMetadata().getLabels())
                .addToLabels(Batch.COMPLETION_INDEX, Integer.toString(index))
                .endMetadata()
                .withSpec(job.getSpec().getTemplate().getSpec())
                .withNewStatus()
                .withPhase(phase)
                .endStatus();
        if ("Succeeded".equals(phase)) {
            pod.editStatus()
                    .addNewContainerStatus()
                    .withName(job.getMetadata().getName())
                    .withNewState()
                    .withNewTerminated()
                    .withExitCode(0)
                    .endTerminated()
                    .endState()
                    .endContainerStatus()
                    .endStatus();
        }
        this.api.client().pods().resource(pod.build()).create();
        return name;
    }

    private void expectLog(final String podName, final String container, final String log) {
        this.api
                .server()
                .expect()
                .get()
                .withPath("/api/v1/namespaces/" + NAMESPACE + "/pods/" + podName + "/log?container=" + container)
                .andReturn(200, log)
                .once();
    }

    private static Map<String, Map<String, Object>> sessions(final Response response) {
        final List<Map<String, Object>> sessions =
                new Gson().fromJson(response.text(), new TypeToken<List<Map<String, Object>>>() {}.getType());
        return sessions.stream().collect(Collectors.toMap(session -> (String) session.get("id"), session -> session));
    }

    private static EnvVar env(final Container container, final String name) {
        final List<EnvVar> matches = container.getEnv().stream()
                .filter(env -> name.equals(env.getName()))
                .toList();
        Assert.assertEquals(name, 1, matches.size());
        return matches.getFirst();
    }

    private static void assertPlain(final int code, final Response response) {
        Assert.assertEquals(response.text(), code, response.code);
        Assert.assertEquals("text/plain", response.contentType());
    }

    private static void assertStatus(final int code, final String reason, final Response response) throws Exception {
        Assert.assertEquals(response.text(), code, response.code);
        Assert.assertEquals("application/json", response.contentType());
        final JsonNode status = new ObjectMapper().readTree(response.text());
        Assert.assertEquals("v1", status.get("apiVersion").asText());
        Assert.assertEquals("Status", status.get("kind").asText());
        Assert.assertEquals("Failure", status.get("status").asText());
        Assert.assertEquals(reason, status.get("reason").asText());
        Assert.assertEquals(code, status.get("code").asInt());
        Assert.assertFalse(status.get("message").asText().isBlank());
    }
}
