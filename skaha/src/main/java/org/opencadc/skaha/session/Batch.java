package org.opencadc.skaha.session;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStateRunning;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.opencadc.skaha.metrics.SessionMetrics;

/**
 * The Batch document, {@code GET /v1/session/{id}} for a batch: the request, the Job's progress, one row per replica,
 * and the Metrics resource rows. Written in full: a field without a value is null.
 */
record Batch(String apiVersion, String kind, Metadata metadata, Spec spec, Status status) {
    /** The Pod label (and annotation) that holds a replica's index. */
    static final String COMPLETION_INDEX = "batch.kubernetes.io/job-completion-index";

    private static final BigDecimal GIBIBYTE = BigDecimal.valueOf(1L << 30);
    private static final String TRUE = "True";
    private static final String FALSE = "False";
    private static final String INDEXES_INCOMPLETE = "IndexesIncomplete";
    private static final List<SessionLabels.Key> IDENTITY_LABELS = List.of(
            SessionLabels.Key.ID,
            SessionLabels.Key.KIND,
            SessionLabels.Key.NAME,
            SessionLabels.Key.USERNAME,
            SessionLabels.Key.COMMUNITY,
            SessionLabels.Key.PROJECT);

    /** The session id, when the Job was created, and its identity labels. */
    record Metadata(String name, String creationTimestamp, Map<String, String> labels) {}

    /** The request, read back from the Job. {@code resources} is one Pod's request. */
    record Spec(
            int replicas,
            BigDecimal maxfail,
            String image,
            List<String> command,
            List<String> args,
            Resources resources) {}

    /** CPU in cores and memory in {@code Gi}, the Metrics quantity format. */
    record Resources(String cpu, String memory) {}

    /**
     * The Job's progress at {@code observedAt}, with the Metrics rows for this session.
     *
     * @param phase One of the session statuses Pending, Running, Completed or Failed; see {@link #phase(Job)}.
     */
    record Status(
            String observedAt,
            String phase,
            String startTime,
            String completionTime,
            int succeeded,
            int failed,
            int active,
            int incomplete,
            List<SessionMetrics.Resource> resources,
            List<Replica> replicas,
            List<Condition> conditions) {}

    /** One index: the phase and app-container exit of its newest Pod, or Pending when it has none. */
    record Replica(int id, String phase, Integer exitCode, String startTime, String completionTime) {}

    record Condition(String type, String status, String reason, String lastTransitionTime) {}

    /**
     * The Job's index counters, as the session list shows them. {@code incomplete} indexes have no Pod.
     *
     * @param total The replicas.
     * @param active Includes Pods that are not yet scheduled.
     */
    record Progress(int total, int succeeded, int failed, int active, int incomplete) {
        static Progress of(final Job job) {
            final int total = BatchSpec.fromJob(job).replicas();
            final JobStatus status = Objects.requireNonNullElse(job.getStatus(), new JobStatus());
            final int succeeded = Objects.requireNonNullElse(status.getSucceeded(), 0);
            final int failed = Objects.requireNonNullElse(status.getFailed(), 0);
            final int active = Objects.requireNonNullElse(status.getActive(), 0);
            return new Progress(total, succeeded, failed, active, Math.max(0, total - succeeded - failed - active));
        }
    }

    static boolean is(final HasMetadata resource) {
        return SessionType.BATCH.applicationName.equals(SessionLabels.get(resource, SessionLabels.Key.KIND));
    }

    /** Failed or Completed once the Job says so; otherwise Pending until Kueue admits the Job, then Running. */
    static String phase(final Job job) {
        final JobStatus status = job.getStatus();
        final String terminal = status == null ? null : SessionBuilder.terminalCondition(status);
        if (terminal != null) {
            return terminal;
        }
        if (Boolean.TRUE.equals(job.getSpec().getSuspend()) || status == null || status.getStartTime() == null) {
            return Session.STATUS_PENDING;
        }
        return Session.STATUS_RUNNING;
    }

    /**
     * When the Job finished, or null while it runs. Kubernetes sets {@code completionTime} only when a Job succeeds, so
     * a failed Job ends at its true {@code Failed} condition.
     */
    static String completionTime(final Job job) {
        final JobStatus status = Objects.requireNonNullElse(job.getStatus(), new JobStatus());
        if (status.getCompletionTime() != null) {
            return status.getCompletionTime();
        }
        final JobCondition finished = trueCondition(status, "Failed", "Complete");
        return finished == null ? null : finished.getLastTransitionTime();
    }

