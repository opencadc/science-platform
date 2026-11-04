package org.opencadc.skaha.session;

import org.junit.Assert;
import org.junit.Test;

public class SshSessionCredentialsTest {
    @Test
    public void generateProducesOpenSshFormats() {
        final SshSessionCredentials credentials = SshSessionCredentials.generate();
        Assert.assertTrue(credentials.publicKeyOpenSshLine().startsWith("ssh-rsa "));
        Assert.assertTrue(credentials.privateKeyPem().contains("BEGIN PRIVATE KEY"));
    }
}
