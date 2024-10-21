package org.opencadc.skaha.utils;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

public class QueueUtilTest {

    @Test
    public void testGetLocalQueue() throws IOException, InterruptedException {
        try (MockedStatic<CommandExecutioner> mockedStatic = Mockito.mockStatic(CommandExecutioner.class)) {

            List<String> groupNames = List.of("skaha-workload-queue-headless");
            String jobType = "headless";
            String[] localQueues = {
                    "skaha-workload-queue-headless::headless",
                    "skaha-workload-queue-interactive::carta,notebook,desktop,contributed,desktop-app"
            };

            // Set up expectations
            mockedStatic.when(() -> CommandExecutioner.execute(any(String[].class), eq(false)))
                    .thenReturn(String.join("\n", localQueues));

            String actualLocalQueue = QueueUtil.getLocalQueue(groupNames, jobType);

            assertEquals("skaha-workload-queue-headless", actualLocalQueue);
        }


    }
}
