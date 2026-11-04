package org.opencadc.skaha.session;

import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.List;
import java.util.Map;
import org.opencadc.skaha.SkahaKubernetesClient;

/** Per-session Secrets for SSH host authentication and sshpiper upstream client keys. */
final class SshSessionSecrets {
    static final String HOST_AUTH_DATA_KEY = "authorized_keys";
    static final String UPSTREAM_PRIVATE_KEY = "ssh-privatekey";

    private SshSessionSecrets() {}

    /** Secret mounted into the session pod; accepts connections from sshpiper using the upstream private key. */
    static String createHostAuthSecret(
            final String sessionId, final String username, final String upstreamPublicKeyOpenSshLine) {
        final String name = hostAuthSecretName(sessionId);
        final Secret secret = new SecretBuilder()
                .withNewMetadata()
                .withName(name)
                .withLabels(hostAuthLabels(username, sessionId))
                .endMetadata()
                .withStringData(Map.of(HOST_AUTH_DATA_KEY, upstreamPublicKeyOpenSshLine + "\n"))
                .build();
        SkahaKubernetesClient.get().secrets().resource(secret).create();
        return name;
    }

    static String createUpstreamPrivateKeySecret(
            final String sessionId, final String username, final String privateKeyPem) {
        final String name = upstreamSecretName(sessionId);
        final Secret secret = new SecretBuilder()
                .withNewMetadata()
                .withName(name)
                .withLabels(hostAuthLabels(username, sessionId))
                .endMetadata()
                .withType("kubernetes.io/ssh-auth")
                .withStringData(Map.of(UPSTREAM_PRIVATE_KEY, privateKeyPem))
                .build();
        SkahaKubernetesClient.get().secrets().resource(secret).create();
        return name;
    }

    static void attachJobOwner(final Job job, final String... secretNames) {
        final OwnerReference owner = new OwnerReferenceBuilder()
                .withApiVersion("batch/v1")
                .withKind("Job")
                .withName(job.getMetadata().getName())
                .withUid(job.getMetadata().getUid())
                .withController(true)
                .withBlockOwnerDeletion(true)
                .build();
        final KubernetesClient client = SkahaKubernetesClient.get();
        for (final String secretName : secretNames) {
            final Secret existing = client.secrets().withName(secretName).get();
            if (existing == null) {
                continue;
            }
            existing.getMetadata().setOwnerReferences(List.of(owner));
            client.secrets().resource(existing).update();
        }
    }

    static void deleteForSession(final String sessionId) {
        final KubernetesClient client = SkahaKubernetesClient.get();
        client.secrets().withName(hostAuthSecretName(sessionId)).delete();
        client.secrets().withName(upstreamSecretName(sessionId)).delete();
    }

    static String hostAuthSecretName(final String sessionId) {
        return "skaha-ssh-host-auth-" + sessionId;
    }

    static String upstreamSecretName(final String sessionId) {
        return "skaha-ssh-pipe-upstream-" + sessionId;
    }

    private static Map<String, String> hostAuthLabels(final String username, final String sessionId) {
        return Map.of(
                SessionLabels.Key.USERNAME.label(), username,
                SessionLabels.Key.ID.label(), sessionId,
                SessionLabels.Key.KIND.label(), SessionType.SSH.applicationName);
    }

    static String pipeResourceName(final String username) {
        final String sanitized = username.toLowerCase().replaceAll("[^0-9a-z-]", "-");
        final String prefix = "skaha-ssh-";
        final int max = 63 - prefix.length();
        final String trimmed = sanitized.length() > max ? sanitized.substring(0, max) : sanitized;
        return prefix + trimmed;
    }
}
