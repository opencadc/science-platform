package org.opencadc.skaha.context;

import ca.nrc.cadc.util.Log4jInit;
import io.fabric8.kubernetes.api.model.LimitRangeItem;
import io.fabric8.kubernetes.api.model.Quantity;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Map;
import junit.framework.AssertionFailedError;
import org.apache.log4j.Level;
import org.everit.json.schema.Schema;
import org.everit.json.schema.loader.SchemaLoader;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;

public class LimitRangeResourceContextTest {
    static {
        // Ensure that the K8S namespace is set for testing.
        Log4jInit.setLevel(LimitRangeResourceContext.class.getPackageName(), Level.DEBUG);
    }

    @Test
    public void testJSONOutput() throws Exception {
        try (final OutputStream outputStream = new ByteArrayOutputStream()) {
            final LimitRangeItem containerLimitRange = LimitRangeResourceContextTest.getContainerLimitRange();

            final LimitRangeResourceContext resourceContext = new LimitRangeResourceContext(containerLimitRange);

            try {
                resourceContext.write(outputStream);
            } catch (JSONException jsonException) {
                throw new AssertionFailedError(jsonException.getMessage() + "\nDocument:\n" + outputStream);
            }

            final String jsonOutput = outputStream.toString();

            try (final InputStream schemaStream = GetAction.class.getResourceAsStream("/context-schema.json");
                    final InputStreamReader schemaStreamReader = new InputStreamReader(schemaStream);
                    final BufferedReader reader = new BufferedReader(schemaStreamReader)) {
                final StringBuilder builder = new StringBuilder();
                reader.lines().forEach(builder::append);
                final JSONObject rawSchema = new JSONObject(builder.toString());
                final Schema schema = SchemaLoader.load(rawSchema);
                schema.validate(new JSONObject(jsonOutput));
            }
        }
    }

    @Test
    public void testCounts() {
        final LimitRangeItem containerLimitRange = LimitRangeResourceContextTest.getContainerLimitRange();

        final LimitRangeResourceContext resourceContext = new LimitRangeResourceContext(containerLimitRange);
        Assert.assertEquals("Wrong CPU count", new IntegerRange(1, 8), resourceContext.getTotalCoreCounts());
        Assert.assertEquals("Wrong Memory count", new IntegerRange(1, 24), resourceContext.getTotalMemoryCounts());
        Assert.assertEquals(
                "Wrong default request core count", new IntegerRange(1, 4), resourceContext.getDefaultCoreCounts());
        Assert.assertEquals(
                "Wrong default request memory count",
                new IntegerRange(2, 16),
                resourceContext.getDefaultMemoryCounts());
    }

    @Test
    public void testCountEnsureUnitless() {
        final LimitRangeItem containerLimitRange = LimitRangeResourceContextTest.getContainerLimitRangeDefaultMinimum();

        final LimitRangeResourceContext resourceContext = new LimitRangeResourceContext(containerLimitRange);
        Assert.assertEquals("Wrong CPU count", new IntegerRange(1, 3), resourceContext.getTotalCoreCounts());
        Assert.assertEquals("Wrong Memory count", new IntegerRange(1, 24), resourceContext.getTotalMemoryCounts());
        Assert.assertEquals(
                "Wrong default request core count", new IntegerRange(1, 4), resourceContext.getDefaultCoreCounts());
        Assert.assertEquals(
                "Wrong default request memory count",
                new IntegerRange(2, 16),
                resourceContext.getDefaultMemoryCounts());
    }

    @Test
    public void testErrors() {
        try {
            new LimitRangeResourceContext(null);
            Assert.fail("Expected NullPointerException");
        } catch (NullPointerException npe) {
            // no-op
        }

        try {
            new LimitRangeResourceContext(new LimitRangeItem());
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException iae) {
            // no-op
        }

        final LimitRangeItem containerLimitRange = new LimitRangeItem();
        containerLimitRange.setMax(Map.of("cpu", Quantity.parse("8"), "memory", Quantity.parse("24Gi")));
        try {
            new LimitRangeResourceContext(containerLimitRange);
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException iae) {
            // no-op
        }

        containerLimitRange.setDefaultRequest(Map.of("cpu", Quantity.parse("1"), "memory", Quantity.parse("2Gi")));
        try {
            new LimitRangeResourceContext(containerLimitRange);
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException iae) {
            // no-op
        }

        containerLimitRange.setDefault(Map.of("cpu", Quantity.parse("4"), "memory", Quantity.parse("16Gi")));
        new LimitRangeResourceContext(containerLimitRange);
    }

    private static LimitRangeItem getContainerLimitRange() {
        final LimitRangeItem containerLimitRange = new LimitRangeItem();
        containerLimitRange.setMax(Map.of("cpu", Quantity.parse("8"), "memory", Quantity.parse("24Gi")));
        containerLimitRange.setDefaultRequest(Map.of("cpu", Quantity.parse("1"), "memory", Quantity.parse("2Gi")));
        containerLimitRange.setDefault(Map.of("cpu", Quantity.parse("4"), "memory", Quantity.parse("16Gi")));

        return containerLimitRange;
    }

    private static LimitRangeItem getContainerLimitRangeDefaultMinimum() {
        final LimitRangeItem containerLimitRange = new LimitRangeItem();
        containerLimitRange.setMax(Map.of("cpu", Quantity.parse("3"), "memory", Quantity.parse("24Gi")));
        containerLimitRange.setDefaultRequest(Map.of("cpu", Quantity.parse("1"), "memory", Quantity.parse("2Gi")));
        containerLimitRange.setDefault(Map.of("cpu", Quantity.parse("4"), "memory", Quantity.parse("16Gi")));

        return containerLimitRange;
    }
}
