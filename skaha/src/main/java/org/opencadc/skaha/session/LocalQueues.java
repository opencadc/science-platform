package org.opencadc.skaha.session;

import ca.nrc.cadc.net.ResourceNotFoundException;
import ca.nrc.cadc.net.TransientException;
import ca.nrc.cadc.util.StringUtil;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.utils.Serialization;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.log4j.Logger;

/**
 * Makes an assignment's LocalQueue safe to submit to, or refuses. The only Skaha code that reads ClusterQueues or
 * writes LocalQueues.
 *
 * <p>Each call reads current state and applies only the difference, so a retry after a crash at any point converges. It
 * never updates, deletes, retargets, or adopts a LocalQueue, and never writes a ClusterQueue.
 */
final class LocalQueues {
    private static final Logger LOGGER = Logger.getLogger(LocalQueues.class);
    static final int MAX_ATTEMPTS = 3;
    static final String UNAVAILABLE = "Session queue unavailable.  Please try again.";
    static final String NOT_FOUND = "Session queue not found.";
    static final String KUEUE_API_GROUP_ENV = "SKAHA_KUEUE_API_GROUP";
    static final String KUEUE_API_VERSION_ENV = "SKAHA_KUEUE_API_VERSION";

    private final KubernetesClient client;
    private final String apiGroupVersion;
    private final ResourceDefinitionContext localQueues;
    private final ResourceDefinitionContext clusterQueues;

    LocalQueues(final KubernetesClient client) {
        this(client, System.getenv());
    }

    LocalQueues(final KubernetesClient client, final Map<String, String> env) {
        this.client = client;
        final String group = envOrDefault(env, KUEUE_API_GROUP_ENV, "kueue.x-k8s.io");
        final String version = envOrDefault(env, KUEUE_API_VERSION_ENV, "v1beta2");
        this.apiGroupVersion = group + "/" + version;
        this.localQueues = context(group, version, "LocalQueue", "localqueues", true);
        this.clusterQueues = context(group, version, "ClusterQueue", "clusterqueues", false);
    }

