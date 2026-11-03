package org.opencadc.skaha.session;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Where one launch runs, and which user and community own its queue.
 *
 * <p>The LocalQueue name is derived from the user and community. It is not stored, so the name cannot drift from the
 * pair.
 *
 * @param namespace workload namespace of the Job and its LocalQueue
 * @param user exact case-sensitive owner, a Kubernetes label value
 * @param community exact case-sensitive community, a Kubernetes label value
 * @param clusterQueue operator ClusterQueue for that community
 * @param workloadPriorityClass Kueue workload priority class, or null for Kueue's default
 */
record LaunchAssignment(
        String namespace, String user, String community, String clusterQueue, String workloadPriorityClass) {
    private static final Pattern DNS_1123_LABEL = Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?");
    private static final String PRIORITY_CLASS_LABEL = "kueue.x-k8s.io/priority-class";
    /** Domain separator is the trailing 0x00, before the length-prefixed pair. */
    private static final byte[] DIGEST_DOMAIN = "skaha-localqueue-v1\0".getBytes(StandardCharsets.US_ASCII);

    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();

    LaunchAssignment {
        namespace = requireText(namespace, "namespace");
        user = SessionLabels.labelValue(SessionLabels.Key.USERNAME, user);
        community = SessionLabels.labelValue(SessionLabels.Key.COMMUNITY, community);
        clusterQueue = requireText(clusterQueue, "clusterQueue");
        if (workloadPriorityClass != null) {
            workloadPriorityClass = SessionLabels.labelValue(PRIORITY_CLASS_LABEL, workloadPriorityClass);
        }
    }

    /**
     * LocalQueue name for this user and community. Readable {@code user-community-lq} when that is a DNS-1123 label of
     * at most 63 characters. Otherwise the 61-character digest name.
     */
    String localQueue() {
        final String readable = readableLocalQueue();
        if (readable.length() <= 63 && DNS_1123_LABEL.matcher(readable).matches()) {
            return readable;
        }
        return "skaha-" + base32(sha256(digestInput())) + "-lq";
    }

    private String readableLocalQueue() {
        return this.user + "-" + this.community + "-lq";
    }

    private static String requireText(final String value, final String field) {
        Objects.requireNonNull(value, field + " cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return value;
    }

    private byte[] digestInput() {
        final ByteArrayOutputStream input = new ByteArrayOutputStream();
        input.writeBytes(DIGEST_DOMAIN);
        // Length prefixes stop "ab" + "c" and "a" + "bc" from sharing a digest.
        for (final String part : new String[] {this.user, this.community}) {
            final byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            input.writeBytes(
                    ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            input.writeBytes(bytes);
        }
        return input.toByteArray();
    }

    private static byte[] sha256(final byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException noSha256) {
            throw new IllegalStateException(noSha256);
        }
    }

    /** RFC 4648 base32, lowercase, unpadded. */
    private static String base32(final byte[] bytes) {
        final StringBuilder encoded = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (final byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                encoded.append(BASE32[(buffer >> (bits - 5)) & 0x1f]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            encoded.append(BASE32[(buffer << (5 - bits)) & 0x1f]);
        }
        return encoded.toString();
    }
}
