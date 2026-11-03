package org.opencadc.skaha.session;

import static org.opencadc.skaha.KubernetesApi.NAMESPACE;

import ca.nrc.cadc.net.ResourceNotFoundException;
import ca.nrc.cadc.net.TransientException;
import io.fabric8.kubernetes.api.model.APIResourceListBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.mockwebserver.http.RecordedRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.opencadc.skaha.KubernetesApi;

/** The LocalQueue a launch submits to, through {@link LocalQueues#ensure} against a mock Kubernetes API. */
public class LocalQueuesTest {
    private static final String KUEUE = "/apis/kueue.x-k8s.io/v1beta2";
    private static final String LOCAL_QUEUES = KUEUE + "/namespaces/" + NAMESPACE + "/localqueues";
    private static final String USERNAME = "canfar.net/username";
    private static final String COMMUNITY = "canfar.net/community";
    private static final String MANAGED_BY = "app.kubernetes.io/managed-by";
    private static final ResourceDefinitionContext LOCAL_QUEUE = context("LocalQueue", "localqueues", true);
    private static final ResourceDefinitionContext CLUSTER_QUEUE = context("ClusterQueue", "clusterqueues", false);

    @Rule
    public final KubernetesApi api = new KubernetesApi();

    @Test
    public void firstLaunchCreatesAPrivateQueueThatLaterLaunchesReuse() throws Exception {
        this.api.clusterQueue("cadc", "default");
        ensure("alice");
        ensure("alice");

        final GenericKubernetesResource queue = localQueue("alice-default-lq");
        Assert.assertEquals(
                Map.of(USERNAME, "alice", COMMUNITY, "default", MANAGED_BY, "skaha"),
                queue.getMetadata().getLabels());
        Assert.assertEquals(
                Map.of("clusterQueue", "cadc"), queue.getAdditionalProperties().get("spec"));
        Assert.assertTrue(queue.getMetadata().getOwnerReferences().isEmpty());
        Assert.assertEquals(1, posts());
    }

    @Test
    public void digestNamedQueueForIdentitiesThatAreNotDnsLabels() throws Exception {
        this.api.clusterQueue("cadc", "default");
        final LaunchAssignment bob = assignment("Bob_Smith");
        new LocalQueues(this.api.client()).ensure(bob);

        Assert.assertTrue(bob.localQueue(), bob.localQueue().matches("skaha-[a-z2-7]{52}-lq"));
        Assert.assertEquals(
                "Bob_Smith",
                localQueue(bob.localQueue()).getMetadata().getLabels().get(USERNAME));
    }

    @Test
    public void missingClusterQueueIsNotFoundWhenKueueIsServed() {
        this.api
                .server()
                .expect()
                .get()
                .withPath(KUEUE)
                .andReturn(
                        200,
                        new APIResourceListBuilder()
                                .withGroupVersion("kueue.x-k8s.io/v1beta2")
                                .build())
                .always();

        Assert.assertThrows(ResourceNotFoundException.class, () -> ensure("alice"));
        Assert.assertNull(localQueue("alice-default-lq"));
    }

    @Test
    public void missingKueueApiIsUnavailableNotNotFound() {
        this.api.server().expect().get().withPath(KUEUE).andReturn(404, "").always();
        Assert.assertThrows(TransientException.class, () -> ensure("alice"));
        Assert.assertNull(localQueue("alice-default-lq"));
    }

    @Test
    public void unusableClusterQueueCreatesNoLocalQueue() {
        for (final GenericKubernetesResource clusterQueue : List.of(
                clusterQueueObject("cadc", "cadc"),
                clusterQueueObject("cadc", null),
                terminating(clusterQueueObject("cadc", "default")))) {
            this.api.server().reset();
            this.api
                    .server()
                    .expect()
                    .get()
                    .withPath(KUEUE + "/clusterqueues/cadc")
                    .andReturn(200, clusterQueue)
                    .always();
            Assert.assertThrows(TransientException.class, () -> ensure("alice"));
            Assert.assertNull(localQueue("alice-default-lq"));
        }
    }

    @Test
    public void forbiddenClusterQueueReadIsUnavailable() {
        this.api
                .server()
                .expect()
                .get()
                .withPath(KUEUE + "/clusterqueues/cadc")
                .andReturn(403, "")
                .always();
        Assert.assertThrows(TransientException.class, () -> ensure("alice"));
    }

    @Test
    public void compliantQueueFromAnotherProducerIsReusedWithoutAdoption() throws Exception {
        this.api.clusterQueue("cadc", "default");
        create(localQueueObject("alice-default-lq", "cadc", Map.of(USERNAME, "alice", COMMUNITY, "default")));

        ensure("alice");

        Assert.assertEquals(
                Map.of(USERNAME, "alice", COMMUNITY, "default"),
                localQueue("alice-default-lq").getMetadata().getLabels());
        Assert.assertEquals(0, patches());
    }

