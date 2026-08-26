package org.opencadc.skaha.metrics;

import java.time.Instant;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class PlatformMetricsTest {
    private static final Instant SNAPSHOT_CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private static final String ONE_BYTE_IN_GIB = "0.000000000931322574615478515625Gi";
    private static final String ONE_GIB_PLUS_ONE_BYTE = "1.000000000931322574615478515625Gi";

    @Test
    public void holdsPlatformCapacityAndAllocationFromFixture() {
        final Map<String, String> capacity = Map.of("cpu", "64", "memory", "512Gi");
        final Map<String, String> allocated = Map.of("cpu", "12.5", "memory", "128Gi");

        final PlatformMetrics metrics = new PlatformMetrics(
                new PlatformMetrics.Metadata(SNAPSHOT_CREATED), new PlatformMetrics.Data(capacity, allocated));

        Assert.assertEquals(SNAPSHOT_CREATED, metrics.metadata().created());
        Assert.assertEquals(capacity, metrics.data().capacity());
        Assert.assertEquals(allocated, metrics.data().allocated());
    }

    @Test
    public void rejectsInvalidCpuStringsInsteadOfReturningZero() {
        Assert.assertThrows(
                IllegalArgumentException.class,
                () -> new PlatformMetrics(
                        new PlatformMetrics.Metadata(Instant.parse("2026-01-01T00:00:00Z")),
                        new PlatformMetrics.Data(
                                Map.of("cpu", "not-a-number", "memory", "512Gi"),
                                Map.of("cpu", "12.5", "memory", "128Gi"))));
    }

    @Test
    public void toClusterResourceFieldsMapsPlatformCapacityAndAllocation() {
        final PlatformMetrics.ClusterResourceFields fields =
                PlatformMetricsFixtures.fixedPlatformMetrics().toClusterResourceFields();

        Assert.assertEquals(64.0, fields.cpuCoresAvailable(), 0.0);
        Assert.assertEquals(12.5, fields.requestedCPUCores(), 0.0);
        Assert.assertEquals("549.756G", fields.ramAvailable());
        Assert.assertEquals("137.439G", fields.requestedRAM());
    }

    @Test
    public void acceptsTinyCpuAndHighPrecisionWholeByteMemoryQuantities() {
        final PlatformMetrics metrics = new PlatformMetrics(
                new PlatformMetrics.Metadata(SNAPSHOT_CREATED),
                new PlatformMetrics.Data(
                        Map.of("cpu", "1n", "memory", ONE_BYTE_IN_GIB),
                        Map.of("cpu", "1.23456789", "memory", ONE_GIB_PLUS_ONE_BYTE)));

        final PlatformMetrics.ClusterResourceFields fields = metrics.toClusterResourceFields();

        Assert.assertEquals(1e-9, fields.cpuCoresAvailable(), 0.0);
        Assert.assertEquals(1.23456789, fields.requestedCPUCores(), 0.0);
        Assert.assertEquals("0G", fields.ramAvailable());
        Assert.assertEquals("1.074G", fields.requestedRAM());
    }

    @Test
    public void requiresCpuAndMemoryInBothResourceMaps() {
        Assert.assertThrows(
                IllegalArgumentException.class,
                () -> new PlatformMetrics.Data(Map.of("memory", "1Gi"), Map.of("cpu", "1")));
        Assert.assertThrows(
                IllegalArgumentException.class,
                () -> new PlatformMetrics.Data(Map.of("cpu", "1"), Map.of("cpu", "1", "memory", "1Gi")));
    }

    @Test
    public void rejectsNegativeNonFiniteMalformedAndOverflowQuantities() {
        for (String value : new String[] {"NaN", "Inf", "-1", "not-a-quantity", "1e9999"}) {
            Assert.assertThrows(
                    value,
                    IllegalArgumentException.class,
                    () -> new PlatformMetrics.Data(
                            Map.of("cpu", value, "memory", "1Gi"), Map.of("cpu", "1", "memory", "1Gi")));
        }
    }

    @Test
    public void rejectsInvalidOrNonRepresentableMemoryQuantities() {
        for (String value :
                new String[] {"NaN", "Inf", "-1", "not-a-quantity", "9223372036854775808", "1n", "1.23456789Gi"}) {
            Assert.assertThrows(
                    value,
                    IllegalArgumentException.class,
                    () -> new PlatformMetrics.Data(
                            Map.of("cpu", "1", "memory", value), Map.of("cpu", "1", "memory", "1Gi")));
        }
    }
}
