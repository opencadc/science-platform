package org.opencadc.skaha;

import ca.nrc.cadc.auth.AuthMethod;
import ca.nrc.cadc.auth.AuthenticationUtil;
import ca.nrc.cadc.auth.AuthorizationToken;
import ca.nrc.cadc.auth.NotAuthenticatedException;
import ca.nrc.cadc.auth.SSLUtil;
import ca.nrc.cadc.auth.SSOCookieCredential;
import ca.nrc.cadc.auth.X509CertificateChain;
import ca.nrc.cadc.net.HttpPost;
import ca.nrc.cadc.net.NetUtil;
import ca.nrc.cadc.net.NetrcFile;
import ca.nrc.cadc.reg.Standards;
import ca.nrc.cadc.reg.client.RegistryClient;
import ca.nrc.cadc.util.FileUtil;
import ca.nrc.cadc.util.StringUtil;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.MissingResourceException;
import javax.security.auth.Subject;
import org.apache.log4j.Logger;

/** Integration test configuration for the v1 API. */
public class TestConfiguration {
    private static final Logger LOGGER = Logger.getLogger(TestConfiguration.class.getName());

    public static final URI DEFAULT_SKAHA_SERVICE_ID = URI.create("ivo://cadc.nrc.ca/skaha");
    public static final URI DEFAULT_GMS_SERVICE_ID = URI.create("ivo://cadc.nrc.ca/gms");
    public static final String DEFAULT_DESKTOP_IMAGE_ID = "images.canfar.net/skaha/desktop:latest";
    public static final String DEFAULT_CARTA_IMAGE_ID = "images.canfar.net/skaha/carta:5.0.3";

    static URI getSkahaServiceID() {
        final String configuredServiceID = System.getenv("SKAHA_SERVICE_ID");
        final URI skahaServiceID = StringUtil.hasText(configuredServiceID)
                ? URI.create(configuredServiceID)
                : TestConfiguration.DEFAULT_SKAHA_SERVICE_ID;
        LOGGER.info("Skaha Service ID: " + skahaServiceID);
        return skahaServiceID;
    }

    static URI getGMSServiceID() {
        final String configuredServiceID = System.getenv("GMS_SERVICE_ID");
        final URI gmsServiceID = StringUtil.hasText(configuredServiceID)
                ? URI.create(configuredServiceID)
                : TestConfiguration.DEFAULT_GMS_SERVICE_ID;
        LOGGER.info("GMS Service ID: " + gmsServiceID);
        return gmsServiceID;
    }

    static String getDesktopImageID() {
        final String configuredImageID = System.getenv("DESKTOP_IMAGE_ID");
        final String desktopImageID =
                StringUtil.hasText(configuredImageID) ? configuredImageID : TestConfiguration.DEFAULT_DESKTOP_IMAGE_ID;
        LOGGER.info("Desktop Image ID: " + desktopImageID);

        return desktopImageID;
    }

    static String getCARTAImageID() {
        final String configuredImageID = System.getenv("CARTA_IMAGE_ID");
        final String desktopImageID =
                StringUtil.hasText(configuredImageID) ? configuredImageID : TestConfiguration.DEFAULT_CARTA_IMAGE_ID;
        LOGGER.info("CARTA Image ID: " + desktopImageID);

        return desktopImageID;
    }

    private static AuthorizationToken getBearerToken(final URL sessionURL) throws Exception {
        final File bearerTokenFile = FileUtil.getFileFromResource("skaha-test.token", SessionUtil.class);
        final String bearerToken = new String(Files.readAllBytes(bearerTokenFile.toPath()));
        return new AuthorizationToken(
                "Bearer", bearerToken.replaceAll("\n", ""), List.of(NetUtil.getDomainName(sessionURL)));
    }

    private static X509CertificateChain getProxyCertificate() throws Exception {
        final File proxyCertificateFile = FileUtil.getFileFromResource("skaha-test.pem", SessionUtil.class);
        return SSLUtil.readPemCertificateAndKey(proxyCertificateFile);
    }

    private static String getCookieValue(URL loginURL) throws Exception {
        final NetrcFile netrcFile = new NetrcFile();
        final PasswordAuthentication passwordAuthentication = netrcFile.getCredentials(loginURL.getHost(), true);
        final Map<String, Object> loginPayload = new HashMap<>();
        loginPayload.put("username", passwordAuthentication.getUserName());
        loginPayload.put("password", String.valueOf(passwordAuthentication.getPassword()));

        try (final ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            final HttpPost httpPost = new HttpPost(loginURL, loginPayload, outputStream);
            httpPost.run();

            outputStream.flush();
            return outputStream.toString();
        }
    }

    /**
     * Read in the current user's credentials from the local path.
     *
     * @param sessionURL The current URL to use to deduce a domain.
     * @return Subject instance, never null.
     */
    static Subject getCurrentUser(final URL sessionURL, final boolean allowAnonymous) throws Exception {
        final Subject subject = new Subject();

        try {
            final AuthorizationToken bearerToken = TestConfiguration.getBearerToken(sessionURL);
            subject.getPublicCredentials().add(bearerToken);
            subject.getPublicCredentials().add(AuthMethod.TOKEN);
            return subject;
        } catch (MissingResourceException noTokenFile) {
            LOGGER.warn("No bearer token (skaha-test.token) found in path.");
        }

        try {
            final X509CertificateChain proxyCertificate = TestConfiguration.getProxyCertificate();
            subject.getPublicCredentials().add(proxyCertificate);
            subject.getPublicCredentials().add(AuthMethod.CERT);
            return subject;
        } catch (MissingResourceException noProxyCertificate) {
            LOGGER.warn("No proxy certificate (skaha-test.pem) found in path.");
        }

        final RegistryClient registryClient = new RegistryClient();
        final URL loginURL = registryClient.getServiceURL(
                TestConfiguration.getGMSServiceID(), Standards.UMS_LOGIN_10, AuthMethod.ANON);
        final String cookieValue = TestConfiguration.getCookieValue(loginURL);
        LOGGER.info("Using cookie value: " + cookieValue);
        subject.getPublicCredentials().add(new SSOCookieCredential(cookieValue, NetUtil.getDomainName(sessionURL)));
        subject.getPublicCredentials().add(new SSOCookieCredential(cookieValue, "cadc-ccda.hia-iha.nrc-cnrc.gc.ca"));
        subject.getPublicCredentials().add(new SSOCookieCredential(cookieValue, "canfar.net"));
        subject.getPublicCredentials().add(AuthMethod.COOKIE);

        if (AuthenticationUtil.getAuthMethod(subject) == AuthMethod.ANON && !allowAnonymous) {
            throw new NotAuthenticatedException("No credentials supplied and anonymous not allowed.");
        }

        return subject;
    }
}
