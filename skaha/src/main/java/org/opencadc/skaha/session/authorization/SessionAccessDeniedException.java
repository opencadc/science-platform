package org.opencadc.skaha.session.authorization;

/**
 * An authenticated caller is not permitted to perform the requested Skaha operation (typically mapped to HTTP 403
 * Forbidden). Prefer this type instead of {@link java.security.AccessControlException}, which is deprecated for removal.
 *
 * <p>For missing or invalid authentication (HTTP 401), callers typically use {@link
 * ca.nrc.cadc.auth.NotAuthenticatedException}.
 */
public class SessionAccessDeniedException extends SecurityException {

    private static final long serialVersionUID = 1L;

    public SessionAccessDeniedException(final String message) {
        super(message);
    }

    public SessionAccessDeniedException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
