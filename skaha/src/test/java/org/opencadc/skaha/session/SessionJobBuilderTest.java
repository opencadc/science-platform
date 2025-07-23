package org.opencadc.skaha.session;

import ca.nrc.cadc.util.FileUtil;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobSpec;
import io.kubernetes.client.openapi.models.V1NodeAffinity;
import io.kubernetes.client.openapi.models.V1NodeSelectorRequirement;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.util.Yaml;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.apache.commons.lang3.RandomStringUtils;
import org.jetbrains.annotations.NotNull;
import org.junit.Assert;
import org.junit.Test;

public class SessionJobBuilderTest {
    @Test
    public void testParsing() throws Exception {
        final Path testBaseValuesPath = FileUtil.getFileFromResource(
                        "test-base-values.yaml", SessionJobBuilderTest.class)
                .toPath();
        final String fileContent = Files.readString(testBaseValuesPath);

        final Map<String, String> parametersToReplaceValues = new HashMap<>();
        final String[] parametersToReplace = new String[] {
            PostAction.SKAHA_SESSIONID,
            PostAction.SKAHA_HOSTNAME,
            PostAction.SKAHA_SESSIONTYPE,
            PostAction.SKAHA_POSIXID
        };

        for (final String param : parametersToReplace) {
            Assert.assertTrue("Test file is missing required field.", fileContent.contains(param));
            parametersToReplaceValues.put(param, RandomStringUtils.secure().nextAlphanumeric(12));
        }

        SessionJobBuilder testSubject =
                SessionJobBuilder.fromPath(testBaseValuesPath).withParameters(parametersToReplaceValues);
        final String output = testSubject.build();

        for (final Map.Entry<String, String> entry : parametersToReplaceValues.entrySet()) {
            Assert.assertFalse("Entry not replaced.", output.contains(entry.getKey()));
            Assert.assertTrue("Value not injected into file.", output.contains(entry.getValue()));
        }

        V1Job job = (V1Job) Yaml.load(output);
        V1PodSpec podSpec = Objects.requireNonNull(job.getSpec()).getTemplate().getSpec();
        Assert.assertNotNull("PodSpec should not be null", podSpec);

        // Verify that the image pull secrets are not set.  Version 21.0+ of the Skaha API returns null for no secrets,
        // but
        // 24.0.0 returns an empty list.
        Assert.assertTrue(
                "PodSpec should not have image pull secrets",
                (podSpec.getImagePullSecrets() == null
                        || podSpec.getImagePullSecrets().isEmpty()));
    }

    private Map<String, String> loadParametersFromFile(final Path testBaseValuesPath) throws Exception {
        final String fileContent = Files.readString(testBaseValuesPath);
        final Map<String, String> parametersToReplaceValues = new HashMap<>();
        final String[] parametersToReplace = new String[] {PostAction.SKAHA_SESSIONID};
        for (final String param : parametersToReplace) {
            Assert.assertTrue("Test file is missing required field.", fileContent.contains(param));
            parametersToReplaceValues.put(param, RandomStringUtils.secure().nextAlphanumeric(12));
        }

        return parametersToReplaceValues;
    }

    private Path getTestFilePath(final String jobFileName) {
        return FileUtil.getFileFromResource(jobFileName, SessionJobBuilderTest.class).toPath();
    }

    private V1Job getTestBaseValuesAffinityJob() throws Exception {
        final Path testBaseValuesPath = getTestFilePath("test-base-values-affinity.yaml");
        final Map<String, String> parametersToReplaceValues = loadParametersFromFile(testBaseValuesPath);

        final SessionJobBuilder testSubject = SessionJobBuilder.fromPath(testBaseValuesPath)
                .withGPUEnabled(true)
                .withParameters(parametersToReplaceValues)
                .withImageSecret("my-secret")
                .withGPUCount(2);
        final String output = testSubject.build();

        for (final Map.Entry<String, String> entry : parametersToReplaceValues.entrySet()) {
            Assert.assertFalse("Entry not replaced.", output.contains(entry.getKey()));
            Assert.assertTrue("Value not injected into file.", output.contains(entry.getValue()));
        }

        return (V1Job) Yaml.load(output);
    }

