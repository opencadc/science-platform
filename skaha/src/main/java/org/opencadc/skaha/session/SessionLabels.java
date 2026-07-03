package org.opencadc.skaha.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Canonical Kubernetes label keys and selector builders for Skaha sessions.
 *
 * <p>All session label names are owned here so template YAML, kubectl selectors, and Kubernetes client code share one
 * label contract.
 */
public final class SessionLabels {
    private static final String DEFAULT_SCOPE = "default";
    private static final String ACCELERATOR_GPU = "gpu";
    private static final String ACCELERATOR_NONE = "none";
    private static final String FLAVOR_FIXED = "fixed";
    private static final String FLAVOR_FLEXIBLE = "flexible";
    private static final String HEADLESS = "headless";
    private static final String DESKTOP_APP = "desktop-app";
    private static final Pattern KUBERNETES_LABEL_VALUE = Pattern.compile("[A-Za-z0-9]([-A-Za-z0-9_.]*[A-Za-z0-9])?");

    private SessionLabels() {}

    /**
     * Build validated canonical labels from session label values.
     *
     * <p>{@code canfar.net/community} and {@code canfar.net/project} default to {@code default}; application labels are
     * omitted when blank; {@code app.kubernetes.io/version} must be supplied through {@link #version(String)}.
     *
     * @param values label values keyed by canonical session key
     * @return immutable Kubernetes label map
     * @throws IllegalArgumentException when a required value is missing or violates Kubernetes label syntax
     */
    static Map<String, String> canonical(final Map<Key, String> values) {
        Objects.requireNonNull(values, "values cannot be null");

        final Map<String, String> labels = new LinkedHashMap<>();
        labels.put(Key.COMMUNITY.label, defaultWhenBlank(Key.COMMUNITY.label, values.get(Key.COMMUNITY)));
        labels.put(Key.PROJECT.label, defaultWhenBlank(Key.PROJECT.label, values.get(Key.PROJECT)));
        labels.put(Key.MANAGED_BY.label, "skaha");
        labels.put(Key.PART_OF.label, "canfar");

        for (final Map.Entry<Key, String> entry : values.entrySet()) {
            final Key key = Objects.requireNonNull(entry.getKey(), "label key cannot be null");
            final String value = entry.getValue();

            if (Key.COMMUNITY == key || Key.PROJECT == key) {
                labels.put(key.label, defaultWhenBlank(key.label, value));
                continue;
            }

            if (Key.APP_ID == key && (value == null || value.isBlank())) {
                continue;
            }

            if (Key.VERSION == key) {
                throw new IllegalArgumentException(Key.VERSION.label + " is derived from SKAHA_VERSION");
            }

            labels.put(key.label, validateLabelValue(key.label, value));
        }

        labels.putIfAbsent(Key.ACCELERATOR.label, ACCELERATOR_NONE);
        validateAccelerator(labels.get(Key.ACCELERATOR.label));
        validateFlavor(labels.get(Key.FLAVOR.label));

        return Map.copyOf(labels);
    }

    /**
     * Build the labels consumed by Kueue.
     *
     * @param queueName local queue name for the job
     * @param priorityClass priority class for the queued job
     * @return immutable Kubernetes label map for Kueue
     */
    public static Map<String, String> kueue(final String queueName, final String priorityClass) {
        return Map.of(
                "kueue.x-k8s.io/queue-name",
                validateLabelValue("kueue.x-k8s.io/queue-name", queueName),
                "kueue.x-k8s.io/priority-class",
                validateLabelValue("kueue.x-k8s.io/priority-class", priorityClass));
    }

    /**
     * Build a selector for one user's session.
     *
     * @param username session owner
     * @param sessionID session identifier
     * @return comma-separated Kubernetes label selector
     */
    public static String forSession(final String username, final String sessionID) {
        return selector(labelEquals(Key.ID, sessionID), labelEquals(Key.USERNAME, username));
    }

    /**
     * Build a selector for all sessions owned by a user.
     *
     * @param username session owner
     * @return comma-separated Kubernetes label selector, or {@code ""} when username is blank
     */
    public static String forUser(final String username) {
        return hasText(username) ? selector(labelEquals(Key.USERNAME, username)) : "";
    }

    /**
     * Build a selector for session listings and pod metrics.
     *
     * @param username session owner, or blank to omit this filter
     * @param sessionID session identifier, or blank to omit this filter
     * @param omitHeadless true to exclude headless sessions
     * @return comma-separated Kubernetes label selector
     */
    public static String forUserSessions(final String username, final String sessionID, final boolean omitHeadless) {
        final List<LabelSelectorRequirement> requirements = new ArrayList<>();
        if (hasText(sessionID)) {
            requirements.add(labelEquals(Key.ID, sessionID));
        }
        if (hasText(username)) {
            requirements.add(labelEquals(Key.USERNAME, username));
        }
        if (omitHeadless) {
            requirements.add(withoutHeadless());
        }
        return selector(requirements.toArray(new LabelSelectorRequirement[0]));
    }

    /**
     * Build a selector for one desktop application job.
     *
     * @param sessionID parent session identifier
     * @param username session owner
     * @param appID desktop application identifier
     * @return comma-separated Kubernetes label selector
     */
    public static String forDesktopApp(final String sessionID, final String username, final String appID) {
        return selector(
                labelEquals(Key.ID, sessionID),
                labelEquals(Key.USERNAME, username),
                labelEquals(Key.APP_ID, appID),
                labelEquals(Key.KIND, DESKTOP_APP));
    }

