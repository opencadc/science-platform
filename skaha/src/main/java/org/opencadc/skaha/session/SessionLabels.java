package org.opencadc.skaha.session;

import ca.nrc.cadc.util.StringUtil;
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
    private static final String KUEUE_QUEUE_NAME = "kueue.x-k8s.io/queue-name";
    private static final String KUEUE_PRIORITY_CLASS = "kueue.x-k8s.io/priority-class";
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

            labels.put(key.label, validateCanonicalLabelValue(key, value));
        }

        labels.putIfAbsent(Key.ACCELERATOR.label, ACCELERATOR_NONE);
        validateAccelerator(labels.get(Key.ACCELERATOR.label));
        validateFlavor(labels.get(Key.FLAVOR.label));

        return Map.copyOf(labels);
    }

    /**
     * Build validated Kueue labels for a Job.
     *
     * @param queueName local queue name
     * @param priorityClass priority class name
     * @return immutable Kueue label map
     */
    static Map<String, String> kueue(final String queueName, final String priorityClass) {
        return Map.of(
                KUEUE_QUEUE_NAME,
                validateLabelValue(KUEUE_QUEUE_NAME, queueName),
                KUEUE_PRIORITY_CLASS,
                validateLabelValue(KUEUE_PRIORITY_CLASS, priorityClass));
    }

    /** Selector for one user's session. */
    public static String forSession(final String username, final String sessionID) {
        return selector(labelEquals(Key.ID, sessionID), labelEquals(Key.USERNAME, username));
    }

    /** Selector for all sessions owned by a user, or {@code ""} when username is blank. */
    public static String forUser(final String username) {
        return StringUtil.hasText(username) ? selector(labelEquals(Key.USERNAME, username)) : "";
    }

    /** Selector for session listings and pod metrics. */
    public static String forUserSessions(final String username, final String sessionID, final boolean omitHeadless) {
        final List<String> requirements = new ArrayList<>();
        if (StringUtil.hasText(sessionID)) {
            requirements.add(labelEquals(Key.ID, sessionID));
        }
        if (StringUtil.hasText(username)) {
            requirements.add(labelEquals(Key.USERNAME, username));
        }
        if (omitHeadless) {
            requirements.add(labelNotEquals(Key.KIND, HEADLESS));
        }
        return selector(requirements.toArray(new String[0]));
    }

    /** Selector for one desktop application job. */
    public static String forDesktopApp(final String sessionID, final String username, final String appID) {
        return selector(
                labelEquals(Key.ID, sessionID),
                labelEquals(Key.USERNAME, username),
                labelEquals(Key.APP_ID, appID),
                labelEquals(Key.KIND, DESKTOP_APP));
    }

    /** Selector for the parent session job, excluding desktop application jobs. */
    public static String forSessionExceptDesktopApp(final String username, final String sessionID) {
        return selector(
                labelEquals(Key.ID, sessionID),
                labelEquals(Key.USERNAME, username),
                labelNotEquals(Key.KIND, DESKTOP_APP));
    }

    /** Escape the session kind label for kubectl JSONPath and custom-column usage. */
    static String sessionKindJsonPath() {
        return jsonPath(Key.KIND);
    }

    static String require(final Map<String, String> labels, final Key key) {
        return Objects.requireNonNull(get(labels, key), key.label + " label is required");
    }

    static String get(final Map<String, String> labels, final Key key) {
        Objects.requireNonNull(labels, "labels cannot be null");
        return labels.get(Objects.requireNonNull(key, "key cannot be null").label);
    }

    static boolean fixedResources(final Map<String, String> labels) {
        return FLAVOR_FIXED.equals(get(labels, Key.FLAVOR));
    }

    /** Validate the Skaha application version label value. */
    static String version(final String value) {
        return validateLabelValue(Key.VERSION.label, value);
    }

    private static String labelEquals(final Key key, final String value) {
        return key.label + "=" + validateLabelValue(key.label, value);
    }

    private static String labelNotEquals(final Key key, final String value) {
        return key.label + "!=" + validateLabelValue(key.label, value);
    }

    private static String selector(final String... requirements) {
        return String.join(",", requirements);
    }

    private static String jsonPath(final Key key) {
        return Objects.requireNonNull(key, "key cannot be null").label.replace(".", "\\.");
    }

    private static String defaultWhenBlank(final String label, final String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_SCOPE;
        }
        return validateLabelValue(label, value);
    }

    private static String validateCanonicalLabelValue(final Key key, final String value) {
        if (Key.NAME == key) {
            return validateLabelValue(key.label, truncateLabelValue(key.label, value));
        }

        return validateLabelValue(key.label, value);
    }

    private static String truncateLabelValue(final String label, final String value) {
        if (value == null) {
            throw new IllegalArgumentException(label + " label value cannot be null");
        }

        String candidate = value.length() > 63 ? value.substring(0, 63) : value;
        while (!candidate.isEmpty()
                && !KUBERNETES_LABEL_VALUE.matcher(candidate).matches()) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return candidate;
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

    enum Key {
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

        String label() {
            return this.label;
        }
    }
}
