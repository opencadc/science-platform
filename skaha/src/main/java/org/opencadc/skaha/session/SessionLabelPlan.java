package org.opencadc.skaha.session;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class SessionLabelPlan {
    private final Map<String, String> jobLabels;

    private SessionLabelPlan(final Map<String, String> jobLabels) {
        this.jobLabels = Map.copyOf(jobLabels);
    }

    static SessionLabelPlan of(final Map<String, String> canonicalLabels) {
        return new SessionLabelPlan(Objects.requireNonNull(canonicalLabels, "canonicalLabels cannot be null"));
    }

    Map<String, String> jobLabels() {
        return jobLabels;
    }

    Map<String, String> serviceMetadataLabels() {
        final Map<String, String> metadataLabels = new LinkedHashMap<>();
        for (final SessionLabels.Key key : SessionLabels.Key.values()) {
            final String value = jobLabels.get(key.label());
            if (value != null) {
                metadataLabels.put(key.label(), value);
            }
        }
        return Map.copyOf(metadataLabels);
    }

    Map<String, String> serviceSelector() {
        final Map<String, String> selector = new LinkedHashMap<>();
        selector.put(SessionLabels.Key.ID.label(), required(SessionLabels.Key.ID));
        selector.put(SessionLabels.Key.KIND.label(), required(SessionLabels.Key.KIND));
        return Map.copyOf(selector);
    }

    Map<String, String> ingressMetadataLabels() {
        return serviceMetadataLabels();
    }

    private String required(final SessionLabels.Key key) {
        return Objects.requireNonNull(jobLabels.get(key.label()), key.label() + " label is required");
    }
}