    /**
     * Build a selector for the parent session job while excluding desktop application jobs.
     *
     * @param username session owner
     * @param sessionID session identifier
     * @return comma-separated Kubernetes label selector
     */
    public static String forSessionExceptDesktopApp(final String username, final String sessionID) {
        return selector(
                labelEquals(Key.ID, sessionID),
                labelEquals(Key.USERNAME, username),
                labelNotEquals(Key.KIND, DESKTOP_APP));
    }

    /**
     * Build a selector requirement that excludes headless sessions.
     *
     * @return selector requirement for non-headless sessions
     */
    public static LabelSelectorRequirement withoutHeadless() {
        return labelNotEquals(Key.KIND, HEADLESS);
    }

    /**
     * Build an equality selector requirement for a canonical label key.
     *
     * @param key canonical label key
     * @param value expected label value
     * @return selector requirement using the {@code =} operator
     */
    public static LabelSelectorRequirement labelEquals(final Key key, final String value) {
        return new LabelSelectorRequirement(key, "=", value);
    }

    /**
     * Build an inequality selector requirement for a canonical label key.
     *
     * @param key canonical label key
     * @param value rejected label value
     * @return selector requirement using the {@code !=} operator
     */
    public static LabelSelectorRequirement labelNotEquals(final Key key, final String value) {
        return new LabelSelectorRequirement(key, "!=", value);
    }

    /**
     * Join selector requirements into Kubernetes selector syntax.
     *
     * @param requirements selector requirements to join
     * @return comma-separated Kubernetes label selector
     */
    public static String selector(final LabelSelectorRequirement... requirements) {
        Objects.requireNonNull(requirements, "requirements cannot be null");

        final StringBuilder selector = new StringBuilder();

        for (final LabelSelectorRequirement requirement : requirements) {
            Objects.requireNonNull(requirement, "label selector requirement cannot be null");
            if (!selector.isEmpty()) {
                selector.append(',');
            }
            selector.append(requirement.format());
        }

        return selector.toString();
    }

    /**
     * Escape a canonical label key for kubectl JSONPath and custom-column usage.
     *
     * @param key canonical label key
     * @return label path with dots escaped
     */
    public static String jsonPath(final Key key) {
        return Objects.requireNonNull(key, "key cannot be null").label.replace(".", "\\.");
    }

    /**
     * Validate the Skaha application version label value.
     *
     * @param value chart application version passed through {@code SKAHA_VERSION}
     * @return validated label value
     */
    static String version(final String value) {
        return validateLabelValue(Key.VERSION.label, value);
    }

    private static boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }

    private static String defaultWhenBlank(final String label, final String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_SCOPE;
        }

        return validateLabelValue(label, value);
    }

    private static String validateLabelValue(final String label, final String value) {
        if (value == null) {
            throw new IllegalArgumentException(label + " label value cannot be null");
        }

        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " label value cannot be blank");
        }

        if (value.length() > 63) {
            throw new IllegalArgumentException(label + " label value exceeds 63 characters");
        }

        if (!KUBERNETES_LABEL_VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " label value must match Kubernetes label value syntax");
        }

        return value;
    }

    private static void validateAccelerator(final String accelerator) {
        if (!ACCELERATOR_GPU.equals(accelerator) && !ACCELERATOR_NONE.equals(accelerator)) {
            throw new IllegalArgumentException("accelerator must be gpu or none");
        }
    }

    private static void validateFlavor(final String flavor) {
        if (flavor != null && !FLAVOR_FIXED.equals(flavor) && !FLAVOR_FLEXIBLE.equals(flavor)) {
            throw new IllegalArgumentException("flavor must be fixed or flexible");
        }
    }

    /** Canonical label keys used by Skaha session jobs, services, ingresses, and selectors. */
    public enum Key {
        COMMUNITY("canfar.net/community"),
        PROJECT("canfar.net/project"),
        ID("canfar.net/id"),
        USERNAME("canfar.net/username"),
        NAME("canfar.net/name"),
        KIND("canfar.net/kind"),
        FLAVOR("canfar.net/flavor"),
        JOB("canfar.net/job"),
        APP_ID("canfar.net/app-id"),
        ACCELERATOR("canfar.net/accelerator"),
        VERSION("app.kubernetes.io/version"),
        MANAGED_BY("app.kubernetes.io/managed-by"),
        PART_OF("app.kubernetes.io/part-of");

        private final String label;

        Key(final String label) {
            this.label = label;
        }

        /**
         * Return the Kubernetes label key.
         *
         * @return label key string
         */
        public String label() {
            return this.label;
        }
    }

    /** One Kubernetes label selector requirement using a canonical key. */
    public static final class LabelSelectorRequirement {
        private final Key key;
        private final String operator;
        private final String value;

        private LabelSelectorRequirement(final Key key, final String operator, final String value) {
            this.key = Objects.requireNonNull(key, "key cannot be null");
            this.operator = Objects.requireNonNull(operator, "operator cannot be null");
            this.value = validateLabelValue(key.label, value);
        }

        /**
         * Format the requirement for Kubernetes label selector syntax.
         *
         * @return formatted selector requirement
         */
        private String format() {
            return key.label + operator + value;
        }
    }
}
