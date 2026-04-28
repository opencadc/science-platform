package org.opencadc.skaha.session.authorization;

import javax.security.auth.Subject;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;

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
     * @throws IOException delegated IO failures when validating authorization.
     * @throws SessionAccessDeniedException if access is denied.
     */
    void authorizeGeneralSessionAccess(final Subject subject) throws IOException;

    /**
     * URI used as audience/resource identifier for Skaha callback tokens ({@code TokenTool} write grants). When
     * {@link PermissionsApiSessionAuthorizer} is used and no stable URI exists yet, this returns empty until the
     * permissions API defines one.
     */
    Optional<URI> getSkahaWriteGrantAudience();
}
