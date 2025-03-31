package org.opencadc.skaha.session;

import static org.opencadc.skaha.utils.CommandExecutioner.execute;

import ca.nrc.cadc.net.ResourceNotFoundException;
import ca.nrc.cadc.util.StringUtil;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.Config;
import org.apache.log4j.Logger;
import org.opencadc.skaha.K8SUtil;
import org.opencadc.skaha.SkahaAction;
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
                        .map(customColumn -> String.format("%s:%s", customColumn.name(), customColumn.selectorPrefix))
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

    public static Session getSession(String forUserID, String sessionID, final String topLevelDirectory)
            throws Exception {
        final List<Session> sessions = SessionDAO.getSessions(forUserID, sessionID, topLevelDirectory);
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

    static List<Session> getInteractiveSessions(final String forUserID) throws Exception {
        Objects.requireNonNull(forUserID, "User ID to search for must not be null");
        final ApiClient client = Config.fromCluster();
        Configuration.setDefaultApiClient(client);

        final BatchV1Api api = new BatchV1Api(client);
        final V1JobList jobList = api.listNamespacedJob(K8SUtil.getWorkloadNamespace())
                .allowWatchBookmarks(Boolean.TRUE)
                .labelSelector("canfar-net-userid=" + forUserID)
                .execute();

        return jobList.getItems().stream()
                .map(SessionMapper::from)
                .collect(Collectors.toList());
    }

    static List<Session> getSessions(String forUserID, String sessionID, final String topLevelDirectory)
            throws Exception {
        String k8sNamespace = K8SUtil.getWorkloadNamespace();
        String[] sessionsCMD = SessionDAO.getSessionsCMD(k8sNamespace, forUserID, sessionID);
        String sessionList = execute(sessionsCMD);
        LOGGER.debug("Session list: " + sessionList);

        List<Session> sessions = new ArrayList<>();
        if (StringUtil.hasLength(sessionList)) {
            Map<String, String> jobExpiryTimes = null;
            Map<String, String[]> resourceUsages = null;
            if (forUserID != null) {
                jobExpiryTimes = SessionDAO.getJobExpiryTimes(k8sNamespace, forUserID);
                resourceUsages = SessionDAO.getResourceUsages(k8sNamespace, forUserID);
            }

            String[] lines = sessionList.split("\n");
            for (String line : lines) {
                Session session = SessionDAO.constructSession(K8SUtil.getSessionsHostName(), line, topLevelDirectory);
                if (forUserID != null) {
                    // get expiry time
                    String uid = getUID(line);
                    String startTimeStr = session.getStartTime();
                    if (startTimeStr.equalsIgnoreCase(NONE)) {
                        session.setExpiryTime(startTimeStr);
                    } else {
                        Instant instant = Instant.parse(startTimeStr);
                        String jobExpiryTimesStr = jobExpiryTimes.get(uid);
                        if (jobExpiryTimesStr == null) {
                            session.setExpiryTime(NONE);
                        } else {
                            instant = instant.plus(Integer.parseInt(jobExpiryTimesStr), ChronoUnit.SECONDS);
                            session.setExpiryTime(instant.toString());
                        }
                    }

                    // get RAM and CPU usage
                    String fullName = getFullName(line);
                    if (resourceUsages.isEmpty()) {
                        // no job in 'Running' state
                        session.setCPUCoresInUse(NONE);
                        session.setRAMInUse(NONE);

                    } else {
                        // at least one job is in 'Running' state
                        String[] resourceUsage = resourceUsages.get(fullName);
                        if (resourceUsage == null) {
                            // job not in 'Running' state
                            session.setCPUCoresInUse(NONE);
                            session.setRAMInUse(NONE);
                        } else {
                            session.setCPUCoresInUse(SessionDAO.toCoreUnit(resourceUsage[0]));
                            session.setRAMInUse(SessionDAO.toCommonUnit(resourceUsage[1]));
                        }

                        // if this session usages GPU, get the GPU usage
                        if (StringUtil.hasText(session.getRequestedGPUCores())
                                && !NONE.equals(session.getRequestedGPUCores())
                                && Double.parseDouble(session.getRequestedGPUCores()) > 0.0) {
                            String[] sessionGPUUsageCMD = getSessionGPUUsageCMD(k8sNamespace, fullName);
                            String sessionGPUUsage = execute(sessionGPUUsageCMD);
                            List<String> gpuUsage = getGPUUsage(sessionGPUUsage);
                            session.setGPURAMInUse(gpuUsage.get(0));
                            session.setGPUUtilization(gpuUsage.get(1));
                        } else {
                            session.setGPURAMInUse(NONE);
                            session.setGPUUtilization(NONE);
                        }
                    }
                }

                sessions.add(session);
            }
        }

        return sessions;
    }

    private static String getFullName(String line) {
        String name = "";
        String[] parts = line.trim().replaceAll("\\s+", " ").split(" ");
        if (parts.length > 8) {
            name = parts[parts.length - 2];
        }

        return name;
    }

    private static String getUID(String line) {
        String uid = "";
        String[] parts = line.trim().replaceAll("\\s+", " ").split(" ");
        if (parts.length > 8) {
            uid = parts[parts.length - 1];
        }

        return uid;
    }

    private static List<String> getGPUUsage(String usageData) {
        final List<String> usage = new ArrayList<>();
        if (StringUtil.hasLength(usageData)) {
            String[] lines = usageData.split("\n");
            for (String line : lines) {
                if (line.contains("%")) {
                    String[] segments = line.trim().split("\\|");
                    if (segments.length > 3) {
                        if (segments[3].contains("%")) {
                            String util = segments[3].trim().split(" ")[0];
                            if (util.contains("%")) {
                                String mem = formatGPUMemoryUsage(segments[2].trim());
                                usage.add(mem);
                                usage.add(util);
                                break;
                            }
                        }
                    }
                }
            }
        }

        // no GPU
        if (usage.isEmpty()) {
            usage.add(NONE);
            usage.add(NONE);
        }

        return usage;
    }

    private static String formatGPUMemoryUsage(String memoryData) {
        final String[] data = memoryData.split("/");
        String data0 = data[0].trim();
        if (data0.substring(data0.length() - 1).equalsIgnoreCase("B")) {
            data0 = toCommonUnit(data0.substring(0, data0.length() - 1));
        }

        String data1 = data[1].trim();
        if (data1.substring(data1.length() - 1).equalsIgnoreCase("B")) {
            data1 = toCommonUnit(data1.substring(0, data1.length() - 1));
        }
        return data0 + " / " + data1;
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
        } else {
            connectURL = "not-applicable";
        }

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
        RUN_AS_UID(".spec.securityContext", "runAsUser",false),
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

    private static class SessionMapper {
        static Session from(final V1Job job) {
            final V1ObjectMeta meta = job.getMetadata();
            final V1JobSpec jobSpec = job.getSpec();
            Objects.requireNonNull(jobSpec, "jobSpec cannot be null");
            final SessionBuilder sessionBuilder = SessionBuilder.fromMetadata(meta)
                    .withPodSpec(jobSpec.getTemplate().getSpec())
                    .withStatus(job.getStatus());

            return sessionBuilder.build();
        }
    }

    private static class SessionBuilder {
        private final String id;
        private final String userID;
        private final String type;
        private final String name;
        private String appID;
        private Long runAsUID;
        private Long runAsGID;
        private List<Integer> supplementalGroups = new ArrayList<>();
        private String image;
        private String status;
        private String startTime;
        private String connectURL;

        private SessionBuilder(final String id, final String userID, final String type, final String name) {
            Objects.requireNonNull(id, "Session ID cannot be null");
            Objects.requireNonNull(userID, "Session UserID cannot be null");
            Objects.requireNonNull(type, "Session Type cannot be null");
            Objects.requireNonNull(name, "Session Name cannot be null");
            this.id = id;
            this.userID = userID;
            this.type = type;
            this.name = name;
        }

        static SessionBuilder fromMetadata(final V1ObjectMeta jobMetadata) {
            Objects.requireNonNull(jobMetadata, "Invalid Job with null Metadata");
            final Map<String, String> labels = jobMetadata.getLabels();
            Objects.requireNonNull(labels, "Invalid Job with null Labels");

            final SessionBuilder sessionBuilder = new SessionBuilder(labels.get(CustomColumns.SESSION_ID.simpleName),
                    labels.get(CustomColumns.USERID.simpleName), labels.get(CustomColumns.TYPE.simpleName),
                    labels.get(CustomColumns.NAME.simpleName));

            sessionBuilder.appID = labels.get(CustomColumns.APP_ID.simpleName);

            return sessionBuilder;
        }

        SessionBuilder withPodSpec(final V1PodSpec podSpec) {
            Objects.requireNonNull(podSpec, "Invalid PodSpec");
            final V1PodSecurityContext podSecurityContext = podSpec.getSecurityContext();
            if (podSecurityContext == null) {
                LOGGER.warn("No Pod Security Context found.");
            } else {
                this.runAsUID = podSecurityContext.getRunAsUser();
                this.runAsGID = podSecurityContext.getRunAsGroup();
                final List<Long> supplementalGroupGIDs = podSecurityContext.getSupplementalGroups();
                if (supplementalGroupGIDs != null && !supplementalGroupGIDs.isEmpty()) {
                    this.supplementalGroups.addAll(supplementalGroupGIDs.stream().map(Long::intValue)
                            .collect(Collectors.toList()));
                }
            }

            final List<V1Container> podContainers = podSpec.getContainers();
            if (podContainers.isEmpty()) {
                LOGGER.warn("No Container found.");
            } else {
                this.image = podContainers.get(0).getImage();
            }

            return this;
        }

        SessionBuilder withStatus(final V1JobStatus jobStatus) {
            Objects.requireNonNull(jobStatus, "Invalid JobStatus");
            final List<V1JobCondition> conditions = jobStatus.getConditions();
            if (conditions == null || conditions.isEmpty()) {
                LOGGER.warn("No Pod Status Conditions found.");
            } else {
                conditions.sort((condition1, condition2) -> {
                    Objects.requireNonNull(condition1.getLastTransitionTime(), "Invalid Job Status Condition 1");
                    Objects.requireNonNull(condition2.getLastTransitionTime(), "Invalid Job Status Condition 2");
                    return condition2.getLastTransitionTime().compareTo(condition1.getLastTransitionTime());
                });

                Collections.reverse(conditions);

                this.status = conditions.get(0).getStatus();
                this.startTime = Objects.requireNonNull(jobStatus.getStartTime(), "Missing Job start time.").toString();
            }

            return this;
        }

        Session build() {
            return new Session(this.id, this.userID, runAsUID == null ? "" : Long.toString(this.runAsUID),
                    runAsGID == null ? "" : Long.toString(this.runAsGID), this.supplementalGroups.toArray(new Integer[0]), this.image,
                    this.type, this.status, this.name, this.startTime, this.connectURL, this.appID);
        }
    }
}
