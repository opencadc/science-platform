package org.opencadc.skaha.context;

import ca.nrc.cadc.util.PropertiesReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Describes the JSON file that contains the default and available resources for the Kubernetes cluster. This will
 * become obsolete when we move to using the LimitRange from the cluster directly.
 *
 * @author majorb
 */
public class StaticResourceContext implements ResourceContext {
    private static final Logger LOGGER = Logger.getLogger(StaticResourceContext.class.getName());
    private static final String K8S_RESOURCES_FILE_NAME = "k8s-resources.json";

    private final JSONObject resourceContextJSON;

    public StaticResourceContext() throws IOException {
        final Writer stringWriter = new StringWriter();
        StaticResourceContext.pipeJSON(stringWriter);
        this.resourceContextJSON = new JSONObject(stringWriter.toString());
    }

    @Override
    public void write(@NotNull final OutputStream outputStream) throws IOException {
        final Writer writer = new BufferedWriter(new OutputStreamWriter(outputStream));
        this.resourceContextJSON.write(writer);
        writer.flush();
    }

    /**
     * This is a well-defined JSON structure, so we can assume that the first and last elements of the core options.
     *
     * @return IntegerRange representing the default core counts.
     */
    @Override
    public IntegerRange getDefaultCoreCounts() {
        final JSONObject coreJSON = getCores();
        return new IntegerRange(coreJSON.getInt("defaultRequest"), coreJSON.getInt("defaultLimit"));
    }

    @Override
    public IntegerRange getDefaultMemoryCounts() {
        final JSONObject coreJSON = getMemory();
        return new IntegerRange(coreJSON.getInt("defaultRequest"), coreJSON.getInt("defaultLimit"));
    }

    @Override
    public IntegerRange getCoreCounts() {
        final JSONObject coreJSON = getCores();
        final JSONArray coreOptionArray = coreJSON.getJSONArray("options");
        return new IntegerRange(coreOptionArray.getInt(0), coreOptionArray.getInt(coreOptionArray.length() - 1));
    }

    @Override
    public IntegerRange getMemoryCounts() {
        final JSONObject coreJSON = getMemory();
        final JSONArray coreOptionArray = coreJSON.getJSONArray("options");
        return new IntegerRange(coreOptionArray.getInt(0), coreOptionArray.getInt(coreOptionArray.length() - 1));
    }

    @Override
    public IntegerRange getGPUCounts() {
        final JSONObject coreJSON = getGPUs();
        final JSONArray coreOptionArray = coreJSON.getJSONArray("options");
        return new IntegerRange(coreOptionArray.getInt(0), coreOptionArray.getInt(coreOptionArray.length() - 1));
    }

    private JSONObject getCores() {
        return this.resourceContextJSON.getJSONObject("cores");
    }

    private JSONObject getMemory() {
        return this.resourceContextJSON.getJSONObject("memoryGB");
    }

    private JSONObject getGPUs() {
        return this.resourceContextJSON.getJSONObject("gpus");
    }

    private static File getResourcesFile() {
        final String configDirSystemProperty = PropertiesReader.class.getName() + ".dir";
        final String configDir;
        if (System.getProperty(configDirSystemProperty) != null) {
            configDir = System.getProperty(configDirSystemProperty);
        } else {
            configDir = System.getProperty("user.home") + "/config";
        }
        LOGGER.info("Using config directory: " + configDir);
        return new File(new File(configDir), StaticResourceContext.K8S_RESOURCES_FILE_NAME);
    }

    private static void pipeJSON(final Writer writer) throws IOException {
        final File propertiesFile = StaticResourceContext.getResourcesFile();
        LOGGER.debug("Reading properties file: " + propertiesFile.getAbsolutePath());
        try (final FileReader fileReader = new FileReader(propertiesFile)) {
            char[] buffer = new char[8192];
            int bytesRead;
            while ((bytesRead = fileReader.read(buffer)) != -1) {
                writer.write(buffer, 0, bytesRead);
            }
            writer.flush(); // ensure everything is written out
        }
        LOGGER.debug("Reading properties file: " + propertiesFile.getAbsolutePath() + " : DONE");
    }
}
