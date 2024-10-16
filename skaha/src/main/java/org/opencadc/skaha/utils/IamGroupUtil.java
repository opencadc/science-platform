package org.opencadc.skaha.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class IamGroupUtil {
    public static final String SEPARATOR = ":";
    private final Map groupLcalQueueMap;

    public IamGroupUtil(String resourcePath) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        File yamlFile = new File(resourcePath);
        groupLcalQueueMap = mapper.readValue(yamlFile, Map.class);
    }
    public String getParentGroup(List<String> groups) {
        return "prototyping-groups/mini-src/platform-users";
    }

    public String getLocalQueue(String group, String jobType) {
        return (String) groupLcalQueueMap.get(group + SEPARATOR + jobType);
    }
}
