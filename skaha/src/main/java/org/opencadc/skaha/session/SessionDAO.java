package org.opencadc.skaha.session;

import static org.opencadc.skaha.utils.CommandExecutioner.execute;

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
import io.kubernetes.client.util.Config;
import java.io.IOException;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.log4j.Logger;
import org.opencadc.skaha.K8SUtil;
import org.opencadc.skaha.SkahaAction;
import org.opencadc.skaha.utils.CommandExecutioner;
import org.opencadc.skaha.utils.KubectlCommandBuilder;

public class SessionDAO {
    public static final Logger LOGGER = Logger.getLogger(SessionDAO.class);

    private static final String NONE = "<none>";

    // Ordered dictionary of columns requested from Kubernetes

    static String[] getSessionsCMD(final String k8sNamespace, final String forUserID, final String sessionID) {
        KubectlCommandBuilder.KubectlCommand sessionsCmd = KubectlCommandBuilder.command("get")
                .pod()
                .namespace(k8sNamespace)
                .noHeaders();

        final String[] labelCriteria = SessionDAO.getSessionsCommandLabelCriteria(forUserID, sessionID);

        if (labelCriteria.length > 0) {
            sessionsCmd.label(String.join(",", labelCriteria));
        }

        final String customColumns = "custom-columns="
                + Arrays.stream(CustomColumns.values())
                        .filter(customColumn -> !customColumn.forUserOnly || forUserID != null)
                        .map(customColumn -> String.format(
                                "%s:%s.%s", customColumn.name(), customColumn.selectorPrefix, customColumn.simpleName))
                        .collect(Collectors.joining(","));

        return sessionsCmd.outputFormat(customColumns).build();
    }

