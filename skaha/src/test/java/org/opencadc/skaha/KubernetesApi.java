package org.opencadc.skaha;

import io.fabric8.kubernetes.api.model.APIResourceListBuilder;
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
import io.fabric8.mockwebserver.http.RecordedRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.function.BooleanSupplier;
import org.junit.Assert;
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
        // Traefik resources are generic: the client discovers their plural names.
        this.server
                .expect()
                .get()
                .withPath("/apis/traefik.io/v1alpha1")
                .andReturn(
                        200,
                        new APIResourceListBuilder()
                                .withGroupVersion("traefik.io/v1alpha1")
                                .addNewResource()
                                .withKind("Middleware")
                                .withName("middlewares")
                                .withNamespaced(true)
                                .endResource()
                                .addNewResource()
                                .withKind("IngressRoute")
                                .withName("ingressroutes")
                                .withNamespaced(true)
                                .endResource()
                                .build())
                .always();
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

    /** Every request received so far, as "METHOD path". */
    public List<String> requests() throws InterruptedException {
        final List<String> requests = new ArrayList<>();
        RecordedRequest request;
        while ((request = this.server.takeRequest(10, java.util.concurrent.TimeUnit.MILLISECONDS)) != null) {
            requests.add(request.getMethod() + " " + request.getPath());
        }
        return requests;
    }

    /** Wait up to 10 seconds, e.g. for a watch to deliver a change. */
    public static void await(final String what, final BooleanSupplier condition) throws InterruptedException {
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                Assert.fail("Timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }
}
