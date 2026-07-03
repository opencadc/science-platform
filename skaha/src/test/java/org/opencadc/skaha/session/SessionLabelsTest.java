package org.opencadc.skaha.session;

import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class SessionLabelsTest {
    @Test
    public void canonicalLabelsIncludeRequiredKeysWithDefaultsAndQueueLabels() {
        final Map<String, String> labels = SessionLabels.canonical(Map.of(
                SessionLabels.Key.ID, "session-123",
                SessionLabels.Key.USERNAME, "alice",
                SessionLabels.Key.NAME, "Analysis",
                SessionLabels.Key.KIND, "notebook",
                SessionLabels.Key.FLAVOR, "fixed",
                SessionLabels.Key.JOB, "notebook-alice-session-123",
                SessionLabels.Key.ACCELERATOR, "gpu"));

        Assert.assertEquals("default", labels.get("canfar.net/community"));
        Assert.assertEquals("default", labels.get("canfar.net/project"));
        Assert.assertEquals("session-123", labels.get("canfar.net/id"));
        Assert.assertEquals("alice", labels.get("canfar.net/username"));
        Assert.assertEquals("Analysis", labels.get("canfar.net/name"));
        Assert.assertEquals("notebook", labels.get("canfar.net/kind"));
        Assert.assertEquals("fixed", labels.get("canfar.net/flavor"));
        Assert.assertEquals("notebook-alice-session-123", labels.get("canfar.net/job"));
        Assert.assertEquals("gpu", labels.get("canfar.net/accelerator"));
        Assert.assertEquals("skaha", labels.get("app.kubernetes.io/managed-by"));
        Assert.assertEquals("canfar", labels.get("app.kubernetes.io/part-of"));
        Assert.assertFalse(labels.containsKey("canfar.net/app-id"));

        final Map<String, String> kueueLabels = SessionLabels.kueue("alice-default", "interactive-high");

        Assert.assertEquals("alice-default", kueueLabels.get("kueue.x-k8s.io/queue-name"));
        Assert.assertEquals("interactive-high", kueueLabels.get("kueue.x-k8s.io/priority-class"));
    }

    @Test
    public void selectorHelpersUseCanonicalLabelsOnly() {
        Assert.assertEquals(
                "canfar.net/id=session-123,canfar.net/username=alice",
                SessionLabels.forSession("alice", "session-123"));
        Assert.assertEquals("canfar.net/username=alice", SessionLabels.forUser("alice"));
        Assert.assertEquals(
                "canfar.net/id=session-123,canfar.net/username=alice,canfar.net/kind!=headless",
                SessionLabels.forUserSessions("alice", "session-123", true));
        Assert.assertEquals(
                "canfar.net/id=session-123,canfar.net/username=alice,canfar.net/app-id=app-123,canfar.net/kind=desktop-app",
                SessionLabels.forDesktopApp("session-123", "alice", "app-123"));
        Assert.assertEquals("canfar\\.net/kind", SessionLabels.sessionKindJsonPath());
    }

    @Test
    public void canonicalLabelsRejectUnsupportedAcceleratorValues() {
        try {
            SessionLabels.canonical(Map.of(SessionLabels.Key.ACCELERATOR, "tpu"));
            Assert.fail("Expected unsupported accelerator to be rejected.");
        } catch (IllegalArgumentException expected) {
            Assert.assertEquals("accelerator must be gpu or none", expected.getMessage());
        }
    }

    @Test
    public void canonicalLabelsRejectRuntimeVersionInput() {
        try {
            SessionLabels.canonical(Map.of(SessionLabels.Key.VERSION, "2026.07.02"));
            Assert.fail("Expected runtime version input to be rejected.");
        } catch (IllegalArgumentException expected) {
            Assert.assertEquals("app.kubernetes.io/version is derived from SKAHA_VERSION", expected.getMessage());
        }
    }

    @Test
    public void canonicalLabelsValidateFlavorAndKubernetesLabelValues() {
        assertInvalidCanonicalLabel(SessionLabels.Key.FLAVOR, "small", "flavor must be fixed or flexible");
        assertInvalidCanonicalLabel(SessionLabels.Key.ID, "", "canfar.net/id label value cannot be blank");
        assertInvalidCanonicalLabel(
                SessionLabels.Key.ID,
                "0123456789012345678901234567890123456789012345678901234567890123",
                "canfar.net/id label value exceeds 63 characters");
        assertInvalidCanonicalLabel(
                SessionLabels.Key.ID,
                "bad/value",
                "canfar.net/id label value must match Kubernetes label value syntax");

        final Map<String, String> labels = SessionLabels.canonical(Map.of(
                SessionLabels.Key.COMMUNITY, "",
                SessionLabels.Key.PROJECT, " ",
                SessionLabels.Key.APP_ID, " ",
                SessionLabels.Key.ID, "session-123"));

        Assert.assertEquals("default", labels.get("canfar.net/community"));
        Assert.assertEquals("default", labels.get("canfar.net/project"));
        Assert.assertFalse(labels.containsKey("canfar.net/app-id"));

        final Map<SessionLabels.Key, String> nullValue = new HashMap<>();
        nullValue.put(SessionLabels.Key.ID, null);
        try {
            SessionLabels.canonical(nullValue);
            Assert.fail("Expected null label value to be rejected.");
        } catch (IllegalArgumentException expected) {
            Assert.assertEquals("canfar.net/id label value cannot be null", expected.getMessage());
        }
    }

    private static void assertInvalidCanonicalLabel(
            final SessionLabels.Key key, final String value, final String expectedMessage) {
        try {
            SessionLabels.canonical(Map.of(key, value));
            Assert.fail("Expected invalid label value to be rejected.");
        } catch (IllegalArgumentException expected) {
            Assert.assertEquals(expectedMessage, expected.getMessage());
        }
    }
}
