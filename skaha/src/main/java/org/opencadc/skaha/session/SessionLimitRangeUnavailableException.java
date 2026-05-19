package org.opencadc.skaha.session;

/**
 * Raised when session LimitRange configuration is required but missing or unreadable. Mapped to HTTP 503 for
 * {@code view=stats} only.
 */
public class SessionLimitRangeUnavailableException extends RuntimeException {

    /** Stable client-facing message for HTTP 503 responses. */
    public static final String CLIENT_MESSAGE = "Session resource limits unavailable";

    public SessionLimitRangeUnavailableException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
