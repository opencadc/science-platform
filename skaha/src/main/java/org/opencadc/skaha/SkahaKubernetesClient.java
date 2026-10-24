package org.opencadc.skaha;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

/**
 * The one Fabric8 client per replica. It is configured the standard Fabric8 way (the in-cluster ServiceAccount, with
 * token rotation, and the {@code KUBERNETES_*} environment settings), defaults to the workload namespace, and never
 * resends a request: a create must not be repeated blindly, so callers own any retry.
 */
public final class SkahaKubernetesClient {
    private static KubernetesClient client;

    private SkahaKubernetesClient() {}

    /** Build the client for the workload namespace. */
    public static synchronized void initialize(final String workloadNamespace) {
        install(new KubernetesClientBuilder()
                .withConfig(new ConfigBuilder(Config.autoConfigure(null))
                        .withNamespace(workloadNamespace)
                        .withRequestRetryBackoffLimit(0)
                        .build())
                .build());
    }

    /** Use the given client, e.g. one pointed at a mock API server in tests. */
    public static synchronized void install(final KubernetesClient kubernetesClient) {
        if (SkahaKubernetesClient.client != null) {
            throw new IllegalStateException("Kubernetes client is already initialized");
        }
        SkahaKubernetesClient.client = kubernetesClient;
    }

    public static synchronized KubernetesClient get() {
        if (SkahaKubernetesClient.client == null) {
            throw new IllegalStateException("Kubernetes client is not initialized");
        }
        return SkahaKubernetesClient.client;
    }

    /** Close and forget the client. Safe to call when there is none. */
    public static synchronized void close() {
        if (SkahaKubernetesClient.client != null) {
            SkahaKubernetesClient.client.close();
            SkahaKubernetesClient.client = null;
        }
    }
}
