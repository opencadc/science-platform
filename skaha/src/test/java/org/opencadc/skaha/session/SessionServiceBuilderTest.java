package org.opencadc.skaha.session;

import ca.nrc.cadc.util.FileUtil;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.util.Yaml;
import java.io.File;
import java.io.IOException;
import java.util.Objects;
import org.junit.Assert;
import org.junit.Test;
import org.opencadc.skaha.Job;
import org.opencadc.skaha.SessionType;

public class SessionServiceBuilderTest {
    @Test
    public void testBuild() throws Exception {
        try {
            new SessionServiceBuilder(null);
            Assert.fail("Expected NullPointerException");
        } catch (NullPointerException nullPointerException) {
            // NullPointerException expected
        }

        final Job job = new Job("name", "uid", "my-sessionID", SessionType.CARTA);
        final SessionServiceBuilder sessionServiceBuilder = new SessionServiceBuilder(job) {
            @Override
            V1Service loadService() throws IOException {
                final File file =
                        FileUtil.getFileFromResource("test-carta-service.yaml", SessionServiceBuilderTest.class);
                return (V1Service) Yaml.load(file);
            }
        };
        final String outputYAML = sessionServiceBuilder.build();

        final V1Service service = (V1Service) Yaml.load(outputYAML);

        final V1ObjectMeta metadata =
                Objects.requireNonNull(Objects.requireNonNull(service).getMetadata());
        Assert.assertEquals("Wrong service name.", "skaha-carta-svc-my-sessionID", metadata.getName());
        Assert.assertEquals(
                "Wrong owner uid",
                "uid",
                Objects.requireNonNull(Objects.requireNonNull(metadata.getOwnerReferences())
                                .get(0))
                        .getUid());
        Assert.assertEquals(
                "Wrong owner name",
                "name",
                Objects.requireNonNull(Objects.requireNonNull(metadata.getOwnerReferences())
                                .get(0))
                        .getName());
        Assert.assertEquals(
                "Wrong spec selector",
                "my-sessionID",
                Objects.requireNonNull(Objects.requireNonNull(service.getSpec()).getSelector())
                        .get("canfar-net-sessionID"));
    }
}