    @Test
    public void unsafeExistingQueuesAreRefusedUnchanged() {
        final Map<String, GenericKubernetesResource> unsafe = new HashMap<>();
        unsafe.put(
                "unowned and missing a label", localQueueObject("alice-default-lq", "cadc", Map.of(USERNAME, "alice")));
        unsafe.put(
                "conflicting username on a Skaha queue",
                localQueueObject(
                        "alice-default-lq",
                        "cadc",
                        Map.of(USERNAME, "mallory", COMMUNITY, "default", MANAGED_BY, "skaha")));
        unsafe.put(
                "wrong ClusterQueue",
                localQueueObject(
                        "alice-default-lq",
                        "other",
                        Map.of(USERNAME, "alice", COMMUNITY, "default", MANAGED_BY, "skaha")));
        unsafe.put(
                "owner reference",
                withOwner(localQueueObject(
                        "alice-default-lq",
                        "cadc",
                        Map.of(USERNAME, "alice", COMMUNITY, "default", MANAGED_BY, "skaha"))));
        unsafe.put(
                "readable name with no identity",
                localQueueObject("alice-default-lq", "cadc", Map.of(MANAGED_BY, "skaha")));

        for (final Map.Entry<String, GenericKubernetesResource> queue : unsafe.entrySet()) {
            this.api.server().reset();
            this.api.clusterQueue("cadc", "default");
            create(queue.getValue());
            Assert.assertThrows(queue.getKey(), TransientException.class, () -> ensure("alice"));
            Assert.assertEquals(
                    queue.getKey(),
                    queue.getValue().getMetadata().getLabels(),
                    localQueue("alice-default-lq").getMetadata().getLabels());
        }
    }

    @Test
    public void terminatingQueueIsRefused() {
        this.api.clusterQueue("cadc", "default");
        this.api
                .server()
                .expect()
                .get()
                .withPath(LOCAL_QUEUES + "/alice-default-lq")
                .andReturn(
                        200,
                        terminating(localQueueObject(
                                "alice-default-lq",
                                "cadc",
                                Map.of(USERNAME, "alice", COMMUNITY, "default", MANAGED_BY, "skaha"))))
                .always();
        Assert.assertThrows(TransientException.class, () -> ensure("alice"));
    }

    @Test
    public void hyphenatedPairsSharingAReadableNameCannotTakeEachOthersQueue() throws Exception {
        this.api.clusterQueue("cq-xy", "x-y");
        this.api.clusterQueue("cq-y", "y");
        new LocalQueues(this.api.client()).ensure(new LaunchAssignment(NAMESPACE, "bob", "x-y", "cq-xy", null));

        Assert.assertThrows(TransientException.class, () -> new LocalQueues(this.api.client())
                .ensure(new LaunchAssignment(NAMESPACE, "bob-x", "y", "cq-y", null)));
        Assert.assertEquals(
                Map.of(USERNAME, "bob", COMMUNITY, "x-y", MANAGED_BY, "skaha"),
                localQueue("bob-x-y-lq").getMetadata().getLabels());
    }

    @Test
    public void skahaQueueMissingLabelsIsRepairedWhenItsNameProvesTheIdentity() throws Exception {
        this.api.clusterQueue("cadc", "default");
        final GenericKubernetesResource oneLabel = localQueueObject(
                "alice-default-lq", "cadc", Map.of(USERNAME, "alice", MANAGED_BY, "skaha", "keep", "me"));
        create(oneLabel);
        ensure("alice");
        Assert.assertEquals(
                Map.of(USERNAME, "alice", COMMUNITY, "default", MANAGED_BY, "skaha", "keep", "me"),
                localQueue("alice-default-lq").getMetadata().getLabels());

        final LaunchAssignment digest = assignment("Alice");
        create(localQueueObject(digest.localQueue(), "cadc", Map.of(MANAGED_BY, "skaha")));
        new LocalQueues(this.api.client()).ensure(digest);
        Assert.assertEquals(
                Map.of(USERNAME, "Alice", COMMUNITY, "default", MANAGED_BY, "skaha"),
                localQueue(digest.localQueue()).getMetadata().getLabels());
    }

    @Test
    public void createConflictValidatesTheWinner() throws Exception {
        this.api.clusterQueue("cadc", "default");
        final String path = LOCAL_QUEUES + "/alice-default-lq";
        this.api.server().expect().get().withPath(path).andReturn(404, "").once();
        this.api
                .server()
                .expect()
                .post()
                .withPath(LOCAL_QUEUES)
                .andReturn(409, "")
                .once();
        this.api
                .server()
                .expect()
                .get()
                .withPath(path)
                .andReturn(
                        200,
                        localQueueObject(
                                "alice-default-lq",
                                "cadc",
                                Map.of(USERNAME, "alice", COMMUNITY, "default", MANAGED_BY, "skaha")))
                .once();

        ensure("alice");
    }

