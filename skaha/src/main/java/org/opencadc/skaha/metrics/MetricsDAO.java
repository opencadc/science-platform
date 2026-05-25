package org.opencadc.skaha.metrics;

/**
 * Data access for platform and per-pod metrics used by Skaha session APIs.
 *
 * <p>Platform snapshots come from the co-deployed Metrics HTTP API; pod usage comes from the Kubernetes metrics API.
 */
public interface MetricsDAO {

    /**
     * Fetch the current platform metrics snapshot.
     *
     * @return platform metrics from the Metrics backend
     * @throws Exception if the snapshot cannot be retrieved
     */
    PlatformMetrics getPlatformMetrics() throws Exception;

    /**
     * Fetch per-pod CPU and memory usage for session workloads in the Skaha namespace.
     *
     * @param userID constrain by user ID when non-null/non-blank; otherwise all users
     * @param omitHeadless when true, exclude headless session pods
     * @return pod metrics keyed by pod name (raw Kubernetes quantity strings)
     * @throws Exception if pod metrics cannot be retrieved
     */
    PodMetrics getPodMetrics(String userID, boolean omitHeadless) throws Exception;
}
