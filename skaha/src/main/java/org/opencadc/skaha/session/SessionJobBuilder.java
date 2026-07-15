package org.opencadc.skaha.session;

import ca.nrc.cadc.util.StringUtil;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.Yaml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.opencadc.skaha.K8SUtil;

/** Class to interface with Kubernetes. */
public class SessionJobBuilder {
    private static final Logger LOGGER = Logger.getLogger(SessionJobBuilder.class);

    record LaunchArtifacts(V1Job job, SessionLabelPlan labels) {}

    /** Configuration for the queue to use. */
    private final Map<String, String> parameters = new HashMap<>();

    private final Path jobFilePath;

    // Options
    private boolean gpuEnabled;
    private int gpuCount = 0;
    private QueueConfiguration queueConfiguration;
    private String imageRegistrySecretName;

    private SessionJobBuilder(final Path jobFilePath) {
        this.jobFilePath = jobFilePath;
    }

    /**
     * Create a new builder from the provided path.
     *
     * @param jobFilePath The Path of the template file.
     * @return SessionJobBuilder instance. Never null.
     */
    static SessionJobBuilder fromPath(final Path jobFilePath) {
        return new SessionJobBuilder(jobFilePath);
    }

    /** Obtain a mutable Job metadata label map attached to the Job. */
    @NotNull private static Map<String, String> getOrCreateJobLabels(V1Job job) {
        final V1ObjectMeta jobMetadata = Objects.requireNonNullElse(job.getMetadata(), new V1ObjectMeta());
        final Map<String, String> labels = new HashMap<>(Objects.requireNonNullElse(jobMetadata.getLabels(), Map.of()));
        jobMetadata.setLabels(labels);
        job.setMetadata(jobMetadata);

        return labels;
    }

    private static V1NodeSelectorRequirement getV1NodeSelectorRequirement(
            List<V1NodeSelectorTerm> gpuRequiredNodeSelectorTerms) {
        if (gpuRequiredNodeSelectorTerms.size() != 1) {
            throw new IllegalStateException("GPU Node Selector cannot exceed one selector.");
        }

        final V1NodeSelectorTerm gpuNodeSelectorTerm = gpuRequiredNodeSelectorTerms.get(0);
        final List<V1NodeSelectorRequirement> gpuNodeSelectorMatchExpressions =
                gpuNodeSelectorTerm.getMatchExpressions();

        if (gpuNodeSelectorMatchExpressions == null) {
            throw new IllegalStateException("Preset GPU Node Selector match expressions are missing.");
        } else if (gpuNodeSelectorMatchExpressions.size() != 1) {
            throw new IllegalStateException("Preset GPU Node Selector match expressions must be exactly one (found "
                    + gpuNodeSelectorMatchExpressions.size() + ")");
        }

        return gpuNodeSelectorMatchExpressions.get(0);
    }

    static String setConfigValue(String doc, String key, String value) {
        String regKey = key.replace(".", "\\.");
        String regex = "\\$[{]" + regKey + "[}]";
        return doc.replaceAll(regex, value);
    }

    /**
     * Pass parameters to be replaced in the job file.
     *
     * @param parameters Map of parameter String key to String values to replace.
     * @return This SessionJobBuilder, never null.
     */
    SessionJobBuilder withParameters(final Map<String, String> parameters) {
        this.parameters.putAll(parameters);
        return this;
    }

    /**
     * Enable GPU scheduling.
     *
     * @param enableGPU True if GPU scheduling enabled, False otherwise.
     * @return This SessionJobBuilder, never null.
     */
    SessionJobBuilder withGPUEnabled(final boolean enableGPU) {
        this.gpuEnabled = enableGPU;
        return this;
    }

    /**
     * Request some number of GPUs.
     *
     * @param gpuCount The count of GPUs to request.
     * @return This SessionJobBuilder instance, never null
     */
    SessionJobBuilder withGPUCount(final int gpuCount) {
        this.gpuCount = gpuCount;
        return this;
    }

