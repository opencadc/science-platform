package org.opencadc.skaha.session;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.opencadc.skaha.SkahaKubernetesClient;
import org.opencadc.skaha.repository.ImageRepositoryAuth;

/** The per-user image pull Secret for private images, written with server-side apply so it is updated in place. */
final class RegistryPullSecret {
    private RegistryPullSecret() {}

    /** Write the user's pull Secret and return its name. */
    static String apply(final String username, final ImageRepositoryAuth registryAuth) {
        final Secret secret = build(username, registryAuth);
        SkahaKubernetesClient.get()
                .secrets()
                .resource(secret)
                .fieldManager("skaha")
                .forceConflicts()
                .serverSideApply();
        return secret.getMetadata().getName();
    }

    private static Secret build(final String username, final ImageRepositoryAuth registryAuth) {
        final String password = new String(registryAuth.getSecret(), StandardCharsets.UTF_8);
        final Map<String, Object> config = Map.of(
                "auths",
                Map.of(
                        registryAuth.getHost(),
                        Map.of(
                                "username", registryAuth.getUsername(),
                                "password", password,
                                "auth", base64(registryAuth.getUsername() + ":" + password))));
        return new SecretBuilder()
                .withNewMetadata()
                .withName("registry-auth-" + username.toLowerCase())
                .endMetadata()
                .withType("kubernetes.io/dockerconfigjson")
                .withData(Map.of(".dockerconfigjson", base64(Serialization.asJson(config))))
                .build();
    }

    private static String base64(final String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
