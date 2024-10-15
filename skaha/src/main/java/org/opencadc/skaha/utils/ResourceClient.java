package org.opencadc.skaha.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ResourceClient {
    private static final Logger log = Logger.getLogger(ResourceClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    static {
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    private <T> T deserialize(Class<T> className, String item) throws JsonProcessingException {
        try {
            return mapper.readValue(item, className);
        } catch (JsonProcessingException e) {
            log.error(e);
            throw e;
        }
    }

    public Resource getResourceByGroup(String clusterqueue) throws Exception {
        String[] getClusterQueue = new String[]{"kubectl", "get", "clusterqueues", clusterqueue, "-o", "jsonpath={.status.flavorsUsage}"};
        return getResourcesFromResourceFlavors(getClusterQueue);

    }

    public Resource getResourceLimitClusterQueue(String clusterqueue) throws Exception {
        String[] getClusterQueueResourceLimits = new String[]{"kubectl", "get", "clusterqueues", clusterqueue, "-o", "jsonpath={.spec.resourceGroups[*].flavors}"};
        return getResourcesFromResourceFlavors(getClusterQueueResourceLimits);

    }

    @SuppressWarnings("unchecked")
    private Resource getResourcesFromResourceFlavors(String[] command) throws Exception {
        String rawClusterQueueResources = CommandExecutioner.execute(command);
        if (rawClusterQueueResources.isEmpty()) throw new Exception("unable to find clusterqueue");
        List<LinkedHashMap<String, Object>> flavors = (List<LinkedHashMap<String, Object>>) deserialize(List.class, rawClusterQueueResources);
        return flavors.stream().map(this::getResourcesFromResourceFlavors).reduce(new Resource(), (Resource::add));
    }

    @SuppressWarnings("unchecked")
    private Resource getResourcesFromResourceFlavors(LinkedHashMap<String, Object> flavor) {
        Resource resource = new Resource();
        List<LinkedHashMap<String, Object>> flavorResources = (List<LinkedHashMap<String, Object>>) flavor.get("resources");
        flavorResources.forEach(flavorResource -> {
            String resourceType = flavorResource.get("name").toString();
            String resourceSize = flavorResource.get("nominalQuota") == null ?
                    flavorResource.get("total").toString() :
                    flavorResource.get("nominalQuota").toString();

            switch (resourceType) {
                case "cpu":
                    resource.setCpu(convertResourceSize(resourceSize));
                case "memory":
                    resource.setMemory(convertResourceSize(resourceSize));
                case "ephemeralStorage":
                    resource.setEphemeralStorage(convertResourceSize(resourceSize));
            }
        });
        return resource;
    }

    @SuppressWarnings("unchecked")
    public Resource getResourcesByUser(String uid) throws IOException, InterruptedException {
        String[] getPods = new String[]{"kubectl", "get", "pods", "-l", "canfar-net-userid=" + uid, "--field-selector=status.phase=Running", "-o", "jsonpath={range .items[*]}{.spec.containers[*].resources.limits}{\",\"}{end}"};

        String rawPodInfo = CommandExecutioner.execute(getPods);
        String podInfo = rawPodInfo.isEmpty() ? rawPodInfo : rawPodInfo.substring(0, rawPodInfo.length() - 1);
        List<LinkedHashMap<String, Object>> deserialize = (List<LinkedHashMap<String, Object>>) deserialize(List.class, "[" + podInfo + "]");
        return deserialize.stream().map(this::buildResourceFromMap).reduce(new Resource(), (Resource::add));
    }

    private Resource buildResourceFromMap(Map<String, Object> resource) {
        String cpu = (String) resource.get("cpu");
        String memory = (String) resource.get("memory");
        String ephemeralStorage = (String) resource.get("ephemeral-storage");
        return new Resource(convertResourceSize(cpu), convertResourceSize(ephemeralStorage), convertResourceSize(memory));
    }

    public float convertResourceSize(String resourceSize) {
        if (Character.isDigit(resourceSize.charAt(resourceSize.length() - 1))) {
            return Float.parseFloat(resourceSize);
        }

        String regex = "(\\d+)([KMG]i?|B)";

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex);
        java.util.regex.Matcher matcher = pattern.matcher(resourceSize);

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
            throw new IllegalArgumentException("Invalid memory format: " + resourceSize);
        }
    }

    public static void main(String[] args) throws Exception {
        ResourceClient rs = new ResourceClient();
        System.out.println(rs.getResourcesByUser("anuja").cpu());
        System.out.println(rs.getResourceLimitClusterQueue("test").cpu());
        System.out.println(rs.getResourceByGroup("test").memory());
    }
}

