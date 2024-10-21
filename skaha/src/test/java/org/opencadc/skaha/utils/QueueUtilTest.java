package org.opencadc.skaha.utils;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
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

            mockedStatic.when(() -> CommandExecutioner.execute(any(String[].class), eq(false)))
                    .thenReturn(String.join("\n", localQueues));

            String actualLocalQueue = QueueUtil.getLocalQueue(groupNames, jobType);

            assertEquals("skaha-workload-queue-headless", actualLocalQueue);
        }

    }

    @Test
    public void testShouldThrowExceptionIfNoLocalqueueIsAvailable() {
        try (MockedStatic<CommandExecutioner> mockedStatic = Mockito.mockStatic(CommandExecutioner.class)) {

            List<String> groupNames = List.of("skaha-workload-queue-headless");
            String jobType = "headless";

            mockedStatic.when(() -> CommandExecutioner.execute(any(String[].class), eq(false)))
                    .thenReturn("");

            RuntimeException e = assertThrows(RuntimeException.class, ()-> QueueUtil.getLocalQueue(groupNames, jobType));
            assertEquals(e.getMessage(), "No LocalQueue available");
        }
    }

    @Test
    public void testShouldThrowExceptionForMultipleLocalQueuesForGroup() {
        try (MockedStatic<CommandExecutioner> mockedStatic = Mockito.mockStatic(CommandExecutioner.class)) {

            List<String> groupNames = List.of("skaha-workload-queue-headless");
            String jobType = "headless";
            String[] localQueues = {
                    "skaha-workload-queue-headless::headless",
                    "skaha-workload-queue-interactive::carta,notebook,desktop,contributed,desktop-app",
                    "skaha-workload-queue-headless-another::headless"
            };

            mockedStatic.when(() -> CommandExecutioner.execute(any(String[].class), eq(false)))
                    .thenReturn(String.join("\n", localQueues));

            RuntimeException e = assertThrows(RuntimeException.class, ()-> QueueUtil.getLocalQueue(groupNames, jobType));
            assertEquals(e.getMessage(), "More than one LocalQueue for a group is Unsupported");
        }
    }
}
