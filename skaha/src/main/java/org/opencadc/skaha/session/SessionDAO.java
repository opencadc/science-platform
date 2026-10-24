package org.opencadc.skaha.session;

import ca.nrc.cadc.net.ResourceNotFoundException;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobList;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.ScalableResource;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1Status;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.log4j.Logger;
import org.opencadc.skaha.K8SUtil;
import org.opencadc.skaha.SkahaAction;
import org.opencadc.skaha.SkahaKubernetesClient;
import org.opencadc.skaha.metrics.MetricsDAO;
import org.opencadc.skaha.metrics.PodResourceUsage;

public class SessionDAO {
    public static final Logger LOGGER = Logger.getLogger(SessionDAO.class);

    public static Session getSession(String forUserID, String sessionID) throws Exception {
        final List<Session> sessions = SessionDAO.getUserSessions(forUserID, sessionID, false);
        if (!sessions.isEmpty()) {
            for (Session session : sessions) {
                // exclude 'desktop-app'
                if (!SkahaAction.TYPE_DESKTOP_APP.equalsIgnoreCase(session.getType())) {
                    return session;
                }
            }
        }

        throw new ResourceNotFoundException("session " + sessionID + " not found");
    }

    static void deleteDesktopApplicationJob(final String sessionID, final String username, final String appID)
            throws Exception {
        Objects.requireNonNull(sessionID, "sessionID cannot be null");
        Objects.requireNonNull(username, "username cannot be null");
        Objects.requireNonNull(appID, "appID cannot be null");

        final ApiClient client = Configuration.getDefaultApiClient();
        final BatchV1Api api = new BatchV1Api(client);

        final V1Status status = api.deleteCollectionNamespacedJob(K8SUtil.getWorkloadNamespace())
                .labelSelector(SessionLabels.forDesktopApp(sessionID, username, appID))
                .propagationPolicy("Background")
                .execute();

        if (status == null) {
            LOGGER.debug("Deleted desktop-app job " + appID + ": NO STATUS REPORTED");
        } else if (status.getStatus() != null) {
            LOGGER.debug("Deleted desktop-app job " + appID + ": " + status);
            if (status.getStatus().equalsIgnoreCase("failure")) {
                LOGGER.warn("Delete desktop-app job " + appID + " returned non-success status: " + status.getReason());
            } else if (status.getStatus().equalsIgnoreCase("success")) {
                LOGGER.info("Delete desktop-app job " + appID + " succeeded.");
            } else {
                LOGGER.info("Delete desktop-app job " + appID + " returned unknown status: " + status.getStatus());
            }
        } else {
            LOGGER.debug("Deleted desktop-app job " + appID + ": NO INFORMATION AVAILABLE");
        }
    }

    static void deleteJob(final String sessionID, final String username) throws Exception {
        Objects.requireNonNull(sessionID, "sessionID cannot be null");
        Objects.requireNonNull(username, "username cannot be null");

        final ApiClient client = Configuration.getDefaultApiClient();
        final BatchV1Api api = new BatchV1Api(client);

        final V1Status status = api.deleteCollectionNamespacedJob(K8SUtil.getWorkloadNamespace())
                .labelSelector(SessionLabels.forSessionExceptDesktopApp(username, sessionID))
                .propagationPolicy("Background")
                .execute();

        if (status == null) {
            LOGGER.debug("Deleted job " + sessionID + ": NO STATUS REPORTED");
        } else if (status.getStatus() != null) {
            LOGGER.debug("Deleted desktop-app job " + sessionID + ": " + status);
            if (status.getStatus().equalsIgnoreCase("failure")) {
                if (Integer.valueOf(404).equals(status.getCode())) {
                    throw new ResourceNotFoundException("session " + sessionID + " not found");
                } else {
                    LOGGER.warn("Delete job " + sessionID + " returned non-success status: " + status.getReason());
                }
            } else if (status.getStatus().equalsIgnoreCase("success")) {
                LOGGER.info("Delete job " + sessionID + " succeeded.");
            } else {
                LOGGER.info("Delete job " + sessionID + " returned unknown status: " + status.getStatus());
            }
        } else {
            LOGGER.debug("Delete job " + sessionID + ": NO INFORMATION AVAILABLE");
        }
    }

    static List<Session> getUserSessions(final String forUserID, final String sessionID, final boolean omitHeadless)
            throws Exception {
        final ApiClient client = Configuration.getDefaultApiClient();

        final BatchV1Api api = new BatchV1Api(client);
        final BatchV1Api.APIlistNamespacedJobRequest jobListRequest =
                api.listNamespacedJob(K8SUtil.getWorkloadNamespace());

        final String labelSelector = SessionLabels.forUserSessions(forUserID, sessionID, omitHeadless);
        jobListRequest.labelSelector(labelSelector);

        final List<V1Job> userJobs = jobListRequest.execute().getItems();
        final PodResourceUsage podResourceUsage = loadPodResourceUsage(forUserID, omitHeadless, userJobs);
        LOGGER.debug("Found " + userJobs.size() + " jobs for user " + forUserID + " with selector " + labelSelector
                + " before filtering.");
        final List<Session> sessions = userJobs.stream()
                .map(job -> SessionBuilder.fromJob(job, podResourceUsage))
                .collect(Collectors.toList());
        LOGGER.debug("Found " + sessions.size() + " sessions for user " + forUserID + " with selector " + labelSelector
                + " after filtering.");
        return sessions;
    }

