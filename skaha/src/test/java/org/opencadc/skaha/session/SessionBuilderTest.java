package org.opencadc.skaha.session;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.api.model.batch.v1.JobSpec;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatus;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.opencadc.skaha.metrics.PodResourceUsage;

public class SessionBuilderTest {
    @Before
    public void setupHomePath() {
        TestUtil.setupUserStorageEnvironment();
    }

    @After
    public void removeHomePath() {
        TestUtil.tearDownUserStorageEnvironment();
    }

    @Test
    public void testStatusNull() {
        final SessionBuilder testSubject = new SessionBuilder("sessionID", "userID", "notebook");
        final SessionBuilder testSubjectFromNull = testSubject.withStatus(null, true);

        Assert.assertEquals(
                "Wrong status",
                Session.STATUS_PENDING,
                testSubjectFromNull.build().getStatus());
    }

    @Test
    public void testStatus() {
        final SessionBuilder testSubject = new SessionBuilder("sessionID", "userID", "notebook");

        testStatus(testSubject, false, false, false, false, Session.STATUS_PENDING);
        testStatus(testSubject, false, true, false, false, Session.STATUS_COMPLETED);
        testStatus(testSubject, false, false, false, true, Session.STATUS_PENDING);
        testStatus(testSubject, false, false, true, true, Session.STATUS_RUNNING);
        testStatus(testSubject, true, true, false, false, Session.STATUS_COMPLETED);
        testStatus(testSubject, true, false, false, false, Session.STATUS_FAILED);
    }

    @Test
    public void testStatusFromJobConditions() {
        final SessionBuilder testSubject = new SessionBuilder("sessionID", "userID", "notebook");

        // Pods were never created (e.g. rejected at admission), then the deadline passed: no counters, Failed
        // condition.
        Assert.assertEquals(
                Session.STATUS_FAILED,
                testSubject
                        .withStatus(statusWithCondition("Failed", "True", "DeadlineExceeded"), false)
                        .build()
                        .getStatus());

        // Failure decided while a pod is still terminating.
        final JobStatus failureTarget = statusWithCondition("FailureTarget", "True", "BackoffLimitExceeded");
        failureTarget.setActive(1);
        failureTarget.setReady(1);
        Assert.assertEquals(
                Session.STATUS_FAILED,
                testSubject.withStatus(failureTarget, false).build().getStatus());

        Assert.assertEquals(
                Session.STATUS_COMPLETED,
                testSubject
                        .withStatus(statusWithCondition("Complete", "True", null), false)
                        .build()
                        .getStatus());

        // A false condition and a Suspended condition do not decide the outcome.
        Assert.assertEquals(
                Session.STATUS_PENDING,
                testSubject
                        .withStatus(statusWithCondition("Failed", "False", null), false)
                        .build()
                        .getStatus());
        Assert.assertEquals(
                Session.STATUS_PENDING,
                testSubject
                        .withStatus(statusWithCondition("Suspended", "True", "JobSuspended"), true)
                        .build()
                        .getStatus());
    }

    private static JobStatus statusWithCondition(final String type, final String status, final String reason) {
        final JobCondition condition = new JobCondition();
        condition.setType(type);
        condition.setStatus(status);
        condition.setReason(reason);
        final JobStatus jobStatus = new JobStatus();
        jobStatus.setConditions(List.of(condition));
        return jobStatus;
    }

    private void testStatus(
            final SessionBuilder testSubject,
            final boolean failed,
            final boolean succeeded,
            final boolean ready,
            final boolean active,
            final String expectedStatus) {
        final JobStatus status = new JobStatus();
        if (failed) {
            status.setFailed(1);
        }
        if (succeeded) {
            status.setSucceeded(1);
        }
        if (ready) {
            status.setReady(1);
        }
        if (active) {
            status.setActive(1);
        }
        final SessionBuilder testSubjectWithStatus = testSubject.withStatus(status, false);
        Assert.assertEquals(
                "Wrong status", expectedStatus, testSubjectWithStatus.build().getStatus());
    }

    @Test
    public void fromJobReadsCanonicalLabelsAndFlavor() {
        final Job job = jobWithLabels(SessionLabels.canonical(Map.of(
                SessionLabels.Key.COMMUNITY, "astronomy",
                SessionLabels.Key.ID, "session-123",
                SessionLabels.Key.USERNAME, "alice",
                SessionLabels.Key.NAME, "analysis",
                SessionLabels.Key.KIND, "headless",
                SessionLabels.Key.APP_ID, "app-123",
                SessionLabels.Key.FLAVOR, "fixed",
                SessionLabels.Key.JOB, "headless-alice-session-123",
                SessionLabels.Key.ACCELERATOR, "none")));

        final Session session = SessionBuilder.fromJob(job, PodResourceUsage.empty());

        Assert.assertEquals("session-123", session.getId());
        Assert.assertEquals("alice", session.getUserid());
        Assert.assertEquals("analysis", session.getName());
        Assert.assertEquals("headless", session.getType());
        Assert.assertEquals("app-123", session.getAppId());
        Assert.assertEquals("2", session.getRequestedCPUCores());
        Assert.assertNotNull(session.getRequestedRAM());
    }

    private static Job jobWithLabels(final Map<String, String> labels) {
        final ObjectMeta metadata = new ObjectMeta();
        metadata.setName("headless-alice-session-123");
        metadata.setLabels(labels);

        final ResourceRequirements resources = new ResourceRequirements();
        resources.setRequests(Map.of("memory", new Quantity("2Gi"), "cpu", new Quantity("2")));
        resources.setLimits(Map.of("memory", new Quantity("2Gi"), "cpu", new Quantity("2")));

        final Container container = new Container();
        container.setImage("images.example.org/headless:latest");
        container.setResources(resources);

        final PodSpec podSpec = new PodSpec();
        podSpec.setContainers(List.of(container));

        final PodTemplateSpec podTemplate = new PodTemplateSpec();
        podTemplate.setSpec(podSpec);

        final JobSpec jobSpec = new JobSpec();
        jobSpec.setTemplate(podTemplate);

        final Job job = new Job();
        job.setMetadata(metadata);
        job.setSpec(jobSpec);
        return job;
    }
}
