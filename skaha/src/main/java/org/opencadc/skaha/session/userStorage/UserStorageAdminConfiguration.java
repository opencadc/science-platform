package org.opencadc.skaha.session.userStorage;

import ca.nrc.cadc.auth.AuthMethod;
import ca.nrc.cadc.auth.AuthenticationUtil;
import ca.nrc.cadc.auth.AuthorizationToken;
import ca.nrc.cadc.auth.HttpPrincipal;
import ca.nrc.cadc.net.HttpGet;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.id.ClientID;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import javax.security.auth.Subject;
import org.apache.commons.configuration2.CombinedConfiguration;
import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.EnvironmentConfiguration;
import org.apache.commons.configuration2.SystemConfiguration;
import org.apache.commons.configuration2.convert.DefaultConversionHandler;
import org.apache.commons.configuration2.ex.ConversionException;
import org.apache.commons.configuration2.interpol.ConfigurationInterpolator;
import org.apache.commons.configuration2.tree.MergeCombiner;
import org.json.JSONObject;
import org.opencadc.vospace.ContainerNode;
import org.opencadc.vospace.NodeProperty;
import org.opencadc.vospace.VOS;

/**
 * Class to load the User Storage administrative configuration. Supports both OIDC and certificate-based authentication,
 * as well as Environment Variables and System Properties (for testing) for configuration.
 */
public class UserStorageAdminConfiguration {
    private static final String WELL_KNOWN_ENDPOINT = "/.well-known/openid-configuration";
    private static final String TOKEN_ENDPOINT_KEY = "token_endpoint";

    // User Storage administrative credentials.
    private static final String SKAHA_USER_STORAGE_ADMIN_USERNAME = "SKAHA_USER_STORAGE_ADMIN_USERNAME";
    private static final String SKAHA_USER_STORAGE_ADMIN_PASSWORD = "SKAHA_USER_STORAGE_ADMIN_PASSWORD";
    private static final String SKAHA_USER_STORAGE_ADMIN_OIDC_CLIENT_ID = "SKAHA_USER_STORAGE_ADMIN_OIDC_CLIENT_ID";
    private static final String SKAHA_USER_STORAGE_ADMIN_OIDC_CLIENT_SECRET =
            "SKAHA_USER_STORAGE_ADMIN_OIDC_CLIENT_SECRET";
    private static final String SKAHA_USER_STORAGE_ADMIN_OIDC_URI = "SKAHA_USER_STORAGE_ADMIN_OIDC_URI";
    private static final String SKAHA_USER_STORAGE_ADMIN_SERVICE_URI = "SKAHA_USER_STORAGE_ADMIN_SERVICE_URI";
    private static final String SKAHA_USER_STORAGE_ADMIN_USER_HOME_URI = "SKAHA_USER_STORAGE_ADMIN_USER_HOME_URI";

    // For Certificate-based authentication.
    private static final String SKAHA_USER_STORAGE_ADMIN_CERTIFICATE = "SKAHA_USER_STORAGE_ADMIN_CERTIFICATE";

    public final UserStorageAdministrator owner;
    public final URI serviceURI;
    public final URI userHomeBaseURI;

    /**
     * Create a UserStorageAdminConfiguration from the environment variables.
     *
     * @return A new UserStorageAdminConfiguration instance. Never null.
     */
    public static UserStorageAdminConfiguration fromEnv() {
        final CombinedConfiguration configuration = new CombinedConfiguration(new MergeCombiner());
        configuration.setConversionHandler(new URIConversionHandler());

        configuration.addConfiguration(new EnvironmentConfiguration());

        // Allow for system properties to override environment variables.  Useful for testing.
        configuration.addConfiguration(new SystemConfiguration());

        return new UserStorageAdminConfiguration(configuration);
    }

    private UserStorageAdminConfiguration(final Configuration configuration) {
        if (configuration.containsKey(UserStorageAdminConfiguration.SKAHA_USER_STORAGE_ADMIN_OIDC_CLIENT_ID)
                && configuration.containsKey(UserStorageAdminConfiguration.SKAHA_USER_STORAGE_ADMIN_CERTIFICATE)) {
            throw new IllegalArgumentException("Both OIDC client ID and certificate provided for User Storage admin. "
                    + "Please provide only one method of authentication.");
        } else if (configuration.containsKey(UserStorageAdminConfiguration.SKAHA_USER_STORAGE_ADMIN_OIDC_CLIENT_ID)) {
            final String clientID =
                    configuration.getString(UserStorageAdminConfiguration.SKAHA_USER_STORAGE_ADMIN_OIDC_CLIENT_ID);
            final String clientSecret =
                    configuration.getString(UserStorageAdminConfiguration.SKAHA_USER_STORAGE_ADMIN_OIDC_CLIENT_SECRET);
            final String issuerURI =
                    configuration.getString(UserStorageAdminConfiguration.SKAHA_USER_STORAGE_ADMIN_OIDC_URI);
            final String adminUsername =
                    configuration.getString(UserStorageAdminConfiguration.SKAHA_USER_STORAGE_ADMIN_USERNAME);
            final String adminPassword =
                    configuration.getString(UserStorageAdminConfiguration.SKAHA_USER_STORAGE_ADMIN_PASSWORD);
            owner = configureOIDCOwner(clientID, clientSecret, issuerURI, adminUsername, adminPassword);
        } else if (configuration.containsKey(UserStorageAdminConfiguration.SKAHA_USER_STORAGE_ADMIN_CERTIFICATE)) {
            final String certificateString =
                    configuration.getString(UserStorageAdminConfiguration.SKAHA_USER_STORAGE_ADMIN_CERTIFICATE);
            owner = configureCertificateOwner(certificateString);
        } else {
            throw new IllegalArgumentException("No OIDC client ID or certificate provided for User Storage admin.");
        }

        this.serviceURI = configuration.get(URI.class, SKAHA_USER_STORAGE_ADMIN_SERVICE_URI);
        this.userHomeBaseURI = configuration.get(URI.class, SKAHA_USER_STORAGE_ADMIN_USER_HOME_URI);
    }

