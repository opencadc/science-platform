package org.opencadc.skaha.session.authorization;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Objects;
import javax.security.auth.Subject;

/**
 * Authorization backed by a remote permissions HTTP API
 * ({@link SessionAuthorizers#SKAHA_PERMISSIONS_API_BASE_URL_ENV}).
 */
public final class PermissionsApiSessionAuthorizer implements SessionAuthorizer {

    private final URL permissionsApiBaseUrl;

    public PermissionsApiSessionAuthorizer(final String permissionsApiBaseUrl) {
        final String permissionsBaseUrl = Objects.requireNonNull(permissionsApiBaseUrl, "permissionsApiBaseUrl")
                .trim();
        if (permissionsBaseUrl.isEmpty()) {
            throw new IllegalArgumentException("permissionsApiBaseUrl must not be blank.");
        } else {
            try {
                this.permissionsApiBaseUrl =
                        URI.create(permissionsBaseUrl).toURL(); // ensure valid URL and trailing slash
            } catch (final MalformedURLException e) {
                throw new IllegalArgumentException("permissionsApiBaseUrl must contain a valid URL.", e);
            }
        }
    }

    public URL getPermissionsApiBaseUrl() {
        return this.permissionsApiBaseUrl;
    }

    @Override
    public void authorizeGeneralSessionAccess(final Subject subject) throws IOException {
        Objects.requireNonNull(subject, "subject");
        throw new UnsupportedOperationException("TODO: call remote permissions API when implemented");
    }
}
