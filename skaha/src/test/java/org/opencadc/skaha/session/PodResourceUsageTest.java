package org.opencadc.skaha.session;

import org.junit.Assert;
import org.junit.Test;

public class PodResourceUsageTest {
    @Test
    public void testPrintCores() {
        Assert.assertEquals("Wrong core unit.", "1.367", PodResourceUsage.toCoreUnit("1367m"));
        Assert.assertEquals("Wrong core unit.", "0.233", PodResourceUsage.toCoreUnit("233m"));
        Assert.assertEquals("Wrong core unit.", "0.001", PodResourceUsage.toCoreUnit("900000n"));
        Assert.assertEquals("Wrong core unit.", "2.34", PodResourceUsage.toCoreUnit("2.34"));
    }

    @Test
    public void testPrintMemory() {
        Assert.assertEquals("Wrong memory unit.", "1.61", PodResourceUsage.toCommonMemoryUnit("1536Mi"));
        Assert.assertEquals("Wrong memory unit.", "0.54", PodResourceUsage.toCommonMemoryUnit("512Mi"));
        Assert.assertEquals("Wrong memory unit.", "0.51", PodResourceUsage.toCommonMemoryUnit("512M"));
        Assert.assertEquals("Wrong memory unit.", "0.00", PodResourceUsage.toCommonMemoryUnit("1Ki"));
        Assert.assertEquals("Wrong memory unit.", "0.01", PodResourceUsage.toCommonMemoryUnit("6420K"));
        Assert.assertEquals("Wrong memory unit.", "2.51", PodResourceUsage.toCommonMemoryUnit("2.34Gi"));
        Assert.assertEquals("Wrong memory unit.", "2.34", PodResourceUsage.toCommonMemoryUnit("2.34G"));
    }

    @Test
    public void testToBytes() {
        Assert.assertEquals(
                "Wrong bytes.", 1536L * 1024L * 1024L, PodResourceUsage.MemoryUnitConverter.toBytes("1536Mi"));
        Assert.assertEquals(
                "Wrong bytes.", 512L * 1024L * 1024L, PodResourceUsage.MemoryUnitConverter.toBytes("512Mi"));
        Assert.assertEquals("Wrong bytes.", 512L * 1000L * 1000L, PodResourceUsage.MemoryUnitConverter.toBytes("512M"));
        Assert.assertEquals("Wrong bytes.", 1024L, PodResourceUsage.MemoryUnitConverter.toBytes("1Ki"));
        Assert.assertEquals("Wrong bytes.", 6420L * 1000L, PodResourceUsage.MemoryUnitConverter.toBytes("6420K"));
        Assert.assertEquals(
                "Wrong bytes.",
                (long) (2.34 * 1024L * 1024L * 1024L),
                PodResourceUsage.MemoryUnitConverter.toBytes("2.34Gi"));
        Assert.assertEquals(
                "Wrong bytes.",
                (long) (2.34 * 1000L * 1000L * 1000L),
                PodResourceUsage.MemoryUnitConverter.toBytes("2.34G"));
    }
}
