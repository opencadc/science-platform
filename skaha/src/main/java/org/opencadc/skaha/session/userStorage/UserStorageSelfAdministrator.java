package org.opencadc.skaha.session.userStorage;

import javax.security.auth.Subject;

public class UserStorageSelfAdministrator implements UserStorageAdministrator {
    private final Subject selfOwner;

    public UserStorageSelfAdministrator(Subject selfOwner) {
        this.selfOwner = selfOwner;
    }

    @Override
    public Subject toSubject() {
        return this.selfOwner;
    }
}
