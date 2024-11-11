package org.opencadc.skaha.utils;

import java.util.Base64;
import org.junit.Assert;
import org.junit.Test;
import org.opencadc.skaha.K8SUtil;
import org.opencadc.skaha.registry.ImageRegistryAuth;

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
                new String[] {"kubectl", "delete", "-n", K8SUtil.getWorkloadNamespace(), "secret", "mysecret"},
                deleteCommand);
    }

    @Test
    public void testGetRegistryCreateSecretCommand() {
        try {
            CommandExecutioner.getRegistryCreateSecretCommand(null, "secret");
        } catch (IllegalArgumentException e) {
            Assert.assertEquals("registryAuth is required.", e.getMessage());
        }

        final ImageRegistryAuth imageRegistryAuth = ImageRegistryAuth.fromEncoded(
                new String(Base64.getEncoder().encode("username:password".getBytes())), "host");

        try {
            CommandExecutioner.getRegistryCreateSecretCommand(imageRegistryAuth, "");
        } catch (IllegalArgumentException e) {
            Assert.assertEquals("secretName is required.", e.getMessage());
        }

        try {
            CommandExecutioner.getRegistryCreateSecretCommand(imageRegistryAuth, null);
        } catch (IllegalArgumentException e) {
            Assert.assertEquals("secretName is required.", e.getMessage());
        }
    }
}
