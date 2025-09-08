package org.opencadc.skaha.session;

import org.junit.Assert;
import org.junit.Test;

public class SessionURLBuilderTest {
    @Test
    public void testVNCSession() throws Exception {
        final String vncURL =
                SessionURLBuilder.vncSession("host.example.org", "8675309").build();

        Assert.assertEquals(
                "Wrong URL",
                "https://host.example.org/session/desktop/8675309/?password=8675309&path=session/desktop/8675309/",
                vncURL);

        try {
            SessionURLBuilder.vncSession(null, "8675309").build();
            Assert.fail("Expected NullPointerException");
        } catch (NullPointerException e) {
            // Expected
        }
    }

    @Test
    public void testNotebookSession() throws Exception {
        final SessionURLBuilder.NotebookSessionURLBuilder testSubject =
                SessionURLBuilder.notebookSession("host.example.org", "8675309");

        try {
            testSubject.build();
            Assert.fail("Expected NullPointerException");
        } catch (NullPointerException nullPointerException) {
            // Good.
        }

        final SessionURLBuilder.NotebookSessionURLBuilder testSubjectWithTLD =
                testSubject.withTopLevelDirectory("/top-level-dir/sub-dir");

        try {
            testSubjectWithTLD.build();
            Assert.fail("Expected NullPointerException");
        } catch (NullPointerException nullPointerException) {
            // Good.
        }

        final SessionURLBuilder.NotebookSessionURLBuilder testSubjectWithUserName =
                testSubjectWithTLD.withUserName("username");

        Assert.assertEquals(
                "Wrong Notebook URL",
                "https://host.example.org/session/notebook/8675309/lab/tree/top-level-dir/sub-dir/home/username?token=8675309",
                testSubjectWithUserName.build());

        try {
            SessionURLBuilder.notebookSession(null, "8675309").build();
            Assert.fail("Expected NullPointerException");
        } catch (NullPointerException e) {
            // Expected
        }
    }

    @Test
    public void testCartaSession() throws Exception {
        Assert.assertEquals(
                "Wrong Carta URL",
                "https://host.example.org/session/carta/http/8675309/",
                SessionURLBuilder.cartaSession("host.example.org", "8675309").build());

        Assert.assertEquals(
                "Wrong Carta URL",
                "https://host.example.org/session/carta/http/8675309/?socketUrl=wss://host.example.org/session/carta/ws/8675309/",
                SessionURLBuilder.cartaSession("host.example.org", "8675309")
                        .withAlternateSocket(true)
                        .build());

        final SessionURLBuilder.CartaSessionURLBuilder testSubjectWithCARTA5Path = SessionURLBuilder.cartaSession(
                        "cartahost.example.org", "8675309")
                .withVersion5Path(true);
        Assert.assertEquals(
                "Wrong Carta 5 URL",
                "https://cartahost.example.org/session/carta/8675309/",
                testSubjectWithCARTA5Path.build());

        try {
            SessionURLBuilder.cartaSession(null, "8675309").build();
            Assert.fail("Expected NullPointerException");
        } catch (NullPointerException e) {
            // Expected
        }
    }

    @Test
    public void testContributedSession() throws Exception {
        final String contributedURL = SessionURLBuilder.contributedSession("host.example.org", "8675309")
                .build();

        Assert.assertEquals("Wrong URL", "https://host.example.org/session/contrib/8675309/", contributedURL);

        try {
            SessionURLBuilder.contributedSession(null, "8675309").build();
            Assert.fail("Expected NullPointerException");
        } catch (NullPointerException e) {
            // Expected
        }
    }

    @Test
    public void testFireflySession() throws Exception {
        final String fireflyURL =
                SessionURLBuilder.fireflySession("host.example.org", "8675309").build();

        Assert.assertEquals("Wrong URL", "https://host.example.org/session/firefly/8675309/firefly/", fireflyURL);

        try {
            SessionURLBuilder.fireflySession(null, "8675309").build();
            Assert.fail("Expected NullPointerException");
        } catch (NullPointerException e) {
            // Expected
        }
    }
}
