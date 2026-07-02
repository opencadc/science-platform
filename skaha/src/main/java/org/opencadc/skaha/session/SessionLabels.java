package org.opencadc.skaha.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

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

    public static Map<String, String> kueue(final String queueName, final String priorityClass) {
        return Map.of(
                "kueue.x-k8s.io/queue-name",
                validateLabelValue("kueue.x-k8s.io/queue-name", queueName),
                "kueue.x-k8s.io/priority-class",
                validateLabelValue("kueue.x-k8s.io/priority-class", priorityClass));
    }

    public static String forSession(final String username, final String sessionID) {
        return selector(labelEquals(Key.ID, sessionID), labelEquals(Key.USERNAME, username));
    }

    public static String forUser(final String username) {
        return hasText(username) ? selector(labelEquals(Key.USERNAME, username)) : "";
    }

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

    public static String forDesktopApp(final String sessionID, final String username, final String appID) {
        return selector(
                labelEquals(Key.ID, sessionID),
                labelEquals(Key.USERNAME, username),
                labelEquals(Key.APP_ID, appID),
                labelEquals(Key.KIND, DESKTOP_APP));
    }

    public static String forSessionExceptDesktopApp(final String username, final String sessionID) {
        return selector(
                labelEquals(Key.ID, sessionID),
                labelEquals(Key.USERNAME, username),
                labelNotEquals(Key.KIND, DESKTOP_APP));
    }

    public static LabelSelectorRequirement withoutHeadless() {
        return labelNotEquals(Key.KIND, HEADLESS);
    }

    public static LabelSelectorRequirement labelEquals(final Key key, final String value) {
        return new LabelSelectorRequirement(key, "=", value);
    }

    public static LabelSelectorRequirement labelNotEquals(final Key key, final String value) {
        return new LabelSelectorRequirement(key, "!=", value);
    }

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

    public static String jsonPath(final Key key) {
        return Objects.requireNonNull(key, "key cannot be null").label.replace(".", "\\.");
    }

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

        public String label() {
            return this.label;
        }
    }

    public static final class LabelSelectorRequirement {
        private final Key key;
        private final String operator;
        private final String value;

        private LabelSelectorRequirement(final Key key, final String operator, final String value) {
            this.key = Objects.requireNonNull(key, "key cannot be null");
            this.operator = Objects.requireNonNull(operator, "operator cannot be null");
            this.value = validateLabelValue(key.label, value);
        }

        private String format() {
            return key.label + operator + value;
        }
    }
}
