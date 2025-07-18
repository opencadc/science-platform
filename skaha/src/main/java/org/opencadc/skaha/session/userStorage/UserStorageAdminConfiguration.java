package org.opencadc.skaha.session.userStorage;

import ca.nrc.cadc.auth.AuthMethod;
import ca.nrc.cadc.auth.AuthenticationUtil;
import ca.nrc.cadc.auth.AuthorizationToken;
import ca.nrc.cadc.auth.HttpPrincipal;
import com.nimbusds.oauth2.sdk.GeneralException;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata;
import java.io.IOException;
import java.net.URI;
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
import org.opencadc.vospace.ContainerNode;
import org.opencadc.vospace.NodeProperty;
import org.opencadc.vospace.VOS;

/**
 * Class to load the User Storage administrative configuration. Supports both OIDC and certificate-based authentication,
 * as well as Environment Variables and System Properties (for testing) for configuration.
 */
public class UserStorageAdminConfiguration {
    // User Storage access control.
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
            owner = configureOIDCOwner(clientID, clientSecret, issuerURI);
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

    private UserStorageAdministrator configureOIDCOwner(String clientID, String clientSecret, String issuerURI) {
        Objects.requireNonNull(clientID, "OIDC client ID must not be null");
        Objects.requireNonNull(clientSecret, "OIDC client secret must not be null");
        Objects.requireNonNull(issuerURI, "OIDC issuer URI must not be null");

        return new UserStorageOIDCAdministrator(
                new ClientID(clientID), new Secret(clientSecret), URI.create(issuerURI));
    }

    /**
     * TODO: Cache this. Pull the Token Endpoint URL from the Well Known JSON document.
     *
     * @return URL of the Token Endpoint for access and refresh tokens. Never null.
     * @throws IOException For a poorly formed URL.
     */
    public static URI getTokenEndpoint(final URI oidcIssuerURI) throws IOException {
        try {
            final OIDCProviderMetadata oidcProviderMetadata = OIDCProviderMetadata.resolve(new Issuer(oidcIssuerURI));
            return oidcProviderMetadata.getTokenEndpointURI();
        } catch (GeneralException generalException) {
            throw new IOException(
                    "Failed to resolve OIDC Provider Metadata from issuer URI: " + oidcIssuerURI, generalException);
        }
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
