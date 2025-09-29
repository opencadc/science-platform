package org.opencadc.skaha.session;

import io.kubernetes.client.openapi.models.V1JobStatus;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class SessionBuilderTest {
    @Before
    public void setupHomePath() {
        System.setProperty("SKAHA_USER_STORAGE_TOP_LEVEL_DIRECTORY", "/tmp");
        System.setProperty("SKAHA_USER_STORAGE_HOME_BASE_DIRECTORY", "/tmp/skaha-test");
        System.setProperty("SKAHA_USER_STORAGE_PROJECTS_BASE_DIRECTORY", "/tmp/skaha-test-projects");
        System.setProperty("SKAHA_USER_STORAGE_SERVICE_URI", "ivo://example.org/skaha/userStorage");
        System.setProperty("SKAHA_USER_STORAGE_USER_HOME_URI", "vos://example.org~skaha/home");
        System.setProperty("SKAHA_USER_STORAGE_USER_PROJECTS_URI", "vos://example.org~skaha/projects");
    }

    @After
    public void removeHomePath() {
        System.getProperties().remove("SKAHA_USER_STORAGE_TOP_LEVEL_DIRECTORY ");
        System.getProperties().remove("SKAHA_USER_STORAGE_HOME_BASE_DIRECTORY");
        System.getProperties().remove("SKAHA_USER_STORAGE_PROJECTS_BASE_DIRECTORY");
        System.getProperties().remove("SKAHA_USER_STORAGE_SERVICE_URI");
        System.getProperties().remove("SKAHA_USER_STORAGE_USER_HOME_URI");
        System.getProperties().remove("SKAHA_USER_STORAGE_USER_PROJECTS_URI");
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
