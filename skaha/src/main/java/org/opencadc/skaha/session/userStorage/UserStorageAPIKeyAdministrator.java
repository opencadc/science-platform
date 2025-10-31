package org.opencadc.skaha.session.userStorage;

import ca.nrc.cadc.auth.AuthenticationUtil;
import ca.nrc.cadc.auth.AuthorizationToken;
import ca.nrc.cadc.net.NetUtil;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import javax.security.auth.Subject;

/** An administrator for user storage that uses an API key for authentication. */
public class UserStorageAPIKeyAdministrator implements UserStorageAdministrator {
    private static final String CLIENT_APPLICATION_NAME = "skaha";
    // The challenge type (after the authorization header and before the token) for API Key authentication.
    private static final String ALLOCATION_API_KEY_HEADER_CHALLENGE_TYPE = "admin-api-key";

    private final URL serviceURL;

    // The API key used to authenticate with the user storage service.
    private final String apiKey;

    public UserStorageAPIKeyAdministrator(String apiKey, URL serviceURL) {
        this.apiKey = apiKey;
        this.serviceURL = serviceURL;
    }

    /**
     * Returns the Subject associated with this administrator. This will likely involve remote calls to validate the
     * credentials.
     *
     * @return Subject representing the administrator's credentials. Never null.
     * @throws IOException If the domain name cannot be determined from the service URL.
     */
    @Override
    public Subject toSubject() throws IOException {
        final Subject subject = new Subject();

        subject.getPublicCredentials()
                .add(new AuthorizationToken(
                        UserStorageAPIKeyAdministrator.ALLOCATION_API_KEY_HEADER_CHALLENGE_TYPE,
                        String.format("%s:%s", UserStorageAPIKeyAdministrator.CLIENT_APPLICATION_NAME, this.apiKey),
                        List.of(NetUtil.getDomainName(this.serviceURL))));

        return AuthenticationUtil.augmentSubject(AuthenticationUtil.validateSubject(subject));
    }
}
