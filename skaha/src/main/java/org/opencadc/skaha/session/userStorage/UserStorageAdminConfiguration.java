package org.opencadc.skaha.session.userStorage;

import ca.nrc.cadc.auth.AuthenticationUtil;
import ca.nrc.cadc.cred.client.CredUtil;
import java.net.URI;
import java.util.Collection;
import javax.security.auth.Subject;
import org.apache.commons.configuration2.CombinedConfiguration;
import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.EnvironmentConfiguration;
import org.apache.commons.configuration2.SystemConfiguration;
import org.apache.commons.configuration2.convert.DefaultConversionHandler;
import org.apache.commons.configuration2.ex.ConversionException;
import org.apache.commons.configuration2.interpol.ConfigurationInterpolator;
import org.apache.commons.configuration2.tree.MergeCombiner;
import org.apache.log4j.Logger;

/**
 * Class to load the User Storage administrative configuration. Supports both OIDC and certificate-based authentication,
 * as well as Environment Variables and System Properties (for testing) for configuration.
 */
public class UserStorageAdminConfiguration {
    private static final Logger LOGGER = Logger.getLogger(UserStorageAdminConfiguration.class.getName());

    // For Certificate-based authentication.
    public static final String SKAHA_USER_STORAGE_ADMIN_CERTIFICATE_ENABLED =
            "SKAHA_USER_STORAGE_ADMIN_CERTIFICATE_ENABLED";

    // A configure Permissions API service.
    public static final String SKAHA_SESSIONS_AUTHORIZATION_PERMISSIONS_API_ENABLED =
            "SKAHA_SESSIONS_AUTHORIZATION_PERMISSIONS_API_ENABLED";

    // Represents the
    public final Subject requestOwner;

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
        if (configuration.containsKey(UserStorageAdminConfiguration.SKAHA_USER_STORAGE_ADMIN_CERTIFICATE_ENABLED)
                && configuration.getBoolean(
                        UserStorageAdminConfiguration.SKAHA_USER_STORAGE_ADMIN_CERTIFICATE_ENABLED)) {
            LOGGER.debug("Configuring certificate client for User Storage.");
            requestOwner = CredUtil.createOpsSubject();
        } else if (configuration.containsKey(
                        UserStorageAdminConfiguration.SKAHA_SESSIONS_AUTHORIZATION_PERMISSIONS_API_ENABLED)
                && configuration.getBoolean(
                        UserStorageAdminConfiguration.SKAHA_SESSIONS_AUTHORIZATION_PERMISSIONS_API_ENABLED)) {
            LOGGER.debug("Configuring session authorization API for User Storage.");
            requestOwner = AuthenticationUtil.getCurrentSubject();
        } else {
            throw new IllegalArgumentException("No OIDC client ID or certificate provided for User Storage admin.");
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
