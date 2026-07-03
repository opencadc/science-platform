package org.opencadc.skaha.session;

import ca.nrc.cadc.net.ResourceNotFoundException;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Status;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.log4j.Logger;
import org.opencadc.skaha.K8SUtil;
import org.opencadc.skaha.KubernetesJob;
import org.opencadc.skaha.SkahaAction;
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

    static KubernetesJob getJob(final String jobName) throws Exception {
        final ApiClient client = Configuration.getDefaultApiClient();
        final BatchV1Api api = new BatchV1Api(client);

        final V1Job job =
                api.readNamespacedJob(jobName, K8SUtil.getWorkloadNamespace()).execute();
        final V1ObjectMeta jobMetadata = Objects.requireNonNullElse(job.getMetadata(), new V1ObjectMeta());
        final Map<String, String> labels = Objects.requireNonNullElse(jobMetadata.getLabels(), new HashMap<>());

        final SessionLabels.SessionMetadata sessionMetadata = SessionLabels.fromMetadata(labels);

        return new KubernetesJob(
                jobName,
                jobMetadata.getUid(),
                sessionMetadata.id(),
                SessionType.fromApplicationStringType(sessionMetadata.kind()));
    }

    static List<Session> getUserSessions(final String forUserID, final String sessionID, final boolean omitHeadless)
            throws Exception {
        final ApiClient client = Configuration.getDefaultApiClient();

        final BatchV1Api api = new BatchV1Api(client);
        final BatchV1Api.APIlistNamespacedJobRequest jobListRequest =
                api.listNamespacedJob(K8SUtil.getWorkloadNamespace());

        final String labelSelector = SessionLabels.forUserSessions(forUserID, sessionID, omitHeadless);
        jobListRequest.labelSelector(labelSelector);

        final PodResourceUsage podResourceUsage = loadPodResourceUsage(forUserID, omitHeadless);
        final List<V1Job> userJobs = jobListRequest.execute().getItems();
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
        return loadPodResourceUsage(MetricsDAO.getDefault(), forUserID, omitHeadless);
    }

    static PodResourceUsage loadPodResourceUsage(
            final MetricsDAO metricsDAO, final String forUserID, final boolean omitHeadless) {
        return metricsDAO.getPodResourceUsage(forUserID, omitHeadless);
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
