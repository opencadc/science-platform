package org.opencadc.skaha.session;

import ca.nrc.cadc.util.StringUtil;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobSpec;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PodSecurityContext;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1PodTemplateSpec;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1SecurityContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.kubernetes.client.openapi.models.V1VolumeMount;
import org.apache.log4j.Logger;
import org.jspecify.annotations.NonNull;

/**
 * Responsible for loading YAML Templates into Kubernetes to create Deployment, Ingress, and Service objects for User
 * Sessions.
 */
public class Builder {
    private static final Logger LOGGER = Logger.getLogger(Builder.class);

    private final SessionContext sessionContext;
    private final SessionProfileConfiguration sessionProfileConfiguration;

    Builder(final SessionContext sessionContext, final SessionProfileConfiguration sessionProfileConfiguration) {
        this.sessionContext = sessionContext;
        this.sessionProfileConfiguration = sessionProfileConfiguration;
    }

    static Builder fromContext(final SessionContext sessionContext) {
        final SessionProfileConfiguration sessionProfileConfiguration =
                SessionPlatformConfiguration.fromConfigurationDirectory()
                        .getSessionProfileConfiguration(sessionContext.getSessionType());
        return new Builder(sessionContext, sessionProfileConfiguration);
    }

    public final KubernetesSession build() throws IOException {
        final SessionType sessionType = sessionContext.getSessionType();
        final Map<TemplateLoader.TemplateObjectType, KubernetesObject> templateObjectMapping =
                TemplateLoader.loadTemplate(sessionType);

        final V1Job v1Job = buildJob((V1Job) templateObjectMapping.get(TemplateLoader.TemplateObjectType.JOB));

        return new KubernetesSession(sessionType, v1Job, null, null);
    }

    V1Job buildJob(final V1Job job) throws IOException {
        LOGGER.debug("Builder.buildDeployment: Building Deployment for session " + sessionContext.getIdentifier());
        final V1ObjectMeta metadata = getObjectMeta(job);
        final String jobName = Objects.requireNonNull(metadata.getName(), "Job name not properly set.");

        // Save to match on by the Deployment selector.
        final Map<String, String> labels = LabelBuilder.labels(metadata, this.sessionContext);
        job.setMetadata(metadata);

        // Spec level handling.
        final V1JobSpec jobSpec = Objects.requireNonNullElse(job.getSpec(), new V1JobSpec());

        final V1PodTemplateSpec podTemplateSpec = jobSpec.getTemplate();
        final V1ObjectMeta podMetadata = Objects.requireNonNullElse(podTemplateSpec.getMetadata(), new V1ObjectMeta());
        podMetadata.setLabels(labels);
        podTemplateSpec.setMetadata(podMetadata);

        final V1PodSpec podSpec = Objects.requireNonNullElse(podTemplateSpec.getSpec(), new V1PodSpec());
        podSpec.setHostname(this.sessionProfileConfiguration.getHostname());
        final V1PodSecurityContext configuredPodSecurityContext =
                this.sessionProfileConfiguration.getPodSecurityContextConfig();
        if (configuredPodSecurityContext != null) {
            podSpec.setSecurityContext(configuredPodSecurityContext);
        }

        ContainersBuilder.buildContainers(podSpec, jobName, sessionContext, sessionProfileConfiguration);

        podTemplateSpec.setSpec(podSpec);

        job.setSpec(jobSpec);

        return job;
    }

    private @NonNull V1ObjectMeta getObjectMeta(final V1Job job) {
        final String deploymentName = String.format(
                "%s-%s-%s",
                sessionContext.getOwnerName(),
                sessionContext.getSessionType().applicationName,
                sessionContext.getIdentifier());

        // Metadata handling: ensure metadata exists, set the name, and apply labels for session identification and
        // management
        final V1ObjectMeta metadata = Objects.requireNonNullElse(job.getMetadata(), new V1ObjectMeta());
        metadata.setName(deploymentName);
        return metadata;
    }

    static class LabelBuilder {
        // Label names and other Kubernetes specific configuration
        private static final String LABEL_PREFIX = "science-platform.opencadc.org";
        private static final String SESSION_ID_LABEL_KEY = String.join("/", LabelBuilder.LABEL_PREFIX, "session-id");
        private static final String SESSION_TYPE_LABEL_KEY =
                String.join("/", LabelBuilder.LABEL_PREFIX, "session-type");
        private static final String OWNER_NAME_LABEL_KEY = String.join("/", LabelBuilder.LABEL_PREFIX, "owner-name");

