package org.opencadc.skaha.metrics;

/**
 * Data access for platform metrics from the co-deployed Metrics backend.
 *
 * <p>Callers receive a typed {@link PlatformMetrics} envelope; HTTP and deserialization belong to implementations of
 * this interface.
 */
public interface MetricsDAO {

    /**
     * Fetch the current platform metrics snapshot.
     *
     * @return platform metrics from the Metrics backend
     * @throws Exception if the snapshot cannot be retrieved
     */
    PlatformMetrics getPlatformMetrics() throws Exception;
}
