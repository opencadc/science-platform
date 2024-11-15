package org.opencadc.skaha.repository;

import ca.nrc.cadc.util.Base64;
import ca.nrc.cadc.util.StringUtil;
import java.nio.charset.StandardCharsets;

/**
 * Represents credentials to an image repository.  Will be used from the request input as a base64 encoded value.
 */
public class ImageRepositoryAuth {
    private static final String VALUE_DELIMITER = ":";

    private final String host;
    private final String username;
    private final byte[] secret;

    ImageRepositoryAuth(final String username, final byte[] secret, final String host) {
        if (!StringUtil.hasText(username)) {
            throw new IllegalArgumentException("username is required.");
        } else if (secret.length == 0) {
            throw new IllegalArgumentException("secret value cannot be empty.");
        } else if (!StringUtil.hasText(host)) {
            throw new IllegalArgumentException("repository host is required.");
        }

        this.username = username;
        this.secret = secret;
        this.host = host;
    }

    /**
     * Constructor to use the Base64 encoded value to obtain the credentials for an Image Repository.
     *
     * @param encodedValue The Base64 encoded String.  Never null.
     * @param host  The repository host for these credentials.
     * @return ImageRepositoryAuth instance.  Never null.
     */
    public static ImageRepositoryAuth fromEncoded(final String encodedValue, final String host) {
        if (!StringUtil.hasText(encodedValue)) {
            throw new IllegalArgumentException("Encoded auth username and key is required.");
        } else if (!StringUtil.hasText(host)) {
            throw new IllegalArgumentException("Repository host is required.");
        }

        final String decodedValue = new String(Base64.decode(encodedValue), StandardCharsets.UTF_8);
        final String[] values = decodedValue.split(ImageRepositoryAuth.VALUE_DELIMITER);

        if (values.length != 2) {
            throw new IllegalArgumentException("Invalid input.  Must be in form of username:secret");
        }

        return new ImageRepositoryAuth(values[0].trim(), values[1].trim().getBytes(), host);
    }

    public String getEncoded() {
        return Base64.encodeString(this.username + ImageRepositoryAuth.VALUE_DELIMITER + new String(this.secret));
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
