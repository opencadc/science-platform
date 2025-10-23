package org.opencadc.skaha.utils;

import org.junit.Assert;
import org.junit.Test;

public class MemoryUnitConverterTest {
    @Test
    public void testFormatBytes() {
        Assert.assertEquals(
                "Wrong memory format.",
                0.09D,
                MemoryUnitConverter.format(90000L, MemoryUnitConverter.MemoryUnit.M),
                0.0D);

        Assert.assertEquals(
                "Wrong memory format.",
                1.0D,
                MemoryUnitConverter.format(1024L * 1024L, MemoryUnitConverter.MemoryUnit.Mi),
                0.0D);

        Assert.assertEquals(
                "Wrong memory format.",
                1.5D,
                MemoryUnitConverter.format(1536L * 1024L, MemoryUnitConverter.MemoryUnit.Mi),
                0.0D);

        Assert.assertEquals(
                "Wrong memory format.",
                2.0D,
                MemoryUnitConverter.format(2L * 1024L * 1024L * 1024L, MemoryUnitConverter.MemoryUnit.Gi),
                0.0D);
    }

    @Test
    public void testHumanReadableFormat() {
        Assert.assertEquals(
                "Wrong human readable format.",
                "0.09M",
                MemoryUnitConverter.formatHumanReadable(90000L, MemoryUnitConverter.MemoryUnit.M));

        Assert.assertEquals(
                "Wrong human readable format.",
                "1Mi",
                MemoryUnitConverter.formatHumanReadable(1024L * 1024L, MemoryUnitConverter.MemoryUnit.Mi));

        Assert.assertEquals(
                "Wrong human readable format.",
                "4.6Mi",
                MemoryUnitConverter.formatHumanReadable(4.6D, MemoryUnitConverter.MemoryUnit.Mi));
    }
}
