package org.opencadc.skaha.session;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.opencadc.skaha.K8SUtil;
import org.opencadc.skaha.SkahaKubernetesClient;

/** Creates and deletes sshpiper {@code Pipe} objects for SSH sessions. */
public final class SshPipeManager {
    private static final ResourceDefinitionContext PIPE_CONTEXT = new ResourceDefinitionContext.Builder()
            .withGroup("sshpiper.com")
            .withVersion("v1beta1")
            .withKind("Pipe")
            .withPlural("pipes")
            .withNamespaced(true)
            .build();

    private SshPipeManager() {}

    /**
     * Register routing for the user's SSH session.
     *
     * @param job Created SSH session Job (UID required for ownerReference).
     * @param username POSIX username ({@code Pipe.spec.from.username}).
     * @param sessionId Session id (Service name suffix).
     * @param userPublicKeyOpenSshLine Downstream public key (one OpenSSH line).
     * @param upstreamPrivateKeySecretName Secret holding sshpiper → pod client private key.
     */
    public static void createForSession(
            final Job job,
            final String username,
            final String sessionId,
            final String userPublicKeyOpenSshLine,
            final String upstreamPrivateKeySecretName) {
        final String namespace = K8SUtil.getWorkloadNamespace();
        final String serviceHost = "skaha-ssh-svc-" + sessionId + "." + namespace + ".svc:22";
        final GenericKubernetesResource pipe = new GenericKubernetesResourceBuilder()
                .withApiVersion("sshpiper.com/v1beta1")
                .withKind("Pipe")
                .withMetadata(new ObjectMetaBuilder()
                        .withName(SshSessionSecrets.pipeResourceName(username))
                        .withNamespace(namespace)
                        .withOwnerReferences(List.of(new OwnerReferenceBuilder()
                                .withApiVersion("batch/v1")
                                .withKind("Job")
                                .withName(job.getMetadata().getName())
                                .withUid(job.getMetadata().getUid())
                                .withController(true)
                                .withBlockOwnerDeletion(true)
                                .build()))
                        .build())
                .build();
        pipe.setAdditionalProperty(
                "spec",
                Map.of(
                        "from",
                        List.of(Map.of(
                                "username",
                                username,
                                "authorized_keys_data",
                                Base64.getEncoder()
                                        .encodeToString(
                                                userPublicKeyOpenSshLine.trim().getBytes(StandardCharsets.UTF_8)))),
                        "to",
                        Map.of(
                                "host",
                                serviceHost,
                                "username",
                                username,
                                "private_key_secret",
                                Map.of("name", upstreamPrivateKeySecretName))));
        SkahaKubernetesClient.get()
                .genericKubernetesResources(PIPE_CONTEXT)
                .resource(pipe)
                .create();
    }

    /** Remove the Pipe for this user if present (Job deletion also GCs via ownerReference). */
    public static void deleteForUser(final String username) {
        final KubernetesClient client = SkahaKubernetesClient.get();
        client.genericKubernetesResources(PIPE_CONTEXT)
                .withName(SshSessionSecrets.pipeResourceName(username))
                .delete();
    }
}
