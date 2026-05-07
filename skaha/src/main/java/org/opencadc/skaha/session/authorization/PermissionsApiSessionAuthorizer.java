package org.opencadc.skaha.session.authorization;

import ca.nrc.cadc.auth.AuthorizationToken;
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

/**
 * Authorization backed by a remote permissions HTTP API
 * ({@link PermissionsApiSessionAuthorizer#SKAHA_PERMISSIONS_API_BASE_URL_ENV}).
 */
public final class PermissionsApiSessionAuthorizer implements SessionAuthorizer {
    private static final Logger LOGGER = Logger.getLogger(PermissionsApiSessionAuthorizer.class);
    static final String SKAHA_PERMISSIONS_API_BASE_URL_ENV = "SKAHA_PERMISSIONS_API_BASE_URL";
    static final String SKAHA_PERMISSIONS_API_AUTH_BASE_URL_ENV = "SKAHA_PERMISSIONS_API_AUTH_BASE_URL";
    static final String SKAHA_PERMISSIONS_API_NAME_ENV = "SKAHA_PERMISSIONS_API_NAME";
    static final String SKAHA_PERMISSIONS_API_TYPE_ENV = "SKAHA_PERMISSIONS_API_TYPE";
    static final String SKAHA_PERMISSIONS_API_ROUTE_ENV = "SKAHA_PERMISSIONS_API_ROUTE";
    static final String SKAHA_PERMISSIONS_API_VERSION_ENV = "SKAHA_PERMISSIONS_API_VERSION";
    static final String SKAHA_PERMISSIONS_API_METHOD_ENV = "SKAHA_PERMISSIONS_API_METHOD";

    private static final String POLICY_TYPE_ROUTE = "route";
    private static final String POLICY_TYPE_PLUGIN = "plugin";

    private final URL permissionsApiBaseUrl;
    private final URL permissionsApiAuthBaseUrl;
    private final String serviceName;
    private final String authoriseType;

    // Required only for the Route type.
    private final String routeName;

    // Optional parameters.
    private final String version;
    private final String method;

    /**
     * Create a new instance from the environment configuration.
     *
     * @param environment The environment variables. Typically from <code>System.getenv()</code>, but can be overridden
     *     for testing.
     * @return A new instance of <code>PermissionsApiSessionAuthorizer</code> configured from the environment.
     */
    public static PermissionsApiSessionAuthorizer fromEnvironment(final Map<String, String> environment) {
        final String permissionsApiBaseUrl = CommonUtils.trimToNull(
                CommonUtils.lookup(environment, PermissionsApiSessionAuthorizer.SKAHA_PERMISSIONS_API_BASE_URL_ENV));
        final String permissionsApiAuthBaseUrl = CommonUtils.trimToNull(CommonUtils.lookup(
                environment, PermissionsApiSessionAuthorizer.SKAHA_PERMISSIONS_API_AUTH_BASE_URL_ENV));
        final String serviceName = CommonUtils.trimToNull(
                CommonUtils.lookup(environment, PermissionsApiSessionAuthorizer.SKAHA_PERMISSIONS_API_NAME_ENV));
        final String type = CommonUtils.trimToNull(
                CommonUtils.lookup(environment, PermissionsApiSessionAuthorizer.SKAHA_PERMISSIONS_API_TYPE_ENV));
        final String route = CommonUtils.trimToNull(
                CommonUtils.lookup(environment, PermissionsApiSessionAuthorizer.SKAHA_PERMISSIONS_API_ROUTE_ENV));
        final String version = CommonUtils.trimToNull(
                CommonUtils.lookup(environment, PermissionsApiSessionAuthorizer.SKAHA_PERMISSIONS_API_VERSION_ENV));
        final String method = CommonUtils.trimToNull(
                CommonUtils.lookup(environment, PermissionsApiSessionAuthorizer.SKAHA_PERMISSIONS_API_METHOD_ENV));

        LOGGER.debug("Permissions API Base URL: " + permissionsApiBaseUrl);
        return new PermissionsApiSessionAuthorizer(
                permissionsApiBaseUrl, permissionsApiAuthBaseUrl, serviceName, type, route, version, method);
    }

