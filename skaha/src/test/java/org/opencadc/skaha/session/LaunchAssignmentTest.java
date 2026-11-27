package org.opencadc.skaha.session;

import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/** LocalQueue names are {@code <user>-<community>-lq}, or the assignment is rejected. */
public class LaunchAssignmentTest {

    @Test
    public void localQueueIsUserCommunitySuffix() {
        Assert.assertEquals("bob-default-lq", assignment("bob", "default").localQueue());
        Assert.assertEquals("bob-cadc-lq", assignment("bob", "cadc").localQueue());
        Assert.assertEquals("0-default-lq", assignment("0", "default").localQueue());

        final String boundary = "u".repeat(52) + "-default-lq";
        Assert.assertEquals(63, boundary.length());
        Assert.assertEquals(boundary, assignment("u".repeat(52), "default").localQueue());
    }

    @Test
    public void rejectsNamesThatAreNotDnsLabelsOrExceedSixtyThreeCharacters() {
        for (final String user : List.of("Bob", "bob_smith", "bob.smith", "u".repeat(53))) {
            Assert.assertThrows(IllegalArgumentException.class, () -> assignment(user, "default"));
        }
        Assert.assertThrows(IllegalArgumentException.class, () -> assignment("a".repeat(63), "default"));
    }

    @Test
    public void hyphenatedPairsShareOneLocalQueueName() {
        Assert.assertEquals(
                assignment("bob-x", "y").localQueue(), assignment("bob", "x-y").localQueue());
    }

    @Test
    public void identityMustBeKubernetesLabelValues() {
        Assert.assertThrows(IllegalArgumentException.class, () -> assignment("bob/evil", "default"));
        Assert.assertThrows(IllegalArgumentException.class, () -> assignment(" bob", "default"));
        Assert.assertThrows(IllegalArgumentException.class, () -> assignment("bob", ""));
        Assert.assertThrows(IllegalArgumentException.class, () -> assignment("u".repeat(64), "default"));
        Assert.assertThrows(
                IllegalArgumentException.class, () -> new LaunchAssignment(" ", "bob", "default", "cq", null));
        Assert.assertThrows(
                IllegalArgumentException.class, () -> new LaunchAssignment("ns", "bob", "default", " ", null));
        Assert.assertThrows(
                IllegalArgumentException.class,
                () -> new LaunchAssignment("ns", "bob", "default", "cq", "not/a-label"));
    }

    static LaunchAssignment assignment(final String user, final String community) {
        return new LaunchAssignment("skaha-workload", user, community, "cq", null);
    }
}
