package org.opencadc.skaha.metrics;

import ca.nrc.cadc.util.StringUtil;
import io.kubernetes.client.Metrics;
import io.kubernetes.client.custom.PodMetricsList;
import io.kubernetes.client.openapi.Configuration;
import java.util.ArrayList;
import java.util.List;
import org.opencadc.skaha.K8SUtil;
import org.opencadc.skaha.session.SessionAction;

/** Pod usage from the Kubernetes metrics API ({@code metrics.k8s.io}). */
final class KubernetesPodUsageProvider implements PodUsageProvider {

    private final Metrics metricsClient;

    public static KubernetesPodUsageProvider fromConfiguration() {
        return new KubernetesPodUsageProvider(new Metrics(Configuration.getDefaultApiClient()));
    }

    private KubernetesPodUsageProvider(final Metrics metricsClient) {
        this.metricsClient = metricsClient;
    }

    @Override
    public PodMetrics getPodMetrics(final String userID, final boolean omitHeadless) throws Exception {
        final String labelSelector = KubernetesPodUsageProvider.buildLabelSelector(userID, omitHeadless);
        final PodMetricsList podMetricsList =
                metricsClient.getPodMetrics(K8SUtil.getWorkloadNamespace(), labelSelector);
        return PodMetrics.fromKubernetes(podMetricsList);
    }

    /**
     * Build a Kubernetes label selector for session pods in the workload namespace.
     *
     * @return comma-separated selector, or {@code ""} when no filters apply (all pods in the namespace, same as
     *     historical {@code kubectl top pod} without {@code -l})
     */
    static String buildLabelSelector(final String userID, final boolean omitHeadless) {
        final List<String> labelSelectors = new ArrayList<>();
        if (StringUtil.hasLength(userID)) {
            labelSelectors.add("canfar-net-userid=" + userID);
        }
        if (omitHeadless) {
            labelSelectors.add("canfar-net-sessionType!=" + SessionAction.SESSION_TYPE_HEADLESS);
        }
        return String.join(",", labelSelectors);
    }
}
