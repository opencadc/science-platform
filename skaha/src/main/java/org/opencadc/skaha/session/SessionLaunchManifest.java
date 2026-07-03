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
 * Assembles session launch Kubernetes manifests with one canonical session label set.
 *
 * <p>Kueue labels remain Job-only; Service and Ingress metadata receive only session identity labels.
 */
final class SessionLaunchManifest {
    private final V1Job job;
    private final Map<String, String> metadataLabels;

    private SessionLaunchManifest(final V1Job job, final Map<String, String> metadataLabels) {
        this.job = Objects.requireNonNull(job, "job cannot be null");
        this.metadataLabels = Map.copyOf(metadataLabels);
    }

    static SessionLaunchManifest fromJob(final V1Job job) {
        final V1ObjectMeta metadata = Objects.requireNonNullElse(job.getMetadata(), new V1ObjectMeta());
        final Map<String, String> labels = new HashMap<>(Objects.requireNonNullElse(metadata.getLabels(), Map.of()));
        return new SessionLaunchManifest(job, metadataLabels(labels));
    }

    String job() {
        return Yaml.dump(job);
    }

    String service(final String serviceString) throws IOException {
        final V1Service service = (V1Service) Yaml.load(serviceString);
        final V1ObjectMeta metadata = Objects.requireNonNullElse(service.getMetadata(), new V1ObjectMeta());
        final Map<String, String> labels = new HashMap<>(Objects.requireNonNullElse(metadata.getLabels(), Map.of()));
        labels.putAll(metadataLabels);
        metadata.setLabels(labels);
        service.setMetadata(metadata);

        final V1ServiceSpec spec = Objects.requireNonNullElse(service.getSpec(), new V1ServiceSpec());
        spec.setSelector(serviceSelector());
        service.setSpec(spec);

        return Yaml.dump(service);
    }

    String ingress(final String ingressString) {
        final org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
        final List<Object> documents = new ArrayList<>();

        for (final Object document : yaml.loadAll(ingressString)) {
            if (document instanceof Map<?, ?> yamlDocument) {
                applyMetadataLabels(yamlDocument);
            }
            documents.add(document);
        }

        return yaml.dumpAll(documents.iterator());
    }

    private static Map<String, String> metadataLabels(final Map<String, String> labels) {
        final Map<String, String> metadataLabels = new LinkedHashMap<>();
        for (final SessionLabels.Key key : SessionLabels.Key.values()) {
            if (labels.containsKey(key.label())) {
                metadataLabels.put(key.label(), labels.get(key.label()));
            }
        }
        return Map.copyOf(metadataLabels);
    }

    private Map<String, String> serviceSelector() {
        final SessionLabels.SessionMetadata metadata = SessionLabels.fromMetadata(metadataLabels);
        final Map<String, String> selector = new LinkedHashMap<>();
        selector.put(SessionLabels.Key.ID.label(), metadata.id());
        selector.put(SessionLabels.Key.KIND.label(), metadata.kind());
        return selector;
    }

    @SuppressWarnings("unchecked")
    private void applyMetadataLabels(final Map<?, ?> yamlDocument) {
        final Map<String, Object> document = (Map<String, Object>) yamlDocument;
        final Map<String, Object> metadata =
                (Map<String, Object>) document.computeIfAbsent("metadata", key -> new LinkedHashMap<>());
        final Map<String, Object> labels = new LinkedHashMap<>();
        final Object currentLabels = metadata.get("labels");
        if (currentLabels instanceof Map<?, ?> currentLabelMap) {
            currentLabelMap.forEach((key, value) -> labels.put(key.toString(), value));
        }
        labels.putAll(metadataLabels);
        metadata.put("labels", labels);
    }
}
