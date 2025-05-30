package org.opencadc.skaha.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * A builder for constructing kubectl commands.
 *
 * @author anujachaitanya
 */
public class KubectlCommandBuilder {
    public static KubectlCommand command(String operation) {
        return new KubectlCommand(operation);
    }

    public static class KubectlCommand {
        private final List<String> commandParts;

        private KubectlCommand(String operation) {
            this.commandParts = new ArrayList<>();
            this.commandParts.add("kubectl");
            this.commandParts.add(operation);
        }

        public KubectlCommand namespace(String namespace) {
            this.commandParts.add("--namespace");
            this.commandParts.add(namespace);
            return this;
        }

        public KubectlCommand argument(String argument) {
            this.commandParts.add(argument);
            return this;
        }

        public KubectlCommand outputFormat(String format) {
            this.commandParts.add("-o");
            this.commandParts.add(format);
            return this;
        }

        public KubectlCommand json() {
            return outputFormat("json");
        }

        public KubectlCommand option(String option, String value) {
            this.commandParts.add(option);
            this.commandParts.add(value);
            return this;
        }

        public KubectlCommand pod() {
            this.commandParts.add("pod");
            return this;
        }

        public KubectlCommand job() {
            this.commandParts.add("job");
            return this;
        }

        public KubectlCommand pvc() {
            this.commandParts.add("pvc");
            return this;
        }

        public KubectlCommand label(String label) {
            this.commandParts.add("-l");
            this.commandParts.add(label);
            return this;
        }

        public KubectlCommand noHeaders() {
            this.commandParts.add("--no-headers=true");
            return this;
        }

        public KubectlCommand selector(String selector) {
            this.commandParts.add("--selector=" + selector);
            return this;
        }

        public String[] build() {
            return this.commandParts.toArray(new String[0]);
        }
    }
}
