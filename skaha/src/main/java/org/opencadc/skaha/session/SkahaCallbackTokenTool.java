package org.opencadc.skaha.session;

import ca.nrc.cadc.util.Base64;
import ca.nrc.cadc.util.RsaSignatureGenerator;
import ca.nrc.cadc.util.RsaSignatureVerifier;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.util.Objects;
import org.apache.log4j.Logger;
import org.opencadc.permissions.Grant;
import org.opencadc.skaha.session.authorization.SessionAccessDeniedException;

/**
 * RSA-signed session callback tokens using the same {@code meta~sig} transport as
 * {@link org.opencadc.permissions.TokenTool}. Metadata contains {@code gnt} (grant simple name) and {@code sub}
 * (session id) only. Tokens minted by legacy {@link org.opencadc.permissions.TokenTool} that also embed {@code uri=}
 * remain valid: validation ignores {@code uri}.
 */
public final class SkahaCallbackTokenTool {

    private static final Logger log = Logger.getLogger(SkahaCallbackTokenTool.class);

    private static final String KEY_META_GRANT = "gnt";

    // Actually the Session ID. Kept as "sub" for compatibility with legacy TokenTool tokens.
    private static final String KEY_META_SUBJECT = "sub";

    private static final String TOKEN_DELIM = "~";

    private final RsaSignatureGenerator sg;
    private final RsaSignatureVerifier sv;

    public SkahaCallbackTokenTool(final byte[] publicKey, final byte[] privateKey) {
        Objects.requireNonNull(publicKey, "publicKey");
        Objects.requireNonNull(privateKey, "privateKey");
        this.sv = new RsaSignatureVerifier(publicKey);
        this.sg = new RsaSignatureGenerator(privateKey);
    }

    /** Mint a callback token for the given session id (stored as {@code sub} metadata). */
    public String generateToken(final Class<? extends Grant> grantClass, final String sessionID) {
        log.debug("[generateToken]: grant: " + grantClass);
        log.debug("[generateToken]: sessionID: " + sessionID);

        final StringBuilder metaSb = new StringBuilder();
        metaSb.append(SkahaCallbackTokenTool.KEY_META_GRANT).append("=").append(grantClass.getSimpleName());
        if (sessionID != null) {
            metaSb.append("&");
            metaSb.append(SkahaCallbackTokenTool.KEY_META_SUBJECT).append("=").append(sessionID);
        }
        final byte[] metaBytes = metaSb.toString().getBytes();

        final String sig;
        try {
            final byte[] sigBytes = this.sg.sign(new ByteArrayInputStream(metaBytes));
            sig = new String(Base64.encode(sigBytes));
            log.debug("Created signature: " + sig + " for meta: " + metaSb);
        } catch (InvalidKeyException | IOException | RuntimeException e) {
            throw new IllegalStateException("Could not sign token", e);
        }
        final String meta = new String(Base64.encode(metaBytes));
        log.debug("meta: " + meta);
        log.debug("sig: " + sig);

        final StringBuilder token = new StringBuilder();
        final String metaUrlEncoded = SkahaCallbackTokenTool.base64UrlEncode(meta);
        final String sigUrlEncoded = SkahaCallbackTokenTool.base64UrlEncode(sig);

        log.debug("metaURLEncoded: " + metaUrlEncoded);
        log.debug("sigURLEncoded: " + sigUrlEncoded);

        token.append(metaUrlEncoded);
        token.append(SkahaCallbackTokenTool.TOKEN_DELIM);
        token.append(sigUrlEncoded);
        log.debug("Created token path: " + token);

        return token.toString();
    }

    /**
     * Verify signature and grant type. If {@code uri=} is present (legacy {@link org.opencadc.permissions.TokenTool}
     * tokens), it is ignored.
     *
     * @return session id from token metadata ({@code sub})
     */
    @SafeVarargs
    public final String validateSessionCallbackToken(
            final String token, final Class<? extends Grant>... expectedGrantClass) throws IOException {

        log.debug("validating session callback token");
        final String[] parts = token.split(SkahaCallbackTokenTool.TOKEN_DELIM);
        if (parts.length != 2) {
            log.debug("invalid format, not two parts");
            throw new SessionAccessDeniedException("Invalid auth token");
        }

        final byte[] metaBytes = Base64.decode(SkahaCallbackTokenTool.base64UrlDecode(parts[0]));
        final byte[] sigBytes = Base64.decode(SkahaCallbackTokenTool.base64UrlDecode(parts[1]));

        final boolean verified;
        try {
            verified = this.sv.verify(new ByteArrayInputStream(metaBytes), sigBytes);
        } catch (InvalidKeyException | RuntimeException e) {
            log.debug("Received invalid signature", e);
            throw new SessionAccessDeniedException("Invalid auth token");
        }
        if (!verified) {
            log.debug("verified==false");
            throw new SessionAccessDeniedException("Invalid auth token");
        }

        final String[] metaParams = new String(metaBytes).split("&");
        String grant = null;
        String sessionID = null;
        for (final String metaParam : metaParams) {
            log.debug("Processing param: " + metaParam);
            final int eqIndex = metaParam.indexOf("=");
            if (eqIndex < 2) {
                log.debug("invalid param key/value pair");
                throw new SessionAccessDeniedException("Invalid auth token");
            }
            final String key = metaParam.substring(0, eqIndex);
            final String value = metaParam.substring(eqIndex + 1);
            if (SkahaCallbackTokenTool.KEY_META_GRANT.equals(key)) {
                grant = value;
            }
            if (SkahaCallbackTokenTool.KEY_META_SUBJECT.equals(key)) {
                sessionID = value;
            }
        }
        log.debug("[validateSessionCallbackToken]: grant: " + grant);
        log.debug("[validateSessionCallbackToken]: sessionID: " + sessionID);

        boolean grantMatch = false;
        for (final Class<? extends Grant> c : expectedGrantClass) {
            grantMatch = grantMatch || c.getSimpleName().equals(grant);
            log.debug("grant class expected: " + c.getSimpleName());
        }
        if (!grantMatch) {
            log.debug("[validateSessionCallbackToken]: wrong grant class: " + grant);
            throw new SessionAccessDeniedException("Invalid auth token");
        }

        return sessionID;
    }

    static String base64UrlEncode(final String s) {
        if (s == null) {
            return null;
        }
        return s.replace("/", "-").replace("+", "_");
    }

    static String base64UrlDecode(final String s) {
        if (s == null) {
            return null;
        }
        return s.replace("_", "+").replace("-", "/");
    }
}