    /**
     * Package private constructor for testing.
     *
     * @param permissionsApiBaseUrl The base URL for the Permissions API
     * @param serviceName The service name to lookup
     * @param authoriseType The policy type (route or plugin)
     * @param routeName The route name for the route type
     * @param version The optional version of the policy
     * @param method The HTTP method. Defaults to GET.
     */
    PermissionsApiSessionAuthorizer(
            final String permissionsApiBaseUrl,
            final String permissionsApiAuthBaseUrl,
            final String serviceName,
            final String authoriseType,
            final String routeName,
            final String version,
            final String method) {
        final String permissionsBaseUrl = Objects.requireNonNull(permissionsApiBaseUrl, "permissionsApiBaseUrl")
                .trim();
        if (permissionsBaseUrl.isEmpty()) {
            throw new IllegalArgumentException("permissionsApiBaseUrl must not be blank.");
        } else {
            try {
                this.permissionsApiBaseUrl = URI.create(permissionsBaseUrl).toURL();
            } catch (final MalformedURLException e) {
                throw new IllegalArgumentException("permissionsApiBaseUrl must contain a valid URL.", e);
            }
        }

        final String authBaseUrl = Objects.requireNonNull(permissionsApiAuthBaseUrl, "permissionsApiAuthBaseUrl")
                .trim();
        if (authBaseUrl.isEmpty()) {
            throw new IllegalArgumentException("permissionsApiAuthBaseUrl must not be blank.");
        } else {
            try {
                this.permissionsApiAuthBaseUrl = URI.create(authBaseUrl).toURL();
            } catch (final MalformedURLException e) {
                throw new IllegalArgumentException("permissionsApiAuthBaseUrl must contain a valid URL.", e);
            }
        }

        this.serviceName = Objects.requireNonNull(serviceName, "serviceName must not be null.");
        this.authoriseType = Objects.requireNonNull(authoriseType, "authoriseType must either be plugin or route.");

        if (!this.authoriseType.equalsIgnoreCase(POLICY_TYPE_PLUGIN)
                && !this.authoriseType.equalsIgnoreCase(POLICY_TYPE_ROUTE)) {
            throw new IllegalArgumentException("authoriseType must either be plugin or route.");
        }

        this.routeName = this.authoriseType.equalsIgnoreCase("route")
                ? Objects.requireNonNull(routeName, "route name is required when route type selected")
                : "";
        this.version = Objects.requireNonNullElse(version, "");
        this.method = Objects.requireNonNullElse(method, "GET");
    }

    public URL getPermissionsApiBaseUrl() {
        return this.permissionsApiBaseUrl;
    }

    @Override
    public void authorizeGeneralSessionAccess(final Subject subject) throws Exception {
        Objects.requireNonNull(subject, "subject");
        LOGGER.debug("Permissions API Base URL: " + permissionsApiBaseUrl);
        LOGGER.debug("Permissions API Auth API Base URL: " + permissionsApiAuthBaseUrl);
        final PermissionsAPIClient permissionsAPIClient =
                new PermissionsAPIClient(this.permissionsApiBaseUrl, this.permissionsApiAuthBaseUrl);
        final AuthorizationToken authorizationToken = CommonUtils.getAuthorizationToken(subject);

        // Shouldn't ever happen since the calling user should already be authenticated, but here for completeness.
        final String tokenValue = authorizationToken == null ? "" : authorizationToken.getCredentials();

        switch (this.authoriseType) {
            case PermissionsApiSessionAuthorizer.POLICY_TYPE_ROUTE: {
                final AuthorisationResult authorisationResult = permissionsAPIClient.authoriseRoute(
                        this.serviceName,
                        this.routeName,
                        tokenValue,
                        this.method,
                        PermissionsApiSessionAuthorizer.requestBody(),
                        this.version);
                if (!authorisationResult.isAuthorised) {
                    throw new SessionAccessDeniedException(
                            "Subject is not authorized to access Skaha according to permissions API plugin authorisation.");
                }
                return;
            }
            case PermissionsApiSessionAuthorizer.POLICY_TYPE_PLUGIN: {
                final AuthorisationResult authorisationResult = permissionsAPIClient.authorisePlugin(
                        this.serviceName, tokenValue, PermissionsApiSessionAuthorizer.requestBody(), this.version);
                if (!authorisationResult.isAuthorised) {
                    throw new SessionAccessDeniedException(
                            "Subject is not authorized to access Skaha according to permissions API route authorisation.");
                }
                break;
            }
            default: {
                throw new IllegalStateException("authoriseType must either be plugin or route.");
            }
        }
    }

    private static JSONObject requestBody() {
        return new JSONObject();
    }
}
