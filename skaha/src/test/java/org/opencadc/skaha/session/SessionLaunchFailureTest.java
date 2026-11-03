package org.opencadc.skaha.session;

import static org.opencadc.skaha.KubernetesApi.NAMESPACE;

import ca.nrc.cadc.net.TransientException;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.opencadc.skaha.KubernetesApi;

/**
 * How a launch ends for each Kubernetes API outcome. A launch either returns the created Job, or fails with nothing
 * left behind: never a session ID for a Job that cannot run, and never a blind second create.
 */
@RunWith(Parameterized.class)
public class SessionLaunchFailureTest {
    private static final String JOB_NAME = "skaha-notebook-alice-abc123";
    private static final String JOBS = "/apis/batch/v1/namespaces/" + NAMESPACE + "/jobs";
    private static final String SERVICE = "apiVersion: v1\nkind: Service\nmetadata:\n  name: svc-abc123\n"
            + "  ownerReferences:\n  - {apiVersion: batch/v1, kind: Job, name: \"${skaha.jobname}\", uid: \"${skaha.jobuid}\"}\n"
            + "spec:\n  ports:\n  - port: 8888\n";

    /**
     * One API outcome and what the launch must do.
     *
     * @param error null when the launch succeeds
     * @param uid the launched Job's UID on success, or null for "whatever the API assigned"
     */
    record Case(
            String name,
            Consumer<KubernetesMockServer> api,
            String appID,
            List<String> owned,
            int requestTimeoutMillis,
            Class<? extends Exception> error,
            String uid,
            boolean jobLeft,
            boolean deleteSent) {
        @Override
        public String toString() {
            return this.name;
        }
    }

    @Parameterized.Parameters(name = "{0}")
    public static List<Object[]> cases() {
        return List.of(
                row(new Case("created", api -> {}, null, List.of(SERVICE), 10_000, null, null, true, false)),
                row(new Case(
                        "409 from our own earlier attempt",
                        api -> conflictThenExisting(api, job(null, "uid-1")),
                        null,
                        List.of(),
                        10_000,
                        null,
                        "uid-1",
                        false,
                        false)),
                row(new Case(
                        "409 from another session",
                        api -> conflictThenExisting(api, withSession(job(null, "uid-x"), "other1")),
                        null,
                        List.of(),
                        10_000,
                        TransientException.class,
                        null,
                        false,
                        false)),
                row(new Case(
                        "409 from another desktop app",
                        api -> conflictThenExisting(api, job("app2", "uid-x")),
                        "app1",
                        List.of(),
                        10_000,
                        TransientException.class,
                        null,
                        false,
                        false)),
                row(new Case(
                        "503, not created: retried",
                        api -> uncertainThenAbsent(api, 503),
                        null,
                        List.of(),
                        10_000,
                        null,
                        null,
                        true,
                        false)),
                row(new Case(
                        "504 that landed",
                        api -> {
                            api.expect()
                                    .post()
                                    .withPath(JOBS)
                                    .andReturn(504, "")
                                    .once();
                            api.expect()
                                    .get()
                                    .withPath(JOBS + "/" + JOB_NAME)
                                    .andReturn(200, job(null, "uid-landed"))
                                    .once();
                        },
                        null,
                        List.of(),
                        10_000,
                        null,
                        "uid-landed",
                        false,
                        false)),
                row(new Case(
                        "request timeout, not created: retried",
                        api -> {
                            // The mock answers one request at a time: the delay must leave the read-back time to
                            // answer.
                            api.expect()
                                    .post()
                                    .withPath(JOBS)
                                    .andReply(201, request -> {
                                        sleep(1200);
                                        return job(null, "too-late");
                                    })
                                    .once();
                            api.expect()
                                    .get()
                                    .withPath(JOBS + "/" + JOB_NAME)
                                    .andReturn(404, "")
                                    .once();
                        },
                        null,
                        List.of(),
                        1000,
                        null,
                        null,
                        true,
                        false)),
                row(new Case(
                        "422 rejected: not retried",
                        api -> api.expect()
                                .post()
                                .withPath(JOBS)
                                .andReturn(422, "")
                                .once(),
                        null,
                        List.of(),
                        10_000,
                        IllegalStateException.class,
                        null,
                        false,
                        false)),
                row(new Case(
                        "uncertain every attempt",
                        api -> {
                            for (int i = 0; i < SessionLauncher.MAX_CREATE_ATTEMPTS; i++) {
                                uncertainThenAbsent(api, 503);
                            }
                        },
                        null,
                        List.of(),
                        10_000,
                        TransientException.class,
                        null,
                        false,
                        true)),
                row(new Case(
                        "owned Service fails: Job deleted",
                        api -> api.expect()
                                .post()
                                .withPath("/api/v1/namespaces/" + NAMESPACE + "/services")
                                .andReturn(500, "")
                                .once(),
                        null,
                        List.of(SERVICE),
                        10_000,
                        TransientException.class,
                        null,
                        false,
                        true)),
                row(new Case(
                        "503, then the read-back fails: Job deleted",
                        api -> {
                            api.expect()
                                    .post()
                                    .withPath(JOBS)
                                    .andReturn(503, "")
                                    .once();
                            api.expect()
                                    .get()
                                    .withPath(JOBS + "/" + JOB_NAME)
                                    .andReturn(500, "")
                                    .once();
                        },
                        null,
                        List.of(),
                        10_000,
                        TransientException.class,
                        null,
                        false,
                        true)),
                row(new Case(
                        "owned Service fails and so does the cleanup: launch still fails",
                        api -> {
                            api.expect()
                                    .post()
                                    .withPath("/api/v1/namespaces/" + NAMESPACE + "/services")
                                    .andReturn(500, "")
                                    .once();
                            api.expect()
                                    .delete()
                                    .withPath(JOBS + "/" + JOB_NAME)
                                    .andReturn(500, "")
                                    .once();
                        },
                        null,
                        List.of(SERVICE),
                        10_000,
                        TransientException.class,
                        null,
                        true,
                        true)),
                row(new Case(
                        "owned template does not parse: Job deleted",
                        api -> {},
                        null,
                        List.of("kind: [unclosed"),
                        10_000,
                        TransientException.class,
                        null,
                        false,
                        true)));
    }

