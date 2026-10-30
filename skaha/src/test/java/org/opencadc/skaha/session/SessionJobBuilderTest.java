package org.opencadc.skaha.session;

import ca.nrc.cadc.util.FileUtil;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.NodeSelectorRequirement;
import io.fabric8.kubernetes.api.model.NodeSelectorRequirementBuilder;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.TopologySpreadConstraint;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.utils.Serialization;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import org.junit.Assert;
import org.junit.Test;

/**
 * The Job, Service and ingress a launch builds from a template: parameters, canonical labels, queue, GPU affinity and
 * image pull Secret. The session flows ({@link SessionLifecycleTest}) cover the chart's templates end to end; these
 * cover the template variants the chart does not ship.
 */
public class SessionJobBuilderTest {
    private static final Map<String, String> SESSION_LABELS = Map.of(
            "canfar.net/id", "session-123",
            "canfar.net/name", "analysis",
            "canfar.net/username", "alice",
            "canfar.net/job", "notebook-alice-session-123");

    @Test
    public void testParametersSubstituted() throws Exception {
        final Map<String, String> parameters = parameters("notebook");
        parameters.put(PostAction.SKAHA_HOSTNAME, "skaha-host");
        parameters.put(PostAction.SKAHA_POSIXID, "20001");
        final Job job = build("test-base-values.yaml", parameters, builder -> builder.withImage("img:1"));

        final String output = Serialization.asYaml(job);
        parameters.forEach((name, value) -> {
            Assert.assertFalse("Not replaced: " + name, output.contains(name));
            Assert.assertTrue("Not injected: " + value, output.contains(value));
        });
        Assert.assertEquals("img:1", container(job).getImage());
        Assert.assertTrue(podSpec(job).getImagePullSecrets().isEmpty());
        Assert.assertNull("No GPU limit", gpu(container(job).getResources().getLimits()));
        Assert.assertNull("No GPU request", gpu(container(job).getResources().getRequests()));
    }

    /** Canonical labels on the Job and Pod; Kueue's only on the Job; the template's own labels kept. */
    @Test
    public void testJobAndPodLabels() throws Exception {
        final Job job =
                build("test-base-values-queue.yaml", parameters("notebook"), builder -> builder.withGPUEnabled(true)
                        .withGPUCount(2)
                        .withQueue(new QueueConfiguration("notebook", "high", "my-queue")));

        final Map<String, String> jobLabels = job.getMetadata().getLabels();
        final Map<String, String> podLabels =
                job.getSpec().getTemplate().getMetadata().getLabels();
        for (final Map<String, String> labels : List.of(jobLabels, podLabels)) {
            assertContains(labels, SESSION_LABELS);
            assertContains(
                    labels,
                    Map.of(
                            "canfar.net/kind", "notebook",
                            "canfar.net/flavor", "flexible",
                            "canfar.net/accelerator", "gpu",
                            "canfar.net/community", "default",
                            "canfar.net/project", "default",
                            "app.kubernetes.io/managed-by", "skaha",
                            "app.kubernetes.io/part-of", "canfar"));
        }
        Assert.assertEquals("my-queue", jobLabels.get("kueue.x-k8s.io/queue-name"));
        Assert.assertEquals("high", jobLabels.get("kueue.x-k8s.io/priority-class"));
        Assert.assertEquals(Boolean.TRUE, job.getSpec().getSuspend());
        Assert.assertEquals("job", jobLabels.get("template-owned"));
        Assert.assertEquals("pod", podLabels.get("template-owned"));
        Assert.assertFalse(podLabels.containsKey("kueue.x-k8s.io/queue-name"));
        Assert.assertFalse(podLabels.containsKey("kueue.x-k8s.io/priority-class"));
    }

    @Test
    public void testLongSessionNameTruncatedForLabels() throws Exception {
        final String longName = "desktop-" + "a".repeat(80);
        final Map<String, String> parameters = parameters("desktop-app");
        parameters.put(PostAction.SKAHA_SESSIONNAME, longName);

        final String label = build("test-base-values.yaml", parameters, builder -> builder.withImage("img:1"))
                .getMetadata()
                .getLabels()
                .get("canfar.net/name");
        Assert.assertEquals(63, label.length());
        Assert.assertTrue(longName.startsWith(label));
    }

