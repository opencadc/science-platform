package org.opencadc.skaha.metrics;

/**
 * Platform metrics envelope returned by the Metrics backend {@code GET /api/v1/metrics/platform}.
 *
 * <p>Combines snapshot metadata with {@linkplain PlatformMetricsData platform capacity} and
 * {@linkplain PlatformMetricsData platform allocation} resource maps.
 *
 * @param metadata snapshot metadata including {@link PlatformMetricsMetadata#created()}
 * @param data platform capacity and allocation keyed by Kueue resource names
 */
public record PlatformMetrics(PlatformMetricsMetadata metadata, PlatformMetricsData data) {}
