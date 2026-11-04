package org.opencadc.skaha.session;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.opencadc.skaha.K8SUtil;
import org.opencadc.skaha.SkahaKubernetesClient;

/** Per-user Secret of registered SSH public keys (sshpiper downstream auth). */
public final class SshUserRegisteredKeys {
    static final String AUTHORIZED_KEYS = "authorized_keys";
    private static final String NAME_PREFIX = "skaha-ssh-user-keys-";

    private SshUserRegisteredKeys() {}

    /** Kubernetes Secret name holding this user's registered public keys. */
    public static String secretName(final String username) {
        final String sanitized = username.toLowerCase().replaceAll("[^0-9a-z-]", "-");
        final int max = 63 - NAME_PREFIX.length();
        final String trimmed = sanitized.length() > max ? sanitized.substring(0, max) : sanitized;
        return NAME_PREFIX + trimmed;
    }

    /**
     * Append an OpenSSH public key line if not already present. Creates or patches the user Secret. Active sshpiper
     * Pipes that reference {@link #secretName(String)} pick up new keys from the Secret.
     */
    public static void registerPublicKey(final String username, final String openSshPublicKeyLine) {
        final String line = openSshPublicKeyLine.trim();
        final String name = secretName(username);
        final KubernetesClient client = SkahaKubernetesClient.get();
        final Secret existing = client.secrets().withName(name).get();
        final String merged = mergeAuthorizedKeys(existing, line);
        if (existing == null) {
            final Secret secret = new SecretBuilder()
                    .withNewMetadata()
                    .withName(name)
                    .withNamespace(K8SUtil.getWorkloadNamespace())
                    .withLabels(Map.of(SessionLabels.Key.USERNAME.label(), username))
                    .endMetadata()
                    .withStringData(Map.of(AUTHORIZED_KEYS, merged))
                    .build();
            client.secrets().resource(secret).create();
            return;
        }
        final String current = readAuthorizedKeys(existing);
        if (merged.equals(current)) {
            return;
        }
        final Secret updated = new SecretBuilder(existing)
                .withStringData(Map.of(AUTHORIZED_KEYS, merged))
                .build();
        client.secrets().resource(updated).update();
    }

    static String mergeAuthorizedKeys(final Secret existing, final String newLine) {
        final Set<String> lines = new LinkedHashSet<>(parseAuthorizedKeys(existing));
        lines.add(newLine);
        return String.join("\n", lines) + "\n";
    }

    private static List<String> parseAuthorizedKeys(final Secret secret) {
        final List<String> lines = new ArrayList<>();
        if (secret == null) {
            return lines;
        }
        final String raw = readAuthorizedKeys(secret);
        if (raw.isEmpty()) {
            return lines;
        }
        for (final String part : raw.split("\n")) {
            final String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    private static String readAuthorizedKeys(final Secret secret) {
        if (secret.getStringData() != null && secret.getStringData().containsKey(AUTHORIZED_KEYS)) {
            return secret.getStringData().get(AUTHORIZED_KEYS);
        }
        if (secret.getData() != null && secret.getData().containsKey(AUTHORIZED_KEYS)) {
            return new String(
                    Base64.getDecoder().decode(secret.getData().get(AUTHORIZED_KEYS)), StandardCharsets.UTF_8);
        }
        return "";
    }
}
