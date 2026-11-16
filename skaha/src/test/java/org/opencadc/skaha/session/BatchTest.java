package org.opencadc.skaha.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobConditionBuilder;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;
import org.opencadc.skaha.metrics.SessionMetrics;

/** The Batch document read from a batch Job, its Pods and its Metrics report. */
public class BatchTest {
    private static final Instant OBSERVED = Instant.parse("2026-11-13T18:20:00.250Z");
    private static final String CONTAINER = "batch-k7nq2wpx";

    /** Four replicas, three admitted: 0 succeeded, 1 has an unscheduled Pod, 2 runs (after a failed try), 3 waits. */
    @Test
    public void replicaRowsFollowTheNewestPodOfEachIndex() {
        final Job job = job(4, 3, false, null)
                .editStatus()
                .withStartTime("2026-11-13T17:06:02Z")
                .withSucceeded(1)
                .withActive(2)
                .endStatus()
                .build();
        final List<Pod> pods = List.of(
                pod(0, "2026-11-13T17:06:03Z", "Succeeded", terminated(0, "17:06:04Z", "17:21:10Z")),
                pod(1, "2026-11-13T17:06:03Z", "Pending", null),
                pod(2, "2026-11-13T17:06:03Z", "Failed", terminated(2, "17:06:04Z", "17:09:40Z")),
                pod(2, "2026-11-13T18:02:10Z", "Running", running("18:02:11Z")));

        final Batch batch = Batch.of(job, pods, null, OBSERVED);

        Assert.assertEquals("Batch", batch.kind());
        Assert.assertEquals("k7nq2wpx", batch.metadata().name());
        Assert.assertEquals(4, batch.spec().replicas());
        Assert.assertNull(batch.spec().maxfail());
        Assert.assertEquals(new Batch.Resources("1", "4Gi"), batch.spec().resources());
        Assert.assertEquals(
                List.of(
                        new Batch.Replica(0, "Succeeded", 0, "2026-11-13T17:06:04Z", "2026-11-13T17:21:10Z"),
                        new Batch.Replica(1, "Pending", null, null, null),
                        new Batch.Replica(2, "Running", null, "2026-11-13T18:02:11Z", null),
                        new Batch.Replica(3, "Pending", null, null, null)),
                batch.status().replicas());
        Assert.assertEquals(Session.STATUS_RUNNING, batch.status().phase());
        Assert.assertEquals(1, batch.status().succeeded());
        Assert.assertEquals(
                "the unscheduled Pod counts as active", 2, batch.status().active());
        Assert.assertEquals(1, batch.status().incomplete());
        Assert.assertEquals("2026-11-13T18:20:00Z", batch.status().observedAt());
        Assert.assertEquals(
                List.of(
                        new Batch.Condition("Admitted", "True", "PartialAdmission", "2026-11-13T17:06:02Z"),
                        new Batch.Condition("Complete", "False", "IndexesIncomplete", "2026-11-13T17:06:00Z"),
                        new Batch.Condition("Failed", "False", "IndexesIncomplete", "2026-11-13T17:06:00Z"),
                        new Batch.Condition("Resources", "False", "Unavailable", "2026-11-13T18:20:00Z")),
                batch.status().conditions());
        Assert.assertEquals(List.of(), batch.status().resources());
    }

    @Test
    public void suspendedBatchIsPendingAndNotAdmitted() {
        final Batch batch = Batch.of(job(50, 50, true, "0.25").build(), List.of(), null, OBSERVED);

        Assert.assertEquals(Session.STATUS_PENDING, batch.status().phase());
        Assert.assertEquals(new BigDecimal("0.25"), batch.spec().maxfail());
        Assert.assertEquals(50, batch.status().replicas().size());
        Assert.assertEquals(50, batch.status().incomplete());
        Assert.assertEquals(
                new Batch.Condition("Admitted", "False", "Suspended", "2026-11-13T17:06:00Z"),
                batch.status().conditions().getFirst());
    }

    @Test
    public void finishedBatchTakesTheJobOutcome() {
        final Job failed = job(4, 4, false, "0.25")
                .editStatus()
                .withStartTime("2026-11-13T17:06:02Z")
                .withCompletionTime("2026-11-13T17:30:00Z")
                .withSucceeded(3)
                .withFailed(1)
                .addToConditions(new JobConditionBuilder()
                        .withType("Failed")
                        .withStatus("True")
                        .withReason("MaxFailedIndexesExceeded")
                        .withLastTransitionTime("2026-11-13T17:30:00Z")
                        .build())
                .endStatus()
                .build();
        final Batch batch = Batch.of(failed, List.of(), null, OBSERVED);
        Assert.assertEquals(Session.STATUS_FAILED, batch.status().phase());
        Assert.assertEquals("2026-11-13T17:30:00Z", batch.status().completionTime());
        Assert.assertEquals(
                new Batch.Condition("Failed", "True", "MaxFailedIndexesExceeded", "2026-11-13T17:30:00Z"),
                batch.status().conditions().get(2));
        Assert.assertEquals(
                new Batch.Condition("Admitted", "True", "Ready", "2026-11-13T17:06:02Z"),
                batch.status().conditions().getFirst());

        final Job complete = job(4, 4, false, null)
                .editStatus()
                .withStartTime("2026-11-13T17:06:02Z")
                .withSucceeded(4)
                .addToConditions(new JobConditionBuilder()
                        .withType("Complete")
                        .withStatus("True")
                        .withLastTransitionTime("2026-11-13T17:40:00Z")
                        .build())
                .endStatus()
                .build();
        final Batch done = Batch.of(complete, List.of(), null, OBSERVED);
        Assert.assertEquals(Session.STATUS_COMPLETED, done.status().phase());
        Assert.assertEquals(
                new Batch.Condition("Complete", "True", "AllIndexesSucceeded", "2026-11-13T17:40:00Z"),
                done.status().conditions().get(1));
    }

