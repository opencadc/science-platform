package org.opencadc.skaha.session.userStorage;

import ca.nrc.cadc.auth.AuthMethod;
import ca.nrc.cadc.auth.AuthenticationUtil;
import ca.nrc.cadc.auth.AuthorizationToken;
import ca.nrc.cadc.auth.HttpPrincipal;
import ca.nrc.cadc.net.ResourceNotFoundException;
import ca.nrc.cadc.reg.client.RegistryClient;
import ca.nrc.cadc.util.InvalidConfigException;
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
    public static final String SKAHA_USER_STORAGE_ADMIN_API_KEY = "SKAHA_USER_STORAGE_ADMIN_API_KEY";

    // For Certificate-based authentication.
    public static final String SKAHA_USER_STORAGE_ADMIN_CERTIFICATE = "SKAHA_USER_STORAGE_ADMIN_CERTIFICATE";

    public final UserStorageAdministrator owner;

    private final UserStorageConfiguration userStorageConfiguration;

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
        this.userStorageConfiguration = UserStorageConfiguration.fromConfiguration(configuration);

        if (configuration.containsKey(UserStorageAdminConfiguration.SKAHA_USER_STORAGE_ADMIN_API_KEY)
                && configuration.containsKey(UserStorageAdminConfiguration.SKAHA_USER_STORAGE_ADMIN_CERTIFICATE)) {
            throw new InvalidConfigException("Both an API Key and certificate provided for User Storage admin. "
                    + "Please provide only one method of authentication.");
        }

        if (configuration.containsKey(UserStorageAdminConfiguration.SKAHA_USER_STORAGE_ADMIN_CERTIFICATE)) {
            final String certificateString =
                    configuration.getString(UserStorageAdminConfiguration.SKAHA_USER_STORAGE_ADMIN_CERTIFICATE);
            owner = configureCertificateOwner(certificateString);
        } else if (configuration.containsKey(UserStorageAdminConfiguration.SKAHA_USER_STORAGE_ADMIN_API_KEY)) {
            owner = configureAPIKeyOwner(
                    configuration.getString(UserStorageAdminConfiguration.SKAHA_USER_STORAGE_ADMIN_API_KEY));
        } else {
            throw new IllegalArgumentException("No OIDC client ID or certificate provided for User Storage admin.");
        }
    }

    private UserStorageAdministrator configureAPIKeyOwner(String apiKey) {
        Objects.requireNonNull(apiKey, "API Key must not be null");

        final RegistryClient registryClient = new RegistryClient();
        try {
            return new UserStorageAPIKeyAdministrator(
                    apiKey, registryClient.getAccessURL(this.userStorageConfiguration.serviceURI));
        } catch (IOException | ResourceNotFoundException lookupException) {
            throw new InvalidConfigException(
                    "Failed to get access to: " + this.userStorageConfiguration.serviceURI, lookupException);
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
                final String creatorUserID = httpPrincipals.toArray(new HttpPrincipal[0])[0].getName();
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
