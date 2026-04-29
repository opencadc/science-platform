package org.opencadc.skaha.session.authorization;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class SessionAuthorizersTest {

    @Test
    public void fromEnvironmentOnlyUsersGroupReturnsGroupUriAuthorizer() {
        final Map<String, String> env = new HashMap<>();
        env.put(SessionAuthorizers.SKAHA_GROUP_ENABLED_FLAG_ENV, Boolean.TRUE.toString());
        env.put(GroupURISessionAuthorizer.SKAHA_USERS_GROUP_ENV, "ivo://example.org/skaha?skaha-users");
        final SessionAuthorizer auth = SessionAuthorizers.fromEnvironment(env);
        Assert.assertTrue(auth instanceof GroupURISessionAuthorizer);
        final GroupURISessionAuthorizer g = (GroupURISessionAuthorizer) auth;
        Assert.assertEquals(
                URI.create("ivo://example.org/skaha?skaha-users"),
                g.getSkahaUsersGroupUri().getURI());
    }

    @Test
    public void fromEnvironmentOnlyPermissionsBaseReturnsPermissionsApiAuthorizer() throws Exception {
        final Map<String, String> env = new HashMap<>();
        env.put(SessionAuthorizers.SKAHA_PERMISSIONS_API_ENABLED_FLAG_ENV, Boolean.TRUE.toString());
        env.put(PermissionsApiSessionAuthorizer.SKAHA_PERMISSIONS_API_BASE_URL_ENV, "https://permissions.example/v1/");
        env.put(PermissionsApiSessionAuthorizer.SKAHA_PERMISSIONS_API_NAME_ENV, "skaha");
        env.put(PermissionsApiSessionAuthorizer.SKAHA_PERMISSIONS_API_TYPE_ENV, "plugin");
        final SessionAuthorizer auth = SessionAuthorizers.fromEnvironment(env);
        Assert.assertTrue(auth instanceof PermissionsApiSessionAuthorizer);
        final PermissionsApiSessionAuthorizer p = (PermissionsApiSessionAuthorizer) auth;
        Assert.assertEquals(URI.create("https://permissions.example/v1/").toURL(), p.getPermissionsApiBaseUrl());
    }

    @Test
    public void fromEnvironmentNeitherSetThrowsIllegalStateException() {
        final Map<String, String> env = new HashMap<>();
        try {
            SessionAuthorizers.fromEnvironment(env);
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(
                    expected.getMessage().contains(SessionAuthorizers.SKAHA_PERMISSIONS_API_ENABLED_FLAG_ENV));
            Assert.assertTrue(expected.getMessage().contains(SessionAuthorizers.SKAHA_GROUP_ENABLED_FLAG_ENV));
        }
    }

    @Test
    public void fromEnvironmentBothSetThrowsIllegalStateException() {
        final Map<String, String> env = new HashMap<>();
        env.put(SessionAuthorizers.SKAHA_PERMISSIONS_API_ENABLED_FLAG_ENV, Boolean.TRUE.toString());
        env.put(SessionAuthorizers.SKAHA_GROUP_ENABLED_FLAG_ENV, Boolean.TRUE.toString());
        try {
            SessionAuthorizers.fromEnvironment(env);
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("Only one authorization method"));
        }
    }

    /**
     * {@link SessionAuthorizers#fromEnvironment(Map)} rejects enabling both authorization modes or neither (lines
     * 41–47): exactly one of the enable flags must parse to {@code true}.
     */
    @Test
    public void fromEnvironmentExactlyOneAuthorizationModeFlagMustBeEnabled() {
        final Map<String, String> bothEnabled = new HashMap<>();
        bothEnabled.put(SessionAuthorizers.SKAHA_PERMISSIONS_API_ENABLED_FLAG_ENV, Boolean.TRUE.toString());
        bothEnabled.put(SessionAuthorizers.SKAHA_GROUP_ENABLED_FLAG_ENV, Boolean.TRUE.toString());
        try {
            SessionAuthorizers.fromEnvironment(bothEnabled);
            Assert.fail("expected IllegalStateException when both authorization flags are true");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(
                    expected.getMessage(),
                    expected.getMessage().contains(SessionAuthorizers.SKAHA_PERMISSIONS_API_ENABLED_FLAG_ENV));
            Assert.assertTrue(
                    expected.getMessage(),
                    expected.getMessage().contains(SessionAuthorizers.SKAHA_GROUP_ENABLED_FLAG_ENV));
            Assert.assertTrue(expected.getMessage().contains("Only one authorization method"));
        }

        final Map<String, String> neitherEnabled = new HashMap<>();
        try {
            SessionAuthorizers.fromEnvironment(neitherEnabled);
            Assert.fail("expected IllegalStateException when neither authorization flag is true");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(
                    expected.getMessage(),
                    expected.getMessage().contains(SessionAuthorizers.SKAHA_PERMISSIONS_API_ENABLED_FLAG_ENV));
            Assert.assertTrue(
                    expected.getMessage(),
                    expected.getMessage().contains(SessionAuthorizers.SKAHA_GROUP_ENABLED_FLAG_ENV));
            Assert.assertTrue(expected.getMessage().contains("One authorization method must be enabled"));
        }
    }

    @Test
    public void fromEnvironmentWhitespaceOnlyUsersGroupCountsAsUnsetWhenPermissionsSet() {
        final Map<String, String> env = new HashMap<>();
        env.put(SessionAuthorizers.SKAHA_PERMISSIONS_API_ENABLED_FLAG_ENV, Boolean.TRUE.toString());
        env.put(GroupURISessionAuthorizer.SKAHA_USERS_GROUP_ENV, "   ");
        env.put(PermissionsApiSessionAuthorizer.SKAHA_PERMISSIONS_API_BASE_URL_ENV, "https://perm/");
        env.put(PermissionsApiSessionAuthorizer.SKAHA_PERMISSIONS_API_NAME_ENV, "skaha");
        env.put(PermissionsApiSessionAuthorizer.SKAHA_PERMISSIONS_API_TYPE_ENV, "plugin");
        final SessionAuthorizer auth = SessionAuthorizers.fromEnvironment(env);
        Assert.assertTrue(auth instanceof PermissionsApiSessionAuthorizer);
    }

    @Test
    public void fromEnvironmentTrimsUsersGroupValue() {
        final Map<String, String> env = new HashMap<>();
        env.put(SessionAuthorizers.SKAHA_GROUP_ENABLED_FLAG_ENV, Boolean.TRUE.toString());
        env.put(GroupURISessionAuthorizer.SKAHA_USERS_GROUP_ENV, "  ivo://trim.example/skaha?trimmed  ");
        final GroupURISessionAuthorizer g = (GroupURISessionAuthorizer) SessionAuthorizers.fromEnvironment(env);
        Assert.assertEquals(
                URI.create("ivo://trim.example/skaha?trimmed"),
                g.getSkahaUsersGroupUri().getURI());
    }

    @Test(expected = IllegalArgumentException.class)
    public void groupUriAuthorizerRejectsBlankUri() {
        new GroupURISessionAuthorizer(" ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void permissionsApiAuthorizerRejectsBlankBaseUrl() {
        new PermissionsApiSessionAuthorizer("\t", "\r", "\n", "\t", "\r", "\n");
    }
}
