package org.opencadc.skaha.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class ResourceClient {
    private static final Logger log = Logger.getLogger(ResourceClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    static {
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    private <T> T deserialize(Class<T> className, String item) {
        try {
            return mapper.readValue(item, className);
        } catch (JsonProcessingException e) {
            log.error(e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public Resource getResourceByGroup(String clusterqueue) throws Exception {
        String[] getClusterQueue = new String[]{
                "kubectl", "get", "clusterqueues", clusterqueue, "-o", "jsonpath={.status.flavorsUsage}"};
        String rawClusterQueueInfo = CommandExecutioner.execute(getClusterQueue);
        if (rawClusterQueueInfo.isEmpty()) throw new Exception("unable to find clusterqueue");
        List<LinkedHashMap<String, Object>> flavors = (List<LinkedHashMap<String, Object>>) deserialize(List.class, rawClusterQueueInfo);
        return flavors.stream().map(this::getResourcesFromResourceFlavors)
                .reduce(new Resource(), (Resource::add));

    }

    @SuppressWarnings("unchecked")
    private Resource getResourcesFromResourceFlavors(LinkedHashMap<String, Object> flavor) {
        Resource resource = new Resource();
        List<LinkedHashMap<String, Object>> flavorResources = (List<LinkedHashMap<String, Object>>) flavor.get("resources");
        flavorResources.forEach(flavorResource -> {
            String resourceType = (String) flavorResource.get("name");
            String resourceSize = (String) flavorResource.get("nominalQuota");
            switch (resourceType) {
                case "cpu":
                    resource.setCpu(convertResourceSizeToMb(resourceSize));
                case "memory":
                    resource.setMemory(convertResourceSizeToMb(resourceSize));
                case "ephemeralStorage":
                    resource.setEphemeralStorage(convertResourceSizeToMb(resourceSize));
            }
        });
        return resource;
    }

    @SuppressWarnings("unchecked")
    public Resource getResourcesByUser(String uid) throws IOException, InterruptedException {
        String[] getPods = new String[]{
                "kubectl", "get", "pods", "-l", "canfar-net-userid=" + uid, "--field-selector=status.phase=Running", "-o", "jsonpath={range .items[*]}{.spec.containers[*].resources.limits}{\",\"}{end}"};

        String rawPodInfo = CommandExecutioner.execute(getPods);
        String podInfo = rawPodInfo.isEmpty() ? rawPodInfo : rawPodInfo.substring(0, rawPodInfo.length() - 1);
        List<LinkedHashMap<String, Object>> deserialize = (List<LinkedHashMap<String, Object>>) deserialize(List.class, "[" + podInfo + "]");
        return deserialize.stream()
                .map(this::buildResourceFromMap)
                .reduce(new Resource(), (Resource::add));
    }

    private Resource buildResourceFromMap(Map<String, Object> resource) {
        String cpu = (String) resource.get("cpu");
        String memory = (String) resource.get("memory");
        String ephemeralStorage = (String) resource.get("ephemeral-storage");
        return new Resource(convertResourceSizeToMb(cpu), convertResourceSizeToMb(ephemeralStorage), convertResourceSizeToMb(memory));
    }

    // TODO: Verify whether cpu will have unit or not.
    public float convertResourceSizeToMb(String size) {
        String regex = "(\\d+)([KMGm]i?|B)";

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex);
        java.util.regex.Matcher matcher = pattern.matcher(size);
        if (matcher.matches()) {
            float value = Float.parseFloat(matcher.group(1));
            String unit = matcher.group(2);

            switch (unit) {
                case "Ki":
                case "KiB":
                    return value / 1024;
                case "Mi":
                case "MiB":
                case "M":
                case "m":
                    return value;
                case "Gi":
                case "GiB":
                    return value * 1024;
                case "K":
                    return value / 1000;
                case "G":
                    return value * 1000;
                default:
                    throw new IllegalArgumentException("Unsupported memory unit: " + unit);
            }
        } else {
            throw new IllegalArgumentException("Invalid memory format: " + size);
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        ResourceClient rs = new ResourceClient();
        System.out.println(rs.getResourcesByUser("anuja").cpu());
    }
}

