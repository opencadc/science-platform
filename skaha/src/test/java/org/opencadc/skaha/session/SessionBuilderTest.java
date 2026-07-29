package org.opencadc.skaha.session;

import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobSpec;
import io.kubernetes.client.openapi.models.V1JobStatus;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1PodTemplateSpec;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
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

    private void testStatus(
            final SessionBuilder testSubject,
            final boolean failed,
            final boolean succeeded,
            final boolean ready,
            final boolean active,
            final String expectedStatus) {
        final V1JobStatus status = new V1JobStatus();
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
        final V1Job job = jobWithLabels(SessionLabels.canonical(Map.of(
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

    private static V1Job jobWithLabels(final Map<String, String> labels) {
        final V1ObjectMeta metadata = new V1ObjectMeta();
        metadata.setName("headless-alice-session-123");
        metadata.setLabels(labels);

        final V1ResourceRequirements resources = new V1ResourceRequirements();
        resources.setRequests(Map.of("memory", new Quantity("2Gi"), "cpu", new Quantity("2")));
        resources.setLimits(Map.of("memory", new Quantity("2Gi"), "cpu", new Quantity("2")));

        final V1Container container = new V1Container();
        container.setImage("images.example.org/headless:latest");
        container.setResources(resources);

        final V1PodSpec podSpec = new V1PodSpec();
        podSpec.setContainers(List.of(container));

        final V1PodTemplateSpec podTemplate = new V1PodTemplateSpec();
        podTemplate.setSpec(podSpec);

        final V1JobSpec jobSpec = new V1JobSpec();
        jobSpec.setTemplate(podTemplate);

        final V1Job job = new V1Job();
        job.setMetadata(metadata);
        job.setSpec(jobSpec);
        return job;
    }
}
