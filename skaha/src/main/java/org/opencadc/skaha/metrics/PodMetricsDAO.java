package org.opencadc.skaha.metrics;

import ca.nrc.cadc.util.StringUtil;
import io.kubernetes.client.Metrics;
import io.kubernetes.client.custom.PodMetricsList;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import java.util.ArrayList;
import java.util.List;
import org.opencadc.skaha.K8SUtil;
import org.opencadc.skaha.session.SessionAction;

/**
 * Fetches per-pod resource usage from the Kubernetes metrics API ({@code metrics.k8s.io}).
 *
 * <p>Uses {@link Configuration#getDefaultApiClient()} (in-cluster service account). Does not call
 * {@link io.kubernetes.client.util.Config#fromCluster()} here.
 */
public class PodMetricsDAO {

    private final Metrics metricsClient;

    public PodMetricsDAO() {
        this(new Metrics(Configuration.getDefaultApiClient()));
    }

    PodMetricsDAO(final Metrics metricsClient) {
        this.metricsClient = metricsClient;
    }

    public PodMetrics getPodMetrics(final String userID, final boolean omitHeadless) throws ApiException {
        final String labelSelector = buildLabelSelector(userID, omitHeadless);
        final PodMetricsList podMetricsList =
                metricsClient.getPodMetrics(K8SUtil.getWorkloadNamespace(), labelSelector);
        return PodMetricsMapper.fromKubernetes(podMetricsList);
    }

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
