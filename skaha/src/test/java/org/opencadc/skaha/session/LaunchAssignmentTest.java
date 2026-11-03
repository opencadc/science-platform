package org.opencadc.skaha.session;

import ca.nrc.cadc.util.FileUtil;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.nio.file.Files;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/** LocalQueue names against vectors from the independent generator in skaha/tools/localqueue_name_vectors.py. */
public class LaunchAssignmentTest {
    private record Vector(String user, String community, String localQueue) {}

    @Test
    public void localQueueNamesMatchGeneratedVectors() throws Exception {
        final List<Vector> vectors = new Gson()
                .fromJson(
                        Files.readString(
                                FileUtil.getFileFromResource("localqueue-name-vectors.json", LaunchAssignmentTest.class)
                                        .toPath()),
                        new TypeToken<List<Vector>>() {}.getType());
        Assert.assertFalse(vectors.isEmpty());
        for (final Vector vector : vectors) {
            final String name = assignment(vector.user(), vector.community()).localQueue();
            Assert.assertEquals(vector.user() + "/" + vector.community(), vector.localQueue(), name);
            Assert.assertTrue(name, name.length() <= 63);
        }
    }

    @Test
    public void readableNameOnlyForLowercaseDnsLabelsWithinSixtyThreeCharacters() {
        Assert.assertEquals("bob-default-lq", assignment("bob", "default").localQueue());
        Assert.assertTrue(assignment("bob", "default").readableLocalQueueName());
        Assert.assertEquals(
                63, assignment("u".repeat(52), "default").localQueue().length());

        for (final LaunchAssignment digest : List.of(
                assignment("Bob", "default"),
                assignment("bob_smith", "default"),
                assignment("bob.smith", "default"),
                assignment("u".repeat(53), "default"))) {
            Assert.assertFalse(digest.user(), digest.readableLocalQueueName());
            Assert.assertTrue(digest.localQueue().matches("skaha-[a-z2-7]{52}-lq"));
        }
        Assert.assertNotEquals(
                assignment("Bob", "default").localQueue(),
                assignment("bob", "default").localQueue());
    }

    @Test
    public void hyphenatedPairsShareOneReadableName() {
        Assert.assertEquals(
                assignment("bob-x", "y").localQueue(), assignment("bob", "x-y").localQueue());
    }

    @Test
    public void identityMustBeKubernetesLabelValues() {
        Assert.assertThrows(IllegalArgumentException.class, () -> assignment("bob/evil", "default"));
        Assert.assertThrows(IllegalArgumentException.class, () -> assignment(" bob", "default"));
        Assert.assertThrows(IllegalArgumentException.class, () -> assignment("bob", ""));
        Assert.assertThrows(IllegalArgumentException.class, () -> assignment("u".repeat(64), "default"));
    }

    static LaunchAssignment assignment(final String user, final String community) {
        return new LaunchAssignment("skaha-workload", user, community, "cq", null);
    }
}
