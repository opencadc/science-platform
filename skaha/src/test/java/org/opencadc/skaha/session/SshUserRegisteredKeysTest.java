package org.opencadc.skaha.session;

import static org.opencadc.skaha.KubernetesApi.NAMESPACE;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.opencadc.skaha.KubernetesApi;

public class SshUserRegisteredKeysTest {
    @Rule
    public final KubernetesApi api = new KubernetesApi();

    @Test
    public void registerPublicKeyCreatesThenPatchesSecret() {
        SshUserRegisteredKeys.registerPublicKey("alice", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample office-key");

        final Secret first = this.api
                .client()
                .secrets()
                .withName("skaha-ssh-user-keys-alice")
                .get();
        Assert.assertNotNull(first);
        Assert.assertTrue(
                first.getStringData().get(SshUserRegisteredKeys.AUTHORIZED_KEYS).contains("office-key"));

        SshUserRegisteredKeys.registerPublicKey("alice", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample home-key");

        final Secret second = this.api
                .client()
                .secrets()
                .withName("skaha-ssh-user-keys-alice")
                .get();
        final String keys = second.getStringData().get(SshUserRegisteredKeys.AUTHORIZED_KEYS);
        Assert.assertTrue(keys.contains("office-key"));
        Assert.assertTrue(keys.contains("home-key"));
    }

    @Test
    public void registerPublicKeyIsIdempotentForSameLine() {
        final String key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample same-key";
        SshUserRegisteredKeys.registerPublicKey("bob", key);
        final Secret before =
                this.api.client().secrets().withName("skaha-ssh-user-keys-bob").get();
        final int resourceVersionBefore = Integer.parseInt(before.getMetadata().getResourceVersion());

        SshUserRegisteredKeys.registerPublicKey("bob", key);

        final Secret after =
                this.api.client().secrets().withName("skaha-ssh-user-keys-bob").get();
        Assert.assertEquals(
                resourceVersionBefore, Integer.parseInt(after.getMetadata().getResourceVersion()));
    }

    @Test
    public void mergeAuthorizedKeysDecodesExistingDataKey() {
        final Secret existing = new SecretBuilder()
                .withNewMetadata()
                .withName("skaha-ssh-user-keys-carol")
                .withNamespace(NAMESPACE)
                .endMetadata()
                .withData(java.util.Map.of(
                        SshUserRegisteredKeys.AUTHORIZED_KEYS,
                        java.util.Base64.getEncoder()
                                .encodeToString("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample existing\n".getBytes())))
                .build();
        this.api.client().secrets().resource(existing).create();

        final String merged = SshUserRegisteredKeys.mergeAuthorizedKeys(
                existing, "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample added");
        Assert.assertTrue(merged.contains("existing"));
        Assert.assertTrue(merged.contains("added"));
    }
}