    private UserStorageAdministrator configureOIDCOwner(
            String clientID, String clientSecret, String issuerURI, String adminUsername, String adminPassword) {
        Objects.requireNonNull(clientID, "OIDC client ID must not be null");
        Objects.requireNonNull(clientSecret, "OIDC client secret must not be null");
        Objects.requireNonNull(issuerURI, "OIDC issuer URI must not be null");
        Objects.requireNonNull(adminUsername, "Administrator username must not be null");
        Objects.requireNonNull(adminPassword, "Administrator password must not be null");

        return new UserStorageOIDCAdministrator(
                new ClientID(clientID),
                new Secret(clientSecret),
                URI.create(issuerURI),
                adminUsername,
                adminPassword.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Pull the Token Endpoint URL from the Well Known JSON document.
     *
     * @return URL of the Token Endpoint for access and refresh tokens. Never null.
     * @throws IOException For a poorly formed URL.
     */
    public static URI getTokenEndpoint(final URI oidcIssuerURI) throws IOException {
        final JSONObject jsonObject = UserStorageAdminConfiguration.getWellKnownJSON(oidcIssuerURI);
        final String tokenEndpointString = jsonObject.getString(UserStorageAdminConfiguration.TOKEN_ENDPOINT_KEY);
        return URI.create(tokenEndpointString);
    }

    /**
     * Obtain the .well-known endpoint JSON document. TODO: Cache this?
     *
     * @return The JSON Object of the response data.
     * @throws MalformedURLException If URLs cannot be created as expected.
     */
    private static JSONObject getWellKnownJSON(final URI oidcIssuerURI) throws IOException {
        final URL configurationURL = URI.create(
                        oidcIssuerURI.toString() + UserStorageAdminConfiguration.WELL_KNOWN_ENDPOINT)
                .toURL();
        final Writer writer = new StringWriter();
        final HttpGet httpGet = new HttpGet(configurationURL, inputStream -> {
            final Reader inputReader = new BufferedReader(new InputStreamReader(inputStream));
            final char[] buffer = new char[8192];
            int charsRead;
            while ((charsRead = inputReader.read(buffer)) >= 0) {
                writer.write(buffer, 0, charsRead);
            }
            writer.flush();
        });

        httpGet.run();

        return new JSONObject(writer.toString());
    }

    private UserStorageAdministrator configureCertificateOwner(final String certificateString) {
        Objects.requireNonNull(certificateString, "Certificate String must not be null");
        return new UserStorageCertificateAdministrator(certificateString.getBytes(StandardCharsets.UTF_8));
    }

    public void configureOwner(final ContainerNode userHomeNode, final Subject resourceOwner) {
        final AuthMethod authMethod = AuthenticationUtil.getAuthMethod(resourceOwner);
        if (authMethod == AuthMethod.TOKEN) {
            final AuthorizationToken accessToken = resourceOwner.getPublicCredentials(AuthorizationToken.class).stream()
                    .filter(token -> AuthenticationUtil.CHALLENGE_TYPE_BEARER.equalsIgnoreCase(token.getType()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No Bearer token found in Subject."));
            userHomeNode
                    .getProperties()
                    .add(new NodeProperty(VOS.PROPERTY_URI_CREATOR_JWT, accessToken.getCredentials()));
        } else {
            // Try to set the owner of the user home node from what Principals are in the Subject.
            final Set<HttpPrincipal> httpPrincipals = resourceOwner.getPrincipals(HttpPrincipal.class);
            if (httpPrincipals.isEmpty()) {
                throw new IllegalStateException(
                        "No HTTP Principal found in Subject. Cannot determine creator username.  Ensure the Subject has been properly authenticated and augmented.");
            } else {
                // Take the first username.
                final String creatorUserID = httpPrincipals.toArray(new HttpPrincipal[0])[0].getProxyUser();
                userHomeNode.getProperties().add(new NodeProperty(VOS.PROPERTY_URI_CREATOR, creatorUserID));
            }
        }
    }

    private static class URIConversionHandler extends DefaultConversionHandler {
        @Override
        public <T> T to(Object src, Class<T> targetCls, ConfigurationInterpolator ci) {
            if (URI.class.isAssignableFrom(targetCls)) {
                if (src instanceof String) {
                    return targetCls.cast(URI.create((String) src));
                } else if (src instanceof URI) {
                    return targetCls.cast(src);
                } else {
                    throw new ConversionException(
                            "Cannot convert " + src.getClass().getName() + " to URI");
                }
            } else {
                return super.to(src, targetCls, ci);
            }
        }

        @Override
        public Object toArray(Object src, Class<?> elemClass, ConfigurationInterpolator ci) {
            return null;
        }

        @Override
        public <T> void toCollection(
                Object src, Class<T> elemClass, ConfigurationInterpolator ci, Collection<T> dest) {}
    }
}