    /** The Metrics rows are copied as Metrics wrote them; a value Metrics left out stays out. */
    @Test
    public void documentWritesNullsAndCopiesMetricsRows() throws Exception {
        final SessionMetrics report = new SessionMetrics(
                "k7nq2wpx",
                List.of(
                        new SessionMetrics.Resource("cpu", "4", "3", "1.2", "0.4"),
                        new SessionMetrics.Resource("nvidia.com/gpu", "0", "0", null, null)));
        final String json = SessionAction.DOCUMENTS.writeValueAsString(
                Batch.of(job(4, 4, true, null).build(), List.of(), report, OBSERVED));

        final JsonNode document = new ObjectMapper().readTree(json);
        Assert.assertTrue(json, document.get("spec").get("maxfail").isNull());
        Assert.assertTrue(json, document.get("status").get("startTime").isNull());
        Assert.assertTrue(json, document.get("status").get("completionTime").isNull());
        Assert.assertTrue(
                json,
                document.get("status").get("replicas").get(0).get("exitCode").isNull());
        Assert.assertEquals("Pending", document.get("status").get("phase").asText());
        Assert.assertEquals(
                new ObjectMapper()
                        .readTree(
                                "[{\"name\":\"cpu\",\"total\":\"4\",\"requests\":\"3\",\"usage\":\"1.2\",\"efficiency\":\"0.4\"},"
                                        + "{\"name\":\"nvidia.com/gpu\",\"total\":\"0\",\"requests\":\"0\"}]"),
                document.get("status").get("resources"));
        Assert.assertEquals(
                "True",
                document.get("status").get("conditions").get(3).get("status").asText());

        final String withMaxfail = SessionAction.DOCUMENTS.writeValueAsString(
                Batch.of(job(4, 4, true, "0.25").build(), List.of(), report, OBSERVED));
        Assert.assertTrue(withMaxfail, withMaxfail.contains("\"maxfail\" : 0.25,"));
    }

    private static JobBuilder job(
            final int replicas, final int parallelism, final boolean suspended, final String maxfail) {
        final JobBuilder job = new JobBuilder()
                .withNewMetadata()
                .withName(CONTAINER)
                .withCreationTimestamp("2026-11-13T17:06:00Z")
                .withLabels(SessionLabels.canonical(Map.of(
                        SessionLabels.Key.ID, "k7nq2wpx",
                        SessionLabels.Key.USERNAME, "bob",
                        SessionLabels.Key.NAME, "field-reduction",
                        SessionLabels.Key.KIND, "batch",
                        SessionLabels.Key.COMMUNITY, "cadc")))
                .endMetadata()
                .withNewSpec()
                .withCompletionMode("Indexed")
                .withCompletions(replicas)
                .withParallelism(parallelism)
                .withSuspend(suspended)
                .withNewTemplate()
                .withNewSpec()
                .addNewContainer()
                .withName(CONTAINER)
                .withImage("images.canfar.net/skaha/astroml:latest")
                .withCommand("python")
                .withArgs("/arc/projects/demo/worker.py")
                .withNewResources()
                .addToRequests("cpu", new Quantity("1.0"))
                .addToRequests("memory", new Quantity("4.0Gi"))
                .endResources()
                .endContainer()
                .endSpec()
                .endTemplate()
                .endSpec()
                .withNewStatus()
                .endStatus();
        if (maxfail != null) {
            job.editMetadata()
                    .addToAnnotations(BatchSpec.MAXFAIL_ANNOTATION, maxfail)
                    .endMetadata();
        }
        return job;
    }

    private static Pod pod(
            final int index, final String created, final String phase, final ContainerStatus containerStatus) {
        final PodBuilder pod = new PodBuilder()
                .withNewMetadata()
                .withName(CONTAINER + "-" + index + "-" + created.hashCode())
                .withCreationTimestamp(created)
                .addToLabels(Batch.COMPLETION_INDEX, Integer.toString(index))
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName(CONTAINER)
                .endContainer()
                .endSpec()
                .withNewStatus()
                .withPhase(phase)
                .endStatus();
        if (containerStatus != null) {
            pod.editStatus().addToContainerStatuses(containerStatus).endStatus();
        }
        return pod.build();
    }

    private static ContainerStatus terminated(final int exitCode, final String started, final String finished) {
        return new ContainerStatusBuilder()
                .withName(CONTAINER)
                .withNewState()
                .withNewTerminated()
                .withExitCode(exitCode)
                .withStartedAt("2026-11-13T" + started)
                .withFinishedAt("2026-11-13T" + finished)
                .endTerminated()
                .endState()
                .build();
    }

    private static ContainerStatus running(final String started) {
        return new ContainerStatusBuilder()
                .withName(CONTAINER)
                .withNewState()
                .withNewRunning()
                .withStartedAt("2026-11-13T" + started)
                .endRunning()
                .endState()
                .build();
    }
}
