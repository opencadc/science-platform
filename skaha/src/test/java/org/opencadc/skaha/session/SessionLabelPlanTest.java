package org.opencadc.skaha.session;

import java.util.EnumMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class SessionLabelPlanTest {
    @Test
    public void projectionsSplitJobMetadataAndSelector() {
        final Map<SessionLabels.Key, String> values = new EnumMap<>(SessionLabels.Key.class);
        values.put(SessionLabels.Key.ID, "session-123");
        values.put(SessionLabels.Key.USERNAME, "alice");
        values.put(SessionLabels.Key.NAME, "Analysis");
        values.put(SessionLabels.Key.KIND, "notebook");
        values.put(SessionLabels.Key.JOB, "notebook-alice-session-123");
        values.put(SessionLabels.Key.FLAVOR, "fixed");
        values.put(SessionLabels.Key.ACCELERATOR, "none");

        final Map<String, String> canonical = new java.util.HashMap<>(SessionLabels.canonical(values));
        canonical.put(SessionLabels.Key.VERSION.label(), SessionLabels.version("1.2.3"));
        final SessionLabelPlan plan = SessionLabelPlan.of(canonical);

        Assert.assertEquals("session-123", plan.jobLabels().get("canfar.net/id"));
        Assert.assertEquals("1.2.3", plan.jobLabels().get("app.kubernetes.io/version"));
        Assert.assertEquals("session-123", plan.serviceMetadataLabels().get("canfar.net/id"));
        Assert.assertEquals("1.2.3", plan.serviceMetadataLabels().get("app.kubernetes.io/version"));
        Assert.assertEquals(2, plan.serviceSelector().size());
        Assert.assertEquals("session-123", plan.serviceSelector().get("canfar.net/id"));
        Assert.assertEquals("notebook", plan.serviceSelector().get("canfar.net/kind"));
        Assert.assertEquals(plan.serviceMetadataLabels(), plan.ingressMetadataLabels());
    }
}
