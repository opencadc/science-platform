package org.opencadc.skaha.job;

import ca.nrc.cadc.rest.SyncInput;
import ca.nrc.cadc.util.StringUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import org.opencadc.skaha.SessionType;
import org.opencadc.skaha.context.ResourceContexts;

public class HeadlessJob extends Job {
    private final String command;
    private final String[] arguments;
    private final Properties environmentVariables = new Properties();

    HeadlessJob(final HeadlessJobBuilder builder) {
        super(builder);

        this.command = Objects.requireNonNull(builder.command, "Command cannot be null");
        this.arguments = builder.arguments.toArray(new String[0]);
        this.environmentVariables.putAll(builder.environmentVariables);
    }

    @Override
    public SessionType getType() {
        return SessionType.HEADLESS;
    }

    public static HeadlessJobBuilder builder() {
        return new HeadlessJobBuilder();
    }

    public static class HeadlessJobBuilder extends JobBuilder {
        static final String COMMAND_PARAMETER = "cmd";
        static final String ARGUMENTS_PARAMETER = "args";
        static final String ENVIRONMENT_PARAMETER = "env";

        private String command;
        private final List<String> arguments = new ArrayList<>();
        private final Properties environmentVariables = new Properties();

        @Override
        public <T extends JobBuilder> T withRequest(SyncInput syncInput, ResourceContexts resourceContexts) {
            final HeadlessJobBuilder headlessJobBuilder = super.withRequest(syncInput, resourceContexts);
            headlessJobBuilder.command = syncInput.getParameter(HeadlessJobBuilder.COMMAND_PARAMETER);

            final String args = syncInput.getParameter(HeadlessJobBuilder.ARGUMENTS_PARAMETER);
            if (StringUtil.hasText(args)) {
                headlessJobBuilder.arguments.addAll(Arrays.asList(args.split("\\s+")));
            }

            final List<String> envs = syncInput.getParameters(HeadlessJobBuilder.ENVIRONMENT_PARAMETER);
            if (envs != null) {
                for (final String env : envs) {
                    String[] parts = env.split("=", 2);
                    if (parts.length == 2) {
                        headlessJobBuilder.environmentVariables.setProperty(parts[0].trim(), parts[1].trim());
                    } else {
                        throw new IllegalArgumentException("Invalid environment variable format: " + env);
                    }
                }
            }

            return (T) headlessJobBuilder;
        }

        public HeadlessJobBuilder withCommand(String command) {
            this.command = Objects.requireNonNull(command, "Command cannot be null");
            return this;
        }

        public HeadlessJobBuilder withArgument(String argument) {
            this.arguments.add(Objects.requireNonNull(argument, "Arguments cannot be null"));
            return this;
        }

        public HeadlessJobBuilder withEnvironmentVariable(String key, String value) {
            Objects.requireNonNull(key, "Environment variable key cannot be null");
            Objects.requireNonNull(value, "Environment variable value cannot be null");
            this.environmentVariables.setProperty(key, value);
            return this;
        }

        public HeadlessJob build() {
            return new HeadlessJob(this);
        }
    }
}
