package org.opencadc.skaha.utils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

import java.util.Map;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.opencadc.skaha.K8SUtil;

@RunWith(MockitoJUnitRunner.class)
public class UserVolumeUtilsTest {
    private static final String USERNAME = "testUser";
    private static final String NAMESPACE = "testNamespace";

    @Test
    public void testPopulateUserVolumeTemplate() throws Exception {
        TestUtils.setEnv("USER_DATASETS_ROOT_PATH", "/datasets");

        String mockPvcResult =
                "{ \"items\": [ { \"metadata\": { \"name\": \"pvc1\", \"labels\": { \"link_target\": \"target1\" } }, \"status\": { \"phase\": \"Bound\" } } ] }";

        try (MockedStatic<CommandExecutioner> mockedStatic = mockStatic(CommandExecutioner.class)) {
            mockedStatic
                    .when(() -> CommandExecutioner.execute(any(String[].class)))
                    .thenReturn(mockPvcResult);

            Map<String, String> result = UserVolumeUtils.populateUserVolumeTemplate(USERNAME, NAMESPACE);

            String expectedVolumes =
                    "      - name: runtime-volume-testUser-0\n        persistentVolumeClaim:\n         claimName: pvc1\n";
            String expectedVolumeMounts = "        - mountPath: \"" + K8SUtil.userDatasetsRootPath()
                    + "/target1\"\n          name: runtime-volume-testUser-0\n";

            Assert.assertNotNull(result);
            Assert.assertTrue(result.containsKey("runtimeVolumes"));
            Assert.assertTrue(result.containsKey("runtimeVolumeMounts"));
            Assert.assertEquals(expectedVolumes, result.get("runtimeVolumes"));
            Assert.assertEquals(expectedVolumeMounts, result.get("runtimeVolumeMounts"));

            verifyKubectlCommand(mockedStatic);
        }
    }

    @Test
    public void testPopulateUserVolumeTemplateWithNoPvc() throws Exception {
        String mockPvcResult = "{ \"items\": [] }";

        try (MockedStatic<CommandExecutioner> mockedStatic = mockStatic(CommandExecutioner.class)) {
            mockedStatic
                    .when(() -> CommandExecutioner.execute(any(String[].class)))
                    .thenReturn(mockPvcResult);

            Map<String, String> result = UserVolumeUtils.populateUserVolumeTemplate(USERNAME, NAMESPACE);

            verifyEmptyResults(result);
            verifyKubectlCommand(mockedStatic);
        }
    }

    @Test
    public void testPopulateUserVolumeTemplateWithException() throws Exception {
        try (MockedStatic<CommandExecutioner> mockedStatic = mockStatic(CommandExecutioner.class)) {
            mockedStatic
                    .when(() -> CommandExecutioner.execute(any(String[].class)))
                    .thenThrow(new RuntimeException("Test exception"));

            Map<String, String> result = UserVolumeUtils.populateUserVolumeTemplate(USERNAME, NAMESPACE);

            verifyEmptyResults(result);
            verifyKubectlCommand(mockedStatic);
        }
    }

    private void verifyEmptyResults(Map<String, String> result) {
        Assert.assertNotNull(result);
        Assert.assertTrue(result.containsKey("runtimeVolumes"));
        Assert.assertTrue(result.containsKey("runtimeVolumeMounts"));
        Assert.assertEquals("", result.get("runtimeVolumes"));
        Assert.assertEquals("", result.get("runtimeVolumeMounts"));
    }

    private void verifyKubectlCommand(MockedStatic<CommandExecutioner> mockedStatic) {
        mockedStatic.verify(() -> CommandExecutioner.execute(
                new String[] {"kubectl", "get", "--namespace", NAMESPACE, "pvc", "-l", "username=testUser", "-o", "json"
                }));
    }
}
