package org.opencadc.skaha.session.authorization;

import ca.nrc.cadc.auth.AuthenticationUtil;
import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import javax.security.auth.Subject;

/**
 * Authorization backed by membership in a configured IVOA group URI ({@link SessionAuthorizers#SKAHA_USERS_GROUP_ENV}).
 * Logic will migrate here from {@code SkahaAction#initiateGeneralFlow}.
 */
public final class GroupURISessionAuthorizer implements SessionAuthorizer {

    private final URI skahaUsersGroupUri;

    public GroupURISessionAuthorizer(final String skahaUsersGroupUri) {
        final String groupURI = Objects.requireNonNull(skahaUsersGroupUri, "Group URI cannot be null.")
                .trim();
        if (groupURI.isEmpty()) {
            throw new IllegalArgumentException("Group URI cannot be empty.");
        } else {
            this.skahaUsersGroupUri = URI.create(groupURI);
        }
    }

    public URI getSkahaUsersGroupUri() {
        return skahaUsersGroupUri;
    }

    @Override
    public void authorizeGeneralSessionAccess(final Subject subject) throws IOException {
        Objects.requireNonNull(subject, "subject");
        throw new UnsupportedOperationException(
                "TODO: migrate IvoaGroupClient membership check from SkahaAction.initiateGeneralFlow");
    }

    @Override
    public Optional<URI> getSkahaWriteGrantAudience() {
        return Optional.of(skahaUsersGroupUri);
    }
}