    /**
     * Read a batch.
     *
     * @param job The batch's Job.
     * @param pods The session's Pods.
     * @param metrics The Metrics report for the session, or null when it could not be read.
     * @param observedAt When the Job and Pods were read.
     */
    static Batch of(final Job job, final List<Pod> pods, final SessionMetrics metrics, final Instant observedAt) {
        final String observed = observedAt.truncatedTo(ChronoUnit.SECONDS).toString();
        final BatchSpec request = BatchSpec.fromJob(job);
        final Progress progress = Progress.of(job);
        final JobStatus jobStatus = Objects.requireNonNullElse(job.getStatus(), new JobStatus());
        final Container app =
                job.getSpec().getTemplate().getSpec().getContainers().getFirst();

        final Map<String, String> labels = new LinkedHashMap<>();
        for (final SessionLabels.Key key : IDENTITY_LABELS) {
            final String value = SessionLabels.get(job, key);
            if (value != null) {
                labels.put(key.label(), value);
            }
        }

        final Map<Integer, Pod> newestPods = pods.stream()
                .filter(pod -> completionIndex(pod) != null)
                .collect(Collectors.toMap(
                        Batch::completionIndex, Function.identity(), BinaryOperator.maxBy(SessionPods.BY_CREATION)));

        return new Batch(
                "canfar.net/v1alpha1",
                "Batch",
                new Metadata(
                        SessionLabels.get(job, SessionLabels.Key.ID),
                        job.getMetadata().getCreationTimestamp(),
                        labels),
                new Spec(
                        request.replicas(),
                        request.maxfail(),
                        app.getImage(),
                        List.copyOf(Objects.requireNonNullElse(app.getCommand(), List.of())),
                        List.copyOf(Objects.requireNonNullElse(app.getArgs(), List.of())),
                        resources(app)),
                new Status(
                        observed,
                        phase(job),
                        jobStatus.getStartTime(),
                        completionTime(job),
                        progress.succeeded(),
                        progress.failed(),
                        progress.active(),
                        progress.incomplete(),
                        metrics == null ? List.of() : metrics.resources(),
                        IntStream.range(0, request.replicas())
                                .mapToObj(index -> replica(index, newestPods.get(index)))
                                .toList(),
                        conditions(job, request.replicas(), metrics != null, observed)));
    }

    private static Integer completionIndex(final Pod pod) {
        try {
            return Integer.valueOf(Objects.requireNonNullElse(pod.getMetadata().getLabels(), Map.<String, String>of())
                    .get(COMPLETION_INDEX));
        } catch (NumberFormatException noIndex) {
            return null;
        }
    }

    private static Replica replica(final int index, final Pod pod) {
        if (pod == null) {
            return new Replica(index, Session.STATUS_PENDING, null, null, null);
        }
        final String phase = pod.getStatus() == null || pod.getStatus().getPhase() == null
                ? Session.STATUS_PENDING
                : pod.getStatus().getPhase();
        final ContainerState state = appContainerState(pod);
        final ContainerStateRunning running = state == null ? null : state.getRunning();
        final ContainerStateTerminated terminated = state == null ? null : state.getTerminated();
        if (terminated != null) {
            return new Replica(
                    index, phase, terminated.getExitCode(), terminated.getStartedAt(), terminated.getFinishedAt());
        }
        return new Replica(index, phase, null, running == null ? null : running.getStartedAt(), null);
    }

    private static ContainerState appContainerState(final Pod pod) {
        if (pod.getStatus() == null) {
            return null;
        }
        final String app = SessionPods.appContainer(pod);
        return Objects.requireNonNullElse(pod.getStatus().getContainerStatuses(), List.<ContainerStatus>of()).stream()
                .filter(containerStatus -> app.equals(containerStatus.getName()))
                .map(ContainerStatus::getState)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static Resources resources(final Container app) {
        final Map<String, Quantity> requests = Objects.requireNonNullElse(
                Objects.requireNonNullElse(app.getResources(), new ResourceRequirements())
                        .getRequests(),
                Map.of());
        final Quantity cpu = requests.get("cpu");
        final Quantity memory = requests.get("memory");
        return new Resources(
                cpu == null ? null : plain(cpu.getNumericalAmount()),
                memory == null ? null : plain(memory.getNumericalAmount().divide(GIBIBYTE)) + "Gi");
    }

    private static String plain(final BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static List<Condition> conditions(
            final Job job, final int replicas, final boolean metricsRead, final String observedAt) {
        final String created = job.getMetadata().getCreationTimestamp();
        final JobStatus status = job.getStatus();

        final Condition admitted;
        if (Boolean.TRUE.equals(job.getSpec().getSuspend())) {
            final JobCondition suspended = trueCondition(status, "Suspended");
            admitted = new Condition(
                    "Admitted", FALSE, "Suspended", suspended == null ? created : suspended.getLastTransitionTime());
        } else {
            // Kueue writes the admitted parallelism before it clears suspend.
            final int parallelism = Objects.requireNonNullElse(job.getSpec().getParallelism(), replicas);
            admitted = new Condition(
                    "Admitted",
                    TRUE,
                    parallelism < replicas ? "PartialAdmission" : "Ready",
                    status == null || status.getStartTime() == null ? created : status.getStartTime());
        }

        final JobCondition complete = trueCondition(status, "Complete", "SuccessCriteriaMet");
        final JobCondition failed = trueCondition(status, "Failed", "FailureTarget");
        return List.of(
                admitted,
                complete == null
                        ? new Condition("Complete", FALSE, INDEXES_INCOMPLETE, created)
                        : new Condition("Complete", TRUE, "AllIndexesSucceeded", complete.getLastTransitionTime()),
                failed == null
                        ? new Condition("Failed", FALSE, INDEXES_INCOMPLETE, created)
                        : new Condition("Failed", TRUE, failed.getReason(), failed.getLastTransitionTime()),
                metricsRead
                        ? new Condition("Resources", TRUE, "Available", observedAt)
                        : new Condition("Resources", FALSE, "Unavailable", observedAt));
    }

    /** The Job's true condition of the first of these types it has, or null. */
    private static JobCondition trueCondition(final JobStatus status, final String... types) {
        if (status == null) {
            return null;
        }
        for (final String type : types) {
            for (final JobCondition condition :
                    Objects.requireNonNullElse(status.getConditions(), List.<JobCondition>of())) {
                if (type.equals(condition.getType()) && TRUE.equalsIgnoreCase(condition.getStatus())) {
                    return condition;
                }
            }
        }
        return null;
    }
}
