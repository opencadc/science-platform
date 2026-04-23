package org.opencadc.skaha.session;

import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1ContainerPort;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobSpec;
import io.kubernetes.client.openapi.models.V1PodSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class CartaSessionProfile implements SessionProfile {
    @Override
    public void configure(
            V1Job job, SessionContext sessionContext, SessionProfileConfiguration sessionProfileConfiguration) {
        final V1JobSpec jobSpec = Objects.requireNonNullElse(job.getSpec(), new V1JobSpec());
        final V1PodSpec podSpec =
                Objects.requireNonNullElse(jobSpec.getTemplate().getSpec(), new V1PodSpec());
        final V1Container container = podSpec.getContainers().getFirst();
        container.setCommand(Arrays.asList(
                "/bin/sh",
                "-c",
                String.format(
                        "/skaha-system/skaha-carta.sh %s %s \"%s\"",
                        sessionProfileConfiguration.getTopLevelDirectory(),
                        sessionProfileConfiguration.getProjectsDirectory(),
                        sessionContext.getSessionURLPath())));

        container.addEnvItem(
                new V1EnvVar().name("OMP_NUM_THREADS").value(Integer.toString(sessionContext.getThreadCount())));

        final List<V1ContainerPort> ports = Objects.requireNonNullElse(container.getPorts(), new ArrayList<>());
        ports.add(new V1ContainerPort().containerPort(6901).protocol("TCP").name("frontend-port"));
        container.setPorts(ports);
    }
}
