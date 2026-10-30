package org.opencadc.skaha.session;

import ca.nrc.cadc.util.StringUtil;
import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.NodeAffinity;
import io.fabric8.kubernetes.api.model.NodeSelector;
import io.fabric8.kubernetes.api.model.NodeSelectorRequirement;
import io.fabric8.kubernetes.api.model.NodeSelectorTerm;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.TopologySpreadConstraintBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.utils.Serialization;
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
import java.util.regex.Matcher;
import java.util.stream.Stream;
import org.apache.log4j.Logger;
import org.opencadc.skaha.K8SUtil;

/**
 * Builds a session Job from an operator template.
 *
 * <p>Template placeholders ({@code ${key}}) are filled with trusted values only. Values a user controls (the image, a
 * headless command, its arguments and environment) are set on the parsed Job as typed fields, so they cannot change the
 * YAML structure.
 */
public class SessionJobBuilder {
    private static final Logger LOGGER = Logger.getLogger(SessionJobBuilder.class);

    // Stands in for the image while the template is parsed; replaced on every container that uses it.
    static final String IMAGE_PLACEHOLDER = "skaha-session-image";

    record LaunchArtifacts(Job job, SessionLabelPlan labels) {}

    private final Map<String, String> parameters = new HashMap<>();

    private final Path jobFilePath;

    // Options
    private boolean gpuEnabled;
    private int gpuCount = 0;
    private QueueConfiguration queueConfiguration;
    private String imageRegistrySecretName;
    private String image;
    private String priorityClassName;
    private HeadlessCommand headlessCommand;
    private FlexibleSpread flexibleSpread;

    /**
     * Spread flexible session Pods ({@code canfar.net/flavor=flexible}) across topology domains with a topology spread
     * constraint. Their memory request is below their limit, so packing them onto one node invites memory pressure
     * there. The spread is a preference ({@code ScheduleAnyway}): it never blocks a session.
     *
     * @param maxSkew The largest allowed difference in flexible Pods between two domains.
     * @param topologyKey The node label that defines a domain, e.g. {@code kubernetes.io/hostname}.
     */
    record FlexibleSpread(int maxSkew, String topologyKey) {
        /** The configured spread, or null when the chart disables it. */
        static FlexibleSpread fromEnv() {
            final String maxSkew = System.getenv("SKAHA_FLEXIBLE_SPREAD_MAX_SKEW");
            if (!StringUtil.hasText(maxSkew)) {
                return null;
            }
            try {
                return new FlexibleSpread(
                        Integer.parseInt(maxSkew.trim()), System.getenv("SKAHA_FLEXIBLE_SPREAD_TOPOLOGY_KEY"));
            } catch (NumberFormatException notANumber) {
                throw new IllegalStateException(
                        "SKAHA_FLEXIBLE_SPREAD_MAX_SKEW must be an integer, not '" + maxSkew + "'", notANumber);
            }
        }
    }

    /**
     * A headless session's command line, from the request.
     *
     * @param command The executable, or null to use the image's entrypoint.
     * @param args Arguments, possibly empty.
     * @param env Environment variables in request order, possibly empty.
     */
    record HeadlessCommand(String command, List<String> args, List<EnvVar> env) {
        /**
         * Parse the request parameters: {@code args} split on single spaces, and each {@code env} entry split on its
         * first {@code =}. Entries without a name are ignored.
         */
        static HeadlessCommand fromRequest(final String command, final String args, final List<String> envs) {
            final List<String> argList =
                    args == null ? List.of() : Stream.of(args.split(" ")).toList();
            final List<EnvVar> envList = new ArrayList<>();
            for (final String env : Objects.requireNonNullElse(envs, List.<String>of())) {
                final int separator = env.indexOf('=');
                if (separator > 0) {
                    envList.add(new EnvVar(env.substring(0, separator), env.substring(separator + 1), null));
                } else {
                    LOGGER.debug("invalid key/value env var: " + env);
                }
            }
            return new HeadlessCommand(command, argList, envList);
        }
    }

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

