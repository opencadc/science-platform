package org.opencadc.skaha.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.opencadc.skaha.SkahaKubernetesClient;
import org.opencadc.skaha.repository.ImageRepositoryAuth;

/**
 * The per-user image pull Secret for private images, written with server-side apply: updated in place, so there is no
 * delete-then-create gap for a concurrent launch, and credentials never appear in process arguments.
 */
final class RegistryPullSecret {
    static final String FIELD_MANAGER = "skaha";

    private RegistryPullSecret() {}

    static String name(final String username) {
        return "registry-auth-" + username.toLowerCase();
    }

    static void apply(final String username, final ImageRepositoryAuth registryAuth) {
        SkahaKubernetesClient.get()
                .secrets()
                .resource(build(username, registryAuth))
                .fieldManager(FIELD_MANAGER)
                .forceConflicts()
                .serverSideApply();
    }

    /** The same Secret {@code kubectl create secret docker-registry} produced. */
    static Secret build(final String username, final ImageRepositoryAuth registryAuth) {
        final String password = new String(registryAuth.getSecret(), StandardCharsets.UTF_8);
        final Map<String, Object> config = Map.of(
                "auths",
                Map.of(
                        registryAuth.getHost(),
                        Map.of(
                                "username", registryAuth.getUsername(),
                                "password", password,
                                "auth", base64(registryAuth.getUsername() + ":" + password))));
        try {
            return new SecretBuilder()
                    .withNewMetadata()
                    .withName(name(username))
                    .endMetadata()
                    .withType("kubernetes.io/dockerconfigjson")
                    .withData(Map.of(".dockerconfigjson", base64(new ObjectMapper().writeValueAsString(config))))
                    .build();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize registry credentials", exception);
        }
    }

    private static String base64(final String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
