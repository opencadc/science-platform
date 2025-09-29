package org.opencadc.skaha.context;

import ca.nrc.cadc.util.Log4jInit;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.models.V1LimitRangeItem;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.Set;
import junit.framework.AssertionFailedError;
import org.apache.log4j.Level;
import org.json.JSONException;
import org.junit.Assert;
import org.junit.Test;

public class LimitRangeResourceContextTest {
    static {
        // Ensure that the K8S namespace is set for testing.
        Log4jInit.setLevel(LimitRangeResourceContext.class.getPackageName(), Level.DEBUG);
    }

    @Test
    public void testJSONOutput() throws Exception {
        final OutputStream outputStream = new ByteArrayOutputStream();
        final V1LimitRangeItem containerLimitRange = LimitRangeResourceContextTest.getContainerLimitRange();

        final LimitRangeResourceContext resourceContext = new LimitRangeResourceContext(containerLimitRange);

        try {
            resourceContext.write(outputStream);
        } catch (JSONException jsonException) {
            throw new AssertionFailedError(jsonException.getMessage() + "\nDocument:\n" + outputStream);
        }

        final String jsonOutput = outputStream.toString();

        final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V4);
        final JsonSchema jsonSchema = factory.getSchema(GetAction.class.getResourceAsStream("/context-schema.json"));
        final Set<ValidationMessage> errorMessages = jsonSchema.validate(jsonOutput, InputFormat.JSON);

        Assert.assertTrue("JSON output did not validate: " + errorMessages, errorMessages.isEmpty());
    }

    @Test
    public void testCounts() {
        final V1LimitRangeItem containerLimitRange = LimitRangeResourceContextTest.getContainerLimitRange();

        final LimitRangeResourceContext resourceContext = new LimitRangeResourceContext(containerLimitRange);
        Assert.assertEquals("Wrong CPU count", new IntegerRange(1, 8), resourceContext.getTotalCoreCounts());
        Assert.assertEquals("Wrong CPU count", new IntegerRange(4, 24), resourceContext.getTotalMemoryCounts());
        Assert.assertEquals(
                "Wrong default request core count", new IntegerRange(1, 4), resourceContext.getDefaultCoreCounts());
        Assert.assertEquals(
                "Wrong default request core count", new IntegerRange(2, 16), resourceContext.getDefaultMemoryCounts());
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
            new LimitRangeResourceContext(new V1LimitRangeItem());
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException iae) {
            // no-op
        }

        final V1LimitRangeItem containerLimitRange = new V1LimitRangeItem();

        containerLimitRange.setMin(Map.of("cpu", Quantity.fromString("1"), "memory", Quantity.fromString("4Gi")));
        try {
            new LimitRangeResourceContext(containerLimitRange);
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException iae) {
            // no-op
        }

        containerLimitRange.setMax(Map.of("cpu", Quantity.fromString("8"), "memory", Quantity.fromString("24Gi")));
        try {
            new LimitRangeResourceContext(containerLimitRange);
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException iae) {
            // no-op
        }

        containerLimitRange.setDefaultRequest(
                Map.of("cpu", Quantity.fromString("1"), "memory", Quantity.fromString("2Gi")));
        try {
            new LimitRangeResourceContext(containerLimitRange);
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException iae) {
            // no-op
        }

        containerLimitRange.setDefault(Map.of("cpu", Quantity.fromString("4"), "memory", Quantity.fromString("16Gi")));
        new LimitRangeResourceContext(containerLimitRange);
    }

    private static V1LimitRangeItem getContainerLimitRange() {
        final V1LimitRangeItem containerLimitRange = new V1LimitRangeItem();
        containerLimitRange.setMin(Map.of("cpu", Quantity.fromString("1"), "memory", Quantity.fromString("4Gi")));
        containerLimitRange.setMax(Map.of("cpu", Quantity.fromString("8"), "memory", Quantity.fromString("24Gi")));
        containerLimitRange.setDefaultRequest(
                Map.of("cpu", Quantity.fromString("1"), "memory", Quantity.fromString("2Gi")));
        containerLimitRange.setDefault(Map.of("cpu", Quantity.fromString("4"), "memory", Quantity.fromString("16Gi")));

        return containerLimitRange;
    }
}
