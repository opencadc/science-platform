package org.opencadc.skaha.session;

import java.nio.file.Path;
import org.opencadc.skaha.K8SUtil;

public enum SessionType {
    CARTA(true, true, "carta"),
    CONTRIBUTED(true, true, "contributed"),
    DESKTOP(true, true, "desktop"),
    NOTEBOOK(true, true, "notebook"),
    HEADLESS(false, false, "headless"),
    DESKTOP_APP(false, false, "desktop-app"),
    FIREFLY(true, true, "firefly");

    private final boolean supportsIngress;
    private final boolean supportsService;
    final String applicationName;

    SessionType(final boolean supportsIngress, final boolean supportsService, final String applicationName) {
        this.supportsIngress = supportsIngress;
        this.supportsService = supportsService;
        this.applicationName = applicationName;
    }

    /**
     * Obtain a Session Type from the requested string (type parameter).
     *
     * @param applicationStringType The requested session type
     * @return SessionType The session type
     * @throws IllegalArgumentException if the session type is invalid
     */
    public static SessionType fromApplicationStringType(final String applicationStringType) {
        for (SessionType type : SessionType.values()) {
            if (type.applicationName.equalsIgnoreCase(applicationStringType.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException("Invalid session type: " + applicationStringType);
    }

    public Path getIngressConfigPath(final boolean isLegacyCARTA) {
        return Path.of(String.format(
                "%s/config/ingress-%s%s.yaml",
                K8SUtil.getWorkingDirectory(),
                this.name().toLowerCase(),
                (isLegacyCARTA && this == CARTA) ? "-legacy" : ""));
    }

    public Path getServiceConfigPath(final boolean isLegacyCARTA) {
        return Path.of(String.format(
                "%s/config/service-%s%s.yaml",
                K8SUtil.getWorkingDirectory(),
                this.name().toLowerCase(),
                (isLegacyCARTA && this == CARTA) ? "-legacy" : ""));
    }

    public Path getJobConfigPath(final boolean isLegacyCARTA) {
        return Path.of(String.format(
                "%s/config/launch-%s%s.yaml",
                K8SUtil.getWorkingDirectory(),
                this.name().toLowerCase(),
                (isLegacyCARTA && this == CARTA) ? "-legacy" : ""));
    }

    public boolean supportsIngress() {
        return this.supportsIngress;
    }

    public boolean supportsService() {
        return this.supportsService;
    }

    public boolean isHeadless() {
        return this == HEADLESS;
    }
}
