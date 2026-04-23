package org.opencadc.skaha.session;

import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1PersistentVolume;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaim;
import io.kubernetes.client.openapi.models.V1PodSecurityContext;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1SecurityContext;
import io.kubernetes.client.openapi.models.V1Volume;
import io.kubernetes.client.openapi.models.V1VolumeMount;

import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates configuration information per User Session Type. This will contain Kubernetes (YAML) specific
 * configuration, typically loaded from a mounted ConfigMap that was set by a Helm Chart. This supports deployer
 * provided configuration such as: - Pod Security Context - Resource Requests and Limits - Environment Variables -
 * Volumes and Volume Mounts
 */
public class SessionProfileConfiguration {
    private V1SecurityContext securityContextConfig;
    private V1PodSecurityContext podSecurityContextConfig;
    private final List<V1Volume> volumeConfig = new ArrayList<>();
    private final List<V1VolumeMount> volumeMountConfig = new ArrayList<>();
    private final List<V1EnvVar> environmentVariableConfig = new ArrayList<>();
    private String hostname;
    private String namespace;
    private String topLevelDirectory;
    private String projectsDirectory;
    private String homeDirectory;
    private String imagePullPolicy;

    public SessionProfileConfiguration() {}

    public V1SecurityContext getSecurityContextConfig() {
        return securityContextConfig;
    }

    public V1PodSecurityContext getPodSecurityContextConfig() {
        return podSecurityContextConfig;
    }

    public List<V1Volume> getVolumeConfig() {
        return volumeConfig;
    }

    public List<V1VolumeMount> getVolumeMountConfig() {
        return volumeMountConfig;
    }

    public List<V1EnvVar> getEnvironmentVariableConfig() {
        return environmentVariableConfig;
    }

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getTopLevelDirectory() {
        return topLevelDirectory;
    }

    public void setTopLevelDirectory(String topLevelDirectory) {
        this.topLevelDirectory = topLevelDirectory;
    }

    public String getHomeDirectory() {
        return homeDirectory;
    }

    public void setHomeDirectory(String homeDirectory) {
        this.homeDirectory = homeDirectory;
    }

    public String getProjectsDirectory() {
        return projectsDirectory;
    }

    public void setProjectsDirectory(String projectsDirectory) {
        this.projectsDirectory = projectsDirectory;
    }

    public String getImagePullPolicy() {
        return imagePullPolicy;
    }

    public void setImagePullPolicy(String imagePullPolicy) {
        this.imagePullPolicy = imagePullPolicy;
    }

    public void setSecurityContextConfig(V1SecurityContext securityContextConfig) {
        this.securityContextConfig = securityContextConfig;
    }

    public void setPodSecurityContextConfig(V1PodSecurityContext podSecurityContextConfig) {
        this.podSecurityContextConfig = podSecurityContextConfig;
    }

    public void setVolumeConfig(List<V1Volume> volumeConfig) {
        if (volumeConfig != null) {
            this.volumeConfig.clear();
            this.volumeConfig.addAll(volumeConfig);
        }
    }

    public void setVolumeMountConfig(List<V1VolumeMount> volumeMountConfig) {
        if (volumeMountConfig != null) {
            this.volumeMountConfig.clear();
            this.volumeMountConfig.addAll(volumeMountConfig);
        }
    }

    public void setEnvironmentVariableConfig(List<V1EnvVar> environmentVariableConfig) {
        if (environmentVariableConfig != null) {
            this.environmentVariableConfig.clear();
            this.environmentVariableConfig.addAll(environmentVariableConfig);
        }
    }
}