    private static String[] getSessionsCommandLabelCriteria(final String userID, final String sessionID) {
        final List<String> labelCriteria = new ArrayList<>();

        if (StringUtil.hasText(userID)) {
            labelCriteria.add("canfar-net-userid=" + userID);
        }

        if (StringUtil.hasText(sessionID)) {
            labelCriteria.add("canfar-net-sessionID=" + sessionID);
        }

        return labelCriteria.toArray(new String[0]);
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

    static List<Session> getUserSessions(final String forUserID, final String sessionID, final boolean omitHeadless)
            throws Exception {
        final ApiClient client = Config.fromCluster();
        Configuration.setDefaultApiClient(client);

        final List<String> labelSelectors = new ArrayList<>();
        if (omitHeadless) {
            labelSelectors.add("canfar-net-sessionType!=" + SessionAction.SESSION_TYPE_HEADLESS);
        }

        if (StringUtil.hasLength(sessionID)) {
            labelSelectors.add("canfar-net-sessionID=" + sessionID);
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
        return jobListRequest.execute().getItems().stream()
                .map(job -> SessionBuilder.fromJob(job, podResourceUsage))
                .collect(Collectors.toList());
    }

    private static String toCoreUnit(String cores) {
        final String ret;
        if (StringUtil.hasLength(cores)) {
            if ("m".equals(cores.substring(cores.length() - 1))) {
                // in "m" (millicore) unit, covert to cores
                int milliCores = Integer.parseInt(cores.substring(0, cores.length() - 1));
                ret = ((Double) (milliCores / Math.pow(10, 3))).toString();
            } else {
                // use value as is, can be '<none>' or some value
                ret = cores;
            }
        } else {
            ret = SessionDAO.NONE;
        }

        return ret;
    }

    private static String toCommonUnit(String inK8sUnit) {
        final String ret;
        if (StringUtil.hasLength(inK8sUnit)) {
            if ("i".equals(inK8sUnit.substring(inK8sUnit.length() - 1))) {
                // unit is in Ki, Mi, Gi, etc., remove the i
                ret = inK8sUnit.substring(0, inK8sUnit.length() - 1);
            } else {
                // use value as is, can be '<none>' or some value
                ret = inK8sUnit;
            }
        } else {
            ret = SessionDAO.NONE;
        }

        return ret;
    }

    private static Map<String, String[]> getResourceUsages(String k8sNamespace, String forUserID) throws Exception {
        Map<String, String[]> resourceUsages = new HashMap<>();
        String[] sessionResourceUsageCMD = getSessionResourceUsageCMD(k8sNamespace, forUserID);
        try {
            LOGGER.debug("Resource usage command: " + String.join(" ", sessionResourceUsageCMD));
            String sessionResourceUsageMap = execute(sessionResourceUsageCMD);
            LOGGER.debug("Resource used: " + sessionResourceUsageMap);
            if (StringUtil.hasLength(sessionResourceUsageMap)) {
                String[] lines = sessionResourceUsageMap.split("\n");
                for (String line : lines) {
                    String[] resourceUsage = line.trim().replaceAll("\\s+", " ").split(" ");
                    String fullName = resourceUsage[0];
                    String[] resources = {resourceUsage[1], resourceUsage[2]};
                    resourceUsages.put(fullName, resources);
                }
            }
        } catch (IOException ex) {
            // error or no session using any resources, return empty resourceUsages
            LOGGER.debug("failed to query for metrics", ex);
        }

        return resourceUsages;
    }

    private static String[] getSessionResourceUsageCMD(String k8sNamespace, String forUserID) {
        return KubectlCommandBuilder.command("get")
                .namespace(k8sNamespace)
                .argument("top")
                .pod()
                .noHeaders()
                .label("canfar-net-userid=" + forUserID)
                .outputFormat(
                        "custom-columns=FULL_NAME:.metadata.name,REQUESTED_CPU:.spec.containers[0].resources.requests.cpu,REQUESTED_RAM:.spec.containers[0].resources.requests.memory")
                .build();
    }

    private static String[] getSessionGPUUsageCMD(String k8sNamespace, String podName) {
        return KubectlCommandBuilder.command("exec")
                .namespace(k8sNamespace)
                .argument("-it")
                .argument(podName)
                .argument("--")
                .argument("nvidia-smi")
                .build();
    }

    private static String[] getJobExpiryTimeCMD(String k8sNamespace, String forUserID) {
        return KubectlCommandBuilder.command("get")
                .namespace(k8sNamespace)
                .job()
                .label("canfar-net-userid=" + forUserID)
                .noHeaders()
                .outputFormat("custom-columns=UID:.metadata.uid,EXPIRY:.spec.activeDeadlineSeconds")
                .build();
    }

    private static Map<String, String> getJobExpiryTimes(String k8sNamespace, String forUserID) throws Exception {
        final Map<String, String> jobExpiryTimes = new HashMap<>();
        String[] jobExpiryTimeCMD = getJobExpiryTimeCMD(k8sNamespace, forUserID);
        String jobExpiryTimeMap = execute(jobExpiryTimeCMD);
        LOGGER.debug("Expiry times: " + jobExpiryTimeMap);
        if (StringUtil.hasLength(jobExpiryTimeMap)) {
            String[] lines = jobExpiryTimeMap.split("\n");
            for (String line : lines) {
                String[] expiryTime = line.trim().replaceAll("\\s+", " ").split(" ");
                jobExpiryTimes.put(expiryTime[0], expiryTime[1]);
            }
        }

        return jobExpiryTimes;
    }

    static String getConnectURL(
            final String sessionHostName,
            final String type,
            final String id,
            final String image,
            final String topLevelDirectory,
            final String userid)
            throws URISyntaxException {
        final String connectURL;

        if (SessionAction.SESSION_TYPE_DESKTOP.equals(type)) {
            connectURL = SessionURLBuilder.vncSession(sessionHostName, id).build();
        } else if (SessionAction.SESSION_TYPE_CARTA.equals(type)) {
            connectURL = SessionURLBuilder.cartaSession(sessionHostName, id)
                    .withAlternateSocket(image.endsWith(":1.4"))
                    .build();
        } else if (SessionAction.SESSION_TYPE_NOTEBOOK.equals(type)) {
            connectURL = SessionURLBuilder.notebookSession(sessionHostName, id)
                    .withTopLevelDirectory(topLevelDirectory)
                    .withUserName(userid)
                    .build();
        } else if (SessionAction.SESSION_TYPE_CONTRIB.equals(type)) {
            connectURL =
                    SessionURLBuilder.contributedSession(sessionHostName, id).build();
        } else if (SessionAction.SESSION_TYPE_FIREFLY.equals(type)) {
            connectURL = SessionURLBuilder.fireflySession(sessionHostName, id).build();
        } else {
            connectURL = "";
        }

        return connectURL;
    }

    static Session constructSession(String sessionHostName, String k8sOutput, final String topLevelDirectory)
            throws Exception {
        LOGGER.debug("line: " + k8sOutput);
        final List<CustomColumns> allColumns = Arrays.asList(CustomColumns.values());

        // Items are separated by 3 or more spaces.  We can't separate on all spaces because the supplemental groups
        // are in a space-delimited array.
        final String[] parts = k8sOutput.trim().split(" {3,}");

        String id = parts[allColumns.indexOf(CustomColumns.SESSION_ID)];
        String userid = parts[allColumns.indexOf(CustomColumns.USERID)];
        String image = parts[allColumns.indexOf(CustomColumns.IMAGE)];
        String type = parts[allColumns.indexOf(CustomColumns.TYPE)];
        String deletionTimestamp = parts[allColumns.indexOf(CustomColumns.DELETION)];
        final String status = (deletionTimestamp != null && !NONE.equals(deletionTimestamp))
                ? Session.STATUS_TERMINATING
                : parts[allColumns.indexOf(CustomColumns.STATUS)];
        final String connectURL = SessionDAO.getConnectURL(sessionHostName, type, id, image, topLevelDirectory, userid);

        final Session session = new Session(
                id,
                userid,
                parts[allColumns.indexOf(CustomColumns.RUN_AS_UID)],
                parts[allColumns.indexOf(CustomColumns.RUN_AS_GID)],
                SessionDAO.fromStringArray(parts[allColumns.indexOf(CustomColumns.SUPPLEMENTAL_GROUPS)]),
                image,
                type,
                status,
                parts[allColumns.indexOf(CustomColumns.NAME)],
                parts[allColumns.indexOf(CustomColumns.STARTED)],
                connectURL,
                parts[allColumns.indexOf(CustomColumns.APP_ID)]);

        // Check if all columns were requested (set by forUserId)
        final int requestedRamIndex = allColumns.indexOf(CustomColumns.REQUESTED_RAM);
        if (parts.length > requestedRamIndex) {
            session.setRequestedRAM(toCommonUnit(parts[requestedRamIndex]));
        }

        final int requestedCPUIndex = allColumns.indexOf(CustomColumns.REQUESTED_CPU);
        if (parts.length > requestedCPUIndex) {
            session.setRequestedCPUCores(toCoreUnit(parts[requestedCPUIndex]));
        }

        final int requestedGPUIndex = allColumns.indexOf(CustomColumns.REQUESTED_GPU);
        if (parts.length > requestedGPUIndex) {
            session.setRequestedGPUCores(toCoreUnit(parts[requestedGPUIndex]));
        }

        return session;
    }

    /**
     * Example input is [4444 5555 6666]. Convert to an actual integer array.
     *
     * @param inputArray Kubernetes output of an array of integers.
     * @return integer array, never null.
     */
    private static Integer[] fromStringArray(final String inputArray) {
        if (inputArray.equals(SessionDAO.NONE)) {
            return new Integer[0];
        } else {
            final Object[] parsedArray = Arrays.stream(
                            inputArray.replace("[", "").replace("]", "").trim().split(" "))
                    .filter(StringUtil::hasText)
                    .map(Integer::parseInt)
                    .toArray();
            return Arrays.copyOf(parsedArray, parsedArray.length, Integer[].class);
        }
    }

    private enum CustomColumns {
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
                    this.expiryTime = SessionDAO.getExpiryTimeString(this.startTime, secondsUntilExpire);
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

                    final V1ResourceRequirements resourceRequirements = podContainer.getResources();

                    if (resourceRequirements != null) {
                        final Map<String, Quantity> resourceRequests = resourceRequirements.getRequests();
                        if (resourceRequests != null) {
                            if (resourceRequests.containsKey("memory")) {
                                this.requestedMemory =
                                        resourceRequests.get("memory").toSuffixedString();
                            }

                            if (resourceRequests.containsKey("cpu")) {
                                this.requestedCPUCores =
                                        resourceRequests.get("cpu").toSuffixedString();
                            }

                            if (resourceRequests.containsKey("nvidia\\.com/gpu")) {
                                this.requestedGPUCores =
                                        resourceRequests.get("nvidia\\.com/gpu").toSuffixedString();
                            } else {
                                // Set to zero to satisfy UI conditions.
                                this.requestedGPUCores = "0";
                            }
                        }
                    }
                }

                try {
                    this.connectURL = SessionDAO.getConnectURL(
                            K8SUtil.getSessionsHostName(),
                            this.type,
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
                this.expiryTime = SessionDAO.getExpiryTimeString(this.startTime, this.activeExpirySeconds);
            }

            final Integer failure = jobStatus.getFailed();
            final Integer success = jobStatus.getSucceeded();
            final Integer running = jobStatus.getActive();
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

    private static class PodResourceUsage {
        final Map<String, String> cpu;
        final Map<String, String> memory;

        private PodResourceUsage(final Map<String, String> cpu, final Map<String, String> memory) {
            this.cpu = Collections.unmodifiableMap(cpu);
            this.memory = Collections.unmodifiableMap(memory);
        }

        static PodResourceUsage get(final String userID, final boolean omitHeadless) throws Exception {
            final Map<String, String> cpuMetrics = new HashMap<>();
            final Map<String, String> memoryMetrics = new HashMap<>();
            final List<String> labelSelectors = new ArrayList<>();

            if (StringUtil.hasLength(userID)) {
                labelSelectors.add("canfar-net-userid=" + userID);
            }

            if (omitHeadless) {
                labelSelectors.add("canfar-net-sessionType!=" + SessionAction.SESSION_TYPE_HEADLESS);
            }

            final String[] topCommand = KubectlCommandBuilder.command("top")
                    .namespace(K8SUtil.getWorkloadNamespace())
                    .noHeaders()
                    .pod()
                    .label(String.join(",", labelSelectors))
                    .build();

            LOGGER.debug("Resource usage command: " + String.join(" ", topCommand));
            final String sessionResourceUsageMap = CommandExecutioner.execute(topCommand);
            LOGGER.debug("Resource usage command output: " + sessionResourceUsageMap);
            if (StringUtil.hasLength(sessionResourceUsageMap)) {
                final String[] lines = sessionResourceUsageMap.split("\n");
                for (final String line : lines) {
                    final String[] resourceUsage =
                            line.trim().replaceAll("\\s+", " ").split(" ");
                    final String fullName = resourceUsage[0];

                    cpuMetrics.put(fullName, resourceUsage[1]);
                    memoryMetrics.put(fullName, resourceUsage[2]);
                }
            }

            return new PodResourceUsage(cpuMetrics, memoryMetrics);
        }
    }

    static String getExpiryTimeString(final String startTimeString, final Long expiryTimeInSeconds) {
        final String outputTemplate = "%s-%s-%sT%s:%s:%sZ";
        final Pattern expectedFormat = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):?(\\d{2})?.*Z");
        final Matcher matcher = expectedFormat.matcher(startTimeString);
        final List<String> captureGroups = new ArrayList<>();
        if (matcher.find()) {
            for (int i = 0; i < matcher.groupCount(); i++) {
                final String nextMatch = matcher.group(i + 1);
                if (StringUtil.hasLength(nextMatch)) {
                    captureGroups.add(nextMatch);
                }
            }
        }

        final int capturedGroupCount = captureGroups.size();

        // Expected order of the groups resulting in count:
        // Calendar.YEAR, Calendar.MONTH, Calendar.DAY_OF_MONTH, Calendar.HOUR, Calendar.MINUTE, Calendar.SECOND
        //
        // Some dates, however, are missing some elements if the value is 0.
        final int expectedCount = 6;
        final int missingGroups = expectedCount - capturedGroupCount;
        if (missingGroups > 3) {
            LOGGER.warn("Unparsable start time: " + startTimeString);
            return null;
        } else {
            if (missingGroups > 0) {
                for (int i = 0; i < missingGroups; i++) {
                    captureGroups.add("00");
                }
            }
        }

        final String[] captureGroupsArray = captureGroups.toArray(new String[0]);
        final String instantTime = String.format(outputTemplate, (Object[]) captureGroupsArray);
        final Instant instant = Instant.parse(instantTime);
        return instant.plusSeconds(expiryTimeInSeconds).toString();
    }
}
