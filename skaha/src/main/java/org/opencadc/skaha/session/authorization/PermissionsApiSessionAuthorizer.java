package org.opencadc.skaha.session.authorization;

import ca.nrc.cadc.auth.AuthenticationUtil;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Objects;
import java.util.Optional;
import javax.security.auth.Subject;

/**
 * Authorization backed by a remote permissions HTTP API
 * ({@link SessionAuthorizers#SKAHA_PERMISSIONS_API_BASE_URL_ENV}).
 *
 * <p>{@link #getSkahaWriteGrantAudience()} returns empty until the permissions API defines an audience URI for Skaha
 * callback tokens.
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

    /**
     * No stable audience URI yet for TokenTool grants when using the remote permissions API; callers must avoid issuing
     * callback tokens against this mode until defined.
     */
    @Override
    public Optional<URI> getSkahaWriteGrantAudience() {
        return Optional.empty();
    }
}
