package org.opencadc.skaha.session.userStorage;

import java.net.URI;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;

import ca.nrc.cadc.auth.AuthMethod;
import ca.nrc.cadc.auth.AuthenticationUtil;
import ca.nrc.cadc.auth.AuthorizationToken;
import ca.nrc.cadc.auth.HttpPrincipal;
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

import javax.security.auth.Subject;

public class UserStorageConfiguration {
    public static final String SKAHA_USER_STORAGE_TOP_LEVEL_DIRECTORY = "SKAHA_USER_STORAGE_TOP_LEVEL_DIRECTORY";
    public static final String SKAHA_USER_STORAGE_HOME_BASE_DIRECTORY = "SKAHA_USER_STORAGE_HOME_BASE_DIRECTORY";
    public static final String SKAHA_USER_STORAGE_PROJECTS_BASE_DIRECTORY =
            "SKAHA_USER_STORAGE_PROJECTS_BASE_DIRECTORY";
    public static final String SKAHA_USER_STORAGE_SERVICE_URI = "SKAHA_USER_STORAGE_SERVICE_URI";
    public static final String SKAHA_USER_STORAGE_USER_HOME_URI = "SKAHA_USER_STORAGE_USER_HOME_URI";

    public final URI serviceURI;
    public final URI userHomeBaseURI;
    public final Path topLevelDirectory;
    public final Path homeBaseDirectory;
    public final Path projectsBaseDirectory;

    public static UserStorageConfiguration fromEnv() {
        final CombinedConfiguration configuration = new CombinedConfiguration(new MergeCombiner());
        configuration.setConversionHandler(new PathConversionHandler());

        configuration.addConfiguration(new EnvironmentConfiguration());

        // Allow for system properties to override environment variables.  Useful for testing.
        configuration.addConfiguration(new SystemConfiguration());
        return new UserStorageConfiguration(configuration);
    }

    private UserStorageConfiguration(final Configuration configuration) {
        this.serviceURI = configuration.get(URI.class, UserStorageConfiguration.SKAHA_USER_STORAGE_SERVICE_URI);
        this.userHomeBaseURI = configuration.get(URI.class, UserStorageConfiguration.SKAHA_USER_STORAGE_USER_HOME_URI);
        this.topLevelDirectory =
                configuration.get(Path.class, UserStorageConfiguration.SKAHA_USER_STORAGE_TOP_LEVEL_DIRECTORY);
        this.homeBaseDirectory =
                configuration.get(Path.class, UserStorageConfiguration.SKAHA_USER_STORAGE_HOME_BASE_DIRECTORY);
        this.projectsBaseDirectory =
                configuration.get(Path.class, UserStorageConfiguration.SKAHA_USER_STORAGE_PROJECTS_BASE_DIRECTORY);

        Objects.requireNonNull(
                this.serviceURI,
                "User Storage (Cavern) Service URI must not be null.  Please set the "
                        + UserStorageConfiguration.SKAHA_USER_STORAGE_SERVICE_URI
                        + " environment variable or system property.");
        Objects.requireNonNull(
                this.userHomeBaseURI,
                "User Storage (Cavern) User Home Base URI must not be null.  Please set the "
                        + UserStorageConfiguration.SKAHA_USER_STORAGE_USER_HOME_URI
                        + " environment variable or system property.");
        Objects.requireNonNull(
                this.topLevelDirectory,
                "User Storage (Cavern) Top Level Directory must not be null.  Please set the "
                        + UserStorageConfiguration.SKAHA_USER_STORAGE_TOP_LEVEL_DIRECTORY
                        + " environment variable or system property.");
        Objects.requireNonNull(
                this.homeBaseDirectory,
                "User Storage (Cavern) Home Base Directory must not be null.  Please set the "
                        + UserStorageConfiguration.SKAHA_USER_STORAGE_HOME_BASE_DIRECTORY
                        + " environment variable or system property.");
        Objects.requireNonNull(
                this.projectsBaseDirectory,
                "User Storage (Cavern) Projects Base Directory must not be null.  Please set the "
                        + UserStorageConfiguration.SKAHA_USER_STORAGE_PROJECTS_BASE_DIRECTORY
                        + " environment variable or system property.");
    }

    public static void configureOwner(final ContainerNode userHomeNode, final Subject resourceOwner) {
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

    private static class PathConversionHandler extends DefaultConversionHandler {
        @Override
        public <T> T to(Object src, Class<T> targetCls, ConfigurationInterpolator ci) {
            if (Path.class.isAssignableFrom(targetCls)) {
                if (src instanceof String) {
                    return targetCls.cast(Path.of((String) src));
                } else if (src instanceof Path) {
                    return targetCls.cast(src);
                } else {
                    throw new ConversionException(
                            "Cannot convert " + src.getClass().getName() + " to Path");
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