    private static NodeSelectorRequirement getNodeSelectorRequirement(
            final List<NodeSelectorTerm> gpuRequiredNodeSelectorTerms) {
        if (gpuRequiredNodeSelectorTerms.size() != 1) {
            throw new IllegalStateException("GPU Node Selector cannot exceed one selector.");
        }

        final List<NodeSelectorRequirement> gpuNodeSelectorMatchExpressions =
                gpuRequiredNodeSelectorTerms.get(0).getMatchExpressions();

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
        return doc.replaceAll(regex, Matcher.quoteReplacement(value));
    }

    /**
     * Pass parameters to be replaced in the job file. Only trusted values belong here; see {@link #withImage(String)}
     * and {@link #withHeadlessCommand(HeadlessCommand)} for request values.
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
     * The session image, set on every container whose template image is {@code ${software.imageid}}.
     *
     * @param image The image ID from the request.
     * @return This SessionJobBuilder, never null.
     */
    SessionJobBuilder withImage(final String image) {
        this.image = image;
        return this;
    }

    /**
     * The Pod priority class. Replaces the headless template's {@code ${headless.priority}} slot.
     *
     * @param priorityClassName The PriorityClass name, or null for none.
     * @return This SessionJobBuilder, never null.
     */
    SessionJobBuilder withPriorityClass(final String priorityClassName) {
        this.priorityClassName = priorityClassName;
        return this;
    }

    /**
     * Spread the Pod across nodes when it is flexible.
     *
     * @param flexibleSpread The spread, or null for none.
     * @return This SessionJobBuilder, never null.
     */
    SessionJobBuilder withFlexibleSpread(final FlexibleSpread flexibleSpread) {
        this.flexibleSpread = flexibleSpread;
        return this;
    }

    /**
     * The headless command line. Replaces the headless template's {@code ${headless.image.bundle}} slot.
     *
     * @param headlessCommand The command, arguments and environment from the request.
     * @return This SessionJobBuilder, never null.
     */
    SessionJobBuilder withHeadlessCommand(final HeadlessCommand headlessCommand) {
        this.headlessCommand = headlessCommand;
        return this;
    }

    LaunchArtifacts buildLaunch() throws IOException {
        String jobFileString = Files.readString(jobFilePath, StandardCharsets.UTF_8);
        // Request values are set as typed fields after parsing; the template text only ever gets fixed stand-ins,
        // substituted first so no parameter can supply them.
        jobFileString = setConfigValue(jobFileString, PostAction.SOFTWARE_IMAGEID, IMAGE_PLACEHOLDER);
        jobFileString = setConfigValue(
                jobFileString, PostAction.HEADLESS_IMAGE_BUNDLE, "image: \"" + IMAGE_PLACEHOLDER + "\"\n        env:");
        jobFileString = setConfigValue(jobFileString, PostAction.HEADLESS_PRIORITY, "");
        for (final Map.Entry<String, String> entry : this.parameters.entrySet()) {
            jobFileString = SessionJobBuilder.setConfigValue(jobFileString, entry.getKey(), entry.getValue());
        }

        return buildLaunch(Serialization.unmarshal(jobFileString, Job.class));
    }

    private LaunchArtifacts buildLaunch(final Job launchJob) {
        requireImagePlaceholderOnlyAsImage(launchJob);
        final SessionLabelPlan labelPlan = buildLabelPlan(launchJob);
        applyJobLabels(launchJob, labelPlan);
        applyFlexibleSpread(launchJob, labelPlan);
        mergeQueue(launchJob);
        mergeAffinity(launchJob);
        mergeImagePullSecret(launchJob);
        applyImage(launchJob);
        applyPriorityClass(launchJob);
        applyHeadlessCommand(launchJob);

        return new LaunchArtifacts(launchJob, labelPlan);
    }

