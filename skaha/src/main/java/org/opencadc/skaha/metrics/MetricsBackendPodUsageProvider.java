package org.opencadc.skaha.metrics;

/**
 * Future pod-usage source via the Metrics backend HTTP API.
 *
 * <p>Not implemented until the Metrics service exposes a pod-usage endpoint.
 */
final class MetricsBackendPodUsageProvider implements PodUsageProvider {

    @Override
    public PodMetrics getPodMetrics(final String userID, final boolean omitHeadless) {
        throw new UnsupportedOperationException("Metrics backend pod usage is not implemented; use "
                + PodUsageProvider.SKAHA_POD_METRICS_SOURCE + "=" + PodUsageProvider.SOURCE_KUBERNETES);
    }
}
