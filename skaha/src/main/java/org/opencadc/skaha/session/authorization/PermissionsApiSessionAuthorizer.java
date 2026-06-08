package org.opencadc.skaha.session.authorization;

import ca.nrc.cadc.auth.AuthorizationToken;
import ca.nrc.cadc.reg.client.LocalAuthority;
import ca.nrc.cadc.util.InvalidConfigException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import java.util.Objects;
import javax.security.auth.Subject;
import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.opencadc.permissions.client.srcnet.AuthorisationResult;
import org.opencadc.permissions.client.srcnet.PermissionsAPIClient;
import org.opencadc.skaha.utils.CommonUtils;

/** Authorization backed by a remote permissions HTTP API. */
public final class PermissionsApiSessionAuthorizer implements SessionAuthorizer {
    private static final Logger LOGGER = Logger.getLogger(PermissionsApiSessionAuthorizer.class);
    static final String SKAHA_PERMISSIONS_API_NAME_ENV = "SKAHA_PERMISSIONS_API_NAME";
    static final String SKAHA_PERMISSIONS_API_VERSION_ENV = "SKAHA_PERMISSIONS_API_VERSION";

    static final URI STD_AUTH_API = URI.create("ivo://skao.int/std/AuthAPI");
    static final URI STD_PERMISSIONS_API = URI.create("ivo://skao.int/std/PermissionsAPI");

    private final URL permissionsApiAuthBaseUrl;
    private final URL permissionsApiBaseUrl;

    private final String serviceName;

    // Optional parameter.
    private final String version;

    /**
     * Create a new instance from the environment configuration.
     *
     * @param environment The environment variables. Typically from <code>System.getenv()</code>, but can be overridden
     *     for testing.
     * @return A new instance of <code>PermissionsApiSessionAuthorizer</code> configured from the environment.
     */
    public static PermissionsApiSessionAuthorizer fromEnvironment(final Map<String, String> environment) {
        return fromEnvironment(environment, PermissionsApiSessionAuthorizer::localAuthorityLookup);
    }

    static PermissionsApiSessionAuthorizer fromEnvironment(
            final Map<String, String> environment, final StandardServiceLookup lookup) {
        final URI authApiUri = lookup.resolve(PermissionsApiSessionAuthorizer.STD_AUTH_API);
        final URI permissionsApiUri = lookup.resolve(PermissionsApiSessionAuthorizer.STD_PERMISSIONS_API);

        if (authApiUri == null || permissionsApiUri == null) {
            throw new InvalidConfigException(
                    "Specified Permissions API authorization but missing authAPI and/or permissionsAPI configuration.");
        }

        final URL permissionsApiAuthBaseUrl;
        final URL permissionsApiBaseUrl;

        try {
            permissionsApiAuthBaseUrl = authApiUri.toURL();
        } catch (MalformedURLException ex) {
            throw new InvalidConfigException(
                    "invalid URL: " + PermissionsApiSessionAuthorizer.STD_AUTH_API + " = " + authApiUri, ex);
        }
        try {
            permissionsApiBaseUrl = permissionsApiUri.toURL();
        } catch (MalformedURLException ex) {
            throw new InvalidConfigException(
                    "invalid URL: " + PermissionsApiSessionAuthorizer.STD_PERMISSIONS_API + " = " + permissionsApiUri,
                    ex);
        }
        final String serviceName = CommonUtils.trimToNull(
                CommonUtils.lookup(environment, PermissionsApiSessionAuthorizer.SKAHA_PERMISSIONS_API_NAME_ENV));
        final String version = CommonUtils.trimToNull(
                CommonUtils.lookup(environment, PermissionsApiSessionAuthorizer.SKAHA_PERMISSIONS_API_VERSION_ENV));

        LOGGER.debug("Permissions API Base URL: " + permissionsApiBaseUrl);
        return new PermissionsApiSessionAuthorizer(
                permissionsApiBaseUrl, permissionsApiAuthBaseUrl, serviceName, version);
    }

    private static URI localAuthorityLookup(final URI standardId) {
        return new LocalAuthority().getResourceID(standardId, true);
    }

    /**
     * Package private constructor for testing.
     *
     * @param permissionsApiBaseUrl The base URL for the Permissions API
     * @param serviceName The service name to lookup
     * @param version The optional version of the policy
     */
    PermissionsApiSessionAuthorizer(
            final URL permissionsApiBaseUrl,
            final URL permissionsApiAuthBaseUrl,
            final String serviceName,
            final String version) {
        this.permissionsApiAuthBaseUrl =
                Objects.requireNonNull(permissionsApiAuthBaseUrl, "missing permissionsApiAuthBaseUrl");
        this.permissionsApiBaseUrl = Objects.requireNonNull(permissionsApiBaseUrl, "missing permissionsApiBaseUrl");
        this.serviceName = Objects.requireNonNull(serviceName, "serviceName must not be null.");
        this.version = Objects.requireNonNullElse(version, "1");
    }

    public URL getPermissionsApiBaseUrl() {
        return this.permissionsApiBaseUrl;
    }

    @Override
    public void authorizeGeneralSessionAccess(final Subject subject, String requestMethod, String routePath)
            throws Exception {
        Objects.requireNonNull(subject, "subject");
        LOGGER.debug("Permissions API Base URL: " + permissionsApiBaseUrl);
        LOGGER.debug("Permissions API Auth API Base URL: " + permissionsApiAuthBaseUrl);
        final PermissionsAPIClient permissionsAPIClient =
                new PermissionsAPIClient(this.permissionsApiBaseUrl, this.permissionsApiAuthBaseUrl);
        final AuthorizationToken authorizationToken = CommonUtils.getAuthorizationToken(subject);

        // Shouldn't ever happen since the calling user should already be authenticated, but here for completeness.
        final String tokenValue = authorizationToken == null ? "" : authorizationToken.getCredentials();

        final AuthorisationResult authorisationResult = permissionsAPIClient.authoriseRoute(
                this.serviceName,
                routePath,
                tokenValue,
                requestMethod,
                PermissionsApiSessionAuthorizer.requestBody(),
                this.version);

        if (!authorisationResult.isAuthorised) {
            throw new SessionAccessDeniedException(
                    "Subject is not authorized to access Skaha according to permissions API plugin authorisation.");
        }
    }

    private static JSONObject requestBody() {
        return new JSONObject();
    }
}
