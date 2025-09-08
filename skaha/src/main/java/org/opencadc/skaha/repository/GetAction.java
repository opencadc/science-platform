package org.opencadc.skaha.repository;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Arrays;
import org.json.JSONWriter;
import org.opencadc.skaha.SkahaAction;

/**
 * Output the resource context information.
 *
 * @author majorb
 */
public class GetAction extends SkahaAction {

    @Override
    public void doAction() throws Exception {
        initRequest();

        final Writer writer = initWriter();
        final JSONWriter jsonWriter = new JSONWriter(writer).array();
        try {
            Arrays.stream(getHarborHosts()).forEach(jsonWriter::value);
        } finally {
            jsonWriter.endArray();
            writer.flush();
        }
    }

    Writer initWriter() throws IOException {
        this.syncOutput.setHeader("content-type", "application/json");
        return new OutputStreamWriter(syncOutput.getOutputStream());
    }

    String[] getHarborHosts() {
        return this.harborHosts.toArray(new String[0]);
    }
}