    static PodResourceUsage loadPodResourceUsage(final String forUserID, final boolean omitHeadless) {
        return loadPodResourceUsage(forUserID, omitHeadless, List.of());
    }

    static PodResourceUsage loadPodResourceUsage(
            final String forUserID, final boolean omitHeadless, final List<V1Job> userJobs) {
        return loadPodResourceUsage(MetricsDAO.getDefault(), forUserID, omitHeadless, userJobs);
    }

    static PodResourceUsage loadPodResourceUsage(
            final MetricsDAO metricsDAO,
            final String forUserID,
            final boolean omitHeadless,
            final List<V1Job> userJobs) {
        return metricsDAO.getPodResourceUsage(forUserID, omitHeadless, userJobs);
    }

    static String getConnectURL(
            final String sessionHostName,
            final SessionType type,
            final String id,
            final String absoluteHomeDirectory,
            final String userid)
            throws URISyntaxException {
        final String connectURL;

        if (SessionType.DESKTOP == type) {
            connectURL = SessionURLBuilder.vncSession(sessionHostName, id).build();
        } else if (SessionType.CARTA == type) {
            connectURL = SessionURLBuilder.cartaSession(sessionHostName, id).build();
        } else if (SessionType.NOTEBOOK == type) {
            connectURL = SessionURLBuilder.notebookSession(sessionHostName, id)
                    .withAbsoluteHomeDirectory(absoluteHomeDirectory)
                    .withUserName(userid)
                    .build();
        } else if (SessionType.CONTRIBUTED == type) {
            connectURL =
                    SessionURLBuilder.contributedSession(sessionHostName, id).build();
        } else if (SessionType.FIREFLY == type) {
            connectURL = SessionURLBuilder.fireflySession(sessionHostName, id).build();
        } else {
            connectURL = "";
        }

        return connectURL;
    }

    /** The session's desktop Job, parent of its desktop apps. */
    static Job desktopJob(final String sessionID, final String username) throws ResourceNotFoundException {
        final Job job = jobs().withName(K8SUtil.getJobName(sessionID, SessionType.DESKTOP, username))
                .get();
        if (job == null) {
            throw new ResourceNotFoundException("session " + sessionID + " not found");
        }
        return job;
    }

    static List<Job> liveJobs(final String forUserID, final String sessionID, final boolean omitHeadless) {
        return jobs().withLabelSelector(SessionLabels.forUserSessions(forUserID, sessionID, omitHeadless))
                .list()
                .getItems();
    }

    /**
     * Give each active Job of the session the configured expiry from now; a Job with that much time left is left alone.
     *
     * @throws IllegalArgumentException When the session has no active Job.
     */
    static void renew(
            final String username, final String sessionID, final Duration configuredExpiry, final Instant now) {
        final List<Job> activeJobs = liveJobs(username, sessionID, false).stream()
                .filter(job -> job.getStatus() != null
                        && Objects.equals(job.getStatus().getActive(), 1)
                        && job.getStatus().getStartTime() != null)
                .toList();
        if (activeJobs.isEmpty()) {
            throw new IllegalArgumentException("No active job for user " + username + " with session " + sessionID);
        }

        for (final Job job : activeJobs) {
            final Long deadline = job.getSpec().getActiveDeadlineSeconds();
            if (deadline == null) {
                throw new IllegalStateException("Job " + job.getMetadata().getName() + " has no activeDeadlineSeconds");
            }
            final long renewed = renewedDeadlineSeconds(
                    configuredExpiry, deadline, Instant.parse(job.getStatus().getStartTime()), now);
            if (renewed > 0) {
                jobs().withName(job.getMetadata().getName())
                        .patch(
                                PatchContext.of(PatchType.JSON_MERGE),
                                "{\"spec\":{\"activeDeadlineSeconds\":" + renewed + "}}");
            }
        }
    }

    /** The deadline that gives a Job the configured expiry from now, or 0 when it already has at least that long. */
    static long renewedDeadlineSeconds(
            final Duration configuredExpiry, final long activeDeadlineSeconds, final Instant start, final Instant now) {
        final long elapsedSeconds = Duration.between(start, now).toSeconds();
        final long remainingSeconds = activeDeadlineSeconds - elapsedSeconds;
        return configuredExpiry.toSeconds() > remainingSeconds ? elapsedSeconds + configuredExpiry.toSeconds() : 0L;
    }

    static NonNamespaceOperation<Job, JobList, ScalableResource<Job>> jobs() {
        return SkahaKubernetesClient.get().batch().v1().jobs();
    }
}
