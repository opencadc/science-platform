package org.opencadc.skaha.session.authorization;

import java.io.Serial;

/**
 * An authenticated caller is not permitted to perform the requested Skaha operation (typically mapped to HTTP 403
 * Forbidden). Prefer this type instead of {@link java.security.AccessControlException}, which is deprecated for
 * removal.
 *
 * <p>For missing or invalid authentication (HTTP 401), callers typically use
 * {@link ca.nrc.cadc.auth.NotAuthenticatedException}.
 */
public class SessionAccessDeniedException extends SecurityException {

    @Serial
    private static final long serialVersionUID = 20260428L;

    public SessionAccessDeniedException(final String message) {
        super(message);
    }
}
