package org.opencadc.skaha.session.authorization;

import java.util.Map;
import java.util.Objects;

/**
 * Factory for {@link SessionAuthorizer} based on environment configuration.
 *
 * <p>Look for the {@link SessionAuthorizers#SKAHA_PERMISSIONS_API_ENABLED_FLAG_ENV} so that the Permissions API setting
 * will take precedence. Default to the Group URI authorizer. creation fails with {@link IllegalStateException}.
 */
public final class SessionAuthorizers {

    /** Environment variable: Base URL of a remote permissions service (alternative to group URI membership). */
    static String SKAHA_PERMISSIONS_API_ENABLED_FLAG_ENV = "SKAHA_SESSIONS_AUTHORIZATION_PERMISSIONS_API_ENABLED";

    static String SKAHA_GROUP_ENABLED_FLAG_ENV = "SKAHA_SESSIONS_AUTHORIZATION_GROUP_ENABLED";

    /**
     * New instance from the System Environment.
     *
     * @return a SessionAuthorizer instance based on the environment configuration
     */
    public static SessionAuthorizer fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    /**
     * Create a new SessionAuthorizer from the provided Environment.
     *
     * @param env environment map (e.g. from tests); values may be null.
     * @return a SessionAuthorizer instance based on the environment configuration
     */
    public static SessionAuthorizer fromEnvironment(final Map<String, String> env) {
        Objects.requireNonNull(env, "env");

        final boolean permissionsAPIEnabled = Boolean.parseBoolean(
                env.getOrDefault(SessionAuthorizers.SKAHA_PERMISSIONS_API_ENABLED_FLAG_ENV, Boolean.FALSE.toString()));
        final boolean groupAuthEnabled = Boolean.parseBoolean(
                env.getOrDefault(SessionAuthorizers.SKAHA_GROUP_ENABLED_FLAG_ENV, Boolean.FALSE.toString()));

        if (permissionsAPIEnabled && groupAuthEnabled) {
            throw new IllegalStateException("Both " + SessionAuthorizers.SKAHA_PERMISSIONS_API_ENABLED_FLAG_ENV
                    + " and " + SessionAuthorizers.SKAHA_GROUP_ENABLED_FLAG_ENV
                    + " are set to true. Only one authorization method can be enabled.");
        } else if (!permissionsAPIEnabled && !groupAuthEnabled) {
            throw new IllegalStateException("Neither " + SessionAuthorizers.SKAHA_PERMISSIONS_API_ENABLED_FLAG_ENV
                    + " nor " + SessionAuthorizers.SKAHA_GROUP_ENABLED_FLAG_ENV
                    + " are set to true. One authorization method must be enabled.");
        }

        return permissionsAPIEnabled
                ? PermissionsApiSessionAuthorizer.fromEnvironment(env)
                : GroupURISessionAuthorizer.fromEnvironment(env);
    }
}
