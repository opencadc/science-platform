package org.opencadc.skaha.session.authorization;

import javax.security.auth.Subject;

/**
 * Authorizes whether an authenticated subject may use the Skaha system (general HTTP flow). Implementations are chosen
 * via {@link SessionAuthorizers}.
 */
public interface SessionAuthorizer {
    /**
     * Ensures the subject may access Skaha in the general (non-callback) flow. May augment the subject with credentials
     * (for example group lists). Call with Subject.doAs() to pass the current Subject in.
     *
     * @param subject the authenticated subject to check and possibly augment; must not be null.
     * @param requestMethod the request method.
     * @param routePath the request path.
     * @throws Exception delegated I/O or general network lookup failures when validating authorization.
     * @throws SessionAccessDeniedException if access is denied.
     */
    void authorizeGeneralSessionAccess(final Subject subject, String requestMethod, String routePath) throws Exception;
}
