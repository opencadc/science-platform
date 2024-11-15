package org.opencadc.skaha.repository;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Assert;
import org.junit.Test;

public class ImageRepositoryAuthTest {
    @Test
    public void testFromEncodedBadInputs() {
        try {
            ImageRepositoryAuth.fromEncoded(null, "host");
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            Assert.assertEquals("Encoded auth username and key is required.", e.getMessage());
        }

        try {
            ImageRepositoryAuth.fromEncoded("", "host");
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            Assert.assertEquals("Encoded auth username and key is required.", e.getMessage());
        }

        try {
            ImageRepositoryAuth.fromEncoded("value", null);
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            Assert.assertEquals("Repository host is required.", e.getMessage());
        }
    }

    @Test
    public void testFromEncoded() {
        final String encodedValue =
                new String(Base64.getEncoder().encode("username:supersecret".getBytes(StandardCharsets.UTF_8)));
        final ImageRepositoryAuth auth = ImageRepositoryAuth.fromEncoded(encodedValue, "host.example.com");

        Assert.assertEquals("Wrong username", "username", auth.getUsername());
        Assert.assertArrayEquals("Wrong secret", "supersecret".getBytes(), auth.getSecret());
        Assert.assertEquals("Wrong host", "host.example.com", auth.getHost());
    }
}
