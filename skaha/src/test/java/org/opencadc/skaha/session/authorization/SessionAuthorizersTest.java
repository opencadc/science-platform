package org.opencadc.skaha.session.authorization;

import ca.nrc.cadc.util.InvalidConfigException;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class SessionAuthorizersTest {

    private static StandardServiceLookup createTestLookup() {
        return standardId -> {
            if (PermissionsApiSessionAuthorizer.STD_AUTH_API.equals(standardId)) {
                return URI.create("https://auth.example/v1/");
            }
            if (PermissionsApiSessionAuthorizer.STD_PERMISSIONS_API.equals(standardId)) {
                return URI.create("https://permissions.example/v1/");
            }
            return null;
        };
    }

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
        env.put(PermissionsApiSessionAuthorizer.SKAHA_PERMISSIONS_API_NAME_ENV, "skaha");
        final PermissionsApiSessionAuthorizer p =
                PermissionsApiSessionAuthorizer.fromEnvironment(env, createTestLookup());
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
        env.put(GroupURISessionAuthorizer.SKAHA_USERS_GROUP_ENV, "   ");
        env.put(PermissionsApiSessionAuthorizer.SKAHA_PERMISSIONS_API_NAME_ENV, "skaha");
        final PermissionsApiSessionAuthorizer auth =
                PermissionsApiSessionAuthorizer.fromEnvironment(env, createTestLookup());
        Assert.assertNotNull(auth);
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

    @Test(expected = NullPointerException.class)
    public void permissionsApiAuthorizerRejectsNullBaseUrl() throws Exception {
        final URL authUrl = URI.create("https://auth.example/v1/").toURL();
        new PermissionsApiSessionAuthorizer(null, authUrl, "skaha", "1");
    }

    @Test
    public void fromEnvironmentMissingAuthApiThrowsInvalidConfigException() {
        final Map<String, String> env = new HashMap<>();
        env.put(PermissionsApiSessionAuthorizer.SKAHA_PERMISSIONS_API_NAME_ENV, "skaha");
        final StandardServiceLookup lookup = standardId -> {
            if (PermissionsApiSessionAuthorizer.STD_PERMISSIONS_API.equals(standardId)) {
                return URI.create("https://permissions.example/v1/");
            }
            return null;
        };
        try {
            PermissionsApiSessionAuthorizer.fromEnvironment(env, lookup);
            Assert.fail("expected InvalidConfigException");
        } catch (InvalidConfigException expected) {
            Assert.assertTrue(expected.getMessage().contains("missing authAPI"));
        }
    }

    @Test
    public void fromEnvironmentMissingPermissionsApiThrowsInvalidConfigException() {
        final Map<String, String> env = new HashMap<>();
        env.put(PermissionsApiSessionAuthorizer.SKAHA_PERMISSIONS_API_NAME_ENV, "skaha");
        final StandardServiceLookup lookup = standardId -> {
            if (PermissionsApiSessionAuthorizer.STD_AUTH_API.equals(standardId)) {
                return URI.create("https://auth.example/v1/");
            }
            return null;
        };
        try {
            PermissionsApiSessionAuthorizer.fromEnvironment(env, lookup);
            Assert.fail("expected InvalidConfigException");
        } catch (InvalidConfigException expected) {
            Assert.assertTrue(expected.getMessage().contains("missing authAPI"));
        }
    }

    @Test
    public void fromEnvironmentInvalidUrlThrowsInvalidConfigException() {
        final Map<String, String> env = new HashMap<>();
        env.put(PermissionsApiSessionAuthorizer.SKAHA_PERMISSIONS_API_NAME_ENV, "skaha");
        final StandardServiceLookup lookup = standardId -> {
            if (PermissionsApiSessionAuthorizer.STD_AUTH_API.equals(standardId)) {
                return URI.create("ivo://only/authority");
            }
            if (PermissionsApiSessionAuthorizer.STD_PERMISSIONS_API.equals(standardId)) {
                return URI.create("https://permissions.example/v1/");
            }
            return null;
        };
        try {
            PermissionsApiSessionAuthorizer.fromEnvironment(env, lookup);
            Assert.fail("expected InvalidConfigException");
        } catch (InvalidConfigException expected) {
            Assert.assertTrue(expected.getMessage().contains("invalid URL"));
            Assert.assertTrue(expected.getMessage().contains(PermissionsApiSessionAuthorizer.STD_AUTH_API.toString()));
        }
    }
}
