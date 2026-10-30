package org.opencadc.skaha.utils;

import ca.nrc.cadc.auth.PosixPrincipal;
import ca.nrc.cadc.io.ResourceIterator;
import ca.nrc.cadc.net.TransientException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.apache.log4j.Logger;
import org.opencadc.auth.PosixGroup;
import org.opencadc.auth.PosixMapperClient;
import org.opencadc.skaha.K8SUtil;
import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.exceptions.JedisException;

/**
 * A simple Redis Cache for POSIX information. This will update the underlying Redis Set in a transaction to ensure
 * single access. BEWARE - Changes to the items in the Set (i.e. the POSIX entries) will require a purge of the cache to
 * properly reset it. This version will expire the cache after a configurable TTL. On expiration, the next access will
 * refresh the cache.
 *
 * <p>This cache will use keys as follows:
 *
 * <ul>
 *   <li>"users:posix" : Set of POSIX user entries
 *   <li>"groups:posix" : Set of POSIX group entries
 *   <li>"users:posix:cache" : Key to track user cache expiration (see with ttl command)
 *   <li>"groups:posix:cache" : Key to track group cache expiration (see with ttl command)
 * </ul>
 */
public class PosixCache {
    private static final Logger LOGGER = Logger.getLogger(PosixCache.class);

    // Default path to the no-login shell.  This could be risky, but most distributions should support it.
    private static final String NO_LOGIN_SHELL = "/sbin/nologin";

    private static final String UID_MAP_CACHE_KEY = "users:posix:cache";
    private static final String GID_MAP_CACHE_KEY = "groups:posix:cache";
    private static final String UID_MAP_KEY = "users:posix";
    private static final String GID_MAP_FIELD = "groups:posix";

    // One connection pool per Redis URL for the life of the application.
    private static final Map<String, JedisPooled> POOLS = new ConcurrentHashMap<>();

    private static final ExecutorService REFRESH = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "posix-cache-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean REFRESHING = new AtomicBoolean();

    private final JedisPooled jedisPool;
    private final String rootHomeFolder;
    private final PosixMapperClient posixMapperClient;

    /**
     * Construct a new Cache. This will initialize the Redis Pool (JediPooled) with the given URL and client to the
     * POSIX Mapper API.
     *
     * @param cacheURL The Redis URL.
     * @param rootHomeFolder Root of entire system (i.e. containing home and project folders)
     * @param posixMapperClient The Client to the POSIX Mapper API.
     */
    public PosixCache(final String cacheURL, final String rootHomeFolder, final PosixMapperClient posixMapperClient) {
        this(
                POOLS.computeIfAbsent(cacheURL, url -> new JedisPooled(poolConfig(), url)),
                rootHomeFolder,
                posixMapperClient);
    }

    /** Larger than Jedis's default of 8; a launch waits at most 2 seconds for a connection instead of forever. */
    private static ConnectionPoolConfig poolConfig() {
        final ConnectionPoolConfig poolConfig = new ConnectionPoolConfig();
        poolConfig.setMaxTotal(32);
        poolConfig.setMaxWait(Duration.ofSeconds(2));
        return poolConfig;
    }

    PosixCache(final JedisPooled jedisPool, final String rootHomeFolder, final PosixMapperClient posixMapperClient) {
        this.jedisPool = jedisPool;
        this.rootHomeFolder = rootHomeFolder;
        this.posixMapperClient = posixMapperClient;
    }

    /**
     * Obtain the POSIX entry for the provided POSIX Principal in POSIX form. Example output:
     * "username1:x:1000:1000::/rootdir/home/username:/sbin/nologin"
     *
     * @param posixPrincipalEntry The POSIX Principal wrapper to transform.
     * @return String POSIX entry, never null;
     */
    private static String uidMapping(final PosixPrincipalEntry posixPrincipalEntry) {
        return String.format(
                "%s:x:%d:%d::%s:%s",
                posixPrincipalEntry.posixPrincipal.username,
                posixPrincipalEntry.posixPrincipal.getUidNumber(),
                posixPrincipalEntry.posixPrincipal.getUidNumber(),
                posixPrincipalEntry.homeFolder,
                PosixCache.NO_LOGIN_SHELL);
    }

