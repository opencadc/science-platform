package org.opencadc.skaha.metrics;

/** Used to be kind about unsupported platform usage querying. */
public class NoOpPlatformUsageProvider implements PlatformUsageProvider {
    @Override
    public PlatformMetrics getPlatformMetrics() {
        throw new UnsupportedOperationException("Configure the Metrics Service to obtain platform metrics.");
    }
}
