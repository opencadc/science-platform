package org.opencadc.skaha.metrics;

import org.junit.Assert;
import org.junit.Test;

public class ResourceQuantityFormatterTest {
    @Test
    public void testPrintCores() {
        Assert.assertEquals("Wrong core unit.", "1.367", ResourceQuantityFormatter.toCoreUnit("1367m"));
        Assert.assertEquals("Wrong core unit.", "0.233", ResourceQuantityFormatter.toCoreUnit("233m"));
        Assert.assertEquals("Wrong core unit.", "0.001", ResourceQuantityFormatter.toCoreUnit("900000n"));
        Assert.assertEquals("Wrong core unit.", "2.34", ResourceQuantityFormatter.toCoreUnit("2.34"));
    }

    @Test
    public void toCoreUnitPassesThroughWhenNumericPartInvalid() {
        Assert.assertEquals("1.5m", ResourceQuantityFormatter.toCoreUnit("1.5m"));
        Assert.assertEquals("badm", ResourceQuantityFormatter.toCoreUnit("badm"));
    }

    @Test
    public void testPrintMemory() {
        Assert.assertEquals("Wrong memory unit.", "1.61", ResourceQuantityFormatter.toSessionMemoryGb("1536Mi"));
        Assert.assertEquals("Wrong memory unit.", "0.54", ResourceQuantityFormatter.toSessionMemoryGb("512Mi"));
        Assert.assertEquals("Wrong memory unit.", "0.51", ResourceQuantityFormatter.toSessionMemoryGb("512M"));
        Assert.assertEquals("Wrong memory unit.", "0.00", ResourceQuantityFormatter.toSessionMemoryGb("1Ki"));
        Assert.assertEquals("Wrong memory unit.", "0.01", ResourceQuantityFormatter.toSessionMemoryGb("6420K"));
        Assert.assertEquals("Wrong memory unit.", "2.51", ResourceQuantityFormatter.toSessionMemoryGb("2.34Gi"));
        Assert.assertEquals("Wrong memory unit.", "2.34", ResourceQuantityFormatter.toSessionMemoryGb("2.34G"));
    }
}
