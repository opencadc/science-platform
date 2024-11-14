package org.opencadc.skaha;

import ca.nrc.cadc.auth.AuthMethod;
import ca.nrc.cadc.net.HttpGet;
import ca.nrc.cadc.reg.Standards;
import ca.nrc.cadc.reg.client.RegistryClient;
import ca.nrc.cadc.util.Log4jInit;
import ca.nrc.cadc.util.StringUtil;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.security.PrivilegedExceptionAction;
import java.util.Arrays;
import javax.security.auth.Subject;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.junit.Assert;
import org.junit.Test;

public class RepositoryHostsTest {
    private static final Logger log = Logger.getLogger(RepositoryHostsTest.class);

    static {
        Log4jInit.setLevel("org.opencadc.skaha", Level.INFO);
    }

    protected final URL repositoryURL;
    protected final Subject userSubject;

    public RepositoryHostsTest() throws Exception {
        final String configuredServiceEndpoint = System.getProperty("SKAHA_SERVICE_ENDPOINT");
        if (StringUtil.hasText(configuredServiceEndpoint)) {
            repositoryURL = new URL(configuredServiceEndpoint);
        } else {
            final RegistryClient regClient = new RegistryClient();
            final URL imageServiceURL = regClient.getServiceURL(
                    SessionUtil.getSkahaServiceID(), Standards.PROC_SESSIONS_10, AuthMethod.TOKEN);
            repositoryURL = new URL(imageServiceURL.toExternalForm() + "/repository");
        }
        log.info("sessions URL: " + repositoryURL);

        this.userSubject = SessionUtil.getCurrentUser(repositoryURL, false);
    }

    protected static String[] getRepositoryHosts(final URL serviceURLEndpoint) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final HttpGet get = new HttpGet(serviceURLEndpoint, out);
        get.run();
        Assert.assertNull("get repository hosts error", get.getThrowable());
        Assert.assertEquals("response code", 200, get.getResponseCode());
        Assert.assertEquals("content-type", "application/json", get.getContentType());
        final JSONArray jsonArray = new JSONArray(out.toString());
        return jsonArray.toList().stream().map(Object::toString).toArray(String[]::new);
    }

    @Test
    public void testGetImageList() {
        try {
            Subject.doAs(this.userSubject, (PrivilegedExceptionAction<Object>) () -> {
                // should have at least one image
                final String[] repositoryHosts = RepositoryHostsTest.getRepositoryHosts(this.repositoryURL);
                Assert.assertNotEquals(
                        "Should have at least one (" + Arrays.toString(repositoryHosts) + ")",
                        0,
                        repositoryHosts.length);
                return null;
            });

        } catch (Exception t) {
            log.error("unexpected throwable", t);
            Assert.fail("unexpected throwable: " + t);
        }
    }
}