    /** The template's node affinity is kept; a GPU request adds a GPU node requirement and matching limits. */
    @Test
    public void testGPUAffinityAndPullSecret() throws Exception {
        final NodeSelectorRequirement gpuNode = new NodeSelectorRequirementBuilder()
                .withKey("nvidia.com/gpu.count")
                .withOperator("Gt")
                .withValues("0")
                .build();
        final NodeSelectorRequirement templateNode = new NodeSelectorRequirementBuilder()
                .withKey("my-node-please")
                .withOperator("Exists")
                .build();
        for (final int gpus : new int[] {0, 2}) {
            final Job job =
                    build("test-base-values-affinity.yaml", parameters("notebook"), builder -> builder.withImageSecret(
                                    "my-secret")
                            .withGPUEnabled(gpus > 0)
                            .withGPUCount(gpus));

            final Quantity expected = gpus > 0 ? new Quantity(Integer.toString(gpus)) : null;
            Assert.assertEquals(expected, gpu(container(job).getResources().getLimits()));
            Assert.assertEquals(expected, gpu(container(job).getResources().getRequests()));
            final List<NodeSelectorRequirement> required = podSpec(job)
                    .getAffinity()
                    .getNodeAffinity()
                    .getRequiredDuringSchedulingIgnoredDuringExecution()
                    .getNodeSelectorTerms()
                    .get(0)
                    .getMatchExpressions();
            Assert.assertEquals("GPU node requirement", gpus > 0, required.contains(gpuNode));
            Assert.assertTrue(required.contains(templateNode));
            Assert.assertEquals(
                    "my-secret", podSpec(job).getImagePullSecrets().get(0).getName());
            Assert.assertEquals("fixed", job.getMetadata().getLabels().get(SessionLabels.Key.FLAVOR.label()));
        }
    }

    /** Flexible Pods spread across nodes; a fixed Pod, or a disabled spread, adds no constraint. */
    @Test
    public void testFlexibleSpread() throws Exception {
        final SessionJobBuilder.FlexibleSpread spread =
                new SessionJobBuilder.FlexibleSpread(2, "topology.kubernetes.io/zone");

        final Job flexible = build(
                "test-base-values-queue.yaml", parameters("notebook"), builder -> builder.withFlexibleSpread(spread));
        Assert.assertEquals("flexible", flexible.getMetadata().getLabels().get("canfar.net/flavor"));
        final List<TopologySpreadConstraint> constraints = podSpec(flexible).getTopologySpreadConstraints();
        Assert.assertEquals(1, constraints.size());
        final TopologySpreadConstraint constraint = constraints.get(0);
        Assert.assertEquals(Integer.valueOf(2), constraint.getMaxSkew());
        Assert.assertEquals("topology.kubernetes.io/zone", constraint.getTopologyKey());
        Assert.assertEquals("ScheduleAnyway", constraint.getWhenUnsatisfiable());
        Assert.assertEquals("Honor", constraint.getNodeTaintsPolicy());
        Assert.assertEquals(
                Map.of("canfar.net/flavor", "flexible"),
                constraint.getLabelSelector().getMatchLabels());

        final Job fixed = build(
                "test-base-values-affinity.yaml",
                parameters("notebook"),
                builder -> builder.withFlexibleSpread(spread));
        Assert.assertEquals("fixed", fixed.getMetadata().getLabels().get("canfar.net/flavor"));
        Assert.assertTrue(podSpec(fixed).getTopologySpreadConstraints().isEmpty());

        final Job disabled = build("test-base-values-queue.yaml", parameters("notebook"), builder -> builder);
        Assert.assertTrue(podSpec(disabled).getTopologySpreadConstraints().isEmpty());
    }

    @Test
    public void testImagePlaceholderOutsideContainerImageIsRejected() throws Exception {
        final Path template = Files.createTempFile("launch-bad", ".yaml");
        Files.writeString(
                template,
                Files.readString(SessionFixtures.template("launch-notebook.yaml"))
                        .replace(
                                "- name: skaha_hostname",
                                "- name: IMAGE\n          value: \"mirror/${software.imageid}\"\n        - name: skaha_hostname"));

        final SessionJobBuilder builder = SessionJobBuilder.fromPath(template)
                .withParameters(SessionFixtures.parameters("notebook"))
                .withImage("images.example.org/skaha/notebook:1.0");

        Assert.assertThrows(IllegalStateException.class, builder::buildLaunch);
    }

    private static Map<String, String> parameters(final String kind) {
        final Map<String, String> parameters = new HashMap<>();
        parameters.put(PostAction.SKAHA_SESSIONID, "session-123");
        parameters.put(PostAction.SKAHA_SESSIONNAME, "analysis");
        parameters.put(PostAction.SKAHA_SESSIONTYPE, kind);
        parameters.put(PostAction.SKAHA_USERID, "alice");
        parameters.put(PostAction.SKAHA_JOBNAME, "notebook-alice-session-123");
        return parameters;
    }

    private static Job build(
            final String template,
            final Map<String, String> parameters,
            final UnaryOperator<SessionJobBuilder> configure)
            throws Exception {
        return configure
                .apply(SessionJobBuilder.fromPath(resource(template)).withParameters(parameters))
                .buildLaunch()
                .job();
    }

    private static Path resource(final String name) {
        return FileUtil.getFileFromResource(name, SessionJobBuilderTest.class).toPath();
    }

    private static PodSpec podSpec(final Job job) {
        return job.getSpec().getTemplate().getSpec();
    }

    private static Container container(final Job job) {
        return podSpec(job).getContainers().get(0);
    }

    private static Quantity gpu(final Map<String, Quantity> resources) {
        return resources == null ? null : resources.get("nvidia.com/gpu");
    }

    private static void assertContains(final Map<String, String> actual, final Map<String, String> expected) {
        expected.forEach((key, value) ->
                Assert.assertEquals(key, value, Objects.requireNonNull(actual).get(key)));
    }
}
