package org.opencadc.skaha.session;

import io.kubernetes.client.openapi.models.*;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

public class SessionPlatformConfiguration {
    private static final Path CONFIG_FILE_PATH = Path.of("/config/session-platform.yaml");

    private final Map<SessionType, SessionProfileConfiguration> sessionProfileConfigurations = new HashMap<>();

    private SessionPlatformConfiguration(
            final Map<SessionType, SessionProfileConfiguration> sessionProfileConfigurations) {
        this.sessionProfileConfigurations.putAll(Objects.requireNonNull(
                sessionProfileConfigurations, "Session profile configuration map cannot be null"));
    }

    public static SessionPlatformConfiguration fromConfigurationDirectory() {
        try (final BufferedReader reader =
                new BufferedReader(new FileReader(SessionPlatformConfiguration.CONFIG_FILE_PATH.toFile()))) {

            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

            final TypeReference<Map<String, SessionProfileConfiguration>> typeRef = new TypeReference<>() {};
            final Map<SessionType, SessionProfileConfiguration> sessionProfileConfigurationMap =
                    yamlMapper.readValue(reader, typeRef).entrySet().stream()
                            .map(entry -> {
                                final SessionType sessionType = SessionType.fromApplicationStringType(entry.getKey());
                                return Map.entry(sessionType, entry.getValue());
                            })
                            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

            return new SessionPlatformConfiguration(sessionProfileConfigurationMap);
        } catch (IOException exception) {
            throw new RuntimeException(
                    "Failed to load session platform configuration from " + CONFIG_FILE_PATH, exception);
        }
    }

    public SessionProfileConfiguration getSessionProfileConfiguration(SessionType sessionType) {
        return this.sessionProfileConfigurations.get(sessionType);
    }
}
