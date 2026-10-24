package org.opencadc.skaha.session;

import static org.opencadc.skaha.KubernetesApi.NAMESPACE;

import ca.nrc.cadc.net.ResourceNotFoundException;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.EventListBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.opencadc.skaha.KubernetesApi;

public class SessionPodsTest {
    @Rule
    public final KubernetesApi api = new KubernetesApi();

    @Test
    public void testFormatMatchesKubectlColumns() {
        final String table = SessionPods.format(List.of(
                event("job-a.1", "Warning", "FailedCreate", "pods \"x\" is forbidden", "2026-10-23T17:00:00Z", null),
                event("pod-a.2", "Normal", "Pulled", "ok", "2026-10-23T17:01:00Z", "2026-10-23T17:02:00Z")));

        Assert.assertEquals(
                "TYPE      REASON         MESSAGE                 FIRST-TIME             LAST-TIME\n"
                        + "Warning   FailedCreate   pods \"x\" is forbidden   2026-10-23T17:00:00Z   <none>\n"
                        + "Normal    Pulled         ok                      2026-10-23T17:01:00Z   2026-10-23T17:02:00Z",
                table);
        Assert.assertEquals("", SessionPods.format(List.of()));
    }

    @Test
    public void testJobEventsWithoutPod() throws Exception {
        final Map<String, String> labels =
                SessionLabels.canonical(Map.of(SessionLabels.Key.ID, "abc123", SessionLabels.Key.USERNAME, "alice"));
        this.api
                .client()
                .batch()
                .v1()
                .jobs()
                .resource(new JobBuilder()
                        .withNewMetadata()
                        .withName("skaha-notebook-alice-abc123")
                        .withLabels(labels)
                        .endMetadata()
                        .build())
                .create();
        expectEvents(
                "skaha-notebook-alice-abc123",
                event(
                        "skaha-notebook-alice-abc123.1",
                        "Warning",
                        "FailedCreate",
                        "no PriorityClass with name uber-user-preempt-medium was found",
                        "2026-10-23T17:00:00Z",
                        "2026-10-23T17:05:00Z"));
        expectEvents("other-job", event("other-job.1", "Warning", "FailedCreate", "not ours", null, null));

        final String table = SessionPods.events("alice", "abc123");

        Assert.assertTrue(table, table.startsWith("TYPE"));
        Assert.assertTrue(table, table.contains("no PriorityClass with name uber-user-preempt-medium was found"));
        Assert.assertFalse(table, table.contains("not ours"));
    }

    @Test
    public void testPodEventsAndOrdering() throws Exception {
        final Map<String, String> labels =
                SessionLabels.canonical(Map.of(SessionLabels.Key.ID, "abc123", SessionLabels.Key.USERNAME, "alice"));
        this.api
                .client()
                .pods()
                .resource(new PodBuilder()
                        .withNewMetadata()
                        .withName("skaha-notebook-alice-abc123-x1y2z")
                        .withLabels(labels)
                        .endMetadata()
                        .build())
                .create();
        expectEvents(
                "skaha-notebook-alice-abc123-x1y2z",
                event("skaha-notebook-alice-abc123-x1y2z.2", "Warning", "FailedMount", "mount failed", null, null),
                event("skaha-notebook-alice-abc123-x1y2z.1", "Normal", "Scheduled", "assigned", null, null));

        final String[] lines = SessionPods.events("alice", "abc123").split("\n");

        Assert.assertEquals(3, lines.length);
        Assert.assertTrue(lines[1], lines[1].contains("Scheduled"));
        Assert.assertTrue(lines[2], lines[2].contains("FailedMount"));
    }

    @Test
    public void testUnknownSession() {
        Assert.assertThrows(ResourceNotFoundException.class, () -> SessionPods.events("alice", "missing"));
    }

    private void expectEvents(final String objectName, final Event... events) {
        this.api
                .server()
                .expect()
                .get()
                .withPath(
                        "/api/v1/namespaces/" + NAMESPACE + "/events?fieldSelector=involvedObject.name%3D" + objectName)
                .andReturn(200, new EventListBuilder().withItems(events).build())
                .once();
    }

    private static Event event(
            final String name,
            final String type,
            final String reason,
            final String message,
            final String firstTimestamp,
            final String lastTimestamp) {
        return new EventBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(NAMESPACE)
                .endMetadata()
                .withType(type)
                .withReason(reason)
                .withMessage(message)
                .withFirstTimestamp(firstTimestamp)
                .withLastTimestamp(lastTimestamp)
                .build();
    }
}
