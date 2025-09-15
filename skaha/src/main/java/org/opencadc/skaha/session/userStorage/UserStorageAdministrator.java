package org.opencadc.skaha.session.userStorage;

import java.io.IOException;
import javax.security.auth.Subject;

/** Represents the administrator (owner) to be used for administrative operations on the user storage service. */
public interface UserStorageAdministrator {
    /**
     * Returns the Subject associated with this administrator. This will likely involve remote calls to validate the
     * credentials.
     *
     * @return Subject representing the administrator's credentials. Never null.
     * @throws IOException if there is an error communicating with the user storage service or if the credentials are
     *     invalid.
     */
    Subject toSubject() throws IOException;
}
