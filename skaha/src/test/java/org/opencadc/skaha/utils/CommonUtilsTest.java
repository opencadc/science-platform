package org.opencadc.skaha.utils;

import java.math.BigDecimal;
import org.junit.Assert;
import org.junit.Test;

public class CommonUtilsTest {
    @Test
    public void testFromBytes() {
        Assert.assertEquals(
                "Wrong output.", "136Ki", CommonUtils.formatMemoryFromBytes(BigDecimal.valueOf(136 * 1024.0)));

        Assert.assertEquals(
                "Wrong output.",
                "881Gi",
                CommonUtils.formatMemoryFromBytes(BigDecimal.valueOf(881 * 1024.0 * 1024.0 * 1024.0)));
    }
}
