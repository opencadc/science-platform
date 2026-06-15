package org.opencadc.skaha.session.authorization;

import ca.nrc.cadc.ac.Group;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.security.auth.Subject;
import org.apache.log4j.Logger;
import org.opencadc.gms.GroupURI;
import org.opencadc.skaha.utils.CommonUtils;

/**
 * Authorization backed by membership in a configured IVOA group URI
 * ({@link GroupURISessionAuthorizer#SKAHA_USERS_GROUP_ENV}). Logic will migrate here from
 * {@code SkahaAction#initiateGeneralFlow}.
 */
public final class GroupURISessionAuthorizer implements SessionAuthorizer {
    private static final Logger LOGGER = Logger.getLogger(GroupURISessionAuthorizer.class);

    /** Environment variable: Group URI whose members may use Skaha (GMS / IVOA group membership). */
    static final String SKAHA_USERS_GROUP_ENV = "SKAHA_USERS_GROUP";

    private final GroupURI skahaUsersGroupUri;

    /**
     * Create a new instance from the environment configuration.
     *
     * @param environment The environment variables. Typically from <code>System.getenv()</code>, but can be overridden
     *     for testing.
     * @return A new instance of <code>GroupURISessionAuthorizer</code> configured from the environment.
     */
    public static GroupURISessionAuthorizer fromEnvironment(final Map<String, String> environment) {
        final String usersGroup = CommonUtils.trimToNull(
                CommonUtils.lookup(environment, GroupURISessionAuthorizer.SKAHA_USERS_GROUP_ENV));
        return new GroupURISessionAuthorizer(usersGroup);
    }

    /**
     * Package private constructor for testing.
     *
     * @param skahaUsersGroupUri The GroupURI to match against for authorization. Must not be null or empty.
     */
    GroupURISessionAuthorizer(final String skahaUsersGroupUri) {
        final String groupURI = Objects.requireNonNull(skahaUsersGroupUri, "Group URI cannot be null.")
                .trim();
        if (groupURI.isEmpty()) {
            throw new IllegalArgumentException("Group URI cannot be empty.");
        } else {
            this.skahaUsersGroupUri = new GroupURI(URI.create(groupURI));
        }
    }

    public GroupURI getSkahaUsersGroupUri() {
        return skahaUsersGroupUri;
    }

    /**
     * Group URI check.
     *
     * @param subject the authenticated subject to check and possibly augment; must not be null.
     * @param requestMethod the request method. Not used.
     * @param routePath the request path. Not used.
     */
    @Override
    public void authorizeGeneralSessionAccess(
            final Subject subject, final String requestMethod, final String routePath) {
        Objects.requireNonNull(subject, "subject");
        LOGGER.debug("user is a member of skaha user group ");

        final List<Group> groups = CommonUtils.getCachedGroupsFromSubject(subject);
        if (groups.stream().noneMatch(group -> group.getID().equals(skahaUsersGroupUri))) {
            throw new SessionAccessDeniedException("Not authorized to use the skaha system");
        }

        LOGGER.debug("user is a member of skaha user group ");
    }
}