        static Map<String, String> labels(final V1ObjectMeta metadata, final SessionContext sessionContext) {
            final Map<String, String> labels = Objects.requireNonNullElse(metadata.getLabels(), new HashMap<>());
            labels.put(LabelBuilder.SESSION_ID_LABEL_KEY, sessionContext.getIdentifier());
            labels.put(LabelBuilder.OWNER_NAME_LABEL_KEY, sessionContext.getOwnerName());
            labels.put(LabelBuilder.SESSION_TYPE_LABEL_KEY, sessionContext.getSessionType().applicationName);

            metadata.setLabels(labels);

            return labels;
        }
    }

    static class ContainersBuilder {
        static void buildContainers(
                final V1PodSpec podSpec,
                @NonNull final String jobName,
                final SessionContext sessionContext,
                final SessionProfileConfiguration sessionProfileConfiguration) {
            final List<V1Container> containersList =
                    Objects.requireNonNull(podSpec.getContainers(), "No containers defined in the template");
            if (containersList.isEmpty()) {
                throw new IllegalArgumentException("No containers defined in the template");
            }

            final V1Container container = containersList.getFirst();
            container.setName(jobName);
            final List<V1EnvVar> containerEnvironmentVariables =
                    Objects.requireNonNullElse(container.getEnv(), new ArrayList<>());

            containerEnvironmentVariables.add(
                    new V1EnvVar().name("SKAHA_HOSTNAME").value(sessionProfileConfiguration.getHostname()));
            containerEnvironmentVariables.add(
                    new V1EnvVar().name("SKAHA_USERNAME").value(sessionContext.getOwnerName()));
            containerEnvironmentVariables.add(
                    new V1EnvVar().name("SKAHA_SESSIONID").value(sessionContext.getIdentifier()));
            containerEnvironmentVariables.add(new V1EnvVar()
                    .name("HOME")
                    .value(String.format(
                            "%s/%s/%s",
                            sessionProfileConfiguration.getTopLevelDirectory(),
                            sessionProfileConfiguration.getHomeDirectory(),
                            sessionContext.getOwnerName())));
            containerEnvironmentVariables.add(new V1EnvVar()
                    .name("PWD")
                    .value(String.format(
                            "%s/%s/%s",
                            sessionProfileConfiguration.getTopLevelDirectory(),
                            sessionProfileConfiguration.getHomeDirectory(),
                            sessionContext.getOwnerName())));
            containerEnvironmentVariables.addAll(sessionProfileConfiguration.getEnvironmentVariableConfig());

            final V1SecurityContext configuredSecurityContext = sessionProfileConfiguration.getSecurityContextConfig();
            if (configuredSecurityContext != null) {
                container.setSecurityContext(configuredSecurityContext);
            }

            container.setImage(sessionContext.getImage());

            final V1ResourceRequirements resourceRequirements = Objects.requireNonNullElse(container.getResources(), new V1ResourceRequirements());
            final Map<String, Quantity> requests = Objects.requireNonNullElse(resourceRequirements.getRequests(), new HashMap<>());
            requests.put("cpu", Quantity.fromString(sessionContext.getCPURequest()));
            requests.put("memory", Quantity.fromString(sessionContext.getMemoryRequest()));
            if (sessionContext.getGPURequest() > 0) {
                requests.put("nvidia.com/gpu", Quantity.fromString(Integer.toString(sessionContext.getGPURequest())));
            }

            final Map<String, Quantity> limits = Objects.requireNonNullElse(resourceRequirements.getLimits(), new HashMap<>());
            limits.put("cpu", Quantity.fromString(sessionContext.getCPULimit()));
            limits.put("memory", Quantity.fromString(sessionContext.getMemoryLimit()));
            if (sessionContext.getGPURequest() > 0) {
                limits.put("nvidia.com/gpu", Quantity.fromString(Integer.toString(sessionContext.getGPURequest())));
            }

            container.setResources(resourceRequirements);

            final String imagePullPolicy = sessionProfileConfiguration.getImagePullPolicy();
            if (StringUtil.hasText(imagePullPolicy)) {
                container.setImagePullPolicy(imagePullPolicy);
            }

//            - mountPath: "/skaha-system"
//            name: start-carta
//                    - mountPath: "/etc/passwd"
//            name: etc-passwd
//            subPath: passwd
//                    - mountPath: "/etc/group"
//            name: etc-group
//            subPath: group

            final List<V1VolumeMount> volumeMounts = Objects.requireNonNullElse(container.getVolumeMounts(), new ArrayList<>());
            volumeMounts.addAll(sessionProfileConfiguration.getVolumeMountConfig());

            container.setVolumeMounts(volumeMounts);
        }
    }
}
