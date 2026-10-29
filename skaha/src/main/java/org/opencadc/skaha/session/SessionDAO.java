package org.opencadc.skaha.session;

import ca.nrc.cadc.net.ResourceNotFoundException;
import ca.nrc.cadc.util.StringUtil;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.StatusDetails;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobList;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.ScalableResource;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
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

    // Serves user session listings once synced; see getUserSessions.
    private static volatile SessionJobCache cache;

    /** Serve user session listings from the given cache. Pass null to read live again. */
    static void useCache(final SessionJobCache sessionJobCache) {
        SessionDAO.cache = sessionJobCache;
    }

    /** Make a Job this replica just created visible to listings before the watch delivers it. */
    static void created(final Job job) {
        final SessionJobCache current = SessionDAO.cache;
        if (current != null) {
            current.created(job);
        }
    }

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

    static void deleteDesktopApplicationJob(final String sessionID, final String username, final String appID) {
        deleteJobs("desktop-app job " + appID, SessionLabels.forDesktopApp(sessionID, username, appID));
    }

    static void deleteJob(final String sessionID, final String username) {
        deleteJobs("job " + sessionID, SessionLabels.forSessionExceptDesktopApp(username, sessionID));
    }

    /** Delete the selected Jobs; their Pods, Services and ingress go with them (background propagation). */
    private static void deleteJobs(final String description, final String labelSelector) {
        final List<StatusDetails> deleted = jobs().withLabelSelector(labelSelector)
                .withPropagationPolicy(DeletionPropagation.BACKGROUND)
                .delete();
        LOGGER.info("Delete " + description + ": " + deleted.size() + " Job(s) deleted.");
    }

    /**
     * Sessions matching the filters. A user's full listing (no session ID) comes from the per-replica cache while it is
     * current; a lookup by session ID, or any read while the cache is not current, is a live read.
     */
    static List<Session> getUserSessions(final String forUserID, final String sessionID, final boolean omitHeadless) {
        final SessionJobCache current = SessionDAO.cache;
        final List<Job> userJobs = servedFromCache(current, forUserID, sessionID)
                ? current.userJobs(forUserID, omitHeadless)
                : liveJobs(forUserID, sessionID, omitHeadless);
        return toSessions(forUserID, omitHeadless, userJobs);
    }

    static boolean servedFromCache(final SessionJobCache cache, final String forUserID, final String sessionID) {
        if (!StringUtil.hasText(forUserID) || StringUtil.hasText(sessionID) || cache == null) {
            return false;
        }
        if (!cache.isCurrent()) {
            LOGGER.warn("Session Job cache is not current (not synced or watch down); reading sessions live.");
            return false;
        }
        return true;
    }

    /** Sessions matching the filters, always read live, e.g. for the session limit. */
    static List<Session> getUserSessionsLive(
            final String forUserID, final String sessionID, final boolean omitHeadless) {
        return toSessions(forUserID, omitHeadless, liveJobs(forUserID, sessionID, omitHeadless));
    }

    static List<Job> liveJobs(final String forUserID, final String sessionID, final boolean omitHeadless) {
        return jobs().withLabelSelector(SessionLabels.forUserSessions(forUserID, sessionID, omitHeadless))
                .list()
                .getItems();
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

    private static List<Session> toSessions(
            final String forUserID, final boolean omitHeadless, final List<Job> userJobs) {
        final PodResourceUsage podResourceUsage = loadPodResourceUsage(forUserID, omitHeadless, userJobs);
        return userJobs.stream()
                .map(job -> SessionBuilder.fromJob(job, podResourceUsage))
                .collect(Collectors.toList());
    }

    static NonNamespaceOperation<Job, JobList, ScalableResource<Job>> jobs() {
        return SkahaKubernetesClient.get().batch().v1().jobs();
    }

    static PodResourceUsage loadPodResourceUsage(
            final String forUserID, final boolean omitHeadless, final List<Job> userJobs) {
        return loadPodResourceUsage(MetricsDAO.getDefault(), forUserID, omitHeadless, userJobs);
    }

    static PodResourceUsage loadPodResourceUsage(
            final MetricsDAO metricsDAO, final String forUserID, final boolean omitHeadless, final List<Job> userJobs) {
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
}
