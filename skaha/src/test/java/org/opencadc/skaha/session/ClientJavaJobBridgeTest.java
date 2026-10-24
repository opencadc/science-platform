package org.opencadc.skaha.session;

import ca.nrc.cadc.util.FileUtil;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.util.Yaml;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.Assert;
import org.junit.Test;

/** The bridged Fabric8 Job must carry exactly the content SessionJobBuilder produced. */
public class ClientJavaJobBridgeTest {
    @Test
    public void testBaseTemplate() throws Exception {
        assertParity(
                SessionJobBuilder.fromPath(template("test-base-values.yaml")).withParameters(parameters()));
    }

    @Test
    public void testQueuedTemplate() throws Exception {
        assertParity(SessionJobBuilder.fromPath(template("test-base-values-queue.yaml"))
                .withParameters(parameters())
                .withQueue(new QueueConfiguration("notebook", "medium", "skaha-queue")));
    }

    @Test
    public void testGpuAffinityAndPullSecret() throws Exception {
        assertParity(SessionJobBuilder.fromPath(template("test-base-values-affinity.yaml"))
                .withParameters(parameters())
                .withGPUEnabled(true)
                .withGPUCount(2)
                .withImageSecret("registry-auth-alice"));
    }

    static void assertParity(final SessionJobBuilder builder) throws Exception {
        final V1Job built = builder.buildLaunch().job();

        final Job bridged = ClientJavaJobBridge.toFabric8(built);

        final Map<String, Object> expected = flatten("", asMap(Yaml.dump(built)));
        final Map<String, Object> actual = flatten("", asMap(Serialization.asYaml(bridged)));
        final Map<String, String> differences = new TreeMap<>();
        for (final String path : union(expected.keySet(), actual.keySet())) {
            if (!Objects.equals(expected.get(path), actual.get(path))) {
                differences.put(path, expected.get(path) + " -> " + actual.get(path));
            }
        }
        Assert.assertEquals("Fields changed by the bridge", Map.of(), differences);
    }

    /** Flatten nested maps and lists into path -> scalar entries. */
    private static Map<String, Object> flatten(final String prefix, final Object value) {
        final Map<String, Object> flat = new TreeMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, child) -> flat.putAll(flatten(prefix + "/" + key, child)));
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                flat.putAll(flatten(prefix + "[" + i + "]", list.get(i)));
            }
        } else {
            flat.put(prefix, value == null ? null : value.toString());
        }
        return flat;
    }

    private static Set<String> union(final Set<String> first, final Set<String> second) {
        final Set<String> all = new TreeSet<>(first);
        all.addAll(second);
        return all;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(final String yaml) {
        return new org.yaml.snakeyaml.Yaml().loadAs(yaml, Map.class);
    }

    private static Path template(final String name) throws Exception {
        return FileUtil.getFileFromResource(name, ClientJavaJobBridgeTest.class).toPath();
    }

    private static Map<String, String> parameters() {
        final Map<String, String> parameters = new HashMap<>();
        parameters.put(PostAction.SKAHA_SESSIONID, "session-123");
        parameters.put(PostAction.SKAHA_USERID, "alice");
        parameters.put(PostAction.SKAHA_SESSIONNAME, "analysis");
        parameters.put(PostAction.SKAHA_SESSIONTYPE, "notebook");
        parameters.put(PostAction.SKAHA_JOBNAME, "notebook-alice-session-123");
        return parameters;
    }
}
