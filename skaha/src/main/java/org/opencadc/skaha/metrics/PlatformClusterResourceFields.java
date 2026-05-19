package org.opencadc.skaha.metrics;

/**
 * Cluster-wide CPU and RAM figures mapped from {@link PlatformMetrics} for legacy
 * {@code org.opencadc.skaha.session.ResourceStats} fields.
 *
 * <p>{@code cpuCoresAvailable} and {@code ramAvailable} carry <strong>platform capacity</strong>;
 * {@code requestedCPUCores} and {@code requestedRAM} carry <strong>platform allocation</strong> (legacy names retained
 * for API compatibility). Session ceiling ({@code maxCPUCores}, {@code maxRAM}) is not populated in this slice.
 */
public record PlatformClusterResourceFields(
        Double requestedCPUCores, String requestedRAM, Double cpuCoresAvailable, String ramAvailable) {}
