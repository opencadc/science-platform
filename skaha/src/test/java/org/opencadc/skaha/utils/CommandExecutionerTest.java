package org.opencadc.skaha.utils;

import java.util.Base64;
import org.junit.Assert;
import org.junit.Test;
import org.opencadc.skaha.K8SUtil;
import org.opencadc.skaha.repository.ImageRepositoryAuth;

public class CommandExecutionerTest {
    @Test
    public void testGetDeleteSecretCommand() {
        try {
            CommandExecutioner.getDeleteSecretCommand(null);
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            Assert.assertEquals("secretName is required.", e.getMessage());
        }

        final String[] deleteCommand = CommandExecutioner.getDeleteSecretCommand("mysecret");
        Assert.assertArrayEquals(
                "Wrong delete command.",
                new String[] {"kubectl", "--namespace", K8SUtil.getWorkloadNamespace(), "delete", "secret", "mysecret"},
                deleteCommand);
    }

    @Test
    public void testGetRegistryCreateSecretCommand() {
        try {
            CommandExecutioner.getRegistryCreateSecretCommand(null, "secret");
        } catch (IllegalArgumentException e) {
            Assert.assertEquals("registryAuth is required.", e.getMessage());
        }

        final ImageRepositoryAuth imageRepositoryAuth = ImageRepositoryAuth.fromEncoded(
                new String(Base64.getEncoder().encode("username:password".getBytes())), "host");

        try {
            CommandExecutioner.getRegistryCreateSecretCommand(imageRepositoryAuth, "");
        } catch (IllegalArgumentException e) {
            Assert.assertEquals("secretName is required.", e.getMessage());
        }

        try {
            CommandExecutioner.getRegistryCreateSecretCommand(imageRepositoryAuth, null);
        } catch (IllegalArgumentException e) {
            Assert.assertEquals("secretName is required.", e.getMessage());
        }
    }
}
