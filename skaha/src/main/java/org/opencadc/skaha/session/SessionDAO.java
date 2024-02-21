package org.opencadc.skaha.session;

import ca.nrc.cadc.net.ResourceNotFoundException;
import ca.nrc.cadc.util.StringUtil;
import org.apache.log4j.Logger;
import org.opencadc.skaha.K8SUtil;
import org.opencadc.skaha.SkahaAction;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.opencadc.skaha.utils.CommandExecutioner.execute;

public class SessionDAO {
    public static final Logger LOGGER = Logger.getLogger(SessionDAO.class);

    private static final String NONE = "<none>";

    public static List<String> getSessionsCMD(final String k8sNamespace, String forUserID, String sessionID) {
        final List<String> sessionsCMD = new ArrayList<>();
        sessionsCMD.add("kubectl");
        sessionsCMD.add("get");
        sessionsCMD.add("--namespace");
        sessionsCMD.add(k8sNamespace);
        sessionsCMD.add("pod");

        final String[] labelCriteria = SessionDAO.getSessionsCommandLabelCriteria(forUserID, sessionID);

        if (labelCriteria.length > 0) {
            sessionsCMD.add("-l");
            sessionsCMD.add(String.join(",", labelCriteria));
        }

        sessionsCMD.add("--no-headers=true");
        sessionsCMD.add("-o");

        String customColumns = "custom-columns=" +
                "SESSIONID:.metadata.labels.canfar-net-sessionID," +
                "USERID:.metadata.labels.canfar-net-userid," +
                "IMAGE:.spec.containers[0].image," +
                "TYPE:.metadata.labels.canfar-net-sessionType," +
                "STATUS:.status.phase," +
                "NAME:.metadata.labels.canfar-net-sessionName," +
                "STARTED:.status.startTime," +
                "DELETION:.metadata.deletionTimestamp," +
                "APPID:.metadata.labels.canfar-net-appID";
        if (forUserID != null) {
            customColumns = customColumns +
                    ",REQUESTEDRAM:.spec.containers[0].resources.requests.memory," +
                    "REQUESTEDCPU:.spec.containers[0].resources.requests.cpu," +
                    "REQUESTEDGPU:.spec.containers[0].resources.requests.nvidia\\.com/gpu," +
                    "FULLNAME:.metadata.name," +
                    "UID:.metadata.ownerReferences[].uid";
        }

        sessionsCMD.add(customColumns);
        return sessionsCMD;
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

    private static List<Session> getSessions(String forUserID, String sessionID, final String topLevelDirectory)
            throws Exception {
        String k8sNamespace = K8SUtil.getWorkloadNamespace();
        List<String> sessionsCMD = SessionDAO.getSessionsCMD(k8sNamespace, forUserID, sessionID);
        String sessionList = execute(sessionsCMD.toArray(new String[0]));
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
                Session session = SessionDAO.constructSession(line, topLevelDirectory);
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
                        if (StringUtil.hasText(session.getRequestedGPUCores()) &&
                                !NONE.equals(session.getRequestedGPUCores()) &&
                                Double.parseDouble(session.getRequestedGPUCores()) > 0.0) {
                            List<String> sessionGPUUsageCMD = getSessionGPUUsageCMD(k8sNamespace, fullName);
                            String sessionGPUUsage = execute(sessionGPUUsageCMD.toArray(new String[0]));
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
        List<String> sessionResourceUsageCMD = getSessionResourceUsageCMD(k8sNamespace, forUserID);
        try {
            String sessionResourceUsageMap = execute(sessionResourceUsageCMD.toArray(new String[0]));
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
    private static List<String> getSessionResourceUsageCMD(String k8sNamespace, String forUserID) {
        final List<String> getSessionJobCMD = new ArrayList<>();
        getSessionJobCMD.add("kubectl");
        getSessionJobCMD.add("--namespace");
        getSessionJobCMD.add(k8sNamespace);
        getSessionJobCMD.add("top");
        getSessionJobCMD.add("pod");
        getSessionJobCMD.add("-l");
        getSessionJobCMD.add("canfar-net-userid=" + forUserID);
        getSessionJobCMD.add("--no-headers=true");
        getSessionJobCMD.add("--use-protocol-buffers=true");
        return getSessionJobCMD;
    }

    private static List<String> getSessionGPUUsageCMD(String k8sNamespace, String podName) {
        final List<String> getSessionGPUCMD = new ArrayList<>();
        getSessionGPUCMD.add("kubectl");
        getSessionGPUCMD.add("--namespace");
        getSessionGPUCMD.add(k8sNamespace);
        getSessionGPUCMD.add("exec");
        getSessionGPUCMD.add("-it");
        getSessionGPUCMD.add(podName);
        getSessionGPUCMD.add("--");
        getSessionGPUCMD.add("nvidia-smi");
        return getSessionGPUCMD;
    }

    private static List<String> getJobExpiryTimeCMD(String k8sNamespace, String forUserID) {
        final List<String> getSessionJobCMD = new ArrayList<>();
        getSessionJobCMD.add("kubectl");
        getSessionJobCMD.add("get");
        getSessionJobCMD.add("--namespace");
        getSessionJobCMD.add(k8sNamespace);
        getSessionJobCMD.add("job");
        getSessionJobCMD.add("-l");
        getSessionJobCMD.add("canfar-net-userid=" + forUserID);
        getSessionJobCMD.add("--no-headers=true");
        getSessionJobCMD.add("-o");

        String customColumns = "custom-columns="
                + "UID:.metadata.uid,"
                + "EXPIRY:.spec.activeDeadlineSeconds";

        getSessionJobCMD.add(customColumns);
        return getSessionJobCMD;
    }

    private static Map<String, String> getJobExpiryTimes(String k8sNamespace, String forUserID) throws Exception {
        final Map<String, String> jobExpiryTimes = new HashMap<>();
        List<String> jobExpiryTimeCMD = getJobExpiryTimeCMD(k8sNamespace, forUserID);
        String jobExpiryTimeMap = execute(jobExpiryTimeCMD.toArray(new String[0]));
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

    private static Session constructSession(String k8sOutput, final String topLevelDirectory) throws IOException {
        LOGGER.debug("line: " + k8sOutput);
        String[] parts = k8sOutput.trim().replaceAll("\\s+", " ").split(" ");
        String id = parts[0];
        String userid = parts[1];
        String image = parts[2];
        String type = parts[3];
        String status = parts[4];
        String name = parts[5];
        String startTime = parts[6];
        String deletionTimestamp = parts[7];
        String appID = parts[8];
        if (deletionTimestamp != null && !NONE.equals(deletionTimestamp)) {
            status = Session.STATUS_TERMINATING;
        }
        String host = K8SUtil.getHostName();
        String connectURL = "not-applicable";

        if (SessionAction.SESSION_TYPE_DESKTOP.equals(type)) {
            connectURL = SessionAction.getVNCURL(host, id);
        }
        if (SessionAction.SESSION_TYPE_CARTA.equals(type)) {
            if (image.endsWith(":1.4")) {
                // support alt web socket path for 1.4 carta
                connectURL = SessionAction.getCartaURL(host, id, true);
            } else {
                connectURL = SessionAction.getCartaURL(host, id, false);
            }
        }
        if (SessionAction.SESSION_TYPE_NOTEBOOK.equals(type)) {
            connectURL = SessionAction.getNotebookURL(host, id, userid, topLevelDirectory);
        } else if (SessionAction.SESSION_TYPE_CONTRIB.equals(type)) {
            connectURL = SessionAction.getContributedURL(host, id);
        }

        Session session = new Session(id, userid, image, type, status, name, startTime, connectURL);
        session.setAppId(appID);

        if (parts.length > 9) {
            String requestedRAM = parts[9];
            String requestedCPUCores = parts[10];
            String requestedGPUCores = parts[11];
            session.setRequestedRAM(toCommonUnit(requestedRAM));
            session.setRequestedCPUCores(toCoreUnit(requestedCPUCores));
            session.setRequestedGPUCores(toCoreUnit(requestedGPUCores));
        }

        return session;
    }
}
