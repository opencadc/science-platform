package org.opencadc.skaha.session;

import ca.nrc.cadc.util.FileUtil;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobSpec;
import io.kubernetes.client.openapi.models.V1NodeAffinity;
import io.kubernetes.client.openapi.models.V1NodeSelectorRequirement;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1Service;
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
    public void buildAppliesCanonicalLabelsToJobAndPodTemplateMetadata() throws Exception {
        final Path testBaseValuesPath = FileUtil.getFileFromResource(
                        "test-base-values-queue.yaml", SessionJobBuilderTest.class)
                .toPath();
        final Map<String, String> parametersToReplaceValues = new HashMap<>();
        parametersToReplaceValues.put(PostAction.SKAHA_SESSIONID, "session-123");
        parametersToReplaceValues.put(PostAction.SKAHA_SESSIONNAME, "analysis");
        parametersToReplaceValues.put(PostAction.SKAHA_SESSIONTYPE, "notebook");
        parametersToReplaceValues.put(PostAction.SKAHA_USERID, "alice");
        parametersToReplaceValues.put(PostAction.SKAHA_JOBNAME, "notebook-alice-session-123");

        final SessionJobBuilder testSubject = SessionJobBuilder.fromPath(testBaseValuesPath)
                .withParameters(parametersToReplaceValues)
                .withGPUEnabled(true)
                .withGPUCount(2)
                .withQueue(new QueueConfiguration("notebook", "high", "my-queue"));

        final V1Job job = (V1Job) Yaml.load(testSubject.build());
        final Map<String, String> jobLabels =
                Objects.requireNonNull(job.getMetadata().getLabels());
        final Map<String, String> podLabels =
                Objects.requireNonNull(job.getSpec().getTemplate().getMetadata().getLabels());

        assertCanonicalSessionLabels(jobLabels);
        assertCanonicalSessionLabels(podLabels);
        Assert.assertEquals("my-queue", jobLabels.get("kueue.x-k8s.io/queue-name"));
        Assert.assertEquals("high", jobLabels.get("kueue.x-k8s.io/priority-class"));
        Assert.assertEquals("job", jobLabels.get("template-owned"));
        Assert.assertEquals("pod", podLabels.get("template-owned"));
        Assert.assertFalse(podLabels.containsKey("kueue.x-k8s.io/queue-name"));
        Assert.assertFalse(podLabels.containsKey("kueue.x-k8s.io/priority-class"));
        Assert.assertTrue(jobLabels.keySet().containsAll(canonicalSessionLabelKeys()));
        Assert.assertTrue(podLabels.keySet().containsAll(canonicalSessionLabelKeys()));
    }

    @Test
    public void testParsing() throws Exception {
        final Path testBaseValuesPath = FileUtil.getFileFromResource(
                        "test-base-values.yaml", SessionJobBuilderTest.class)
                .toPath();
        final String fileContent = Files.readString(testBaseValuesPath);

        final Map<String, String> parametersToReplaceValues = new HashMap<>();
        final String[] parametersToReplace =
                new String[] {PostAction.SKAHA_SESSIONID, PostAction.SKAHA_HOSTNAME, PostAction.SKAHA_POSIXID};

        for (final String param : parametersToReplace) {
            Assert.assertTrue("Test file is missing required field.", fileContent.contains(param));
            parametersToReplaceValues.put(param, RandomStringUtils.secure().nextAlphanumeric(12));
        }
        parametersToReplaceValues.put(PostAction.SKAHA_USERID, "alice");
        parametersToReplaceValues.put(PostAction.SKAHA_SESSIONNAME, "analysis");
        parametersToReplaceValues.put(PostAction.SKAHA_SESSIONTYPE, "notebook");
        parametersToReplaceValues.put(PostAction.SKAHA_JOBNAME, "notebook-alice-session-123");

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
        Assert.assertTrue(
                "PodSpec should not have image pull secrets",
                Objects.requireNonNull(podSpec.getImagePullSecrets()).isEmpty());
        Assert.assertFalse(
                "Wrong GPU limit.",
                job.getSpec()
                        .getTemplate()
                        .getSpec()
                        .getContainers()
                        .get(0)
                        .getResources()
                        .getLimits()
                        .containsKey("nvidia.com/gpu"));

        // GPU requests should also not be set when GPUs are not enabled.
        if (Objects.requireNonNull(job.getSpec())
                        .getTemplate()
                        .getSpec()
                        .getContainers()
                        .get(0)
                        .getResources()
                        .getRequests()
                != null) {
            Assert.assertFalse(
                    "Wrong GPU request.",
                    job.getSpec()
                            .getTemplate()
                            .getSpec()
                            .getContainers()
                            .get(0)
                            .getResources()
                            .getRequests()
                            .containsKey("nvidia.com/gpu"));
        }
    }

    private void testBaseValuesAffinityJob(final int gpuCount) throws Exception {
        final Path testBaseValuesPath = FileUtil.getFileFromResource(
                        "test-base-values-affinity.yaml", SessionJobBuilderTest.class)
                .toPath();
        final Map<String, String> parametersToReplaceValues = new HashMap<>();
        commonValues(testBaseValuesPath, parametersToReplaceValues);

        SessionJobBuilder testSubject = SessionJobBuilder.fromPath(testBaseValuesPath)
                .withParameters(parametersToReplaceValues)
                .withImageSecret("my-secret");

        if (gpuCount > 0) {
            testSubject = testSubject.withGPUEnabled(true).withGPUCount(gpuCount);
        }

        final String output = testSubject.build();

        for (final Map.Entry<String, String> entry : parametersToReplaceValues.entrySet()) {
            Assert.assertFalse("Entry not replaced.", output.contains(entry.getKey()));
            Assert.assertTrue("Value not injected into file.", output.contains(entry.getValue()));
        }

        final V1Job job = (V1Job) Yaml.load(output);
        if (gpuCount > 0) {
            Assert.assertEquals(
                    "Wrong GPU limit.",
                    gpuCount,
                    job.getSpec()
                            .getTemplate()
                            .getSpec()
                            .getContainers()
                            .get(0)
                            .getResources()
                            .getLimits()
                            .get("nvidia.com/gpu")
                            .getNumber()
                            .intValue());

            // When GPUs are requested, the GPU requests should match the limits.
            Assert.assertEquals(
                    "Wrong GPU request.",
                    gpuCount,
                    Objects.requireNonNull(job.getSpec())
                            .getTemplate()
                            .getSpec()
                            .getContainers()
                            .get(0)
                            .getResources()
                            .getRequests()
                            .get("nvidia.com/gpu")
                            .getNumber()
                            .intValue());
        } else {
            Assert.assertNull(
                    "GPU limit should be null.",
                    job.getSpec()
                            .getTemplate()
                            .getSpec()
                            .getContainers()
                            .get(0)
                            .getResources()
                            .getLimits()
                            .get("nvidia.com/gpu"));

            if (Objects.requireNonNull(job.getSpec())
                            .getTemplate()
                            .getSpec()
                            .getContainers()
                            .get(0)
                            .getResources()
                            .getRequests()
                    != null) {
                Assert.assertFalse(
                        "GPU request should be absent.",
                        job.getSpec()
                                .getTemplate()
                                .getSpec()
                                .getContainers()
                                .get(0)
                                .getResources()
                                .getRequests()
                                .containsKey("nvidia.com/gpu"));
            }
        }

        Assert.assertEquals(
                "Wrong fixed label",
                "fixed",
                Objects.requireNonNull(job.getMetadata().getLabels()).get(SessionLabels.Key.FLAVOR.label()));

        final V1PodSpec podSpec =
                Objects.requireNonNull(job.getSpec()).getTemplate().getSpec();
        Assert.assertNotNull("PodSpec should not be null", podSpec);
        final List<V1NodeSelectorRequirement> testMatchExpressions = getV1NodeSelectorRequirements(podSpec);

        Assert.assertEquals(
                "Wrong pull secret.",
                "my-secret",
                Objects.requireNonNull(podSpec.getImagePullSecrets()).get(0).getName());

        final V1NodeSelectorRequirement gpuRequirement = new V1NodeSelectorRequirement();
        gpuRequirement.setKey("nvidia.com/gpu.count");
        gpuRequirement.setOperator("Gt");
        gpuRequirement.setValues(Collections.singletonList("0"));

        final V1NodeSelectorRequirement providedRequirement = new V1NodeSelectorRequirement();
        providedRequirement.setKey("my-node-please");
        providedRequirement.setOperator("Exists");

        if (gpuCount > 0) {
            Assert.assertTrue("Missing GPU required match expression.", testMatchExpressions.contains(gpuRequirement));
        } else {
            Assert.assertFalse("GPU should be missing.", testMatchExpressions.contains(gpuRequirement));
        }
        Assert.assertTrue(
                "Missing provided (custom) required match expression.",
                testMatchExpressions.contains(providedRequirement));
    }

    @Test
    public void testWithAffinityMergingNoGPUs() throws Exception {
        testBaseValuesAffinityJob(0);
    }

    @Test
    public void testWithAffinityMerging() throws Exception {
        testBaseValuesAffinityJob(2);
    }

    @Test
    public void testWithQueueMerging() throws Exception {
        final Path testBaseValuesPath = FileUtil.getFileFromResource(
                        "test-base-values-queue.yaml", SessionJobBuilderTest.class)
                .toPath();
        final Map<String, String> parametersToReplaceValues = new HashMap<>();
        commonValues(testBaseValuesPath, parametersToReplaceValues);

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
        Assert.assertEquals(
                "Wrong flex label",
                "flexible",
                Objects.requireNonNull(metadata.getLabels()).get(SessionLabels.Key.FLAVOR.label()));
    }

    @Test
    public void serviceMetadataLabelsMergeCanonicalJobLabelsWithoutKueue() throws Exception {
        final Path testBaseValuesPath = FileUtil.getFileFromResource(
                        "test-base-values-queue.yaml", SessionJobBuilderTest.class)
                .toPath();
        final Map<String, String> parametersToReplaceValues = new HashMap<>();
        parametersToReplaceValues.put(PostAction.SKAHA_SESSIONID, "session-123");
        parametersToReplaceValues.put(PostAction.SKAHA_SESSIONNAME, "analysis");
        parametersToReplaceValues.put(PostAction.SKAHA_SESSIONTYPE, "carta");
        parametersToReplaceValues.put(PostAction.SKAHA_USERID, "alice");
        parametersToReplaceValues.put(PostAction.SKAHA_JOBNAME, "carta-alice-session-123");

        final String jobYaml = SessionJobBuilder.fromPath(testBaseValuesPath)
                .withParameters(parametersToReplaceValues)
                .withQueue(new QueueConfiguration("carta", "high", "my-queue"))
                .build();
        String serviceString =
                Files.readString(FileUtil.getFileFromResource("test-carta-service.yaml", SessionJobBuilderTest.class)
                        .toPath());
        serviceString = SessionJobBuilder.setConfigValue(serviceString, PostAction.SKAHA_SESSIONID, "session-123");

        final V1Service service = (V1Service) Yaml.load(SessionLabelApplicator.mergeServiceLabelsAndSelector(
                serviceString, SessionLabelApplicator.metadataLabelsFromJob(jobYaml)));
        final Map<String, String> serviceLabels =
                Objects.requireNonNull(service.getMetadata().getLabels());
        final Map<String, String> serviceSelector =
                Objects.requireNonNull(service.getSpec().getSelector());

        Assert.assertEquals("skaha-carta-svc-session-123", serviceLabels.get("run"));
        Assert.assertEquals("session-123", serviceLabels.get("canfar.net/id"));
        Assert.assertEquals("analysis", serviceLabels.get("canfar.net/name"));
        Assert.assertEquals("carta", serviceLabels.get("canfar.net/kind"));
        Assert.assertEquals("alice", serviceLabels.get("canfar.net/username"));
        Assert.assertEquals("carta-alice-session-123", serviceLabels.get("canfar.net/job"));
        Assert.assertFalse(serviceLabels.containsKey("kueue.x-k8s.io/queue-name"));
        Assert.assertFalse(serviceLabels.containsKey("kueue.x-k8s.io/priority-class"));
        Assert.assertEquals("session-123", serviceSelector.get("canfar.net/id"));
        Assert.assertEquals("carta", serviceSelector.get("canfar.net/kind"));
        Assert.assertFalse(serviceSelector.containsKey("kueue.x-k8s.io/queue-name"));
    }

    @Test
    public void yamlMetadataLabelsMergeCanonicalJobLabelsWithoutKueue() throws Exception {
        final Path testBaseValuesPath = FileUtil.getFileFromResource(
                        "test-base-values-queue.yaml", SessionJobBuilderTest.class)
                .toPath();
        final Map<String, String> parametersToReplaceValues = new HashMap<>();
        parametersToReplaceValues.put(PostAction.SKAHA_SESSIONID, "session-123");
        parametersToReplaceValues.put(PostAction.SKAHA_SESSIONNAME, "analysis");
        parametersToReplaceValues.put(PostAction.SKAHA_SESSIONTYPE, "notebook");
        parametersToReplaceValues.put(PostAction.SKAHA_USERID, "alice");
        parametersToReplaceValues.put(PostAction.SKAHA_JOBNAME, "notebook-alice-session-123");

        final String jobYaml = SessionJobBuilder.fromPath(testBaseValuesPath)
                .withParameters(parametersToReplaceValues)
                .withQueue(new QueueConfiguration("notebook", "high", "my-queue"))
                .build();
        final String ingressYaml =
                """
                apiVersion: traefik.io/v1alpha1
                kind: Middleware
                metadata:
                  name: session-middleware
                spec: {}
                ---
                apiVersion: traefik.io/v1alpha1
                kind: IngressRoute
                metadata:
                  name: session-ingress
                  labels:
                    route-owned: keep
                spec: {}
                """;

        final String mergedYaml = SessionLabelApplicator.mergeYamlMetadataLabels(
                ingressYaml, SessionLabelApplicator.metadataLabelsFromJob(jobYaml));
        final List<Object> documents = new ArrayList<>();
        new org.yaml.snakeyaml.Yaml().loadAll(mergedYaml).forEach(documents::add);

        Assert.assertEquals(2, documents.size());
        assertYamlMetadataLabels(documents.get(0), "session-123", "notebook", null);
        assertYamlMetadataLabels(documents.get(1), "session-123", "notebook", "keep");
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
                .get(0)
                .getMatchExpressions();

        if (matchExpressions != null) {
            testMatchExpressions.addAll(matchExpressions);
        }
        return testMatchExpressions;
    }

    private void commonValues(final Path testBaseValuesPath, final Map<String, String> parametersToReplaceValues)
            throws Exception {
        final String fileContent = Files.readString(testBaseValuesPath);
        final String[] parametersToReplace = new String[] {PostAction.SKAHA_SESSIONID};

        for (final String param : parametersToReplace) {
            Assert.assertTrue("Test file is missing required field.", fileContent.contains(param));
            parametersToReplaceValues.put(param, RandomStringUtils.secure().nextAlphanumeric(12));
        }
        parametersToReplaceValues.put(PostAction.SKAHA_USERID, "alice");
        parametersToReplaceValues.put(PostAction.SKAHA_SESSIONNAME, "analysis");
        parametersToReplaceValues.put(PostAction.SKAHA_SESSIONTYPE, "notebook");
        parametersToReplaceValues.put(PostAction.SKAHA_JOBNAME, "notebook-alice-session-123");
    }

    private static void assertCanonicalSessionLabels(final Map<String, String> labels) {
        Assert.assertEquals("session-123", labels.get("canfar.net/id"));
        Assert.assertEquals("analysis", labels.get("canfar.net/name"));
        Assert.assertEquals("notebook", labels.get("canfar.net/kind"));
        Assert.assertEquals("alice", labels.get("canfar.net/username"));
        Assert.assertEquals("notebook-alice-session-123", labels.get("canfar.net/job"));
        Assert.assertEquals("flexible", labels.get("canfar.net/flavor"));
        Assert.assertEquals("gpu", labels.get("canfar.net/accelerator"));
        Assert.assertEquals("default", labels.get("canfar.net/community"));
        Assert.assertEquals("default", labels.get("canfar.net/project"));
        Assert.assertEquals("skaha", labels.get("app.kubernetes.io/managed-by"));
        Assert.assertEquals("canfar", labels.get("app.kubernetes.io/part-of"));
    }

    private static Set<String> canonicalSessionLabelKeys() {
        return Set.of(
                "canfar.net/community",
                "canfar.net/project",
                "canfar.net/id",
                "canfar.net/username",
                "canfar.net/name",
                "canfar.net/kind",
                "canfar.net/flavor",
                "canfar.net/job",
                "canfar.net/accelerator",
                "app.kubernetes.io/managed-by",
                "app.kubernetes.io/part-of");
    }

    @SuppressWarnings("unchecked")
    private static void assertYamlMetadataLabels(
            final Object document,
            final String expectedID,
            final String expectedKind,
            final String expectedRouteLabel) {
        final Map<String, Object> yamlDocument = (Map<String, Object>) document;
        final Map<String, Object> metadata = (Map<String, Object>) yamlDocument.get("metadata");
        final Map<String, Object> labels = (Map<String, Object>) metadata.get("labels");

        Assert.assertEquals(expectedID, labels.get("canfar.net/id"));
        Assert.assertEquals(expectedKind, labels.get("canfar.net/kind"));
        Assert.assertEquals("alice", labels.get("canfar.net/username"));
        Assert.assertEquals("notebook-alice-session-123", labels.get("canfar.net/job"));
        Assert.assertFalse(labels.containsKey("kueue.x-k8s.io/queue-name"));
        if (expectedRouteLabel != null) {
            Assert.assertEquals(expectedRouteLabel, labels.get("route-owned"));
        }
    }
}
