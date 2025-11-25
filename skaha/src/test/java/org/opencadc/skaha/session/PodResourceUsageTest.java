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
}