    @Test
    public void stalePatchIsNotResent() throws Exception {
        this.api.clusterQueue("cadc", "default");
        final String path = LOCAL_QUEUES + "/alice-default-lq";
        final GenericKubernetesResource stale =
                localQueueObject("alice-default-lq", "cadc", Map.of(USERNAME, "alice", MANAGED_BY, "skaha"));
        stale.getMetadata().setResourceVersion("1");
        this.api.server().expect().get().withPath(path).andReturn(200, stale).once();
        this.api.server().expect().patch().withPath(path).andReturn(409, "").once();
        this.api
                .server()
                .expect()
                .get()
                .withPath(path)
                .andReturn(
                        200,
                        localQueueObject(
                                "alice-default-lq",
                                "cadc",
                                Map.of(USERNAME, "alice", COMMUNITY, "default", MANAGED_BY, "skaha")))
                .once();

        ensure("alice");
        final List<RecordedRequest> patches = new ArrayList<>();
        RecordedRequest request;
        while ((request = this.api.server().takeRequest(10, TimeUnit.MILLISECONDS)) != null) {
            if ("PATCH".equals(request.getMethod())) {
                patches.add(request);
            }
        }
        Assert.assertEquals(1, patches.size());
        Assert.assertEquals(
                Map.of("metadata", Map.of("resourceVersion", "1", "labels", Map.of(COMMUNITY, "default"))),
                Serialization.unmarshal(patches.get(0).getBody().readUtf8(), Map.class));
    }

    @Test
    public void persistentContentionIsUnavailable() {
        this.api.clusterQueue("cadc", "default");
        for (int i = 0; i < LocalQueues.MAX_ATTEMPTS; i++) {
            this.api
                    .server()
                    .expect()
                    .post()
                    .withPath(LOCAL_QUEUES)
                    .andReturn(409, "")
                    .once();
        }
        Assert.assertThrows(TransientException.class, () -> ensure("alice"));
    }

    private void ensure(final String user) throws Exception {
        new LocalQueues(this.api.client()).ensure(assignment(user));
    }

    private static LaunchAssignment assignment(final String user) {
        return new LaunchAssignment(NAMESPACE, user, "default", "cadc", null);
    }

    private void create(final GenericKubernetesResource resource) {
        final boolean namespaced = "LocalQueue".equals(resource.getKind());
        if (namespaced) {
            this.api
                    .client()
                    .genericKubernetesResources(LOCAL_QUEUE)
                    .resource(resource)
                    .create();
        } else {
            this.api
                    .client()
                    .genericKubernetesResources(CLUSTER_QUEUE)
                    .resource(resource)
                    .create();
        }
    }

    private GenericKubernetesResource localQueue(final String name) {
        return this.api
                .client()
                .genericKubernetesResources(LOCAL_QUEUE)
                .withName(name)
                .get();
    }

    private long posts() throws InterruptedException {
        return requests("POST " + LOCAL_QUEUES);
    }

    private long patches() throws InterruptedException {
        return requests("PATCH " + LOCAL_QUEUES);
    }

    private long requests(final String prefix) throws InterruptedException {
        return this.api.requests().stream()
                .filter(request -> request.startsWith(prefix))
                .count();
    }

    private static GenericKubernetesResource clusterQueueObject(final String name, final String community) {
        final GenericKubernetesResourceBuilder builder = new GenericKubernetesResourceBuilder()
                .withApiVersion("kueue.x-k8s.io/v1beta2")
                .withKind("ClusterQueue")
                .withNewMetadata()
                .withName(name)
                .endMetadata();
        if (community != null) {
            builder.editMetadata().addToLabels(COMMUNITY, community).endMetadata();
        }
        return builder.build();
    }

    private static GenericKubernetesResource localQueueObject(
            final String name, final String clusterQueue, final Map<String, String> labels) {
        return new GenericKubernetesResourceBuilder()
                .withApiVersion("kueue.x-k8s.io/v1beta2")
                .withKind("LocalQueue")
                .withNewMetadata()
                .withName(name)
                .withNamespace(NAMESPACE)
                .withLabels(new HashMap<>(labels))
                .endMetadata()
                .addToAdditionalProperties("spec", Map.of("clusterQueue", clusterQueue))
                .build();
    }

    private static GenericKubernetesResource terminating(final GenericKubernetesResource resource) {
        resource.getMetadata().setDeletionTimestamp("2026-11-03T00:00:00Z");
        return resource;
    }

    private static GenericKubernetesResource withOwner(final GenericKubernetesResource resource) {
        resource.getMetadata()
                .setOwnerReferences(List.of(new OwnerReferenceBuilder()
                        .withApiVersion("batch/v1")
                        .withKind("Job")
                        .withName("job")
                        .withUid("uid")
                        .build()));
        return resource;
    }

    private static ResourceDefinitionContext context(final String kind, final String plural, final boolean namespaced) {
        return new ResourceDefinitionContext.Builder()
                .withGroup("kueue.x-k8s.io")
                .withVersion("v1beta2")
                .withKind(kind)
                .withPlural(plural)
                .withNamespaced(namespaced)
                .build();
    }
}
