package org.opencadc.skaha.job;

import io.kubernetes.client.openapi.models.V1Ingress;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.util.Yaml;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import org.opencadc.skaha.K8SUtil;

public class KubernetesJob {
    V1Job job;
    V1Ingress ingress;
    V1Service service;

    KubernetesJob() {}

    public KubernetesJob withJob(final V1Job job) {
        this.job = job;
        return this;
    }

    public KubernetesJob withIngress(final V1Ingress ingress) {
        this.ingress = ingress;
        return this;
    }

    public KubernetesJob withService(final V1Service service) {
        this.service = service;
        return this;
    }

    public static KubernetesJobBuilder builder(final Job job) {
        return new KubernetesJobBuilder(job);
    }

    public void run() {
        final KubernetesExecutor executor = new KubernetesExecutor();
        executor.run(this.job);
    }

    public static class KubernetesJobBuilder extends JobBuilder {
        private final Job job;

        private KubernetesJobBuilder(final Job job) {
            this.job = job;
        }

        public KubernetesJob build() {
            return new KubernetesJob()
                    .withService(loadService())
                    .withJob(loadJob())
                    .withIngress(loadIngress());
        }

        private V1Job loadJob() {
            try (final Reader reader = new FileReader(getJobConfigPath().toFile())) {
                return (V1Job) Yaml.load(reader);
            } catch (IOException ioException) {
                throw new IllegalStateException(
                        "Cannot find or use job config file: " + ioException.getMessage(), ioException);
            }
        }

        private V1Service loadService() {
            if (this.job.getType().supportsService()) {
                try (final Reader reader = new FileReader(getServiceConfigPath().toFile())) {
                    return (V1Service) Yaml.load(reader);
                } catch (IOException ioException) {
                    throw new IllegalStateException(
                            "Cannot find or use service config file: " + ioException.getMessage(), ioException);
                }
            } else {
                return null;
            }
        }

        private V1Ingress loadIngress() {
            if (this.job.getType().supportsIngress()) {
                try (final Reader reader = new FileReader(getIngressConfigPath().toFile())) {
                    return (V1Ingress) Yaml.load(reader);
                } catch (IOException ioException) {
                    throw new IllegalStateException(
                            "Cannot find or use ingress config file: " + ioException.getMessage(), ioException);
                }
            } else {
                return null;
            }
        }

        private Path getServiceConfigPath() {
            return Path.of(String.format(
                    "%s/config/service-%s.yaml",
                    K8SUtil.getWorkingDirectory(), this.job.getType().name().toLowerCase()));
        }

        private Path getIngressConfigPath() {
            return Path.of(String.format(
                    "%s/config/ingress-%s.yaml",
                    K8SUtil.getWorkingDirectory(), this.job.getType().name().toLowerCase()));
        }

        private Path getJobConfigPath() {
            return Path.of(String.format(
                    "%s/config/launch-%s.yaml",
                    K8SUtil.getWorkingDirectory(), this.job.getType().name().toLowerCase()));
        }

        private String getServiceName(final String sessionID) {
            return String.format("skaha-%s-svc-%s", this.job.getType().name().toLowerCase(), sessionID);
        }

        private String getIngressName(final String sessionID) {
            return String.format(
                    "skaha-%s-ingress-%s", this.job.getType().name().toLowerCase(), sessionID);
        }

        private String getMiddlewareName(final String sessionID) {
            return String.format(
                    "skaha-%s-middleware-%s", this.job.getType().name().toLowerCase(), sessionID);
        }
    }
}
