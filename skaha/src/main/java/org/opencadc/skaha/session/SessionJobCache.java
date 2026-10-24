package org.opencadc.skaha.session;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A per-replica, watched copy of session Jobs (label {@code canfar.net/id}) indexed by owner. Jobs this replica just
 * created are included until the watch delivers them, at most {@link #RECENT_CREATE_WINDOW}, so a listing right after a
 * launch shows it.
 */
final class SessionJobCache implements AutoCloseable {
    static final String BY_USERNAME = "username";
    static final Duration RECENT_CREATE_WINDOW = Duration.ofSeconds(60);

    private final SharedIndexInformer<Job> informer;
    private final Map<String, RecentJob> recentlyCreated = new ConcurrentHashMap<>();
    private final Clock clock;

    private record RecentJob(Job job, Instant createdAt) {}

    SessionJobCache(final KubernetesClient client) {
        this(client, Clock.systemUTC());
    }

    SessionJobCache(final KubernetesClient client, final Clock clock) {
        this.clock = clock;
        this.informer = client.batch()
                .v1()
                .jobs()
                .withLabel(SessionLabels.Key.ID.label())
                .runnableInformer(0);
        this.informer.addIndexers(Map.of(BY_USERNAME, job -> {
            final String username = SessionLabels.get(job, SessionLabels.Key.USERNAME);
            return username == null ? List.of() : List.of(username);
        }));
        // Once the watch has delivered a Job, the cache answers for it, including its deletion.
        this.informer.addEventHandler(new ResourceEventHandler<>() {
            @Override
            public void onAdd(final Job job) {
                recentlyCreated.remove(job.getMetadata().getName());
            }

            @Override
            public void onUpdate(final Job oldJob, final Job newJob) {
                recentlyCreated.remove(newJob.getMetadata().getName());
            }

            @Override
            public void onDelete(final Job job, final boolean deletedFinalStateUnknown) {
                recentlyCreated.remove(job.getMetadata().getName());
            }
        });
    }

    /** Start watching; the stage completes when the first full list has loaded. */
    CompletionStage<Void> start() {
        return this.informer.start();
    }

    /** True when loaded and watching, so the cache reflects the API server. */
    boolean isCurrent() {
        return this.informer.hasSynced() && this.informer.isRunning() && this.informer.isWatching();
    }

    void created(final Job job) {
        this.recentlyCreated.put(job.getMetadata().getName(), new RecentJob(job, this.clock.instant()));
    }

    List<Job> userJobs(final String username, final boolean omitHeadless) {
        final Instant expiry = this.clock.instant().minus(RECENT_CREATE_WINDOW);
        this.recentlyCreated.values().removeIf(recent -> recent.createdAt().isBefore(expiry));

        final List<Job> jobs = new ArrayList<>(this.informer.getIndexer().byIndex(BY_USERNAME, username));
        final List<String> cachedNames =
                jobs.stream().map(job -> job.getMetadata().getName()).toList();
        this.recentlyCreated.values().stream()
                .map(RecentJob::job)
                .filter(job -> !cachedNames.contains(job.getMetadata().getName()))
                .filter(job -> username.equals(SessionLabels.get(job, SessionLabels.Key.USERNAME)))
                .forEach(jobs::add);

        return jobs.stream()
                .filter(job -> !omitHeadless
                        || !SessionAction.SESSION_TYPE_HEADLESS.equals(SessionLabels.get(job, SessionLabels.Key.KIND)))
                .toList();
    }

    @Override
    public void close() {
        this.informer.close();
    }
}
