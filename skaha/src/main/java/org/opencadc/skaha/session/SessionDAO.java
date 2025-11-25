package org.opencadc.skaha.session;

import ca.nrc.cadc.net.ResourceNotFoundException;
import ca.nrc.cadc.util.StringUtil;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1Status;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.log4j.Logger;
import org.opencadc.skaha.K8SUtil;
import org.opencadc.skaha.KubernetesJob;
import org.opencadc.skaha.SkahaAction;
import org.opencadc.skaha.utils.MemoryUnitConverter;

public class SessionDAO {
    public static final Logger LOGGER = Logger.getLogger(SessionDAO.class);

    private static final String SESSION_ID_LABEL = "canfar-net-sessionID";
    private static final String DESKTOP_APP_ID_LABEL = "canfar-net-appID";
    private static final String USER_ID_LABEL = "canfar-net-userid";
    private static final String SESSION_TYPE_LABEL = "canfar-net-sessionType";

    static final String NONE = "<none>";

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
                api.listNamespacedJob(K8SUtil.getWorkloadNamespace());

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
                .map(job -> SessionBuilder.fromJob(job, podResourceUsage))
                .collect(Collectors.toList());
        LOGGER.debug("Found " + sessions.size() + " sessions for user " + forUserID + " with selector " + labelSelector
                + " after filtering.");
        return sessions;
    }

    /**
     * Aggregate the allocated CPU and memory resources for all running pods in the workload namespace. This will also
     * take into account the current Pod usages (Actual usage) as it may exceed the requested allocation.
     *
     * @return Map with keys "cpu" and "memory" and their allocated values as BigDecimals.
     * @throws Exception If the Kubernetes API call fails.
     */
    static Map<String, BigDecimal> getAllocatedPodResources() throws Exception {
        final ApiClient client = Configuration.getDefaultApiClient();
        final CoreV1Api api = new CoreV1Api(client);
        final V1PodList podList = api.listNamespacedPod(K8SUtil.getWorkloadNamespace())
                .fieldSelector("status.phase=Running")
                .execute();
        final PodResourceUsage podResourceUsage = PodResourceUsage.getAll();

        final Map<String, BigDecimal> resources = new HashMap<>();
        resources.put("cpu", BigDecimal.ZERO);
        resources.put("memory", BigDecimal.ZERO);
        for (final V1Pod pod : podList.getItems()) {
            final String podName = Objects.requireNonNullElse(pod.getMetadata(), new V1ObjectMeta())
                    .getName();
            final List<V1Container> containers =
                    Objects.requireNonNullElse(pod.getSpec(), new V1PodSpec()).getContainers();
            for (final V1Container container : containers) {
                final V1ResourceRequirements containerResourceRequirements =
                        Objects.requireNonNullElse(container.getResources(), new V1ResourceRequirements());
                final Map<String, Quantity> containerResourceRequests =
                        Objects.requireNonNullElse(containerResourceRequirements.getRequests(), new HashMap<>());
                final BigDecimal cpu = Objects.requireNonNullElse(
                        containerResourceRequests
                                .getOrDefault("cpu", new Quantity("0"))
                                .getNumber(),
                        BigDecimal.ZERO);
                final String currentPodCoreUsage = podResourceUsage.cpu().get(podName);
                // Pod resource usage reported as decimal cores, e.g. "0.5"
                if (StringUtil.hasLength(currentPodCoreUsage)
                        && Double.parseDouble(currentPodCoreUsage) > cpu.doubleValue()) {
                    LOGGER.debug("Pod " + podName + " memory usage " + currentPodCoreUsage + " cores exceeds requested "
                            + cpu.doubleValue() + " cores, using actual usage.");
                    resources.put("cpu", resources.get("cpu").add(new BigDecimal(currentPodCoreUsage)));
                } else {
                    resources.put("cpu", resources.get("cpu").add(cpu));
                }

                final BigDecimal memory = Objects.requireNonNullElse(
                        containerResourceRequests
                                .getOrDefault("memory", new Quantity("0"))
                                .getNumber(),
                        BigDecimal.ZERO);
                final String currentPodMemoryUsage = podResourceUsage.memory().get(podName);
                // Pod resource usage reported as double Gigabytes, e.g. "1.5"
                if (StringUtil.hasLength(currentPodMemoryUsage)) {
                    final long bytes = MemoryUnitConverter.toBytes(
                            currentPodMemoryUsage + MemoryUnitConverter.MemoryUnit.G.name());
                    if (bytes > memory.longValue()) {
                        LOGGER.debug("Pod " + podName + " memory usage " + bytes + " bytes exceeds requested "
                                + memory.longValue() + " bytes, using actual usage.");
                        resources.put("memory", resources.get("memory").add(new BigDecimal(bytes)));
                    } else {
                        resources.put("memory", resources.get("memory").add(memory));
                    }
                } else {
                    resources.put("memory", resources.get("memory").add(memory));
                }
            }
        }

        return resources;
    }

    static String getConnectURL(
            final String sessionHostName,
            final SessionType type,
            final String id,
            final String image,
            final String absoluteHomeDirectory,
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
