package org.opencadc.skaha.session;

import ca.nrc.cadc.util.RsaSignatureGenerator;
import java.net.URI;
import java.security.KeyPair;
import org.junit.Assert;
import org.junit.Test;
import org.opencadc.permissions.TokenTool;
import org.opencadc.permissions.WriteGrant;
import org.opencadc.skaha.session.authorization.SessionAccessDeniedException;

public class SkahaCallbackTokenToolTest {

    private static KeyPair fixedTestKeyPair() {
        return RsaSignatureGenerator.getKeyPair(2048);
    }

    @Test
    public void roundTrip_generateSessionCallbackToken_validates() throws Exception {
        final KeyPair kp = SkahaCallbackTokenToolTest.fixedTestKeyPair();
        final byte[] pub = kp.getPublic().getEncoded();
        final byte[] priv = kp.getPrivate().getEncoded();

        final SkahaCallbackTokenTool tool = new SkahaCallbackTokenTool(pub, priv);
        final String tok = tool.generateToken(WriteGrant.class, "session-abc");

        Assert.assertEquals("session-abc", tool.validateSessionCallbackToken(tok, WriteGrant.class));
    }

    /** Tokens minted by legacy {@link TokenTool} with any artifact URI must validate (embedded uri not matched). */
    @Test
    public void legacyTokenTool_arbitraryArtifactUri_validates() throws Exception {
        final KeyPair kp = SkahaCallbackTokenToolTest.fixedTestKeyPair();
        final byte[] pub = kp.getPublic().getEncoded();
        final byte[] priv = kp.getPrivate().getEncoded();

        final TokenTool legacy = new TokenTool(pub, priv);
        final String legacyToken =
                legacy.generateToken(URI.create("ivo://old-deployment/skaha-users"), WriteGrant.class, "sess-legacy");

        final SkahaCallbackTokenTool skahaTool = new SkahaCallbackTokenTool(pub, priv);
        Assert.assertEquals("sess-legacy", skahaTool.validateSessionCallbackToken(legacyToken, WriteGrant.class));
    }

    @Test(expected = SessionAccessDeniedException.class)
    public void validateSessionCallbackToken_wrongGrant_rejects() throws Exception {
        final KeyPair kp = SkahaCallbackTokenToolTest.fixedTestKeyPair();
        final SkahaCallbackTokenTool tool = new SkahaCallbackTokenTool(
                kp.getPublic().getEncoded(), kp.getPrivate().getEncoded());
        final String tok = tool.generateToken(WriteGrant.class, "x");
        tool.validateSessionCallbackToken(tok, org.opencadc.permissions.ReadGrant.class);
    }
}
