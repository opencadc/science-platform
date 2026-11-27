package org.opencadc.skaha.session;

import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class LaunchRouterTest {
    @Test
    public void everyTypeRoutesToTheDefaultCommunityWithItsOwnPriority() {
        final Map<String, String> env = env();
        env.put("SKAHA_QUEUE_DEFAULT_PRIORITY_CLASS", "high");
        env.put("SKAHA_QUEUE_HEADLESS_PRIORITY_CLASS", "low");
        env.put("SKAHA_QUEUE_DESKTOP-APP_PRIORITY_CLASS", "medium");
        final LaunchRouter router = LaunchRouter.fromEnv(env);

        Assert.assertEquals(
                new LaunchAssignment("skaha-workload", "alice", "default", "cadc", "high"),
                router.assign("alice", SessionType.NOTEBOOK));
        Assert.assertEquals("low", router.assign("alice", SessionType.HEADLESS).workloadPriorityClass());
        Assert.assertEquals(
                "medium", router.assign("alice", SessionType.DESKTOP_APP).workloadPriorityClass());
        Assert.assertEquals(
                "alice-default-lq", router.assign("alice", SessionType.CARTA).localQueue());
    }

    @Test
    public void noPriorityConfiguredLeavesKueueDefault() {
        Assert.assertNull(LaunchRouter.fromEnv(env())
                .assign("alice", SessionType.NOTEBOOK)
                .workloadPriorityClass());
    }

    @Test
    public void staticQueueNamesAreAMigrationError() {
        for (final String legacy : new String[] {"SKAHA_QUEUE_DEFAULT_NAME", "SKAHA_QUEUE_DESKTOP-APP_NAME"}) {
            final Map<String, String> env = env();
            env.put(legacy, "cadc-default");
            final IllegalStateException error =
                    Assert.assertThrows(IllegalStateException.class, () -> LaunchRouter.fromEnv(env));
            Assert.assertTrue(error.getMessage(), error.getMessage().contains(legacy));
        }
    }

    @Test
    public void missingOrInvalidRouteFailsConfiguration() {
        for (final String key : new String[] {
            LaunchRouter.NAMESPACE_ENV, LaunchRouter.DEFAULT_COMMUNITY_ENV, LaunchRouter.DEFAULT_CLUSTER_QUEUE_ENV
        }) {
            final Map<String, String> env = env();
            env.remove(key);
            Assert.assertThrows(key, IllegalStateException.class, () -> LaunchRouter.fromEnv(env));
        }
        final Map<String, String> badCommunity = env();
        badCommunity.put(LaunchRouter.DEFAULT_COMMUNITY_ENV, "not/a/label");
        Assert.assertThrows(IllegalStateException.class, () -> LaunchRouter.fromEnv(badCommunity));
        final Map<String, String> badQueue = env();
        badQueue.put(LaunchRouter.DEFAULT_CLUSTER_QUEUE_ENV, "Cadc");
        Assert.assertThrows(IllegalStateException.class, () -> LaunchRouter.fromEnv(badQueue));
    }

    private static Map<String, String> env() {
        final Map<String, String> env = new HashMap<>();
        env.put(LaunchRouter.NAMESPACE_ENV, "skaha-workload");
        env.put(LaunchRouter.DEFAULT_COMMUNITY_ENV, "default");
        env.put(LaunchRouter.DEFAULT_CLUSTER_QUEUE_ENV, "cadc");
        return env;
    }
}
