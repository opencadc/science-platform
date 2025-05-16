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
    @Test
    public void testPopulateUserVolumeTemplate() throws Exception {
        String userName = "testUser";
        String namespace = "testNamespace";

        String mockPvcResult =
                "{ \"items\": [ { \"metadata\": { \"name\": \"pvc1\", \"labels\": { \"link_target\": \"target1\" } }, \"status\": { \"phase\": \"Bound\" } } ] }";

        try (MockedStatic<CommandExecutioner> mockedStatic = mockStatic(CommandExecutioner.class)) {
            mockedStatic
                    .when(() -> CommandExecutioner.execute(any(String[].class)))
                    .thenReturn(mockPvcResult);

            Map<String, String> result = UserVolumeUtils.populateUserVolumeTemplate(userName, namespace);

            String expectedVolumes =
                    "      - name: runtime-volume-testUser-0\n        persistentVolumeClaim:\n         claimName: pvc1\n";
            String expectedVolumeMounts = "        - mountPath: \"" + K8SUtil.userDatasetsRootPath()
                    + "/target1\"\n          name: runtime-volume-testUser-0\n";

            Assert.assertNotNull(result);
            Assert.assertTrue(result.containsKey("runtimeVolumes"));
            Assert.assertTrue(result.containsKey("runtimeVolumeMounts"));
            Assert.assertEquals(expectedVolumes, result.get("runtimeVolumes"));
            Assert.assertEquals(expectedVolumeMounts, result.get("runtimeVolumeMounts"));

            mockedStatic.verify(() -> CommandExecutioner.execute(new String[] {
                "kubectl", "get", "--namespace", namespace, "pvc", "-l", "username=testUser", "-o", "json"
            }));
        }
    }

    @Test
    public void testPopulateUserVolumeTemplateWithNoPvc() throws Exception {
        String userName = "testUser";
        String namespace = "testNamespace";

        String mockPvcResult = "{ \"items\": [] }";

        try (MockedStatic<CommandExecutioner> mockedStatic = mockStatic(CommandExecutioner.class)) {
            mockedStatic
                    .when(() -> CommandExecutioner.execute(any(String[].class)))
                    .thenReturn(mockPvcResult);

            Map<String, String> result = UserVolumeUtils.populateUserVolumeTemplate(userName, namespace);

            Assert.assertNotNull(result);
            Assert.assertTrue(result.containsKey("runtimeVolumes"));
            Assert.assertTrue(result.containsKey("runtimeVolumeMounts"));
            Assert.assertEquals("", result.get("runtimeVolumes"));
            Assert.assertEquals("", result.get("runtimeVolumeMounts"));

            mockedStatic.verify(() -> CommandExecutioner.execute(new String[] {
                "kubectl", "get", "--namespace", namespace, "pvc", "-l", "username=testUser", "-o", "json"
            }));
        }
    }

    @Test
    public void testPopulateUserVolumeTemplateWithException() throws Exception {
        String userName = "testUser";
        String namespace = "testNamespace";

        try (MockedStatic<CommandExecutioner> mockedStatic = mockStatic(CommandExecutioner.class)) {
            mockedStatic
                    .when(() -> CommandExecutioner.execute(any(String[].class)))
                    .thenThrow(new RuntimeException("Test exception"));

            Map<String, String> result = UserVolumeUtils.populateUserVolumeTemplate(userName, namespace);

            Assert.assertNotNull(result);
            Assert.assertTrue(result.containsKey("runtimeVolumes"));
            Assert.assertTrue(result.containsKey("runtimeVolumeMounts"));
            Assert.assertEquals("", result.get("runtimeVolumes"));
            Assert.assertEquals("", result.get("runtimeVolumeMounts"));

            mockedStatic.verify(() -> CommandExecutioner.execute(new String[] {
                "kubectl", "get", "--namespace", namespace, "pvc", "-l", "username=testUser", "-o", "json"
            }));
        }
    }
}
