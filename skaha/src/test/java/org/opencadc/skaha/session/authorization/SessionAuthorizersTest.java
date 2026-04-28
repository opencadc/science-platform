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
        env.put(SessionAuthorizers.SKAHA_USERS_GROUP_ENV, "ivo://example.org/skaha?skaha-users");
        final SessionAuthorizer auth = SessionAuthorizers.fromEnvironment(env);
        Assert.assertTrue(auth instanceof GroupURISessionAuthorizer);
        final GroupURISessionAuthorizer g = (GroupURISessionAuthorizer) auth;
        Assert.assertEquals(URI.create("ivo://example.org/skaha?skaha-users"), g.getSkahaUsersGroupUri());
    }

    @Test
    public void fromEnvironmentOnlyPermissionsBaseReturnsPermissionsApiAuthorizer() throws Exception {
        final Map<String, String> env = new HashMap<>();
        env.put(SessionAuthorizers.SKAHA_PERMISSIONS_API_BASE_URL_ENV, "https://permissions.example/v1/");
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
            Assert.assertTrue(expected.getMessage().contains(SessionAuthorizers.SKAHA_USERS_GROUP_ENV));
        }
    }

    @Test
    public void fromEnvironmentBothSetThrowsIllegalStateException() {
        final Map<String, String> env = new HashMap<>();
        env.put(SessionAuthorizers.SKAHA_USERS_GROUP_ENV, "ivo://example/gms?g");
        env.put(SessionAuthorizers.SKAHA_PERMISSIONS_API_BASE_URL_ENV, "https://perm/");
        try {
            SessionAuthorizers.fromEnvironment(env);
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("only one"));
        }
    }

    @Test
    public void fromEnvironmentWhitespaceOnlyUsersGroupCountsAsUnsetWhenPermissionsSet() {
        final Map<String, String> env = new HashMap<>();
        env.put(SessionAuthorizers.SKAHA_USERS_GROUP_ENV, "   ");
        env.put(SessionAuthorizers.SKAHA_PERMISSIONS_API_BASE_URL_ENV, "https://perm/");
        final SessionAuthorizer auth = SessionAuthorizers.fromEnvironment(env);
        Assert.assertTrue(auth instanceof PermissionsApiSessionAuthorizer);
    }

    @Test
    public void fromEnvironmentTrimsUsersGroupValue() {
        final Map<String, String> env = new HashMap<>();
        env.put(SessionAuthorizers.SKAHA_USERS_GROUP_ENV, "  ivo://trim.example/x  ");
        final GroupURISessionAuthorizer g = (GroupURISessionAuthorizer) SessionAuthorizers.fromEnvironment(env);
        Assert.assertEquals(URI.create("ivo://trim.example/x"), g.getSkahaUsersGroupUri());
    }

    @Test(expected = IllegalArgumentException.class)
    public void groupUriAuthorizerRejectsBlankUri() {
        new GroupURISessionAuthorizer(" ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void permissionsApiAuthorizerRejectsBlankBaseUrl() {
        new PermissionsApiSessionAuthorizer("\t");
    }
}
