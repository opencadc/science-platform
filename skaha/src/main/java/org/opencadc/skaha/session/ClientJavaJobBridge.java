package org.opencadc.skaha.session;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.util.Yaml;

/**
 * Converts the client-java Job built by {@link SessionJobBuilder} into a Fabric8 Job through its YAML form, so every
 * field is carried over. Temporary: removed once SessionJobBuilder builds Fabric8 models.
 */
final class ClientJavaJobBridge {
    private ClientJavaJobBridge() {}

    static Job toFabric8(final V1Job job) {
        return Serialization.unmarshal(Yaml.dump(job), Job.class);
    }
}
