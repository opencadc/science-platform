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
    FIREFLY(true, true, "firefly"),
    BATCH(false, false, "batch");

    private final boolean supportsIngress;
    private final boolean supportsService;
    public final String applicationName;

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

    /**
     * The Job name. A batch's Pod names add the index and five characters to it; leaving out the user keeps them within
     * 63.
     */
    public String jobName(final String sessionID, final String userID) {
        return this == BATCH ? ("batch-" + sessionID).toLowerCase() : K8SUtil.getJobName(sessionID, this, userID);
    }

    public Path getJobConfigPath() {
        return Path.of(String.format(
                "%s/config/launch-%s.yaml",
                K8SUtil.getWorkingDirectory(), this.name().toLowerCase()));
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

    /**
     * False for the kinds that run unattended from a command line (headless and batch). They take {@code cmd},
     * {@code args} and {@code env}, do not count toward the interactive session cap, and the interactive listing leaves
     * them out.
     */
    public boolean isInteractive() {
        return this != HEADLESS && this != BATCH;
    }

    public boolean isDesktop() {
        return this == DESKTOP;
    }
}
