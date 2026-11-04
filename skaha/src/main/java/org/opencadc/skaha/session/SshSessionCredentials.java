package org.opencadc.skaha.session;

import ca.nrc.cadc.util.RsaSignatureGenerator;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/** Upstream (sshpiper → session pod) RSA key material for one SSH session launch. */
final class SshSessionCredentials {
    private final String publicKeyOpenSshLine;
    private final String privateKeyPem;

    private SshSessionCredentials(final String publicKeyOpenSshLine, final String privateKeyPem) {
        this.publicKeyOpenSshLine = publicKeyOpenSshLine;
        this.privateKeyPem = privateKeyPem;
    }

    static SshSessionCredentials generate() {
        final KeyPair keyPair = RsaSignatureGenerator.getKeyPair(2048);
        final String publicKeyOpenSshLine = toOpenSshRsaLine((RSAPublicKey) keyPair.getPublic());
        final String privateKeyPem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes())
                        .encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        return new SshSessionCredentials(publicKeyOpenSshLine, privateKeyPem);
    }

    String publicKeyOpenSshLine() {
        return this.publicKeyOpenSshLine;
    }

    String privateKeyPem() {
        return this.privateKeyPem;
    }

    private static String toOpenSshRsaLine(final RSAPublicKey publicKey) {
        try {
            final ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            final DataOutputStream writer = new DataOutputStream(encoded);
            writeSshString(writer, "ssh-rsa");
            writeSshMpint(writer, publicKey.getPublicExponent());
            writeSshMpint(writer, publicKey.getModulus());
            writer.flush();
            return "ssh-rsa " + Base64.getEncoder().encodeToString(encoded.toByteArray()) + " skaha-sshpiper-upstream";
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode SSH public key", exception);
        }
    }

    private static void writeSshString(final DataOutputStream writer, final String value) throws IOException {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writer.writeInt(bytes.length);
        writer.write(bytes);
    }

    private static void writeSshMpint(final DataOutputStream writer, final java.math.BigInteger value)
            throws IOException {
        final byte[] bytes = value.toByteArray();
        writer.writeInt(bytes.length);
        writer.write(bytes);
    }
}
