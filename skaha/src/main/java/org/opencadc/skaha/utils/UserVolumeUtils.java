package org.opencadc.skaha.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencadc.skaha.K8SUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class UserVolumeUtils {

    private static final String USER_DATASETS_ROOT_PATH = K8SUtil.userDatasetsRootPath();

    public static void main(String[] args) throws IOException, InterruptedException {
        Map<String, String> userVolumeTemplate = populateUserVolumeTemplate("abhishek.ghosh", "skaha-workload");
        System.out.println(userVolumeTemplate.getOrDefault("runtimeVolumes", ""));
        System.out.println(userVolumeTemplate.getOrDefault("runtimeVolumeMounts", ""));
    }

    public static Map<String, String> populateUserVolumeTemplate(String userName, String namespace) throws IOException, InterruptedException {
        List<Map<String, String>> userRuntimePvcs = getUserPvc(userName, namespace);
        StringBuilder runtimeVolumes = new StringBuilder();
        StringBuilder runtimeVolumeMounts = new StringBuilder();
        for (int i = 0; i < userRuntimePvcs.size(); i++) {
            Map<String, String> pvcInfo = userRuntimePvcs.get(i);
            String volumeName = "runtime-volume-" + userName + "-" + i;
            runtimeVolumeMounts.append(createRuntimeVolumeMount(USER_DATASETS_ROOT_PATH, volumeName, pvcInfo.get("linkTarget")));
            runtimeVolumes.append(createRuntimeVolume(volumeName, pvcInfo.get("pvcName")));
        }
        return Map.of(
                "runtimeVolumes", runtimeVolumes.toString(),
                "runtimeVolumeMounts", runtimeVolumeMounts.toString()
        );
    }

    private static String createRuntimeVolume(String volumeName, String claimName) {
        return "      - name: " + volumeName + "\n" +
                "        persistentVolumeClaim:\n" +
                "          name: " + claimName + "\n";
    }

    private static String createRuntimeVolumeMount(String rootPath, String volumeName, String linkTarget) {
        String fullPath = Path.of(rootPath, linkTarget).toString();
        return "        - mountPath: \"" + fullPath + "\"\n" +
                "          name: " + volumeName + "\n" +
                "          subPath: " + linkTarget + "\n";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> getUserPvc(String userName, String namespace) throws IOException, InterruptedException {
        String[] getUserPvc = KubectlCommandBuilder.command("get")
                .namespace(namespace)
                .pvc()
                .label("username=" + userName)
                .json().build();
        String pvcResult = CommandExecutioner.execute(getUserPvc);
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Map> pvcMap = objectMapper.readValue(pvcResult, Map.class);
        List<Map<String, Map>> pvcList = ((List<Map<String, Map>>) pvcMap.get("items"));
        return pvcList.stream()
                .filter(Objects::nonNull)
                .filter(pvc -> "Bound".equals(pvc.getOrDefault("status", new HashMap<>()).get("phase")))
                .map(pvc -> {
                    Map metadata = pvc.get("metadata");
                    String pvcName = metadata.get("name").toString();
                    Map<String, String> labels = (Map<String, String>) metadata.get("labels");
                    String linkTarget = labels.get("link-target");
                    if (null == linkTarget) return null;
                    return Map.of(
                            "pvcName", pvcName,
                            "linkTarget", linkTarget
                    );
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
}
