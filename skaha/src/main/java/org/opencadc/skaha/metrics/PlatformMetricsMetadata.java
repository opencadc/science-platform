package org.opencadc.skaha.metrics;

import java.time.Instant;

/**
 * Metrics response metadata for a platform metrics snapshot.
 *
 * @param created snapshot time ({@code metadata.created} from the Metrics API)
 */
public record PlatformMetricsMetadata(Instant created) {}
