package org.opencadc.skaha;

import ca.nrc.cadc.auth.AuthMethod;
import ca.nrc.cadc.auth.AuthorizationToken;
import ca.nrc.cadc.auth.SSOCookieCredential;
import java.util.Set;
import javax.security.auth.Subject;

public class AuthenticatedUser {
    final Subject subject;
    private final AuthMethod authMethod;

    public AuthenticatedUser(final Subject subject, final AuthMethod authMethod) {
        this.subject = subject;
        this.authMethod = authMethod;
    }

    void setDomain(final String domain) {
        switch (authMethod) {
            case COOKIE -> {
                final Set<SSOCookieCredential> cookieCredentialSet =
                        subject.getPublicCredentials(SSOCookieCredential.class);
                for (SSOCookieCredential cookieCredential : cookieCredentialSet) {
                    final SSOCookieCredential cookieCredentialWithDomain =
                            new SSOCookieCredential(cookieCredential.getSsoCookieValue(), domain);
                    cookieCredentialSet.remove(cookieCredential);
                    cookieCredentialSet.add(cookieCredentialWithDomain);
                }
            }

            case TOKEN ->
                subject.getPublicCredentials(AuthorizationToken.class)
                        .forEach(authorizationToken ->
                                authorizationToken.getDomains().add(domain));
        }
    }
}