    @Rule
    public final KubernetesApi api = new KubernetesApi();

    private final Case scenario;

    public SessionLaunchFailureTest(final Case scenario) {
        this.scenario = scenario;
    }

    @Test
    public void launch() throws Exception {
        this.scenario.api().accept(this.api.server());
        final Job job = job(this.scenario.appID(), null);

        try (KubernetesClient client = this.api.client(this.scenario.requestTimeoutMillis())) {
            final SessionLauncher launcher = new SessionLauncher(client);
            if (this.scenario.error() == null) {
                final Job launched = launcher.launch(job, labels(job), this.scenario.owned());
                Assert.assertEquals(JOB_NAME, launched.getMetadata().getName());
                if (this.scenario.uid() != null) {
                    Assert.assertEquals(
                            this.scenario.uid(), launched.getMetadata().getUid());
                }
            } else {
                Assert.assertThrows(
                        this.scenario.error(), () -> launcher.launch(job, labels(job), this.scenario.owned()));
            }
        }

        Assert.assertEquals(
                "Job left in the API",
                this.scenario.jobLeft(),
                this.api.client().batch().v1().jobs().withName(JOB_NAME).get() != null);
        Assert.assertEquals(
                "Cleanup DELETE sent",
                this.scenario.deleteSent(),
                this.api.requests().stream()
                        .anyMatch(request -> request.startsWith("DELETE " + JOBS + "/" + JOB_NAME)));
    }

    private static Object[] row(final Case scenario) {
        return new Object[] {scenario};
    }

    private static void conflictThenExisting(final KubernetesMockServer api, final Job existing) {
        api.expect().post().withPath(JOBS).andReturn(409, "").once();
        api.expect()
                .get()
                .withPath(JOBS + "/" + JOB_NAME)
                .andReturn(200, existing)
                .once();
    }

    private static void uncertainThenAbsent(final KubernetesMockServer api, final int code) {
        api.expect().post().withPath(JOBS).andReturn(code, "").once();
        api.expect().get().withPath(JOBS + "/" + JOB_NAME).andReturn(404, "").once();
    }

    private static Job withSession(final Job job, final String sessionID) {
        job.getMetadata().getLabels().put(SessionLabels.Key.ID.label(), sessionID);
        return job;
    }

    private static SessionLabelPlan labels(final Job job) {
        return SessionLabelPlan.of(job.getMetadata().getLabels());
    }

    private static Job job(final String appID, final String uid) {
        final JobBuilder builder = new JobBuilder()
                .withNewMetadata()
                .withName(JOB_NAME)
                .withUid(uid)
                .addToLabels(SessionLabels.Key.ID.label(), SessionFixtures.SESSION_ID)
                .addToLabels(SessionLabels.Key.KIND.label(), "notebook")
                .endMetadata()
                .withNewSpec()
                .withNewTemplate()
                .withNewSpec()
                .addNewContainer()
                .withName("main")
                .withImage("images.example.org/notebook:1")
                .endContainer()
                .withRestartPolicy("OnFailure")
                .endSpec()
                .endTemplate()
                .endSpec();
        if (appID != null) {
            builder.editMetadata()
                    .addToLabels(SessionLabels.Key.APP_ID.label(), appID)
                    .endMetadata();
        }
        return builder.build();
    }

    private static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
