package org.opencadc.skaha.context;

import ca.nrc.cadc.util.Log4jInit;
import ca.nrc.cadc.util.PropertiesReader;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Set;
import junit.framework.AssertionFailedError;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.json.JSONException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class StaticResourceContextTest {
    private static final Logger LOGGER = Logger.getLogger(StaticResourceContextTest.class.getName());

    static {
        // Ensure that the K8S namespace is set for testing.
        Log4jInit.setLevel(StaticResourceContextTest.class.getPackageName(), Level.DEBUG);
    }

    @Before
    public void setupProperties() {
        LOGGER.debug("Setting up properties...");
        final Path resourceParentPath = new File(StaticResourceContextTest.class
                        .getResource("/k8s-resources.json")
                        .getFile())
                .toPath()
                .getParent();
        System.setProperty(PropertiesReader.CONFIG_DIR_SYSTEM_PROPERTY, resourceParentPath.toString());
        LOGGER.debug("Setting up properties: OK -> " + resourceParentPath);
    }

    @Test
    public void testJSONOutput() throws Exception {
        final OutputStream outputStream = new ByteArrayOutputStream();
        final StaticResourceContext resourceWriter = new StaticResourceContext();

        try {
            resourceWriter.write(outputStream);
        } catch (JSONException jsonException) {
            throw new AssertionFailedError(jsonException.getMessage() + "\nDocument:\n" + outputStream);
        } catch (FileNotFoundException fileNotFoundException) {
            throw new AssertionFailedError("Ensure the k8s-resources.json file is exists at "
                    + System.getProperty("user.home") + "/config/k8s-resources.json.");
        }

        final String jsonOutput = outputStream.toString();

        final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V4);
        final JsonSchema jsonSchema = factory.getSchema(GetAction.class.getResourceAsStream("/context-schema.json"));
        final Set<ValidationMessage> errorMessages = jsonSchema.validate(jsonOutput, InputFormat.JSON);

        Assert.assertTrue("JSON output did not validate: " + errorMessages, errorMessages.isEmpty());
    }

    /** See the src/test/resources/k8s-resources.json file for the values used in this test. */
    @Test
    public void testCounts() throws Exception {
        final StaticResourceContext resourceContext = new StaticResourceContext();
        Assert.assertEquals("Wrong CPU count", new IntegerRange(1, 16), resourceContext.getCoreCounts());
        Assert.assertEquals("Wrong Memory count", new IntegerRange(1, 192), resourceContext.getMemoryCounts());
        Assert.assertEquals(
                "Wrong default request core count", new IntegerRange(1, 8), resourceContext.getDefaultCoreCounts());
        Assert.assertEquals(
                "Wrong default request memory count",
                new IntegerRange(4, 32),
                resourceContext.getDefaultMemoryCounts());
    }
}
