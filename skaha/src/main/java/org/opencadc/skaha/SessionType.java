package org.opencadc.skaha;

import java.nio.file.Path;

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
    private final String applicationName;

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
            if (type.applicationName.equalsIgnoreCase(applicationStringType)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Invalid session type: " + applicationStringType);
    }

    public Path getIngressConfigPath() {
        return Path.of(String.format(
                "%s/config/ingress-%s.yaml",
                K8SUtil.getWorkingDirectory(), this.name().toLowerCase()));
    }

    public Path getServiceConfigPath() {
        return Path.of(String.format(
                "%s/config/service-%s.yaml",
                K8SUtil.getWorkingDirectory(), this.name().toLowerCase()));
    }

    public Path getJobConfigPath() {
        return Path.of(String.format(
                "%s/config/launch-%s.yaml",
                K8SUtil.getWorkingDirectory(), this.name().toLowerCase()));
    }

    public String getServiceName(final String sessionID) {
        return String.format("skaha-%s-svc-%s", this.name().toLowerCase(), sessionID);
    }

    public String getIngressName(final String sessionID) {
        return String.format("skaha-%s-ingress-%s", this.name().toLowerCase(), sessionID);
    }

    public String getMiddlewareName(final String sessionID) {
        return String.format("skaha-%s-middleware-%s", this.name().toLowerCase(), sessionID);
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
