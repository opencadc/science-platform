package org.opencadc.skaha.session.userStorage;

import javax.security.auth.Subject;
import java.io.IOException;

public class UserStorageSecretAdministrator implements UserStorageAdministrator {
    /**
     * Returns the Subject associated with this administrator. This will likely involve remote calls to validate the
     * credentials.
     *
     * @return Subject representing the administrator's credentials. Never null.
     * @throws IOException if there is an error communicating with the user storage service or if the credentials are
     *                     invalid.
     */
    @Override
    public Subject toSubject() throws IOException {
        return null;
    }
}
