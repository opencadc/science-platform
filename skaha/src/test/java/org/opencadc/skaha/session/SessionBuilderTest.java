package org.opencadc.skaha.session;

import io.kubernetes.client.openapi.models.V1JobStatus;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

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
}
