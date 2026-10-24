package org.opencadc.skaha.session;

import ca.nrc.cadc.auth.PosixPrincipal;
import ca.nrc.cadc.net.TransientException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;
import org.opencadc.skaha.KubernetesJob;

public class PostActionLaunchTest {
    private static final String JOB_NAME = "skaha-notebook-alice-abc123";
    private static final String SERVICE = "kind: Service\nuid: ${skaha.jobuid}\nowner: ${skaha.jobname}";
    private static final String INGRESS = "kind: IngressRoute\nuid: ${skaha.jobuid}";

    @Test
    public void testLaunchCreatesJobThenOwnedResources() throws Exception {
        final RecordingPostAction testSubject = new RecordingPostAction(-1);

        testSubject.submitSession("kind: Job", JOB_NAME, List.of(SERVICE, INGRESS));

        Assert.assertEquals(
                List.of(
                        "kind: Job",
                        "kind: Service\nuid: job-uid-1\nowner: " + JOB_NAME,
                        "kind: IngressRoute\nuid: job-uid-1"),
                testSubject.created);
        Assert.assertFalse("Job must not be deleted", testSubject.deleted);
    }

    @Test
    public void testJobCreateFailure() {
        final RecordingPostAction testSubject = new RecordingPostAction(0);

        Assert.assertThrows(
                TransientException.class, () -> testSubject.submitSession("kind: Job", JOB_NAME, List.of(SERVICE)));
        Assert.assertEquals(List.of(), testSubject.created);
        // The create may have landed despite the error, so cleanup always runs.
        Assert.assertTrue("Job must be deleted", testSubject.deleted);
    }

    @Test
    public void testOwnedResourceFailureDeletesJob() {
        final RecordingPostAction testSubject = new RecordingPostAction(2);

        Assert.assertThrows(
                TransientException.class,
                () -> testSubject.submitSession("kind: Job", JOB_NAME, List.of(SERVICE, INGRESS)));
        Assert.assertEquals(2, testSubject.created.size());
        Assert.assertTrue("Job must be deleted", testSubject.deleted);
    }

    @Test
    public void testJobReadFailureDeletesJob() {
        final RecordingPostAction testSubject = new RecordingPostAction(-1) {
            @Override
            KubernetesJob readJob(final String jobName) throws Exception {
                throw new IOException("read failed");
            }
        };

        Assert.assertThrows(
                TransientException.class, () -> testSubject.submitSession("kind: Job", JOB_NAME, List.of(SERVICE)));
        Assert.assertEquals(List.of("kind: Job"), testSubject.created);
        Assert.assertTrue("Job must be deleted", testSubject.deleted);
    }

    /** Records creates; the create at index {@code failAt} fails. */
    private static class RecordingPostAction extends PostAction {
        final List<String> created = new ArrayList<>();
        final int failAt;
        boolean deleted = false;
        private int attempts = 0;

        RecordingPostAction(final int failAt) {
            super(null);
            this.failAt = failAt;
            this.sessionID = "abc123";
            this.posixPrincipal = new PosixPrincipal(1000);
            this.posixPrincipal.username = "alice";
        }

        @Override
        void createKubernetesObject(final String yaml) throws IOException {
            if (this.attempts++ == this.failAt) {
                throw new IOException("Error executing command: kubectl create");
            }
            this.created.add(yaml);
        }

        @Override
        KubernetesJob readJob(final String jobName) throws Exception {
            return new KubernetesJob(jobName, "job-uid-1", this.sessionID, SessionType.NOTEBOOK);
        }

        @Override
        void deleteSessionJob() {
            this.deleted = true;
        }
    }
}
