package org.opencadc.skaha.session;

import ca.nrc.cadc.util.StringUtil;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;

/**
 * What a batch runs: how many indexed replicas, and the failed fraction that stops the run. Skaha writes the Indexed
 * Job fields from it at create, and reads it back from the Job for the Batch document.
 *
 * @param replicas The number of indexes, from 1 to {@link #MAX_REPLICAS}.
 * @param maxfail The failed fraction of replicas, from 0 to 1, that stops the Job; null when the request omitted it.
 */
record BatchSpec(int replicas, BigDecimal maxfail) {
    static final int MAX_REPLICAS = 256;
    static final String MAXFAIL_ANNOTATION = "canfar.net/maxfail";
    static final String MIN_PARALLELISM_ANNOTATION = "kueue.x-k8s.io/job-min-parallelism";

    private static final String REPLICAS_RANGE = "replicas must be an integer from 1 to " + MAX_REPLICAS + ".";
    private static final String MAXFAIL_RANGE = "maxfail must be a decimal from 0 to 1.";
    // A Python float has at most 17 decimals; a huge scale (1e-30000000) makes maxFailedIndexes' rounding take seconds.
    private static final int MAX_MAXFAIL_SCALE = 32;

    BatchSpec {
        if (replicas < 1 || replicas > MAX_REPLICAS) {
            throw new IllegalArgumentException(REPLICAS_RANGE);
        }
        if (maxfail != null
                && (maxfail.scale() > MAX_MAXFAIL_SCALE
                        || maxfail.signum() < 0
                        || maxfail.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException(MAXFAIL_RANGE);
        }
    }

    /**
     * Parse the create form.
     *
     * @return The batch to run, or null for a type other than batch.
     * @throws IllegalArgumentException When {@code replicas} is not an integer from 1 to 256, {@code maxfail} is not a
     *     decimal from 0 to 1, or a type other than batch sends {@code maxfail} or more than one replica.
     */
    static BatchSpec fromRequest(final SessionType type, final String replicas, final String maxfail) {
        final BatchSpec spec = new BatchSpec(parseReplicas(replicas), parseMaxfail(maxfail));
        if (type == SessionType.BATCH) {
            return spec;
        }
        if (spec.maxfail() != null) {
            throw new IllegalArgumentException("maxfail is valid only for a batch.");
        }
        if (spec.replicas() > 1) {
            throw new IllegalArgumentException("replicas above 1 is valid only for a batch.");
        }
        return null;
    }

    /** The request a batch Job was created from. */
    static BatchSpec fromJob(final Job job) {
        final String maxfail = Objects.requireNonNullElse(job.getMetadata().getAnnotations(), Map.<String, String>of())
                .get(MAXFAIL_ANNOTATION);
        return new BatchSpec(
                Objects.requireNonNull(job.getSpec().getCompletions(), "batch Job has no completions"),
                maxfail == null ? null : new BigDecimal(maxfail));
    }

    /**
     * The index a log request's {@code replica} parameter names.
     *
     * @throws IllegalArgumentException When {@code replica} is missing, not an integer, or not an index of this batch.
     */
    int replicaIndex(final String replica) {
        final String range = "replica must be an integer from 0 to " + (this.replicas - 1) + ".";
        final int index;
        try {
            index = Integer.parseInt(Objects.requireNonNullElse(replica, "").trim());
        } catch (NumberFormatException notAnInteger) {
            throw new IllegalArgumentException(range, notAnInteger);
        }
        if (index < 0 || index >= this.replicas) {
            throw new IllegalArgumentException(range);
        }
        return index;
    }

    /**
     * The Job's {@code maxFailedIndexes}: the K-th failed index, K = max(1, ceil(maxfail * replicas)), stops the Job.
     *
     * @return K - 1, or null when every index may fail (maxfail absent or 1).
     */
    Integer maxFailedIndexes() {
        if (this.maxfail == null || this.maxfail.compareTo(BigDecimal.ONE) == 0) {
            return null;
        }
        final int stopAt = this.maxfail
                .multiply(BigDecimal.valueOf(this.replicas))
                .setScale(0, RoundingMode.CEILING)
                .intValueExact();
        return Math.max(1, stopAt) - 1;
    }

    private static int parseReplicas(final String replicas) {
        if (!StringUtil.hasText(replicas)) {
            return 1;
        }
        try {
            return Integer.parseInt(replicas.trim());
        } catch (NumberFormatException notAnInteger) {
            throw new IllegalArgumentException(REPLICAS_RANGE, notAnInteger);
        }
    }

    private static BigDecimal parseMaxfail(final String maxfail) {
        if (!StringUtil.hasText(maxfail)) {
            return null;
        }
        try {
            return new BigDecimal(maxfail.trim());
        } catch (NumberFormatException notADecimal) {
            throw new IllegalArgumentException(MAXFAIL_RANGE, notADecimal);
        }
    }
}
