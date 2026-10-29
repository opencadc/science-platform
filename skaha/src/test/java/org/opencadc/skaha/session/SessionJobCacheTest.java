package org.opencadc.skaha.session;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.opencadc.skaha.KubernetesApi;

public class SessionJobCacheTest {
    private static final String NAMESPACE = KubernetesApi.NAMESPACE;

    @Rule
    public final KubernetesApi api = new KubernetesApi();

    private MutableClock clock;
    private SessionJobCache cache;

    @Before
    public void setUp() throws Exception {
        this.clock = new MutableClock(Instant.parse("2026-10-23T12:00:00Z"));
        this.cache = new SessionJobCache(this.api.client(), this.clock);
        this.cache.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @After
    public void tearDown() {
        this.cache.close();
    }

    @Test
    public void testWatchKeepsCacheCurrent() throws Exception {
        Assert.assertTrue(this.cache.isCurrent());
        create(job("skaha-notebook-alice-a1", "alice", "a1", "notebook"));
        create(job("skaha-headless-alice-h1", "alice", "h1", "headless"));
        create(job("skaha-notebook-bob-b1", "bob", "b1", "notebook"));

        await(() -> names(this.cache.userJobs("alice", false)).size() == 2);
        Assert.assertEquals(List.of("skaha-notebook-alice-a1"), names(this.cache.userJobs("alice", true)));

        delete("skaha-notebook-alice-a1");
        await(() -> names(this.cache.userJobs("alice", true)).isEmpty());
    }

    @Test
    public void testJustCreatedJobVisibleBeforeWatch() {
        // The API never shows this Job, as when the watch lags behind the create.
        this.cache.created(job("skaha-notebook-alice-new", "alice", "new1", "notebook"));

        Assert.assertEquals(List.of("skaha-notebook-alice-new"), names(this.cache.userJobs("alice", false)));
        Assert.assertTrue(
                "Other users do not see it", this.cache.userJobs("bob", false).isEmpty());

        this.clock.advance(SessionJobCache.RECENT_CREATE_WINDOW.plusSeconds(1));
        Assert.assertTrue(
                "Forgotten after the window",
                this.cache.userJobs("alice", false).isEmpty());
    }

    @Test
    public void testDeletionNotMasked() throws Exception {
        // Created here, then deleted elsewhere (another replica, a controller) before the window ends.
        this.cache.created(create(job("skaha-notebook-alice-a2", "alice", "a2", "notebook")));
        Assert.assertEquals(1, this.cache.userJobs("alice", false).size());

        delete("skaha-notebook-alice-a2");
        await(() -> this.cache.userJobs("alice", false).isEmpty());
    }

    @Test
    public void testRouting() {
        Assert.assertTrue(SessionDAO.servedFromCache(this.cache, "alice", null));
        Assert.assertFalse("Lookups by session ID are live", SessionDAO.servedFromCache(this.cache, "alice", "a1"));
        Assert.assertFalse("Callbacks have no user", SessionDAO.servedFromCache(this.cache, null, null));
        Assert.assertFalse("No cache", SessionDAO.servedFromCache(null, "alice", null));

        this.cache.close();
        Assert.assertFalse("A stopped watch is not current", this.cache.isCurrent());
        Assert.assertFalse(SessionDAO.servedFromCache(this.cache, "alice", null));
    }

    private Job create(final Job job) {
        return this.api
                .client()
                .batch()
                .v1()
                .jobs()
                .inNamespace(NAMESPACE)
                .resource(job)
                .create();
    }

    private void delete(final String name) {
        this.api
                .client()
                .batch()
                .v1()
                .jobs()
                .inNamespace(NAMESPACE)
                .withName(name)
                .delete();
    }

    private static List<String> names(final List<Job> jobs) {
        return jobs.stream().map(job -> job.getMetadata().getName()).sorted().toList();
    }

    private static void await(final BooleanSupplier condition) throws InterruptedException {
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                Assert.fail("Condition not met within 10 seconds");
            }
            Thread.sleep(20);
        }
    }

    private static Job job(final String name, final String username, final String sessionID, final String kind) {
        final Map<String, String> labels = SessionLabels.canonical(Map.of(
                SessionLabels.Key.ID, sessionID, SessionLabels.Key.USERNAME, username, SessionLabels.Key.KIND, kind));
        return new JobBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(NAMESPACE)
                .withLabels(labels)
                .endMetadata()
                .build();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(final Instant now) {
            this.now = now;
        }

        void advance(final Duration duration) {
            this.now = this.now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.now;
        }
    }
}