    @Test
    public void testWithAffinityMerging() throws Exception {
        final V1Job job = getTestBaseValuesAffinityJob();
        final V1PodSpec podSpec =
                Objects.requireNonNull(job.getSpec()).getTemplate().getSpec();
        Assert.assertNotNull("PodSpec should not be null", podSpec);
        final List<V1NodeSelectorRequirement> testMatchExpressions = getV1NodeSelectorRequirements(podSpec);

        Assert.assertEquals(
                "Wrong pull secret.",
                "my-secret",
                Objects.requireNonNull(podSpec.getImagePullSecrets()).getFirst().getName());

        final V1NodeSelectorRequirement gpuRequirement = new V1NodeSelectorRequirement();
        gpuRequirement.setKey("nvidia.com/gpu.count");
        gpuRequirement.setOperator("Gt");
        gpuRequirement.setValues(Collections.singletonList("0"));

        final V1NodeSelectorRequirement providedRequirement = new V1NodeSelectorRequirement();
        providedRequirement.setKey("my-node-please");
        providedRequirement.setOperator("Exists");

        Assert.assertTrue("Missing GPU required match expression.", testMatchExpressions.contains(gpuRequirement));
        Assert.assertTrue(
                "Missing provided (custom) required match expression.",
                testMatchExpressions.contains(providedRequirement));
    }

    @Test
    public void testWithQueueMerging() throws Exception {
        final Path testBaseValuesPath = getTestFilePath("test-base-values-queue.yaml");
        final Map<String, String> parametersToReplaceValues = loadParametersFromFile(testBaseValuesPath);

        final SessionJobBuilder testSubject = SessionJobBuilder.fromPath(testBaseValuesPath)
                .withParameters(parametersToReplaceValues)
                .withQueue(new QueueConfiguration("notebook", "high", "my-queue"));
        final String output = testSubject.build();

        for (final Map.Entry<String, String> entry : parametersToReplaceValues.entrySet()) {
            Assert.assertFalse("Entry not replaced.", output.contains(entry.getKey()));
            Assert.assertTrue("Value not injected into file.", output.contains(entry.getValue()));
        }

        final V1Job job = (V1Job) Yaml.load(output);
        final V1JobSpec jobSpec = Objects.requireNonNull(job.getSpec());
        Assert.assertEquals("Job should be suspended.", Boolean.TRUE, jobSpec.getSuspend());

        final V1ObjectMeta metadata = Objects.requireNonNull(job.getMetadata());
        Assert.assertEquals(
                "Wrong queue name.",
                "my-queue",
                Objects.requireNonNull(metadata.getLabels()).get(SessionJobBuilder.JOB_QUEUE_LABEL_KEY));
        Assert.assertEquals(
                "Wrong priority class.",
                "high",
                Objects.requireNonNull(metadata.getLabels()).get(SessionJobBuilder.JOB_PRIORITY_CLASS_LABEL_KEY));
    }

    @NotNull private static List<V1NodeSelectorRequirement> getV1NodeSelectorRequirements(V1PodSpec podSpec) {
        assert podSpec != null;
        final V1NodeAffinity nodeAffinity =
                Objects.requireNonNull(podSpec.getAffinity()).getNodeAffinity();

        final List<V1NodeSelectorRequirement> testMatchExpressions = new ArrayList<>();
        assert nodeAffinity != null;
        final List<V1NodeSelectorRequirement> matchExpressions = Objects.requireNonNull(
                        Objects.requireNonNull(nodeAffinity).getRequiredDuringSchedulingIgnoredDuringExecution())
                .getNodeSelectorTerms()
                .getFirst()
                .getMatchExpressions();

        if (matchExpressions != null) {
            testMatchExpressions.addAll(matchExpressions);
        }
        return testMatchExpressions;
    }
}
