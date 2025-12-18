package org.opencadc.skaha;

import javax.servlet.http.HttpServletResponse;

public class AccessDeniedException extends SecurityException {
    public AccessDeniedException(String message) {
        super(message);
    }

    public int getResponseCode() {
        return HttpServletResponse.SC_FORBIDDEN;
    }
}