    /**
     * A template that uses ${software.imageid} other than as a whole container image would launch a Pod that can never
     * start. Checked before request values are set, so a request value that contains the placeholder is not mistaken
     * for template misuse.
     */
    private static void requireImagePlaceholderOnlyAsImage(final Job launchJob) {
        final long asImage = containers(launchJob)
                .filter(container -> IMAGE_PLACEHOLDER.equals(container.getImage()))
                .count();
        final long anywhere = Serialization.asYaml(launchJob).split(IMAGE_PLACEHOLDER, -1).length - 1;
        if (anywhere != asImage) {
            throw new IllegalStateException("Session template uses the image placeholder outside a container image");
        }
    }

    private static Stream<Container> containers(final Job launchJob) {
        final PodSpec podSpec = podSpec(launchJob);
        return Stream.concat(podSpec.getInitContainers().stream(), podSpec.getContainers().stream());
    }

    /** Build the canonical label plan for this launch. */
    private SessionLabelPlan buildLabelPlan(final Job launchJob) {
        final Map<SessionLabels.Key, String> labelValues = new EnumMap<>(SessionLabels.Key.class);
        labelValues.put(SessionLabels.Key.ID, requireFirstNonBlank(PostAction.SKAHA_SESSIONID));
        labelValues.put(SessionLabels.Key.USERNAME, requireFirstNonBlank(PostAction.SKAHA_USERID));
        labelValues.put(SessionLabels.Key.NAME, requireFirstNonBlank(PostAction.SKAHA_SESSIONNAME));
        labelValues.put(SessionLabels.Key.KIND, requireFirstNonBlank(PostAction.SKAHA_SESSIONTYPE));
        final String appId = firstNonBlank(PostAction.SOFTWARE_APPID);
        if (appId != null) {
            labelValues.put(SessionLabels.Key.APP_ID, appId);
        }
        labelValues.put(
                SessionLabels.Key.JOB, requireFirstNonBlank(PostAction.SOFTWARE_JOBNAME, PostAction.SKAHA_JOBNAME));
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
    private void applyJobLabels(final Job launchJob, final SessionLabelPlan plan) {
        launchJob.getMetadata().getLabels().putAll(plan.jobLabels());
        // Templates usually give the Pod template no metadata.
        final PodTemplateSpec podTemplate = launchJob.getSpec().getTemplate();
        if (podTemplate.getMetadata() == null) {
            podTemplate.setMetadata(new ObjectMeta());
        }
        podTemplate.getMetadata().getLabels().putAll(plan.jobLabels());
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

    /** Return the first non-blank value or throw when none are present. */
    private String requireFirstNonBlank(final String... keys) {
        final String value = firstNonBlank(keys);
        if (value == null) {
            throw new IllegalArgumentException("requires one of parameters " + String.join(", ", keys));
        }
        return value;
    }

    /** Infer {@code fixed} vs {@code flexible} from memory request/limit equality. */
    private String getResourceFlavor(final Job launchJob) {
        final ResourceRequirements resourceRequirements = SessionJobBuilder.getResourceRequirements(podSpec(launchJob));
        final Quantity memoryLimit = resourceRequirements.getLimits().get("memory");
        final Quantity memoryRequest = resourceRequirements.getRequests().get("memory");
        if (memoryRequest != null && memoryRequest.equals(memoryLimit)) {
            return SessionLabels.FLAVOR_FIXED;
        }

        return SessionLabels.FLAVOR_FLEXIBLE;
    }

    private void applyFlexibleSpread(final Job launchJob, final SessionLabelPlan labelPlan) {
        final String flavorLabel = SessionLabels.Key.FLAVOR.label();
        if (this.flexibleSpread == null
                || !SessionLabels.FLAVOR_FLEXIBLE.equals(labelPlan.jobLabels().get(flavorLabel))) {
            return;
        }
        podSpec(launchJob)
                .getTopologySpreadConstraints()
                .add(new TopologySpreadConstraintBuilder()
                        .withMaxSkew(this.flexibleSpread.maxSkew())
                        .withTopologyKey(this.flexibleSpread.topologyKey())
                        .withWhenUnsatisfiable("ScheduleAnyway")
                        // Tainted nodes (e.g. GPU only) take no flexible sessions, so they do not count.
                        .withNodeTaintsPolicy("Honor")
                        .withNewLabelSelector()
                        .addToMatchLabels(flavorLabel, SessionLabels.FLAVOR_FLEXIBLE)
                        .endLabelSelector()
                        .build());
    }

    /**
     * Merge the Node Affinity, if present, with the GPU affinity, if present, with any existing affinity.
     *
     * @param launchJob The Job to modify.
     */
    private void mergeAffinity(final Job launchJob) {
        final Affinity gpuAffinity = getGPUSchedulingAffinity();
        if (gpuAffinity == null) {
            return;
        }
        final PodSpec podTemplateSpec = podSpec(launchJob);

        // If we're this far, there is no need to check if gpuEnabled again, so only check if gpuCount is greater
        // than 0.
        if (this.gpuCount > 0) {
            // According to the Kubernetes Documentation:
            // https://kubernetes.io/docs/tasks/manage-gpus/scheduling-gpus/#using-device-plugins
            // only the limits should be set.  However, to enable Fair Share in Kueue, the requests need to be set as
            // well.  As long as they are the same, this should be fine.
            // jenkinsd 2026.05.14
            //
            final ResourceRequirements resourceRequirements =
                    SessionJobBuilder.getResourceRequirements(podTemplateSpec);
            resourceRequirements.getLimits().put("nvidia.com/gpu", new Quantity(Integer.toString(this.gpuCount)));
            resourceRequirements.getRequests().put("nvidia.com/gpu", new Quantity(Integer.toString(this.gpuCount)));
        }

        final Affinity affinity = podTemplateSpec.getAffinity();
        if (affinity == null) {
            podTemplateSpec.setAffinity(gpuAffinity);
            return;
        }

        final NodeAffinity nodeAffinity = affinity.getNodeAffinity();
        final NodeAffinity gpuNodeAffinity = gpuAffinity.getNodeAffinity();
        if (nodeAffinity == null) {
            affinity.setNodeAffinity(gpuNodeAffinity);
            return;
        }

        nodeAffinity
                .getPreferredDuringSchedulingIgnoredDuringExecution()
                .addAll(gpuNodeAffinity.getPreferredDuringSchedulingIgnoredDuringExecution());

        final NodeSelector requiredNodeSelector = nodeAffinity.getRequiredDuringSchedulingIgnoredDuringExecution();
        final NodeSelector gpuRequiredNodeSelector =
                gpuNodeAffinity.getRequiredDuringSchedulingIgnoredDuringExecution();

        // No preset one from the configuration, so assume the GPU setting is the only one.
        if (requiredNodeSelector == null) {
            nodeAffinity.setRequiredDuringSchedulingIgnoredDuringExecution(gpuRequiredNodeSelector);
        } else if (gpuRequiredNodeSelector != null) {
            final List<NodeSelectorTerm> requiredNodeSelectorTerms = requiredNodeSelector.getNodeSelectorTerms();
            final List<NodeSelectorTerm> gpuRequiredNodeSelectorTerms = gpuRequiredNodeSelector.getNodeSelectorTerms();

            if (requiredNodeSelectorTerms == null || requiredNodeSelectorTerms.isEmpty()) {
                requiredNodeSelector.setNodeSelectorTerms(gpuRequiredNodeSelectorTerms);
            } else {
                final NodeSelectorRequirement gpuNodeSelectorMatchExpression =
                        SessionJobBuilder.getNodeSelectorRequirement(gpuRequiredNodeSelectorTerms);
                requiredNodeSelectorTerms.forEach(
                        term -> term.getMatchExpressions().add(gpuNodeSelectorMatchExpression));
            }
        } else {
            LOGGER.debug("Nothing to alter for Node Affinity.");
        }
    }

    private static PodSpec podSpec(final Job launchJob) {
        return launchJob.getSpec().getTemplate().getSpec();
    }

    private static ResourceRequirements getResourceRequirements(final PodSpec podTemplateSpec) {
        final Container container = podTemplateSpec.getContainers().get(0);
        final ResourceRequirements resourceRequirements =
                Objects.requireNonNullElse(container.getResources(), new ResourceRequirements());
        container.setResources(resourceRequirements);

        return resourceRequirements;
    }

    /**
     * For the given Job, determine if it's queue-able, and set the appropriate label and suspend information.
     *
     * @param launchJob The Job to modify.
     */
    private void mergeQueue(final Job launchJob) {
        if (this.queueConfiguration != null) {
            launchJob
                    .getMetadata()
                    .getLabels()
                    .putAll(SessionLabels.kueue(
                            this.queueConfiguration.queueName, this.queueConfiguration.priorityClass));
            launchJob.getSpec().setSuspend(true);
        }
    }

    private void mergeImagePullSecret(final Job launchJob) {
        if (StringUtil.hasText(this.imageRegistrySecretName)) {
            podSpec(launchJob).getImagePullSecrets().add(new LocalObjectReference(this.imageRegistrySecretName));
        }
    }

    /** Set the session image on every container and init container that the template gave the image placeholder. */
    private void applyImage(final Job launchJob) {
        containers(launchJob)
                .filter(container -> IMAGE_PLACEHOLDER.equals(container.getImage()))
                .forEach(container -> container.setImage(requireImage()));
    }

    private String requireImage() {
        if (!StringUtil.hasText(this.image)) {
            throw new IllegalArgumentException("session image is required");
        }
        return this.image;
    }

    private void applyPriorityClass(final Job launchJob) {
        if (StringUtil.hasText(this.priorityClassName)) {
            podSpec(launchJob).setPriorityClassName(this.priorityClassName);
        }
    }

    /** Prepend the request's environment and set its command and arguments on the main container. */
    private void applyHeadlessCommand(final Job launchJob) {
        if (this.headlessCommand == null) {
            return;
        }
        final Container container = podSpec(launchJob).getContainers().get(0);
        if (this.headlessCommand.command() != null) {
            container.setCommand(List.of(this.headlessCommand.command()));
        }
        if (!this.headlessCommand.args().isEmpty()) {
            container.setArgs(this.headlessCommand.args());
        }
        // Request variables first: for a repeated name the template's value, listed later, wins.
        container.getEnv().addAll(0, this.headlessCommand.env());
    }

    /**
     * Obtain the existing GPU scheduling affinity.
     *
     * @return Affinity instance, or null if not enabled.
     */
    private Affinity getGPUSchedulingAffinity() {
        if (!this.gpuEnabled) {
            return null;
        }

        final NodeSelectorRequirement nodeSelectorRequirement = new NodeSelectorRequirement();
        nodeSelectorRequirement.setKey("nvidia.com/gpu.count");

        if (this.gpuCount <= 0) {
            nodeSelectorRequirement.setOperator("DoesNotExist");
        } else {
            nodeSelectorRequirement.setOperator("Gt");
            nodeSelectorRequirement.setValues(Collections.singletonList("0"));
        }

        final NodeSelectorTerm nodeSelectorTerm = new NodeSelectorTerm();
        nodeSelectorTerm.setMatchExpressions(new ArrayList<>(List.of(nodeSelectorRequirement)));
        final NodeSelector gpuRequiredNodeSelector = new NodeSelector();
        gpuRequiredNodeSelector.setNodeSelectorTerms(new ArrayList<>(List.of(nodeSelectorTerm)));
        final NodeAffinity gpuNodeAffinity = new NodeAffinity();
        gpuNodeAffinity.setRequiredDuringSchedulingIgnoredDuringExecution(gpuRequiredNodeSelector);
        final Affinity gpuAffinity = new Affinity();
        gpuAffinity.setNodeAffinity(gpuNodeAffinity);

        return gpuAffinity;
    }
}
