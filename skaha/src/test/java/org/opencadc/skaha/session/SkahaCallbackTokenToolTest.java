package org.opencadc.skaha.session;

import static org.opencadc.skaha.KubernetesApi.NAMESPACE;

import ca.nrc.cadc.util.RsaSignatureGenerator;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import java.util.Map;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.opencadc.permissions.TokenTool;
import org.opencadc.permissions.WriteGrant;
import org.opencadc.skaha.KubernetesApi;
import org.opencadc.skaha.session.authorization.SessionAccessDeniedException;
import org.opencadc.skaha.utils.CommonUtils;

public class SkahaCallbackTokenToolTest {
    private static final String SECRET_PATH = "/api/v1/namespaces/" + NAMESPACE + "/secrets/pre-auth-token-skaha";
    private static final String SECRETS_PATH = "/api/v1/namespaces/" + NAMESPACE + "/secrets";

    @Rule
    public final KubernetesApi api = new KubernetesApi();

    @Before
    @After
    public void forget() {
        SkahaCallbackTokenTool.forget();
    }

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

    @Test
    public void fromSecret_kubectlEraSecret_reusedAndCached() throws Exception {
        final KeyPair keyPair = SkahaCallbackTokenToolTest.fixedTestKeyPair();
        // A token signed with the persisted keys validates with the keys read back.
        final String oldToken = new SkahaCallbackTokenTool(
                        keyPair.getPublic().getEncoded(), keyPair.getPrivate().getEncoded())
                .generateToken(WriteGrant.class, "session-1");
        this.api
                .server()
                .expect()
                .get()
                .withPath(SECRET_PATH)
                .andReturn(200, kubectlEraSecret(keyPair))
                .once();

        final SkahaCallbackTokenTool tool = SkahaCallbackTokenTool.fromSecret();

        Assert.assertSame("Read once, then cached", tool, SkahaCallbackTokenTool.fromSecret());
        Assert.assertEquals("session-1", tool.validateSessionCallbackToken(oldToken, WriteGrant.class));
    }

    @Test
    public void fromSecret_absent_created() throws Exception {
        final SkahaCallbackTokenTool tool = SkahaCallbackTokenTool.fromSecret();

        // The stored keys are the ones in use: a token signed now verifies with a tool read back from the Secret.
        final SkahaCallbackTokenTool stored = SkahaCallbackTokenTool.fromSecret(
                this.api.client().secrets().withName("pre-auth-token-skaha").get());
        Assert.assertEquals(
                "session-2",
                stored.validateSessionCallbackToken(
                        tool.generateToken(WriteGrant.class, "session-2"), WriteGrant.class));
    }

    @Test
    public void fromSecret_concurrentCreate_usesWinner() throws Exception {
        final KeyPair winner = SkahaCallbackTokenToolTest.fixedTestKeyPair();
        this.api
                .server()
                .expect()
                .get()
                .withPath(SECRET_PATH)
                .andReturn(404, "")
                .once();
        this.api
                .server()
                .expect()
                .post()
                .withPath(SECRETS_PATH)
                .andReturn(409, "")
                .once();
        this.api
                .server()
                .expect()
                .get()
                .withPath(SECRET_PATH)
                .andReturn(200, kubectlEraSecret(winner))
                .once();

        final String token = new SkahaCallbackTokenTool(
                        winner.getPublic().getEncoded(), winner.getPrivate().getEncoded())
                .generateToken(WriteGrant.class, "session-3");
        Assert.assertEquals(
                "session-3", SkahaCallbackTokenTool.fromSecret().validateSessionCallbackToken(token, WriteGrant.class));
    }

    @Test
    public void fromSecret_failures() {
        this.api
                .server()
                .expect()
                .get()
                .withPath(SECRET_PATH)
                .andReturn(500, "")
                .once();
        Assert.assertThrows(KubernetesClientException.class, SkahaCallbackTokenTool::fromSecret);

        Assert.assertThrows(
                "Missing key fails closed",
                IllegalStateException.class,
                () -> SkahaCallbackTokenTool.fromSecret(
                        new SecretBuilder().withData(Map.of("public", "Zm9v")).build()));
        Assert.assertThrows(
                "Malformed key fails closed",
                IllegalStateException.class,
                () -> SkahaCallbackTokenTool.fromSecret(new SecretBuilder()
                        .withData(Map.of("public", "%%%", "private", "%%%"))
                        .build()));
    }

    /** The Secret as {@code kubectl create secret generic --from-literal=public=<base64 key>} wrote it. */
    private static Secret kubectlEraSecret(final KeyPair keyPair) {
        return new SecretBuilder()
                .withNewMetadata()
                .withName("pre-auth-token-skaha")
                .endMetadata()
                .withData(Map.of(
                        "public",
                                apiBase64(CommonUtils.encodeBase64(
                                        keyPair.getPublic().getEncoded())),
                        "private",
                                apiBase64(CommonUtils.encodeBase64(
                                        keyPair.getPrivate().getEncoded()))))
                .build();
    }

    private static String apiBase64(final String literal) {
        return Base64.getEncoder().encodeToString(literal.getBytes(StandardCharsets.UTF_8));
    }
}