    /**
     * Set the queue name for the job to use with Kueue.
     *
     * @param queueConfiguration The QueueConfiguration.
     * @return This SessionJobBuilder, never null.
     */
    SessionJobBuilder withQueue(final QueueConfiguration queueConfiguration) {
        this.queueConfiguration = queueConfiguration;
        return this;
    }

    /**
     * Build a single parameter into this builder's parameter map.
     *
     * @param key The key to find.
     * @param value The value to replace with.
     * @return This SessionJobBuilder, never null.
     */
    SessionJobBuilder withParameter(final String key, final String value) {
        this.parameters.put(key, value);
        return this;
    }

    /**
     * Use the provided Kubernetes secret to authenticate with the Image Registry to pull the Image.
     *
     * @param imageRegistrySecretName String existing secret name.
     * @return This SessionJobBuilder, never null.
     */
    SessionJobBuilder withImageSecret(final String imageRegistrySecretName) {
        this.imageRegistrySecretName = imageRegistrySecretName;
        return this;
    }

    /**
     * Construct the Job YAML output of this builder.
     *
     * @return String of Job YAML, never null.
     * @throws IOException If the provided Path cannot be read.
     */
    String build() throws IOException {
        return Yaml.dump(buildLaunch().job());
    }

    /** Build launch artifacts from the rendered template. */
    LaunchArtifacts buildLaunch() throws IOException {
        final byte[] jobFileBytes = Files.readAllBytes(jobFilePath);
        String jobFileString = new String(jobFileBytes, StandardCharsets.UTF_8);
        for (final Map.Entry<String, String> entry : this.parameters.entrySet()) {
            jobFileString = SessionJobBuilder.setConfigValue(jobFileString, entry.getKey(), entry.getValue());
        }

        return buildLaunch(jobFileString);
    }

    /** Construct the launch manifest output of this builder. */
    @Deprecated
    SessionLaunchManifest buildManifest() throws IOException {
        final LaunchArtifacts launch = buildLaunch();
        return SessionLaunchManifest.fromJobAndPlan(launch.job(), launch.labels());
    }

    /** Build and mutate launch artifacts from a rendered YAML template. */
    private LaunchArtifacts buildLaunch(final String jobFileString) throws IOException {
        final V1Job launchJob = (V1Job) Yaml.load(jobFileString);
        final SessionLabelPlan labelPlan = buildLabelPlan(launchJob);
        applyJobLabels(launchJob, labelPlan);
        mergeQueue(launchJob);
        mergeAffinity(launchJob);
        mergeImagePullSecret(launchJob);

        return new LaunchArtifacts(launchJob, labelPlan);
    }

    /** Build the canonical label plan for this launch. */
    private SessionLabelPlan buildLabelPlan(final V1Job launchJob) {
        final Map<SessionLabels.Key, String> labelValues = new EnumMap<>(SessionLabels.Key.class);
        labelValues.put(SessionLabels.Key.ID, requireParam(PostAction.SKAHA_SESSIONID));
        labelValues.put(SessionLabels.Key.USERNAME, requireParam(PostAction.SKAHA_USERID));
        labelValues.put(SessionLabels.Key.NAME, requireParam(PostAction.SKAHA_SESSIONNAME));
        labelValues.put(SessionLabels.Key.KIND, requireParam(PostAction.SKAHA_SESSIONTYPE));
        final String appId = firstNonBlank(PostAction.SOFTWARE_APPID);
        if (appId != null) {
            labelValues.put(SessionLabels.Key.APP_ID, appId);
        }
        labelValues.put(
                SessionLabels.Key.JOB,
                requireFirstNonBlank(PostAction.SOFTWARE_JOBNAME, PostAction.SKAHA_JOBNAME));
        labelValues.put(SessionLabels.Key.FLAVOR, getResourceFlavor(launchJob));
        labelValues.put(SessionLabels.Key.ACCELERATOR, this.gpuCount > 0 ? "gpu" : "none");

        final Map<String, String> labels = new HashMap<>(SessionLabels.canonical(labelValues));
        final String skahaVersion = K8SUtil.getSkahaVersion();
        if (StringUtil.hasText(skahaVersion)) {
            labels.put(SessionLabels.Key.VERSION.label(), SessionLabels.version(skahaVersion));
        }

        return SessionLabelPlan.of(labels);
    }

