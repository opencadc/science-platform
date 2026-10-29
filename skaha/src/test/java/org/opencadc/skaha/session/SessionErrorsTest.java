package org.opencadc.skaha.session;

import static org.opencadc.skaha.KubernetesApi.NAMESPACE;

import ca.nrc.cadc.net.ResourceNotFoundException;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.EventListBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.opencadc.skaha.KubernetesApi;
import org.opencadc.skaha.session.SessionRequests.Request;
import org.opencadc.skaha.session.SessionRequests.Response;

/**
 * Session requests that fail, through the session actions. The exception each action throws decides the HTTP code
 * (cadc-rest maps it); the one mapping Skaha adds, a Kubernetes API failure to 503, runs through the full request. The
 * session cache is off, so every read is live.
 */
public class SessionErrorsTest {
    private final KubernetesApi api = new KubernetesApi();
    private final SessionRequests requests = new SessionRequests();

    @Rule
    public final RuleChain rules = RuleChain.outerRule(this.api).around(this.requests);

    @Before
    public void privateImagesAllowed() {
        this.api
                .server()
                .expect()
                .patch()
                .withPath("/api/v1/namespaces/" + NAMESPACE
                        + "/secrets/registry-auth-alice?fieldManager=skaha&force=true")
                .andReturn(
                        200,
                        new SecretBuilder()
                                .withNewMetadata()
                                .withName("registry-auth-alice")
                                .endMetadata()
                                .build())
                .always();
    }

    @Test
    public void unknownSession() {
        Assert.assertThrows(
                ResourceNotFoundException.class,
                () -> this.requests.get(new Request("nope1234").param("view", "events")));
        Assert.assertThrows(
                ResourceNotFoundException.class,
                () -> this.requests.post(new Request("nope1234/app").param("image", image("terminal"))));
        Assert.assertThrows(
                IllegalArgumentException.class,
                () -> this.requests.post(new Request("nope1234").param("action", "renew")));
    }

    @Test
    public void unsupportedAction() {
        Assert.assertThrows(
                UnsupportedOperationException.class,
                () -> this.requests.post(new Request("abc123").param("action", "pause")));
    }

    @Test
    public void sessionLimit() throws Exception {
        // The limit counts the user's live interactive sessions, including one launched a moment ago.
        this.requests.maxUserSessions = 1;
        this.requests.post(launch("notebook"));
        final IllegalArgumentException limit =
                Assert.assertThrows(IllegalArgumentException.class, () -> this.requests.post(launch("carta")));
        Assert.assertTrue(limit.getMessage(), limit.getMessage().contains("maximum of 1 active sessions"));
        // Headless sessions do not count.
        this.requests.post(launch("headless").param("cmd", "true"));
    }

    @Test
    public void kubernetesUnavailable() throws Exception {
        this.api
                .server()
                .expect()
                .get()
                .withPath(
                        "/apis/batch/v1/namespaces/" + NAMESPACE + "/jobs?labelSelector=canfar.net%2Fusername%3Dalice")
                .andReturn(500, "")
                .always();
        final Response response = this.requests.run("GET", new Request(null));
        Assert.assertEquals(response.text(), 503, response.code);
        Assert.assertTrue(response.text(), response.text().contains("Kubernetes API unavailable"));
    }

    /** A Pod whose container has no log yet (the API answers non-2xx) is skipped, as kubectl logs -l did. */
    @Test
    public void logsNotReady() throws Exception {
        sessionJobWithPod();
        this.api
                .server()
                .expect()
                .get()
                .withPath("/api/v1/namespaces/" + NAMESPACE + "/pods/j-pod/log?container=main")
                .andReturn(500, "")
                .once();
        Assert.assertEquals(
                "", this.requests.get(new Request("s1").param("view", "logs")).text());
    }

    /** The events table keeps the columns and padding of the former kubectl custom-columns output. */
    @Test
    public void eventsTable() throws Exception {
        this.api
                .client()
                .batch()
                .v1()
                .jobs()
                .resource(new JobBuilder()
                        .withNewMetadata()
                        .withName("j")
                        .addToLabels("canfar.net/id", "s1")
                        .addToLabels("canfar.net/username", "alice")
                        .endMetadata()
                        .build())
                .create();
        this.api
                .server()
                .expect()
                .get()
                .withPath("/api/v1/namespaces/" + NAMESPACE + "/events?fieldSelector=involvedObject.name%3Dj")
                .andReturn(
                        200,
                        new EventListBuilder()
                                .addToItems(
                                        event("a.1", "Warning", "FailedCreate", "pods \"x\" is forbidden", null),
                                        event("a.2", "Normal", "Pulled", "ok", "2026-10-23T17:02:00Z"))
                                .build())
                .once();
        Assert.assertEquals(
                "TYPE      REASON         MESSAGE                 FIRST-TIME             LAST-TIME\n"
                        + "Warning   FailedCreate   pods \"x\" is forbidden   2026-10-23T17:00:00Z   <none>\n"
                        + "Normal    Pulled         ok                      2026-10-23T17:00:00Z   2026-10-23T17:02:00Z\n",
                this.requests.get(new Request("s1").param("view", "events")).text());
    }

    private void sessionJobWithPod() {
        this.api
                .client()
                .pods()
                .resource(new io.fabric8.kubernetes.api.model.PodBuilder()
                        .withNewMetadata()
                        .withName("j-pod")
                        .addToLabels("canfar.net/id", "s1")
                        .addToLabels("canfar.net/username", "alice")
                        .endMetadata()
                        .withNewSpec()
                        .addNewContainer()
                        .withName("main")
                        .endContainer()
                        .endSpec()
                        .withNewStatus()
                        .addNewContainerStatus()
                        .withName("main")
                        .withNewState()
                        .withNewRunning()
                        .endRunning()
                        .endState()
                        .endContainerStatus()
                        .endStatus()
                        .build())
                .create();
    }

    private static Request launch(final String kind) {
        return new Request(null)
                .param("type", kind)
                .param("image", image(kind))
                .param("name", kind)
                .registryAuth("alice", "s3cret");
    }

    private static String image(final String name) {
        return SessionRequests.REGISTRY + "/skaha/" + name + ":1.0";
    }

    private static io.fabric8.kubernetes.api.model.Event event(
            final String name, final String type, final String reason, final String message, final String last) {
        return new EventBuilder()
                .withNewMetadata()
                .withName(name)
                .endMetadata()
                .withType(type)
                .withReason(reason)
                .withMessage(message)
                .withFirstTimestamp("2026-10-23T17:00:00Z")
                .withLastTimestamp(last)
                .build();
    }
}
