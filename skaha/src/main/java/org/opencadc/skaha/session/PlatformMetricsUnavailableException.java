package org.opencadc.skaha.session;

/** Raised when the Metrics backend cannot supply platform metrics. Mapped to HTTP 503 for {@code view=stats} only. */
public class PlatformMetricsUnavailableException extends RuntimeException {

    /** Stable client-facing message for HTTP 503 responses. */
    public static final String CLIENT_MESSAGE = "Platform statistics unavailable";

    public PlatformMetricsUnavailableException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
