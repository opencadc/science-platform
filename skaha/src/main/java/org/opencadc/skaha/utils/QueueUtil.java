package org.opencadc.skaha.utils;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class QueueUtil {
    private static final Logger log = Logger.getLogger(QueueUtil.class);

    public static String getLocalQueue(List<String> groupNames, String jobType) throws IOException, InterruptedException, RuntimeException {
        List<String> localQueues = new ArrayList<>();
        for (String groupName : groupNames) {
            String localQueue = getLocalQueueByGroupAndJobType(groupName, jobType);
            if (localQueue != null) localQueues.add(localQueue);
        }
        log.debug("primary group size for the user is : " + localQueues.size());
        if (localQueues.size() > 1) throw new RuntimeException("More than one LocalQueue for an user is Unsupported");
        if (localQueues.isEmpty()) throw new RuntimeException("No LocalQueue available");
        return localQueues.get(0);
    }

    private static String getLocalQueueByGroupAndJobType(String groupName, String jobType) throws IOException, InterruptedException {
        String[] cmd = KubectlCommandBuilder.command("get")
                .argument("-A")
                .argument("localQueue")
                .outputFormat("jsonpath={range .items[?(@.metadata.annotations.group==\"" + groupName + "\")]}{.metadata.name}::{.metadata.annotations.jobType}{\"\\n\"}{end}")
                .build();

        try {
            String allLocalQueuesOutput = CommandExecutioner.execute(cmd, false);
            log.debug("all local queues for the group " + groupName + " is : " + allLocalQueuesOutput);
            if (allLocalQueuesOutput.isEmpty()) return null;
            String[] allLocalQueues = allLocalQueuesOutput.split("\n");
            List<String> localQueues = Arrays.stream(allLocalQueues)
                    .map(localQueue -> findLocalQueueByJobType(jobType, localQueue))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            if (localQueues.size() > 1)
                throw new RuntimeException("More than one LocalQueue for a group is Unsupported");
            return localQueues.get(0);
        } catch (IOException | InterruptedException ex) {
            log.error(ex);
            throw ex;
        }
    }

    private static String findLocalQueueByJobType(String jobType, String localQueue) {
        String[] localQueueParts = localQueue.split("::");
        if (localQueueParts.length <= 1) return null;
        String localQueueName = localQueueParts[0];
        String[] allJobTypes = localQueueParts[1].split(",");
        for (String j : allJobTypes) if (jobType.equals(j)) return localQueueName;
        return null;
    }
}
