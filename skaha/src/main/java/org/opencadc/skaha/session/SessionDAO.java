package org.opencadc.skaha.session;

import ca.nrc.cadc.net.ResourceNotFoundException;
import ca.nrc.cadc.util.StringUtil;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobCondition;
import io.kubernetes.client.openapi.models.V1JobSpec;
import io.kubernetes.client.openapi.models.V1JobStatus;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PodSecurityContext;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1Status;
import io.kubernetes.client.util.Config;
import java.io.IOException;
import java.net.URISyntaxException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.log4j.Logger;
import org.opencadc.skaha.K8SUtil;
import org.opencadc.skaha.KubernetesJob;
import org.opencadc.skaha.SkahaAction;
import org.opencadc.skaha.utils.CommonUtils;

public class SessionDAO {
    public static final Logger LOGGER = Logger.getLogger(SessionDAO.class);

    private static final String SESSION_ID_LABEL = "canfar-net-sessionID";
    private static final String DESKTOP_APP_ID_LABEL = "canfar-net-appID";
    private static final String USER_ID_LABEL = "canfar-net-userid";
    private static final String SESSION_TYPE_LABEL = "canfar-net-sessionType";

    static final String NONE = "<none>";

    static {
        try {
            final ApiClient client = Config.fromCluster();
            Configuration.setDefaultApiClient(client);
        } catch (IOException e) {
            LOGGER.error("Failed to configure k8s client from cluster: " + e.getMessage(), e);
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

    static void deleteDesktopApplicationJob(final String sessionID, final String username, final String appID)
            throws Exception {
        Objects.requireNonNull(sessionID, "sessionID cannot be null");
        Objects.requireNonNull(username, "username cannot be null");
        Objects.requireNonNull(appID, "appID cannot be null");

        final ApiClient client = Configuration.getDefaultApiClient();
        final BatchV1Api api = new BatchV1Api(client);
        final List<String> labelsSelector = new ArrayList<>();

        labelsSelector.add(String.format("%s=%s", SESSION_ID_LABEL, sessionID));
        labelsSelector.add(String.format("%s=%s", USER_ID_LABEL, username));
        labelsSelector.add(String.format("%s=%s", DESKTOP_APP_ID_LABEL, appID));
        labelsSelector.add(String.format("%s=%s", SESSION_TYPE_LABEL, SessionType.DESKTOP_APP.applicationName));

        final V1Status status = api.deleteCollectionNamespacedJob(K8SUtil.getWorkloadNamespace())
                .labelSelector(String.join(",", labelsSelector))
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
        final List<String> labelsSelector = new ArrayList<>();

        labelsSelector.add(String.format("%s=%s", SESSION_ID_LABEL, sessionID));
        labelsSelector.add(String.format("%s=%s", USER_ID_LABEL, username));
        labelsSelector.add(String.format("%s!=%s", SESSION_TYPE_LABEL, SessionType.DESKTOP_APP.applicationName));

        final V1Status status = api.deleteCollectionNamespacedJob(K8SUtil.getWorkloadNamespace())
                .labelSelector(String.join(",", labelsSelector))
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

        return new KubernetesJob(
                jobName,
                jobMetadata.getUid(),
                labels.get(SessionDAO.SESSION_ID_LABEL),
                SessionType.fromApplicationStringType(Objects.requireNonNullElse(
                        labels.get(SessionDAO.SESSION_TYPE_LABEL), SessionType.HEADLESS.applicationName)));
    }

    static List<Session> getUserSessions(final String forUserID, final String sessionID, final boolean omitHeadless)
            throws Exception {
        final ApiClient client = Configuration.getDefaultApiClient();

        final List<String> labelSelectors = new ArrayList<>();
        if (omitHeadless) {
            labelSelectors.add(
                    String.format("%s!=%s", SessionDAO.SESSION_TYPE_LABEL, SessionAction.SESSION_TYPE_HEADLESS));
        }

        if (StringUtil.hasLength(sessionID)) {
            labelSelectors.add(String.format("%s=%s", SessionDAO.SESSION_ID_LABEL, sessionID));
        }

        final BatchV1Api api = new BatchV1Api(client);
        final BatchV1Api.APIlistNamespacedJobRequest jobListRequest =
                api.listNamespacedJob(K8SUtil.getWorkloadNamespace()).allowWatchBookmarks(Boolean.TRUE);

        if (StringUtil.hasLength(forUserID)) {
            labelSelectors.add("canfar-net-userid=" + forUserID);
        }

        final String labelSelector = String.join(",", labelSelectors);
        jobListRequest.labelSelector(labelSelector);

        final PodResourceUsage podResourceUsage = PodResourceUsage.get(forUserID, omitHeadless);
        final List<V1Job> userJobs = jobListRequest.execute().getItems();
        LOGGER.debug("Found " + userJobs.size() + " jobs for user " + forUserID + " with selector " + labelSelector
                + " before filtering.");
        final List<Session> sessions = userJobs.stream()
                .filter(job -> job.getStatus() != null
                        && 0 < Objects.requireNonNullElse(job.getStatus().getActive(), 0))
                .map(job -> SessionBuilder.fromJob(job, podResourceUsage))
                .collect(Collectors.toList());
        LOGGER.debug("Found " + sessions.size() + " sessions for user " + forUserID + " with selector " + labelSelector
                + " after filtering.");
        return sessions;
    }

    static String getConnectURL(
            final String sessionHostName,
            final SessionType type,
            final String id,
            final String image,
            final String topLevelDirectory,
            final String userid)
            throws URISyntaxException {
        final String connectURL;

        if (SessionType.DESKTOP == type) {
            connectURL = SessionURLBuilder.vncSession(sessionHostName, id).build();
        } else if (SessionType.CARTA == type) {
            final String imageVersion = image.substring(image.lastIndexOf(":") + 1);
            final Integer majorVersion = K8SUtil.getMajorImageVersion(image);

            connectURL = SessionURLBuilder.cartaSession(sessionHostName, id)
                    .withAlternateSocket(imageVersion.equalsIgnoreCase("1.4"))
                    .withVersion5Path(majorVersion != null && majorVersion >= 5)
                    .build();
        } else if (SessionType.NOTEBOOK == type) {
            connectURL = SessionURLBuilder.notebookSession(sessionHostName, id)
                    .withTopLevelDirectory(topLevelDirectory)
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

    enum CustomColumns {
        SESSION_ID(".metadata.labels", "canfar-net-sessionID", false),
        USERID(".metadata.labels", "canfar-net-userid", false),
        RUN_AS_UID(".spec.securityContext", "runAsUser", false),
        RUN_AS_GID(".spec.securityContext", "runAsGroup", false),
        SUPPLEMENTAL_GROUPS(".spec.securityContext", "supplementalGroups", false),
        IMAGE(".spec.containers[0]", "image", false),
        TYPE(".metadata.labels", "canfar-net-sessionType", false),
        STATUS(".status", "phase", false),
        NAME(".metadata.labels", "canfar-net-sessionName", false),
        STARTED(".status", "startTime", false),
        DELETION(".metadata", "deletionTimestamp", false),
        APP_ID(".metadata.labels", "canfar-net-appID", false),
        REQUESTED_RAM(".spec.containers[0].resources.requests", "memory", true),
        REQUESTED_CPU(".spec.containers[0].resources.requests", "cpu", true),
        REQUESTED_GPU(".spec.containers[0].resources.requests", "nvidia\\.com/gpu", true),
        FULL_NAME(".metadata", "name", true),
        UID(".metadata.ownerReferences[]", "uid", true);

        final String selectorPrefix;
        final String simpleName;
        final boolean forUserOnly;

        CustomColumns(String selectorPrefix, String simpleName, boolean forUserOnly) {
            this.selectorPrefix = selectorPrefix;
            this.simpleName = simpleName;
            this.forUserOnly = forUserOnly;
        }
    }

    private static class SessionBuilder {
        private final String id;
        private final String userID;
        private final String type;
        private String name;
        private String appID;
        private Long runAsUID;
        private Long runAsGID;
        private final List<Integer> supplementalGroups = new ArrayList<>();
        private String image;
        private String requestedMemory;
        private String requestedCPUCores;
        private String requestedGPUCores;
        private String memoryInUse;
        private String cpuCoresInUse;
        private String status;
        private String startTime;
        private String expiryTime;
        private Long activeExpirySeconds;
        private String connectURL;
        private String jobName; // used to match up resource usages (metrics)
        private boolean isFixedResources;

        private SessionBuilder(final String id, final String userID, final String type) {
            Objects.requireNonNull(id, "Session ID cannot be null");
            Objects.requireNonNull(userID, "Session UserID cannot be null");
            Objects.requireNonNull(type, "Session Type cannot be null");

            this.id = id;
            this.userID = userID;
            this.type = type;
        }

        static Session fromJob(final V1Job job, final PodResourceUsage podResourceUsage) {
            final V1ObjectMeta jobMetadata = job.getMetadata();
            Objects.requireNonNull(jobMetadata, "Invalid Job with null Metadata");

            final V1JobSpec jobSpec = job.getSpec();
            Objects.requireNonNull(jobSpec, "jobSpec cannot be null");

            final Map<String, String> labels = jobMetadata.getLabels();
            Objects.requireNonNull(labels, "Invalid Job with null Labels");

            final String sessionID = labels.get(CustomColumns.SESSION_ID.simpleName);
            final SessionBuilder sessionBuilder = new SessionBuilder(
                    sessionID, labels.get(CustomColumns.USERID.simpleName), labels.get(CustomColumns.TYPE.simpleName));

            sessionBuilder.appID = labels.get(CustomColumns.APP_ID.simpleName);
            sessionBuilder.name = labels.get(CustomColumns.NAME.simpleName);
            final String flexLabelValue = labels.get(SessionJobBuilder.JOB_RESOURCE_FLEXIBLE_LABEL_KEY);

            // Absence of flex is interpreted as fixed resources.  This allows the existing sessions to be treated as
            // fixed.
            sessionBuilder.isFixedResources =
                    !StringUtil.hasText(flexLabelValue) || !Boolean.parseBoolean(flexLabelValue);
            sessionBuilder.jobName = jobMetadata.getName();

            return sessionBuilder
                    .withJobSpec(jobSpec, podResourceUsage)
                    .withStatus(job.getStatus())
                    .build();
        }

        SessionBuilder withJobSpec(final V1JobSpec jobSpec, final PodResourceUsage podResourceUsage) {
            Objects.requireNonNull(jobSpec, "Invalid JobSpec");

            final Long secondsUntilExpire = jobSpec.getActiveDeadlineSeconds();
            if (this.startTime != null) {
                if (secondsUntilExpire == null) {
                    LOGGER.warn("No expiry set for " + this.id);
                } else {
                    this.expiryTime = CommonUtils.getExpiryTimeString(this.startTime, secondsUntilExpire);
                }
            } else {
                this.activeExpirySeconds = secondsUntilExpire;
            }

            final V1PodSpec podSpec = jobSpec.getTemplate().getSpec();

            if (podSpec != null) {
                final V1PodSecurityContext podSecurityContext = podSpec.getSecurityContext();
                if (podSecurityContext == null) {
                    LOGGER.warn("No Pod Security Context found.");
                } else {
                    this.runAsUID = podSecurityContext.getRunAsUser();
                    this.runAsGID = podSecurityContext.getRunAsGroup();
                    final List<Long> supplementalGroupGIDs = podSecurityContext.getSupplementalGroups();
                    if (supplementalGroupGIDs != null && !supplementalGroupGIDs.isEmpty()) {
                        this.supplementalGroups.addAll(supplementalGroupGIDs.stream()
                                .map(Long::intValue)
                                .collect(Collectors.toList()));
                    }
                }

                // The Pod Name starts with the Job Name.  Is this reliable?
                this.memoryInUse = podResourceUsage.memory.entrySet().stream()
                        .filter(entry -> entry.getKey().startsWith(this.jobName))
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .orElse(null);
                this.cpuCoresInUse = podResourceUsage.cpu.entrySet().stream()
                        .filter(entry -> entry.getKey().startsWith(this.jobName))
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .orElse(null);

                final List<V1Container> podContainers = podSpec.getContainers();
                if (podContainers.isEmpty()) {
                    LOGGER.warn("No Container found.");
                } else {
                    final V1Container podContainer = podContainers.get(0);
                    this.image = podContainer.getImage();

                    final V1ResourceRequirements resourceRequirements =
                            Objects.requireNonNullElse(podContainer.getResources(), new V1ResourceRequirements());

                    final Map<String, Quantity> resourceRequests =
                            Objects.requireNonNullElse(resourceRequirements.getRequests(), Collections.emptyMap());
                    if (this.isFixedResources) {
                        if (resourceRequests.containsKey("memory")) {
                            this.requestedMemory = PodResourceUsage.toCommonMemoryUnit(
                                    resourceRequests.get("memory").toSuffixedString());
                        }

                        if (resourceRequests.containsKey("cpu")) {
                            this.requestedCPUCores = PodResourceUsage.toCoreUnit(
                                    resourceRequests.get("cpu").toSuffixedString());
                        }
                    }

                    final Map<String, Quantity> resourceLimits =
                            Objects.requireNonNullElse(resourceRequirements.getLimits(), Collections.emptyMap());
                    if (resourceLimits.containsKey("nvidia.com/gpu")) {
                        this.requestedGPUCores =
                                resourceLimits.get("nvidia.com/gpu").toSuffixedString();
                    } else {
                        // Set to zero to satisfy UI conditions.
                        this.requestedGPUCores = "0";
                    }
                }

                try {
                    this.connectURL = SessionDAO.getConnectURL(
                            K8SUtil.getSessionsHostName(),
                            SessionType.fromApplicationStringType(this.type),
                            this.id,
                            this.image,
                            K8SUtil.getSkahaTld(),
                            this.userID);
                } catch (URISyntaxException e) {
                    LOGGER.warn("Invalid URI for connect URL: " + this);
                    throw new IllegalStateException(e.getMessage(), e);
                }
            }

            return this;
        }

        SessionBuilder withStatus(final V1JobStatus jobStatus) {
            Objects.requireNonNull(jobStatus, "Invalid JobStatus");

            if (jobStatus.getStartTime() != null) {
                this.startTime = jobStatus.getStartTime().toString();
            }

            if (this.activeExpirySeconds != null && this.startTime != null) {
                this.expiryTime = CommonUtils.getExpiryTimeString(this.startTime, this.activeExpirySeconds);
            }

            final Integer failure = jobStatus.getFailed();
            final Integer success = jobStatus.getSucceeded();
            final Integer running = jobStatus.getReady();
            if (failure != null && failure > 0) {
                final List<V1JobCondition> conditions = jobStatus.getConditions();
                if (conditions == null || conditions.isEmpty()) {
                    LOGGER.warn("No Pod Status Conditions found.");
                    this.status = "Failed";
                } else {
                    // Sort, then reverse it to get the latest.
                    conditions.sort((condition1, condition2) -> {
                        final OffsetDateTime conditionOneDateTime = condition1.getLastTransitionTime();
                        final OffsetDateTime conditionTwoDateTime = condition2.getLastTransitionTime();

                        if (conditionOneDateTime == null && conditionTwoDateTime == null) {
                            return 0;
                        } else if (conditionOneDateTime == null) {
                            return 1;
                        } else if (conditionTwoDateTime == null) {
                            return -1;
                        } else {
                            return conditionTwoDateTime.compareTo(conditionOneDateTime);
                        }
                    });

                    final V1JobCondition jobCondition = conditions.get(0);

                    // Suspended and then resumed.
                    if ("JobResumed".equals(jobCondition.getReason())) {
                        this.status = "Running";
                    } else {
                        this.status = String.format("%s (%s)", jobCondition.getType(), jobCondition.getReason());
                    }
                }
            } else if (success != null && success > 0) {
                this.status = "Completed";
            } else if (running != null && running > 0) {
                this.status = "Running";
            } else {
                this.status = "Pending";
            }

            return this;
        }

        Session build() {
            final Session session = new Session(
                    this.id,
                    this.userID,
                    runAsUID == null ? "" : Long.toString(this.runAsUID),
                    runAsGID == null ? "" : Long.toString(this.runAsGID),
                    this.supplementalGroups.toArray(new Integer[0]),
                    this.image,
                    this.type,
                    this.status,
                    this.name,
                    this.startTime,
                    this.connectURL,
                    this.appID);

            session.setExpiryTime(this.expiryTime);
            session.setRequestedRAM(this.requestedMemory);
            session.setRequestedCPUCores(this.requestedCPUCores);
            session.setRequestedGPUCores(this.requestedGPUCores);
            session.setCPUCoresInUse(this.cpuCoresInUse);
            session.setRAMInUse(this.memoryInUse);
            session.setFixedResources(this.isFixedResources);

            return session;
        }

        @Override
        public String toString() {
            return "SessionBuilder{" + "id='"
                    + id + '\'' + ", type='"
                    + type + '\'' + ", name='"
                    + name + '\'' + ", appID='"
                    + appID + '\'' + ", image='"
                    + image + '\'' + ", requestedMemory='"
                    + requestedMemory + '\'' + ", requestedCPUCores='"
                    + requestedCPUCores + '\'' + ", requestedGPUCores='"
                    + requestedGPUCores + '\'' + ", memoryInUse='"
                    + memoryInUse + '\'' + ", cpuCoresInUse='"
                    + cpuCoresInUse + '\'' + ", status='"
                    + status + '\'' + ", startTime='"
                    + startTime + '\'' + ", expiryTime='"
                    + expiryTime + '\'' + ", activeExpirySeconds="
                    + activeExpirySeconds + ", connectURL='"
                    + connectURL + '\'' + ", jobName='"
                    + jobName + '\'' + '}';
        }
    }
}
