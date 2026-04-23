package org.opencadc.skaha.session;

import ca.nrc.cadc.util.FileUtil;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.util.Yaml;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Load and Map the Kubernetes objects defined in the YAML template for a given SessionType to their corresponding
 * KubernetesObject representations.  Duplicate keys (e.g., multiple Deployments) will result in an
 * IllegalArgumentException being thrown, as the current implementation.
 */
class TemplateLoader {
    /**
     * Loads the YAML template for the given session type and returns a list of Kubernetes objects defined in the template.
     * @param type  The session type for which to load the template.
     * @return  Map of Kubernetes objects defined in the template, keyed by their type (e.g., Deployment, Service, Ingress).
     * @throws IOException  Any read errors encountered while loading the template file will be thrown as an IOException.
     */
    static Map<TemplateObjectType, KubernetesObject> loadTemplate(final SessionType type) throws IOException {
        final String templateFileName = type.getTemplateName();
        final File templateFile = FileUtil.getFileFromResource(templateFileName, TemplateLoader.class);
        return Yaml.loadAll(templateFile).stream().map(obj -> {
            if (obj instanceof KubernetesObject) {
                return (KubernetesObject) obj;
            } else {
                throw new IllegalArgumentException(String.format(
                        "Unexpected object type in template: %s. Expected a KubernetesObject.",
                        obj.getClass().getName()));
            }
        }).map(kubernetesObj -> {
            final String kind = kubernetesObj.getKind();
            return switch (kind.toLowerCase()) {
                case "job" -> Map.entry(TemplateObjectType.JOB, kubernetesObj);
                case "service" -> Map.entry(TemplateObjectType.SERVICE, kubernetesObj);
                case "ingress" -> Map.entry(TemplateObjectType.INGRESS, kubernetesObj);
                default -> throw new IllegalArgumentException(String.format(
                        "Unexpected Kubernetes object kind in template: %s. Expected Deployment, Service, or Ingress.",
                        kind));
            };
        }).collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    enum TemplateObjectType {
        DEPLOYMENT,
        JOB,
        SERVICE,
        INGRESS
    }
}
