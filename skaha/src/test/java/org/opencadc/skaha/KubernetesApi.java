package org.opencadc.skaha;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.server.mock.KubernetesMixedDispatcher;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.mockwebserver.Context;
import io.fabric8.mockwebserver.MockWebServer;
import io.fabric8.mockwebserver.ServerRequest;
import io.fabric8.mockwebserver.ServerResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import org.junit.rules.ExternalResource;

/**
 * A mock Kubernetes API for tests: objects are stored and watched (CRUD), with exact-path expectations taking
 * precedence. The rule installs a client configured like production (workload namespace, no HTTP retries) as
 * {@link SkahaKubernetesClient} and removes it afterwards.
 */
public class KubernetesApi extends ExternalResource {
    public static final String NAMESPACE = "skaha-workload";

    private final Map<ServerRequest, Queue<ServerResponse>> responses = new HashMap<>();
    private final KubernetesMockServer server = new KubernetesMockServer(
            new Context(), new MockWebServer(), this.responses, new KubernetesMixedDispatcher(this.responses), false);
    private KubernetesClient client;

    @Override
    protected void before() {
        this.server.init();
        this.client = client(10_000);
        SkahaKubernetesClient.install(this.client);
    }

    @Override
    protected void after() {
        SkahaKubernetesClient.close();
        this.server.destroy();
    }

    public KubernetesClient client() {
        return this.client;
    }

    /** Another client on this API, with its own request timeout. The caller closes it. */
    public KubernetesClient client(final int requestTimeoutMillis) {
        return new KubernetesClientBuilder()
                .withConfig(new ConfigBuilder(Config.empty())
                        .withMasterUrl(this.server.url("/"))
                        .withNamespace(NAMESPACE)
                        .withRequestRetryBackoffLimit(0)
                        .withRequestTimeout(requestTimeoutMillis)
                        .build())
                .build();
    }

    public KubernetesMockServer server() {
        return this.server;
    }
}