    /** Attach job labels to the Job metadata and pod template metadata. */
    private void applyJobLabels(final V1Job launchJob, final SessionLabelPlan plan) {
        final Map<String, String> labels = plan.jobLabels();
        SessionJobBuilder.getOrCreateJobLabels(launchJob).putAll(labels);
        final V1JobSpec jobSpec = Objects.requireNonNullElse(launchJob.getSpec(), new V1JobSpec());
        final V1PodTemplateSpec podTemplate =
                Objects.requireNonNullElse(jobSpec.getTemplate(), new V1PodTemplateSpec());
        final V1ObjectMeta podMetadata = Objects.requireNonNullElse(podTemplate.getMetadata(), new V1ObjectMeta());
        final Map<String, String> podLabels =
                new HashMap<>(Objects.requireNonNullElse(podMetadata.getLabels(), Map.of()));
        podLabels.putAll(labels);
        podMetadata.setLabels(podLabels);
        podTemplate.setMetadata(podMetadata);
        jobSpec.setTemplate(podTemplate);
        launchJob.setSpec(jobSpec);
    }

    /** Return the first non-blank parameter value for the provided keys. */
    private String firstNonBlank(final String... keys) {
        for (final String key : keys) {
            final String value = this.parameters.get(key);
            if (StringUtil.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    /** Return one required parameter value. */
    private String requireParam(final String key) {
        return requireFirstNonBlank(key);
    }

    /** Return the first non-blank value or throw when none are present. */
    private String requireFirstNonBlank(final String... keys) {
        final String value = firstNonBlank(keys);
        if (value == null) {
            throw new IllegalArgumentException("requires one of parameters " + String.join(", ", keys));
        }
        return value;
    }

    /** Infer {@code fixed} vs {@code flexible} from memory request/limit equality. */
    private String getResourceFlavor(final V1Job launchJob) {
        final V1JobSpec jobSpec = Objects.requireNonNullElse(launchJob.getSpec(), new V1JobSpec());
        final V1PodSpec podSpec =
                Objects.requireNonNullElse(jobSpec.getTemplate().getSpec(), new V1PodSpec());
        final V1ResourceRequirements resourceRequirements = SessionJobBuilder.getResourceRequirements(podSpec);
        final Map<String, Quantity> resourceLimits =
                Objects.requireNonNullElse(resourceRequirements.getLimits(), new HashMap<>());
        final Map<String, Quantity> resourceRequests =
                Objects.requireNonNullElse(resourceRequirements.getRequests(), new HashMap<>());

        final Quantity memoryLimit = resourceLimits.get("memory");
        final Quantity memoryRequest = resourceRequests.get("memory");
        if (memoryRequest != null && memoryRequest.equals(memoryLimit)) {
            return "fixed";
        }

        return "flexible";
    }

    /**
     * Merge the Node Affinity, if present, with the GPU affinity, if present, with any existing affinity.
     *
     * @param launchJob The Job to modify.
     */
    private void mergeAffinity(final V1Job launchJob) {
        final V1Affinity gpuAffinity = getGPUSchedulingAffinity();
        if (gpuAffinity != null) {
            final V1JobSpec podTemplate = launchJob.getSpec();
            if (podTemplate != null) {
                // spec.template.spec
                final V1PodSpec podTemplateSpec = podTemplate.getTemplate().getSpec();
                if (podTemplateSpec != null) {
                    final V1Affinity affinity = podTemplateSpec.getAffinity();

                    // If we're this far, there is no need to check if gpuEnabled again, so only check if gpuCount is
                    // greater than 0.
                    if (this.gpuCount > 0) {
                        // According to the Kubernetes Documentation:
                        // https://kubernetes.io/docs/tasks/manage-gpus/scheduling-gpus/#using-device-plugins
                        // only the limits should be set.  However, to enable Fair Share in Kueue, the requests need
                        // to be set as well.  As long as they are the same, this should be fine.
                        // jenkinsd 2026.05.14
                        //
                        final V1ResourceRequirements resourceRequirements =
                                SessionJobBuilder.getResourceRequirements(podTemplateSpec);
                        final Map<String, Quantity> limits =
                                Objects.requireNonNullElse(resourceRequirements.getLimits(), new HashMap<>());
                        limits.put("nvidia.com/gpu", new Quantity(Integer.toString(this.gpuCount)));
                        final Map<String, Quantity> requests =
                                Objects.requireNonNullElse(resourceRequirements.getRequests(), new HashMap<>());
                        requests.put("nvidia.com/gpu", new Quantity(Integer.toString(this.gpuCount)));

                        resourceRequirements.setLimits(limits);
                        resourceRequirements.setRequests(requests);
                    }

                    // spec.template.spec.affinity
                    if (affinity == null) {
                        podTemplateSpec.setAffinity(gpuAffinity);
                    } else {
                        final V1NodeAffinity nodeAffinity = affinity.getNodeAffinity();

                        // spec.template.spec.affinity.nodeAffinity
                        if (nodeAffinity == null) {
                            affinity.setNodeAffinity(gpuAffinity.getNodeAffinity());
                        } else {
                            final List<V1PreferredSchedulingTerm> existingPreferredSchedulingTerms =
                                    nodeAffinity.getPreferredDuringSchedulingIgnoredDuringExecution();
                            final V1NodeAffinity gpuNodeAffinity = gpuAffinity.getNodeAffinity();

                            if (gpuNodeAffinity != null) {
                                final List<V1PreferredSchedulingTerm> gpuAffinityPreferredSchedulingTerms =
                                        gpuNodeAffinity.getPreferredDuringSchedulingIgnoredDuringExecution();

                                final List<V1PreferredSchedulingTerm> mergedPreferredSchedulingTerms =
                                        new ArrayList<>();
                                if (existingPreferredSchedulingTerms != null) {
                                    mergedPreferredSchedulingTerms.addAll(existingPreferredSchedulingTerms);
                                }

                                if (gpuAffinityPreferredSchedulingTerms != null) {
                                    mergedPreferredSchedulingTerms.addAll(gpuAffinityPreferredSchedulingTerms);
                                }

                                if (!mergedPreferredSchedulingTerms.isEmpty()) {
                                    nodeAffinity.setPreferredDuringSchedulingIgnoredDuringExecution(
                                            mergedPreferredSchedulingTerms);
                                }

                                // spec.template.spec.affinity.nodeAffinity.requiredDuringSchedulingIgnoredDuringExecution
                                final V1NodeSelector requiredNodeSelector =
                                        nodeAffinity.getRequiredDuringSchedulingIgnoredDuringExecution();
                                final V1NodeSelector gpuRequiredNodeSelector =
                                        gpuNodeAffinity.getRequiredDuringSchedulingIgnoredDuringExecution();

                                // No preset one from the configuration, so assume the GPU setting is the only one.
                                if (requiredNodeSelector == null) {
                                    nodeAffinity.setRequiredDuringSchedulingIgnoredDuringExecution(
                                            gpuRequiredNodeSelector);
                                } else if (gpuRequiredNodeSelector != null) {
                                    final List<V1NodeSelectorTerm> requiredNodeSelectorTerms =
                                            requiredNodeSelector.getNodeSelectorTerms();
                                    final List<V1NodeSelectorTerm> gpuRequiredNodeSelectorTerms =
                                            gpuRequiredNodeSelector.getNodeSelectorTerms();

                                    if (requiredNodeSelectorTerms.isEmpty()) {
                                        requiredNodeSelector.setNodeSelectorTerms(gpuRequiredNodeSelectorTerms);
                                    } else {
                                        final V1NodeSelectorRequirement gpuNodeSelectorMatchExpression =
                                                SessionJobBuilder.getV1NodeSelectorRequirement(
                                                        gpuRequiredNodeSelectorTerms);
                                        requiredNodeSelectorTerms.forEach(requiredNodeSelectorTerm -> {
                                            final List<V1NodeSelectorRequirement> requiredNodeSelectorMatchExpressions =
                                                    requiredNodeSelectorTerm.getMatchExpressions();
                                            if (requiredNodeSelectorMatchExpressions == null) {
                                                requiredNodeSelectorTerm.setMatchExpressions(
                                                        Collections.singletonList(gpuNodeSelectorMatchExpression));
                                            } else {
                                                requiredNodeSelectorTerm.addMatchExpressionsItem(
                                                        gpuNodeSelectorMatchExpression);
                                            }
                                        });
                                    }
                                } else {
                                    LOGGER.debug("Nothing to alter for Node Affinity.");
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @NotNull private static V1ResourceRequirements getResourceRequirements(V1PodSpec podTemplateSpec) {
        final V1Container container = podTemplateSpec.getContainers().get(0);
        final V1ResourceRequirements resourceRequirements =
                Objects.requireNonNullElse(container.getResources(), new V1ResourceRequirements());
        container.setResources(resourceRequirements);

        return resourceRequirements;
    }

    /**
     * For the given Job, determine if it's queue-able, and set the appropriate label and suspend information.
     *
     * @param launchJob The Job to modify.
     */
    void mergeQueue(final V1Job launchJob) {
        if (this.queueConfiguration != null) {
            LOGGER.debug("Setting queue name to " + this.queueConfiguration);

            final V1JobSpec jobSpec = Objects.requireNonNullElse(launchJob.getSpec(), new V1JobSpec());

            final Map<String, String> labels = SessionJobBuilder.getOrCreateJobLabels(launchJob);
            labels.putAll(
                    SessionLabels.kueue(this.queueConfiguration.queueName, this.queueConfiguration.priorityClass));

            jobSpec.setSuspend(true);

            launchJob.setSpec(jobSpec);
        } else {
            LOGGER.debug("No queue name provided.");
        }
    }

    private void mergeImagePullSecret(final V1Job launchJob) {
        final V1JobSpec podTemplate = launchJob.getSpec();
        if (podTemplate != null && StringUtil.hasText(this.imageRegistrySecretName)) {
            final V1PodSpec podTemplateSpec = podTemplate.getTemplate().getSpec();
            if (podTemplateSpec != null) {
                final List<V1LocalObjectReference> imagePullSecrets = podTemplateSpec.getImagePullSecrets();
                if (imagePullSecrets == null) {
                    podTemplateSpec.setImagePullSecrets(
                            Collections.singletonList(new V1LocalObjectReference().name(this.imageRegistrySecretName)));
                } else {
                    imagePullSecrets.add(new V1LocalObjectReference().name(this.imageRegistrySecretName));
                }
            }
        }
    }

    /**
     * Obtain the existing GPU scheduling affinity.
     *
     * @return V1Affinity instance, or null if not enabled.
     */
    private V1Affinity getGPUSchedulingAffinity() {
        if (!this.gpuEnabled) {
            return null;
        }

        final V1Affinity gpuAffinity = new V1Affinity();
        final V1NodeAffinity gpuNodeAffinity = new V1NodeAffinity();
        final V1NodeSelector gpuRequiredNodeSelector = new V1NodeSelector();
        final List<V1NodeSelectorTerm> nodeSelectorTerms = new ArrayList<>();
        final V1NodeSelectorTerm nodeSelectorTerm = new V1NodeSelectorTerm();
        final V1NodeSelectorRequirement nodeSelectorRequirement = new V1NodeSelectorRequirement();
        nodeSelectorRequirement.setKey("nvidia.com/gpu.count");

        if (this.gpuCount <= 0) {
            nodeSelectorRequirement.setOperator("DoesNotExist");
        } else {
            nodeSelectorRequirement.setOperator("Gt");
            nodeSelectorRequirement.setValues(Collections.singletonList("0"));
        }

        nodeSelectorTerm.addMatchExpressionsItem(nodeSelectorRequirement);
        nodeSelectorTerms.add(nodeSelectorTerm);
        gpuRequiredNodeSelector.setNodeSelectorTerms(nodeSelectorTerms);
        gpuNodeAffinity.setRequiredDuringSchedulingIgnoredDuringExecution(gpuRequiredNodeSelector);
        gpuAffinity.setNodeAffinity(gpuNodeAffinity);

        return gpuAffinity;
    }
}
