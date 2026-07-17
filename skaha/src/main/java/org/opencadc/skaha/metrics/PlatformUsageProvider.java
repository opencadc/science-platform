package org.opencadc.skaha.metrics;

public interface PlatformUsageProvider {
    PlatformMetrics getPlatformMetrics() throws Exception;

    /**
     * Only a single provider is supported, which is the Metrics API.
     *
     * @param metricsConfiguration The Configuration. Required.
     * @return PlatformUsageProvider instance
     */
    static PlatformUsageProvider fromConfiguration(final MetricsConfiguration metricsConfiguration) {
        return metricsConfiguration.metricsBackEndUrl == null
                ? new NoOpPlatformUsageProvider()
                : PlatformMetricsDAO.fromConfiguration(metricsConfiguration);
    }
}
