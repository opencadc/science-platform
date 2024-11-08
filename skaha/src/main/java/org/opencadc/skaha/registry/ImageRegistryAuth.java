package org.opencadc.skaha.registry;

import ca.nrc.cadc.util.Base64;
import ca.nrc.cadc.util.StringUtil;
import java.nio.charset.StandardCharsets;


/**
 * Represents credentials to an image registry.  Will be used from the request input as a base64 encoded value.
 */
public class ImageRegistryAuth {
    private static final String VALUE_DELIMITER = ":";

    private final String host;
    private final String username;
    private final byte[] secret;

    ImageRegistryAuth(final String username, final byte[] secret, final String host) {
        if (!StringUtil.hasText(username)) {
            throw new IllegalArgumentException("username is required.");
        } else if (secret.length == 0) {
            throw new IllegalArgumentException("secret value cannot be empty.");
        } else if (!StringUtil.hasText(host)) {
            throw new IllegalArgumentException("registry host is required.");
        }

        this.username = username;
        this.secret = secret;
        this.host = host;
    }

    /**
     * Constructor to use the Base64 encoded value to obtain the credentials for an Image Registry.
     *
     * @param encodedValue The Base64 encoded String.  Never null.
     * @param host  The registry host for these credentials.
     * @return ImageRegistryAuth instance.  Never null.
     */
    public static ImageRegistryAuth fromEncoded(final String encodedValue, final String host) {
        if (!StringUtil.hasText(encodedValue)) {
            throw new IllegalArgumentException("Encoded auth username and key is required.");
        } else if (!StringUtil.hasText(host)) {
            throw new IllegalArgumentException("Registry host is required.");
        }

        final String decodedValue = new String(Base64.decode(encodedValue), StandardCharsets.UTF_8);
        final String[] values = decodedValue.split(ImageRegistryAuth.VALUE_DELIMITER);

        if (values.length != 2) {
            throw new IllegalArgumentException("Invalid input.  Must be in form of username:secret");
        }

        return new ImageRegistryAuth(values[0].trim(), values[1].trim().getBytes(), host);
    }

    public String getEncoded() {
        return Base64.encodeString(this.username + ImageRegistryAuth.VALUE_DELIMITER + new String(this.secret));
    }

    public String getHost() {
        return host;
    }

    public String getUsername() {
        return username;
    }

    public byte[] getSecret() {
        return secret;
    }
}
