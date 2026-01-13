package org.opencadc.skaha.session;

import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class FlexResourceRequestConfigurationTest {

    @Test
    public void testBadInput() {
        try {
            FlexResourceRequestConfiguration.fromSessionType(null);
            org.junit.Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException illegalArgumentException) {
            // Good.
        }

        try {
            FlexResourceRequestConfiguration.fromSessionType("");
            org.junit.Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException illegalArgumentException) {
            // Good.
        }

        try {
            FlexResourceRequestConfiguration.fromSessionType("notebook", null);
            org.junit.Assert.fail("Expected NullPointerException");
        } catch (NullPointerException nullPointerException) {
            // Good.
        }
    }

    @Test
    public void testGetCPU() {
        final Map<String, String> testEnvironment = new HashMap<>();
        testEnvironment.put("SKAHA_FLEX_RESOURCE_REQUEST_NOTEBOOK_MEMORY", "2.0");
        testEnvironment.put("SKAHA_FLEX_RESOURCE_REQUEST_NOTEBOOK_CPU", "1.5");

        Assert.assertEquals(
                "Values should be default.",
                5.6D,
                FlexResourceRequestConfiguration.fromSessionType("headless", testEnvironment)
                        .getCPU(5.6D),
                0.0D);

        Assert.assertEquals(
                "Values should be default.",
                1.1D,
                FlexResourceRequestConfiguration.fromSessionType("default", testEnvironment)
                        .getCPU(1.1D),
                0.0D);

        Assert.assertEquals(
                "Wrong configured CPU value.",
                1.5D,
                FlexResourceRequestConfiguration.fromSessionType("notebook", testEnvironment)
                        .getCPU(13.0D),
                0.0D);

        Assert.assertEquals(
                "Wrong configured memory value.",
                2.0D,
                FlexResourceRequestConfiguration.fromSessionType("notebook", testEnvironment)
                        .getMemory(13.0D),
                0.0D);
    }
}
