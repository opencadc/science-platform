package org.opencadc.skaha.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.log4j.Logger;
import org.opencadc.skaha.K8SUtil;

public class UserVolumeUtils {
    private static final Logger log = Logger.getLogger(UserVolumeUtils.class);
    private static final String USER_DATASETS_ROOT_PATH = K8SUtil.userDatasetsRootPath();

    /**
     * Populates the user volume template with runtime volumes and mounts.
     *
     * @param userName The name of the user.
     * @param namespace The namespace in which the user exists.
     * @return A map containing the runtime volumes and mounts.
     */
    public static Map<String, String> populateUserVolumeTemplate(String userName, String namespace) {
        StringBuilder runtimeVolumes = new StringBuilder();
        StringBuilder runtimeVolumeMounts = new StringBuilder();
        try {
            List<Map<String, String>> userRuntimePvcs = getUserPvc(userName, namespace);
            log.info("userRuntimePvcs size : " + userRuntimePvcs.size());
            Set<String> linkedTargetsSet = new HashSet<>();
            for (int i = 0; i < userRuntimePvcs.size(); i++) {
                Map<String, String> pvcInfo = userRuntimePvcs.get(i);
                String linkTarget = pvcInfo.get("linkTarget");
                // if the linked target is already added, skip it
                if (linkedTargetsSet.contains(linkTarget)) continue;
                linkedTargetsSet.add(linkTarget);

                String volumeName = createRuntimeVolumeName(userName, i);
                runtimeVolumeMounts.append(createRuntimeVolumeMount(USER_DATASETS_ROOT_PATH, volumeName, linkTarget));
                runtimeVolumes.append(createRuntimeVolume(volumeName, pvcInfo.get("pvcName")));
            }
        } catch (Exception e) {
            log.error("Error while populating user volume template: " + e.getMessage());
        }
        return Map.of(
                "runtimeVolumes", runtimeVolumes.toString(),
                "runtimeVolumeMounts", runtimeVolumeMounts.toString());
    }

    /**
     * Creates a runtime volume name based on the user name and index.
     *
     * @param userName The name of the user.
     * @param i The index of the volume.
     * @return The generated runtime volume name.
     */
    private static String createRuntimeVolumeName(String userName, int i) {
        return "runtime-volume-" + userName.replaceAll("[^0-9a-zA-Z-]", "-") + "-" + i;
    }

    /**
     * Creates a runtime volume definition.
     *
     * @param volumeName The name of the volume.
     * @param claimName The name of the persistent volume claim.
     * @return The generated runtime volume definition.
     */
    private static String createRuntimeVolume(String volumeName, String claimName) {
        return "      - name: " + volumeName + "\n" + "        persistentVolumeClaim:\n"
                + "         claimName: "
                + claimName + "\n";
    }

    /**
     * Creates a runtime volume mount definition.
     *
     * @param rootPath The root path for the mount.
     * @param volumeName The name of the volume.
     * @param linkTarget The target path for the mount.
     * @return The generated runtime volume mount definition.
     */
    private static String createRuntimeVolumeMount(String rootPath, String volumeName, String linkTarget) {
        String fullPath = Path.of(rootPath, linkTarget).toString();
        return "        - mountPath: \"" + fullPath + "\"\n" + "          name: " + volumeName + "\n";
    }

    /**
     * Retrieves the persistent volume claims (PVCs) for a user in a specific namespace.
     *
     * @param userName The name of the user.
     * @param namespace The namespace in which to search for PVCs.
     * @return A list of maps containing PVC information.
     * @throws IOException If there is an error during the command execution.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> getUserPvc(String userName, String namespace)
            throws IOException, InterruptedException {
        String[] getUserPvc = KubectlCommandBuilder.command("get")
                .namespace(namespace)
                .pvc()
                .label("username=" + userName)
                .json()
                .build();
        String pvcResult = CommandExecutioner.execute(getUserPvc);
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Map> pvcMap = objectMapper.readValue(pvcResult, Map.class);
        List<Map<String, Map>> pvcList = ((List<Map<String, Map>>) pvcMap.get("items"));
        if (pvcList == null || pvcList.isEmpty()) {
            log.error("No PVCs found for user: " + userName);
            return List.of();
        }
        return pvcList.stream()
                .filter(Objects::nonNull)
                .filter(pvc -> "Bound"
                        .equals(pvc.getOrDefault("status", new HashMap<>()).get("phase")))
                .map(pvc -> {
                    Map metadata = pvc.get("metadata");
                    String pvcName = metadata.get("name").toString();
                    Map<String, String> labels = (Map<String, String>) metadata.get("labels");
                    String linkTarget = labels.get("link_target");
                    if (null == linkTarget) return null;
                    return Map.of(
                            "pvcName", pvcName,
                            "linkTarget", linkTarget);
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
}