    private static String gidMapping(final PosixGroup posixGroup) {
        return String.format("%s:x:%d:", posixGroup.getGroupURI().getURI().getQuery(), posixGroup.getGID());
    }

    /**
     * Write the POSIX entries a new session needs. The requesting user's own passwd and group entries are written and
     * are required. The full user and group maps are refreshed from the POSIX Mapper in the background if they have
     * expired; the request does not wait for that refresh.
     *
     * @param user The requesting user.
     * @param userGroups The requesting user's groups.
     * @throws TransientException If the requesting user's entries cannot be written.
     */
    public void writePOSIXEntries(final PosixPrincipal user, final Collection<PosixGroup> userGroups)
            throws TransientException {
        try {
            this.jedisPool.sadd(
                    PosixCache.UID_MAP_KEY,
                    PosixCache.uidMapping(new PosixPrincipalEntry(
                            user, Path.of(this.rootHomeFolder, user.username).toString())));
            if (!userGroups.isEmpty()) {
                this.jedisPool.sadd(
                        PosixCache.GID_MAP_FIELD,
                        userGroups.stream().map(PosixCache::gidMapping).toArray(String[]::new));
            }
        } catch (JedisException jedisException) {
            LOGGER.error("Unable to write POSIX entries for " + user.username, jedisException);
            throw new TransientException("Unable to prepare POSIX entries for the session.  Please try again.");
        }
        refreshInBackground();
    }

    /**
     * Refresh the full user and group maps if they have expired, on this replica's single refresh thread. A launch that
     * arrives while a refresh runs does not start another, so a burst of launches downloads the maps once and holds one
     * Redis connection. Failures are logged: the requesting user's entries are already written.
     *
     * @return The refresh, or null when one is already running.
     */
    Future<?> refreshInBackground() {
        if (!REFRESHING.compareAndSet(false, true)) {
            return null;
        }
        return REFRESH.submit(() -> {
            try {
                refresh(
                        UID_MAP_CACHE_KEY,
                        UID_MAP_KEY,
                        this.posixMapperClient::getUserMap,
                        user -> uidMapping(new PosixPrincipalEntry(
                                user,
                                Path.of(this.rootHomeFolder, user.username).toString())));
                refresh(GID_MAP_CACHE_KEY, GID_MAP_FIELD, this.posixMapperClient::getGroupMap, PosixCache::gidMapping);
            } catch (Exception exception) {
                LOGGER.error("POSIX Mapper Cache refresh failed", exception);
            } finally {
                REFRESHING.set(false);
            }
        });
    }

    /**
     * Refresh one map from the POSIX Mapper when its expiry key has no TTL: Redis answers -1 when the key has no expiry
     * and -2 when it does not exist.
     */
    private <T> void refresh(
            final String expiryKey,
            final String setKey,
            final Callable<ResourceIterator<T>> source,
            final Function<T, String> entry)
            throws Exception {
        final long ttlSeconds;
        try (final Pipeline pipeline = this.jedisPool.pipelined()) {
            final Response<Long> ttl = pipeline.ttl(expiryKey);
            pipeline.sync();
            ttlSeconds = ttl.get();
        }
        if (ttlSeconds >= 0) {
            LOGGER.debug("Using existing " + setKey + " (TTL=" + ttlSeconds + "s)");
            return;
        }

        final long start = System.currentTimeMillis();
        final long expirationSeconds = K8SUtil.getPosixMapperCacheTTLSeconds();
        int count = 0;
        try (final Pipeline pipeline = this.jedisPool.pipelined();
                final ResourceIterator<T> entries = source.call()) {
            while (entries.hasNext()) {
                pipeline.sadd(setKey, entry.apply(entries.next()));
                count++;
            }
            pipeline.set(expiryKey, "CACHE_EXPIRATION");
            pipeline.expire(expiryKey, expirationSeconds);
            pipeline.sync();
        }
        LOGGER.debug("Refreshed " + count + " " + setKey + " entries (TTL=" + expirationSeconds + "s) in "
                + (System.currentTimeMillis() - start) + " ms");
    }

    private record PosixPrincipalEntry(PosixPrincipal posixPrincipal, String homeFolder) {}
}
