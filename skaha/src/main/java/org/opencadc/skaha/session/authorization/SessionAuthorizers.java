package org.opencadc.skaha.session.authorization;

import ca.nrc.cadc.util.StringUtil;
import java.util.Map;
import java.util.Objects;

/**
 * Factory for {@link SessionAuthorizer} based on environment configuration.
 *
 * <p>Exactly one of {@link SessionAuthorizers#SKAHA_USERS_GROUP_ENV} or
 * {@link SessionAuthorizers#SKAHA_PERMISSIONS_API_BASE_URL_ENV} must be set (after trim). If both or neither are set,
 * creation fails with {@link IllegalStateException}.
 */
public final class SessionAuthorizers {

    /** Environment variable: Group URI whose members may use Skaha (GMS / Ivoa group membership). */
    static String SKAHA_USERS_GROUP_ENV = "SKAHA_USERS_GROUP";

    /** Environment variable: Base URL of a remote permissions service (alternative to group URI membership). */
    static String SKAHA_PERMISSIONS_API_BASE_URL_ENV = "SKAHA_PERMISSIONS_API_BASE_URL";

    private SessionAuthorizers() {}

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
        final String usersGroup = trimToNull(lookup(env, SessionAuthorizers.SKAHA_USERS_GROUP_ENV));
        final String permissionsBase = trimToNull(lookup(env, SessionAuthorizers.SKAHA_PERMISSIONS_API_BASE_URL_ENV));

        final boolean hasUsers = StringUtil.hasText(usersGroup);
        final boolean hasPermissions = StringUtil.hasText(permissionsBase);

        if (hasUsers && hasPermissions) {
            throw new IllegalStateException("Set only one of " + SessionAuthorizers.SKAHA_USERS_GROUP_ENV + " or "
                    + SessionAuthorizers.SKAHA_PERMISSIONS_API_BASE_URL_ENV);
        }
        if (!hasUsers && !hasPermissions) {
            throw new IllegalStateException("Set exactly one of " + SessionAuthorizers.SKAHA_USERS_GROUP_ENV + " or "
                    + SessionAuthorizers.SKAHA_PERMISSIONS_API_BASE_URL_ENV);
        }

        return hasUsers
                ? new GroupURISessionAuthorizer(usersGroup)
                : new PermissionsApiSessionAuthorizer(permissionsBase);
    }

    private static String lookup(final Map<String, String> env, final String key) {
        return env.get(key);
    }

    private static String trimToNull(final String value) {
        if (value == null) {
            return null;
        }
        final String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}
