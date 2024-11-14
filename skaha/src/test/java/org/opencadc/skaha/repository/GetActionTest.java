package org.opencadc.skaha.repository;

import java.io.StringWriter;
import java.io.Writer;
import org.json.JSONArray;
import org.junit.Assert;
import org.junit.Test;

public class GetActionTest {
    @Test
    public void ensureJSON() throws Exception {
        final String[] hostArray = new String[] {"images.example.org", "images2.example.org"};
        final Writer writer = new StringWriter();
        final GetAction testSubject = new GetAction() {
            @Override
            protected void initRequest() {
                // Do nothing.
            }

            @Override
            Writer initWriter() {
                return writer;
            }

            @Override
            String[] getHarborHosts() {
                return hostArray;
            }
        };

        testSubject.doAction();

        final JSONArray resultArray = new JSONArray(writer.toString());
        final String[] resultStringArray =
                resultArray.toList().stream().map(Object::toString).toArray(String[]::new);

        Assert.assertArrayEquals("Wrong host array", hostArray, resultStringArray);
    }
}
