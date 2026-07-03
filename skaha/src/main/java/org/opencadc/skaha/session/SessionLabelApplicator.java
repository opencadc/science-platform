package org.opencadc.skaha.session;

import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServiceSpec;
import io.kubernetes.client.util.Yaml;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Applies canonical session labels to Kubernetes YAML documents that still originate from templates.
 *
 * <p>This keeps label mutation in one module while the codebase transitions from YAML templates to typed Kubernetes
 * client objects.
 */
final class SessionLabelApplicator {
    private SessionLabelApplicator() {}

    /**
     * Read session metadata labels from a rendered Job YAML document.
     *
     * <p>Kueue labels are intentionally removed because services and ingresses should carry only session identity
     * metadata.
     *
     * @param jobLaunchString rendered Kubernetes Job YAML
     * @return mutable label map copied from job metadata
     * @throws IOException when the YAML document cannot be parsed as a Kubernetes Job
     */
    static Map<String, String> metadataLabelsFromJob(final String jobLaunchString) throws IOException {
        final V1Job job = (V1Job) Yaml.load(jobLaunchString);
        final V1ObjectMeta metadata = Objects.requireNonNullElse(job.getMetadata(), new V1ObjectMeta());
        final Map<String, String> labels = new HashMap<>(Objects.requireNonNullElse(metadata.getLabels(), Map.of()));
        labels.remove(SessionJobBuilder.JOB_QUEUE_LABEL_KEY);
        labels.remove(SessionJobBuilder.JOB_PRIORITY_CLASS_LABEL_KEY);
        return labels;
    }

    /**
     * Merge session labels into a Service YAML document and align its selector with the labeled session job.
     *
     * @param serviceString rendered Kubernetes Service YAML
     * @param labels session metadata labels to merge
     * @return rendered Service YAML with merged labels and selector
     * @throws IOException when the YAML document cannot be parsed as a Kubernetes Service
     */
    static String mergeServiceLabelsAndSelector(final String serviceString, final Map<String, String> labels)
            throws IOException {
        final V1Service service = (V1Service) Yaml.load(serviceString);
        final V1ObjectMeta metadata = Objects.requireNonNullElse(service.getMetadata(), new V1ObjectMeta());
        final Map<String, String> mergedLabels =
                new HashMap<>(Objects.requireNonNullElse(metadata.getLabels(), Map.of()));
        mergedLabels.putAll(labels);
        metadata.setLabels(mergedLabels);
        service.setMetadata(metadata);

        final V1ServiceSpec spec = Objects.requireNonNullElse(service.getSpec(), new V1ServiceSpec());
        spec.setSelector(serviceSelector(labels));
        service.setSpec(spec);

        return Yaml.dump(service);
    }

    /**
     * Merge session labels into metadata labels for each YAML document.
     *
     * @param yamlString one or more Kubernetes YAML documents
     * @param labels session metadata labels to merge
     * @return rendered YAML with merged metadata labels
     */
    static String mergeYamlMetadataLabels(final String yamlString, final Map<String, String> labels) {
        final org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
        final List<Object> documents = new ArrayList<>();

        for (final Object document : yaml.loadAll(yamlString)) {
            if (document instanceof Map<?, ?> yamlDocument) {
                applyMetadataLabels(yamlDocument, labels);
            }
            documents.add(document);
        }

        return yaml.dumpAll(documents.iterator());
    }

    /**
     * Build the minimal selector that binds a Service to one session kind.
     *
     * @param labels session labels already merged onto the matching Job
     * @return selector labels for {@code spec.selector}
     */
    private static Map<String, String> serviceSelector(final Map<String, String> labels) {
        final Map<String, String> selector = new LinkedHashMap<>();
        selector.put(SessionLabels.Key.ID.label(), labels.get(SessionLabels.Key.ID.label()));
        selector.put(SessionLabels.Key.KIND.label(), labels.get(SessionLabels.Key.KIND.label()));
        return selector;
    }

    /**
     * Merge labels into a YAML document metadata map.
     *
     * @param yamlDocument parsed YAML document
     * @param labels session metadata labels to merge
     */
    @SuppressWarnings("unchecked")
    private static void applyMetadataLabels(final Map<?, ?> yamlDocument, final Map<String, String> labels) {
        final Map<String, Object> document = (Map<String, Object>) yamlDocument;
        final Map<String, Object> metadata =
                (Map<String, Object>) document.computeIfAbsent("metadata", key -> new LinkedHashMap<>());
        final Map<String, Object> mergedLabels = new LinkedHashMap<>();
        final Object currentLabels = metadata.get("labels");
        if (currentLabels instanceof Map<?, ?> currentLabelMap) {
            currentLabelMap.forEach((key, value) -> mergedLabels.put(key.toString(), value));
        }
        mergedLabels.putAll(labels);
        metadata.put("labels", mergedLabels);
    }
}
