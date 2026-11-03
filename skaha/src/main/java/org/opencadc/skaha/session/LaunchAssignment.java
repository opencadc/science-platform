package org.opencadc.skaha.session;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Where one launch runs and under whose queue ledger. {@link LaunchRouter} produces it before any Job is built; the
 * builder and launcher read it, and nothing else chooses a queue.
 *
 * @param namespace The workload namespace for the Job and its LocalQueue.
 * @param user The exact, case-sensitive owner, a Kubernetes label value.
 * @param community The exact, case-sensitive Community, a Kubernetes label value.
 * @param clusterQueue The Community's operator-provisioned ClusterQueue.
 * @param workloadPriorityClass The Kueue WorkloadPriorityClass, or null for Kueue's default.
 */
record LaunchAssignment(
        String namespace, String user, String community, String clusterQueue, String workloadPriorityClass) {
    private static final Pattern DNS_1123_LABEL = Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?");
    private static final byte[] DIGEST_DOMAIN = "skaha-localqueue-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();

    LaunchAssignment {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(clusterQueue, "clusterQueue cannot be null");
        SessionLabels.labelValue(SessionLabels.Key.USERNAME, user);
        SessionLabels.labelValue(SessionLabels.Key.COMMUNITY, community);
    }

    /**
     * The LocalQueue name for this User and Community: {@code <user>-<community>-lq} when that is a DNS label of at
     * most 63 characters, otherwise {@code skaha-<base32(sha256)>-lq}. Frozen once queues exist.
     */
    String localQueue() {
        final String readable = readableLocalQueue();
        if (readable.length() <= 63 && DNS_1123_LABEL.matcher(readable).matches()) {
            return readable;
        }
        return "skaha-" + base32(sha256(digestInput())) + "-lq";
    }

    /** True when {@link #localQueue()} is the readable form, whose hyphens can be split more than one way. */
    boolean readableLocalQueueName() {
        return readableLocalQueue().equals(localQueue());
    }

    private String readableLocalQueue() {
        return this.user + "-" + this.community + "-lq";
    }

    private byte[] digestInput() {
        final ByteArrayOutputStream input = new ByteArrayOutputStream();
        input.writeBytes(DIGEST_DOMAIN);
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
