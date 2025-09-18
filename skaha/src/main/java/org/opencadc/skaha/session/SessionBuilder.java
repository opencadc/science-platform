package org.opencadc.skaha.session;

import ca.nrc.cadc.util.StringUtil;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobSpec;
import io.kubernetes.client.openapi.models.V1JobStatus;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PodSecurityContext;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.log4j.Logger;
import org.opencadc.skaha.K8SUtil;
import org.opencadc.skaha.utils.CommonUtils;

/**
 * Builder for Session objects from Kubernetes Job definitions.
 *
 * @author jenkinsd
 */
class SessionBuilder {

    private static final Logger LOGGER = Logger.getLogger(SessionBuilder.class.getName());

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

    SessionBuilder(final String id, final String userID, final String type) {
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
        sessionBuilder.isFixedResources = !StringUtil.hasText(flexLabelValue) || !Boolean.parseBoolean(flexLabelValue);
        sessionBuilder.jobName = jobMetadata.getName();

        return sessionBuilder
                .withJobSpec(jobSpec, podResourceUsage)
                .withStatus(job.getStatus(), Objects.requireNonNullElse(jobSpec.getSuspend(), false))
                .build();
    }

    SessionBuilder withJobSpec(final V1JobSpec jobSpec, final PodResourceUsage podResourceUsage) {
        Objects.requireNonNull(jobSpec, "Invalid JobSpec");

        final Long secondsUntilExpire = jobSpec.getActiveDeadlineSeconds();
        if (this.startTime != null) {
            if (secondsUntilExpire == null) {
                SessionDAO.LOGGER.warn("No expiry set for " + this.id);
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
                SessionDAO.LOGGER.warn("No Pod Security Context found.");
            } else {
                this.runAsUID = podSecurityContext.getRunAsUser();
                this.runAsGID = podSecurityContext.getRunAsGroup();
                final List<Long> supplementalGroupGIDs = podSecurityContext.getSupplementalGroups();
                if (supplementalGroupGIDs != null && !supplementalGroupGIDs.isEmpty()) {
                    this.supplementalGroups.addAll(
                            supplementalGroupGIDs.stream().map(Long::intValue).collect(Collectors.toList()));
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
                SessionDAO.LOGGER.warn("No Container found.");
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
                SessionDAO.LOGGER.warn("Invalid URI for connect URL: " + this);
                throw new IllegalStateException(e.getMessage(), e);
            }
        }

        return this;
    }

    SessionBuilder withStatus(final V1JobStatus jobStatus, final boolean suspended) {
        Objects.requireNonNull(jobStatus, "Invalid JobStatus");

        if (jobStatus.getStartTime() != null) {
            this.startTime = jobStatus.getStartTime().toString();
        }

        if (this.activeExpirySeconds != null && this.startTime != null) {
            this.expiryTime = CommonUtils.getExpiryTimeString(this.startTime, this.activeExpirySeconds);
        }

        final int success = Objects.requireNonNullElse(jobStatus.getSucceeded(), 0);
        final int failed = Objects.requireNonNullElse(jobStatus.getFailed(), 0);
        final int active = Objects.requireNonNullElse(jobStatus.getActive(), 0);
        final int ready = Objects.requireNonNullElse(jobStatus.getReady(), 0);
        final int terminating = Objects.requireNonNullElse(jobStatus.getTerminating(), 0);

        if (active > 0) {
            if (ready > 0) {
                this.status = Session.STATUS_RUNNING;
            } else {
                this.status = Session.STATUS_PENDING;
            }
        } else if (suspended) {
            // Suspended likely means it went to be scheduled but, Kueue has not picked it up yet.
            this.status = Session.STATUS_PENDING;
        } else if (success > 0) {
            this.status = Session.STATUS_COMPLETED;
        } else if (failed > 0) {
            this.status = Session.STATUS_FAILED;
        } else if (terminating > 0) {
            this.status = Session.STATUS_TERMINATING;
        } else {
            this.status = Session.STATUS_PENDING;
        }

        LOGGER.debug("Session Status: " + this.status + " (success=" + success + ", failed=" + failed + ", active="
                + active + ", ready=" + ready + ", terminating=" + terminating + ", suspended=" + suspended
                + ") for " + this.id);

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
}
