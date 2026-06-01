package org.opencadc.skaha;

import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.informer.cache.Lister;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobList;
import io.kubernetes.client.openapi.models.V1Node;
import io.kubernetes.client.openapi.models.V1NodeList;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.util.generic.GenericKubernetesApi;
import java.util.List;
import org.apache.log4j.Logger;

/**
 * Kubernetes informer cache that maintains local mirrors of Jobs, Pods, and Nodes via watch streams. This eliminates
 * per-request API calls to the K8s API server.
 */
public class K8SInformerCache {
    private static final Logger LOGGER = Logger.getLogger(K8SInformerCache.class);
    private static final long RESYNC_PERIOD_MILLIS = 30_000L;
    private static final long SYNC_TIMEOUT_MILLIS = 60_000L;

    private static SharedInformerFactory factory;
    private static SharedIndexInformer<V1Job> jobInformer;
    private static SharedIndexInformer<V1Pod> podInformer;
    private static SharedIndexInformer<V1Node> nodeInformer;
    private static Lister<V1Job> jobLister;
    private static Lister<V1Pod> podLister;
    private static Lister<V1Node> nodeLister;

    public static synchronized void start(final ApiClient client) {
        if (factory != null) {
            LOGGER.warn("Informer cache already started");
            return;
        }

        final String namespace = K8SUtil.getWorkloadNamespace();
        LOGGER.info("Starting K8S informer cache for namespace: " + namespace);

        factory = new SharedInformerFactory(client);

        final GenericKubernetesApi<V1Job, V1JobList> jobApi =
                new GenericKubernetesApi<>(V1Job.class, V1JobList.class, "batch", "v1", "jobs", client);
        jobInformer = factory.sharedIndexInformerFor(jobApi, V1Job.class, RESYNC_PERIOD_MILLIS, namespace);

        final GenericKubernetesApi<V1Pod, V1PodList> podApi =
                new GenericKubernetesApi<>(V1Pod.class, V1PodList.class, "", "v1", "pods", client);
        podInformer = factory.sharedIndexInformerFor(podApi, V1Pod.class, RESYNC_PERIOD_MILLIS, namespace);

        final GenericKubernetesApi<V1Node, V1NodeList> nodeApi =
                new GenericKubernetesApi<>(V1Node.class, V1NodeList.class, "", "v1", "nodes", client);
        nodeInformer = factory.sharedIndexInformerFor(nodeApi, V1Node.class, RESYNC_PERIOD_MILLIS);

        jobLister = new Lister<>(jobInformer.getIndexer(), namespace);
        podLister = new Lister<>(podInformer.getIndexer(), namespace);
        nodeLister = new Lister<>(nodeInformer.getIndexer());

        factory.startAllRegisteredInformers();

        final long startTime = System.currentTimeMillis();
        while (!jobInformer.hasSynced() || !podInformer.hasSynced() || !nodeInformer.hasSynced()) {
            if (System.currentTimeMillis() - startTime > SYNC_TIMEOUT_MILLIS) {
                LOGGER.warn("Informer cache did not sync within " + SYNC_TIMEOUT_MILLIS + "ms. Proceeding anyway.");
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        LOGGER.info("K8S informer cache started. Jobs synced: " + jobInformer.hasSynced() + ", Pods synced: "
                + podInformer.hasSynced() + ", Nodes synced: " + nodeInformer.hasSynced());
    }

    public static synchronized void stop() {
        if (factory != null) {
            factory.stopAllRegisteredInformers();
            factory = null;
            LOGGER.info("K8S informer cache stopped");
        }
    }

    public static boolean isRunning() {
        return factory != null && jobInformer != null && jobInformer.hasSynced();
    }

    public static List<V1Job> listJobs() {
        return jobLister.list();
    }

    public static V1Job getJob(final String name) {
        return jobLister.get(name);
    }

    public static List<V1Pod> listPods() {
        return podLister.list();
    }

    public static List<V1Node> listNodes() {
        return nodeLister.list();
    }
}