    /**
     * Create, reuse, or repair the assignment's LocalQueue.
     *
     * @throws ResourceNotFoundException The Community's ClusterQueue does not exist while the Kueue API does.
     * @throws TransientException The Kueue API is missing; the ClusterQueue is unreadable, terminating, or not labelled
     *     with the Community; the LocalQueue is in a state Skaha must not reuse or repair; it was still contended after
     *     {@link #MAX_ATTEMPTS}; or any other API failure. The log names the reason.
     */
    void ensure(final LaunchAssignment assignment) throws TransientException, ResourceNotFoundException {
        requireClusterQueue(assignment);
        final Resource<GenericKubernetesResource> queue = this.client
                .genericKubernetesResources(this.localQueues)
                .inNamespace(assignment.namespace())
                .withName(assignment.localQueue());
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            final GenericKubernetesResource current;
            try {
                current = queue.get();
            } catch (KubernetesClientException exception) {
                throw unavailable(assignment, "read failed", exception);
            }
            if (current == null) {
                if (written(assignment, "create", () -> this.client
                        .genericKubernetesResources(this.localQueues)
                        .inNamespace(assignment.namespace())
                        .resource(desired(assignment))
                        .create())) {
                    return;
                }
                continue;
            }
            final Map<String, String> missing = missingIdentity(current, assignment);
            if (missing.isEmpty()) {
                return;
            }
            final String patch = Serialization.asJson(Map.of(
                    "metadata",
                    Map.of("resourceVersion", current.getMetadata().getResourceVersion(), "labels", missing)));
            if (written(assignment, "repair", () -> queue.patch(PatchContext.of(PatchType.JSON_MERGE), patch))) {
                return;
            }
        }
        throw unavailable(assignment, "still contended after " + MAX_ATTEMPTS + " attempts", null);
    }

    private void requireClusterQueue(final LaunchAssignment assignment)
            throws TransientException, ResourceNotFoundException {
        final GenericKubernetesResource clusterQueue;
        try {
            clusterQueue = this.client
                    .genericKubernetesResources(this.clusterQueues)
                    .withName(assignment.clusterQueue())
                    .get();
            if (clusterQueue == null && this.client.getApiResources(this.apiGroupVersion) != null) {
                LOGGER.error("ClusterQueue " + assignment.clusterQueue() + " for Community " + assignment.community()
                        + " does not exist.");
                throw new ResourceNotFoundException(NOT_FOUND);
            }
        } catch (KubernetesClientException exception) {
            throw unavailable(assignment, "ClusterQueue read failed", exception);
        }
        if (clusterQueue == null) {
            throw unavailable(assignment, "Kueue API " + this.apiGroupVersion + " is not served", null);
        }
        final ObjectMeta metadata = clusterQueue.getMetadata();
        if (metadata.getDeletionTimestamp() != null) {
            throw unavailable(assignment, "ClusterQueue is terminating", null);
        }
        final String community = labels(metadata).get(SessionLabels.Key.COMMUNITY.label());
        if (!assignment.community().equals(community)) {
            throw unavailable(assignment, "ClusterQueue is labelled with Community " + community, null);
        }
    }

    /**
     * The identity labels to add, or none when the queue is already compliant.
     *
     * @throws TransientException When the queue must be neither reused nor repaired.
     */
    private static Map<String, String> missingIdentity(
            final GenericKubernetesResource current, final LaunchAssignment assignment) throws TransientException {
        final ObjectMeta metadata = current.getMetadata();
        if (metadata.getDeletionTimestamp() != null) {
            throw unavailable(assignment, "terminating", null);
        }
        if (!Objects.requireNonNullElse(metadata.getOwnerReferences(), List.of())
                .isEmpty()) {
            throw unavailable(assignment, "has owner references", null);
        }
        final Object spec = current.getAdditionalProperties().get("spec");
        final Object clusterQueue = spec instanceof Map<?, ?> map ? map.get("clusterQueue") : null;
        if (!assignment.clusterQueue().equals(clusterQueue)) {
            throw unavailable(assignment, "references ClusterQueue " + clusterQueue, null);
        }

        final Map<String, String> labels = labels(metadata);
        final Map<String, String> missing = new LinkedHashMap<>();
        for (final Map.Entry<SessionLabels.Key, String> identity : Map.of(
                        SessionLabels.Key.USERNAME, assignment.user(),
                        SessionLabels.Key.COMMUNITY, assignment.community())
                .entrySet()) {
            final String present = labels.get(identity.getKey().label());
            if (present == null) {
                missing.put(identity.getKey().label(), identity.getValue());
            } else if (!present.equals(identity.getValue())) {
                throw unavailable(assignment, identity.getKey().label() + " is " + present, null);
            }
        }
        if (missing.isEmpty()) {
            return missing;
        }
        if (!SessionLabels.MANAGED_BY_SKAHA.equals(labels.get(SessionLabels.Key.MANAGED_BY.label()))) {
            throw unavailable(assignment, "not managed by Skaha and missing " + missing.keySet(), null);
        }
        if (missing.size() == 2 && assignment.readableLocalQueueName()) {
            throw unavailable(assignment, "readable name without identity labels is ambiguous", null);
        }
        return missing;
    }

    private GenericKubernetesResource desired(final LaunchAssignment assignment) {
        return new GenericKubernetesResourceBuilder()
                .withApiVersion(this.apiGroupVersion)
                .withKind("LocalQueue")
                .withNewMetadata()
                .withName(assignment.localQueue())
                .withNamespace(assignment.namespace())
                .withLabels(SessionLabels.localQueue(assignment))
                .endMetadata()
                .addToAdditionalProperties("spec", Map.of("clusterQueue", assignment.clusterQueue()))
                .build();
    }

    /** True when the write landed; false when its outcome is a conflict or uncertain, so the caller rereads. */
    private static boolean written(final LaunchAssignment assignment, final String action, final Runnable write)
            throws TransientException {
        try {
            write.run();
            return true;
        } catch (KubernetesClientException exception) {
            if (exception.getCode() == 409 || SessionLauncher.isUncertain(exception)) {
                LOGGER.warn("LocalQueue " + assignment.namespace() + "/" + assignment.localQueue() + " " + action
                        + " returned " + exception.getCode() + "; reading it again.");
                return false;
            }
            throw unavailable(assignment, action + " rejected", exception);
        }
    }

    private static TransientException unavailable(
            final LaunchAssignment assignment, final String reason, final Exception cause) {
        LOGGER.error(
                "LocalQueue " + assignment.namespace() + "/" + assignment.localQueue() + " for ClusterQueue "
                        + assignment.clusterQueue() + ": " + reason,
                cause);
        return new TransientException(UNAVAILABLE);
    }

    private static Map<String, String> labels(final ObjectMeta metadata) {
        return Objects.requireNonNullElse(metadata.getLabels(), Map.of());
    }

    private static String envOrDefault(final Map<String, String> env, final String key, final String fallback) {
        final String value = env.get(key);
        return StringUtil.hasText(value) ? value.trim() : fallback;
    }

    private static ResourceDefinitionContext context(
            final String group,
            final String version,
            final String kind,
            final String plural,
            final boolean namespaced) {
        return new ResourceDefinitionContext.Builder()
                .withGroup(group)
                .withVersion(version)
                .withKind(kind)
                .withPlural(plural)
                .withNamespaced(namespaced)
                .build();
    }
}
