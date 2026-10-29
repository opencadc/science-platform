package org.opencadc.skaha.session;

import ca.nrc.cadc.net.TransientException;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.apache.log4j.Logger;

/**
 * Creates a session Job, then the resources it owns. Either everything exists afterwards, or the Job is deleted and the
 * launch fails: a session ID is never returned for a launch that cannot run.
 *
 * <p>When a create's outcome is uncertain (no response, 429, 5xx) the Job is read back and, if absent, created once
 * more. That is safe because the Job name holds the request's random session ID: a 409 can only be this launch's own
 * earlier attempt, confirmed by its {@code canfar.net/id} and {@code canfar.net/app-id} labels.
 */
class SessionLauncher {
    private static final Logger LOGGER = Logger.getLogger(SessionLauncher.class);
    private static final String LAUNCH_FAILED = "Unable to launch session.  Please try again.";

    static final int MAX_CREATE_ATTEMPTS = 2;

    private final KubernetesClient client;

    SessionLauncher(final KubernetesClient client) {
        this.client = client;
    }

    /**
     * Create the Job, then each owned resource.
     *
     * @param ownedResourceYamls Rendered Service and ingress documents; {@code ${skaha.jobuid}} and
     *     {@code ${skaha.jobname}} are filled from the created Job.
     * @return The created Job.
     * @throws TransientException If the outcome was uncertain or an owned resource failed; nothing is left behind.
     * @throws IllegalStateException If the cluster rejected the Job; retrying will not help.
     */
    Job launch(final Job job, final List<String> ownedResourceYamls) throws TransientException {
        final Job created = createJob(job);
        try {
            for (final String ownedResourceYaml : ownedResourceYamls) {
                String yaml = SessionJobBuilder.setConfigValue(
                        ownedResourceYaml,
                        PostAction.SKAHA_JOBUID,
                        created.getMetadata().getUid());
                yaml = SessionJobBuilder.setConfigValue(
                        yaml, PostAction.SKAHA_JOBNAME, created.getMetadata().getName());
                for (final HasMetadata resource : this.client
                        .load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)))
                        .items()) {
                    this.client.resource(resource).create();
                }
            }
        } catch (RuntimeException exception) {
            // KubernetesClientException, or a template that does not parse.
            LOGGER.error(
                    "Owned resource create failed for Job "
                            + created.getMetadata().getName() + "; deleting it.",
                    exception);
            deleteJob(created.getMetadata().getName());
            throw new TransientException(LAUNCH_FAILED);
        }
        return created;
    }

    private Job createJob(final Job job) throws TransientException {
        final String jobName = job.getMetadata().getName();
        for (int attempt = 1; ; attempt++) {
            try {
                return this.client.batch().v1().jobs().resource(job).create();
            } catch (KubernetesClientException exception) {
                final boolean conflict = exception.getCode() == 409;
                if (!conflict && !isUncertain(exception)) {
                    LOGGER.error("Job " + jobName + " create rejected.", exception);
                    throw new IllegalStateException("Session launch was rejected by the cluster.");
                }
                LOGGER.warn("Job " + jobName + " create returned " + exception.getCode() + "; reading it back.");
                final Job existing = readOwnJob(job);
                if (existing != null) {
                    return existing;
                } else if (attempt >= MAX_CREATE_ATTEMPTS) {
                    deleteJob(jobName);
                    throw new TransientException(LAUNCH_FAILED);
                }
            }
        }
    }

    /** The named Job if it is this launch's, null if absent; fails if it belongs to another session or app. */
    private Job readOwnJob(final Job job) throws TransientException {
        final String jobName = job.getMetadata().getName();
        final Job existing;
        try {
            existing = this.client.batch().v1().jobs().withName(jobName).get();
        } catch (KubernetesClientException exception) {
            LOGGER.error("Unable to read Job " + jobName + ".", exception);
            deleteJob(jobName);
            throw new TransientException(LAUNCH_FAILED);
        }
        if (existing != null
                && !(Objects.equals(
                                SessionLabels.get(job, SessionLabels.Key.ID),
                                SessionLabels.get(existing, SessionLabels.Key.ID))
                        && Objects.equals(
                                SessionLabels.get(job, SessionLabels.Key.APP_ID),
                                SessionLabels.get(existing, SessionLabels.Key.APP_ID)))) {
            LOGGER.error("Job " + jobName + " belongs to another session or desktop app.");
            throw new TransientException(LAUNCH_FAILED);
        }
        return existing;
    }

    private void deleteJob(final String jobName) {
        try {
            this.client
                    .batch()
                    .v1()
                    .jobs()
                    .withName(jobName)
                    .withPropagationPolicy(DeletionPropagation.BACKGROUND)
                    .delete();
        } catch (KubernetesClientException exception) {
            LOGGER.error("Unable to delete Job " + jobName + " after a failed launch.", exception);
        }
    }

    /** No HTTP response (Fabric8 reports transport failures as -1), throttled, or a server error. */
    static boolean isUncertain(final KubernetesClientException exception) {
        final int code = exception.getCode();
        return code <= 0 || code == 429 || code >= 500;
    }
}
