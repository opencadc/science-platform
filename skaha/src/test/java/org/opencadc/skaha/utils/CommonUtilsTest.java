package org.opencadc.skaha.utils;

import org.junit.Assert;
import org.junit.Test;

public class CommonUtilsTest {
    @Test
    public void testParseStringAsInstant() {
        Assert.assertEquals(
                "Wrong expiry", "2025-04-10T15:46:00Z", CommonUtils.getExpiryTimeString("2025-04-10T15:45Z", 60L));
        Assert.assertEquals(
                "Wrong expiry",
                "2025-04-10T15:06:33Z",
                CommonUtils.getExpiryTimeString("2025-04-10T15:05:33.900Z", 60L));
    }
}
