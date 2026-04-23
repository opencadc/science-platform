package org.opencadc.skaha.session;

import java.security.SecureRandom;

/**
 * Session Context class encapsulates all necessary information about a user session from a user request, including its
 * type, unique identifier, and optional metadata such as name and owner. It provides a builder-like interface for easy
 * construction. Used by the Builder to create Kubernetes resources for the session, and the SessionProfiler to
 * configure the Deployment for the type in certain cases.
 */
public class SessionContext {
    private final SessionType sessionType;
    private final String identifier;

    private String name;
    private String ownerName;
    private String image;
    private String sessionURLPath;
    private String cpuRequest;
    private String cpuLimit;
    private String memoryRequest;
    private String memoryLimit;
    private int gpuRequest;

    private int threadCount;

    private SessionContext(final SessionType sessionType, final String identifier) {
        this.sessionType = sessionType;
        this.identifier = identifier;
    }

    /**
     * Public method creation.
     *
     * @param type The session type to create a context for.
     * @return New instance of SessionContext with a generated session ID.
     */
    public static SessionContext fromType(final SessionType type) {
        return new SessionContext(type, IDGenerator.generate());
    }

    public SessionContext withName(final String name) {
        this.name = name;
        return this;
    }

    public SessionContext withOwnerName(final String ownerName) {
        this.ownerName = ownerName;
        return this;
    }

    public SessionContext withImage(final String image) {
        this.image = image;
        return this;
    }

    public SessionContext withSessionURLPath(final String sessionURLPath) {
        this.sessionURLPath = sessionURLPath;
        return this;
    }

    public SessionContext withCPURequest(final String cpuRequest) {
        this.cpuRequest = cpuRequest;
        return this;
    }

    public SessionContext withCPULimit(final String cpuLimit) {
        this.cpuLimit = cpuLimit;
        return this;
    }

    public SessionContext withMemoryRequest(final String memoryRequest) {
        this.memoryRequest = memoryRequest;
        return this;
    }

    public SessionContext withMemoryLimit(final String memoryLimit) {
        this.memoryLimit = memoryLimit;
        return this;
    }

    public SessionContext withGPURequest(final int gpuRequest) {
        this.gpuRequest = gpuRequest;
        return this;
    }

    public SessionType getSessionType() {
        return sessionType;
    }

    public String getIdentifier() {
        return identifier;
    }

    public String getName() {
        return name;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public String getImage() {
        return image;
    }

    public String getSessionURLPath() {
        return sessionURLPath;
    }

    public String getCPURequest() {
        return cpuRequest;
    }

    public String getCPULimit() {
        return cpuLimit;
    }

    public String getMemoryRequest() {
        return memoryRequest;
    }

    public String getMemoryLimit() {
        return memoryLimit;
    }

    public int getGPURequest() {
        return gpuRequest;
    }

    public int getThreadCount() {
        return Math.max(1, Integer.parseInt(this.cpuRequest));
    }

    static class IDGenerator {
        private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        private static final SecureRandom SECURE_RANDOM = new SecureRandom();

        static final int DEFAULT_LENGTH = 16;

        public static String generate() {
            return IDGenerator.generate(IDGenerator.DEFAULT_LENGTH);
        }

        public static String generate(final int length) {
            final StringBuilder sb = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                sb.append(CHARS.charAt(SECURE_RANDOM.nextInt(CHARS.length())));
            }

            return sb.toString();
        }
    }
}
