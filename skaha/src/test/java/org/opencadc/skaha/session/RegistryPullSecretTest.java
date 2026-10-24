package org.opencadc.skaha.session;

import static org.opencadc.skaha.KubernetesApi.NAMESPACE;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.opencadc.skaha.KubernetesApi;
import org.opencadc.skaha.repository.ImageRepositoryAuth;

public class RegistryPullSecretTest {
    @Rule
    public final KubernetesApi api = new KubernetesApi();

    @Test
    public void testAppliedAsKubectlDockerRegistrySecret() throws Exception {
        final String path =
                "/api/v1/namespaces/" + NAMESPACE + "/secrets/registry-auth-alice?fieldManager=skaha&force=true";
        this.api.server().expect().patch().withPath(path).andReturn(200, "{}").once();

        RegistryPullSecret.apply("Alice", auth("alice", "s3cret"));

        final var request = this.api.server().takeRequest();
        Assert.assertEquals("PATCH " + path, request.getMethod() + " " + request.getPath());
        Assert.assertEquals(
                "application/apply-patch+yaml",
                request.getHeader("Content-Type").split(";")[0]);
        final Secret applied = io.fabric8.kubernetes.client.utils.Serialization.unmarshal(
                request.getBody().readUtf8(), Secret.class);
        Assert.assertEquals("kubernetes.io/dockerconfigjson", applied.getType());
        Assert.assertEquals(
                Map.of(
                        "auths",
                        Map.of(
                                "images.example.org",
                                Map.of(
                                        "username", "alice",
                                        "password", "s3cret",
                                        "auth", Base64.getEncoder().encodeToString("alice:s3cret".getBytes())))),
                new ObjectMapper()
                        .readValue(
                                new String(
                                        Base64.getDecoder()
                                                .decode(applied.getData().get(".dockerconfigjson")),
                                        StandardCharsets.UTF_8),
                                Map.class));
    }

    @Test
    public void testApplyFailure() {
        this.api
                .server()
                .expect()
                .patch()
                .withPath("/api/v1/namespaces/" + NAMESPACE
                        + "/secrets/registry-auth-alice?fieldManager=skaha&force=true")
                .andReturn(500, "")
                .once();
        Assert.assertThrows(
                KubernetesClientException.class, () -> RegistryPullSecret.apply("alice", auth("alice", "pw")));
    }

    private static ImageRepositoryAuth auth(final String username, final String secret) {
        return ImageRepositoryAuth.fromEncoded(
                Base64.getEncoder().encodeToString((username + ":" + secret).getBytes()), "images.example.org");
    }
}
