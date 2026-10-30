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
    public void setUp() {
        this.clock = new MutableClock(Instant.parse("2026-10-23T12:00:00Z"));
        this.cache = new SessionJobCache(this.api.client(), this.clock);
    }

    @After
    public void tearDown() {
        this.cache.close();
    }

    @Test
    public void testRecentlyCreated() throws Exception {
        this.cache.start().toCompletableFuture().get(10, TimeUnit.SECONDS);

        // The API never shows this Job, as when the watch lags behind the create: listed for its user only, then
        // forgotten after the window.
        this.cache.created(job("skaha-notebook-alice-new", "alice", "new1", "notebook"));
        Assert.assertEquals(List.of("skaha-notebook-alice-new"), names(this.cache.userJobs("alice", false)));
        Assert.assertTrue(
                "Other users do not see it", this.cache.userJobs("bob", false).isEmpty());
        this.clock.advance(SessionJobCache.RECENT_CREATE_WINDOW.plusSeconds(1));
        Assert.assertTrue(
                "Forgotten after the window",
                this.cache.userJobs("alice", false).isEmpty());

        // Created here, then deleted elsewhere (another replica, a controller) inside the window: the deletion shows.
        this.cache.created(create(job("skaha-notebook-alice-a2", "alice", "a2", "notebook")));
        delete("skaha-notebook-alice-a2");
        KubernetesApi.await(
                "the deletion", () -> this.cache.userJobs("alice", false).isEmpty());
    }

    @Test
    public void testFirstSyncFailureRecovers() throws Exception {
        // The API fails the first list, as during an API server restart at pod start; the cache keeps retrying.
        this.api
                .server()
                .expect()
                .get()
                .withPath("/apis/batch/v1/namespaces/" + NAMESPACE
                        + "/jobs?labelSelector=canfar.net%2Fid&resourceVersion=0")
                .andReturn(500, "")
                .once();
        create(job("skaha-notebook-alice-a1", "alice", "a1", "notebook"));

        this.cache.start();

        KubernetesApi.await("the cache to sync after the failed first list", this.cache::isCurrent);
        Assert.assertEquals(List.of("skaha-notebook-alice-a1"), names(this.cache.userJobs("alice", false)));
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
