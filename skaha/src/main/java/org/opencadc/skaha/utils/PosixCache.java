package org.opencadc.skaha.utils;

import ca.nrc.cadc.auth.PosixPrincipal;
import ca.nrc.cadc.io.ResourceIterator;
import ca.nrc.cadc.net.TransientException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
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

    // One connection pool per Redis URL for the life of the application. Full-map refreshes hold a connection while
    // they download the map, so the pool is larger than Jedis's default of 8, and a launch waits at most
    // POOL_MAX_WAIT for a connection instead of forever.
    private static final Map<String, JedisPooled> POOLS = new ConcurrentHashMap<>();
    static final int POOL_MAX_TOTAL = 32;
    static final Duration POOL_MAX_WAIT = Duration.ofSeconds(2);

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

    static ConnectionPoolConfig poolConfig() {
        final ConnectionPoolConfig poolConfig = new ConnectionPoolConfig();
        poolConfig.setMaxTotal(POOL_MAX_TOTAL);
        poolConfig.setMaxWait(POOL_MAX_WAIT);
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
        writeRequestingUserEntries(user, userGroups);
        refreshInBackground();
    }

    void writeRequestingUserEntries(final PosixPrincipal user, final Collection<PosixGroup> userGroups)
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
                writeUserEntries();
                writeGroupEntries();
            } catch (Exception exception) {
                LOGGER.error("POSIX Mapper Cache refresh failed", exception);
            } finally {
                REFRESHING.set(false);
            }
        });
    }

    private void writeUserEntries() throws Exception {
        LOGGER.debug("writeUserEntries()");
        final long currentTTLSeconds;
        try (final Pipeline pipeline = this.jedisPool.pipelined()) {
            final Response<Long> existingTTLSeconds = pipeline.ttl(PosixCache.UID_MAP_CACHE_KEY);
            pipeline.sync();
            currentTTLSeconds = existingTTLSeconds.get();
        }

        // Redis will return -1 if the key exists but has no associated expire, and -2 if the key does not exist.
        // Either way, refresh the cache with an expiry.
        if (currentTTLSeconds < 0) {
            final long currTimeMillis = System.currentTimeMillis();
            final long expirationSeconds = K8SUtil.getPosixMapperCacheTTLSeconds();
            try (final Pipeline pipeline = this.jedisPool.pipelined();
                    final ResourceIterator<PosixPrincipal> posixPrincipalIterator =
                            this.posixMapperClient.getUserMap()) {
                final Set<Response<Long>> responses = new HashSet<>();
                while (posixPrincipalIterator.hasNext()) {
                    final PosixPrincipal posixPrincipal = posixPrincipalIterator.next();
                    responses.add(pipeline.sadd(
                            PosixCache.UID_MAP_KEY,
                            PosixCache.uidMapping(new PosixPrincipalEntry(
                                    posixPrincipal,
                                    Path.of(this.rootHomeFolder, posixPrincipal.username)
                                            .toString()))));
                }

                pipeline.set(PosixCache.UID_MAP_CACHE_KEY, "UID_CACHE_EXPIRATION");
                pipeline.expire(PosixCache.UID_MAP_CACHE_KEY, expirationSeconds);
                pipeline.sync();
                LOGGER.debug("writeUserEntries(): OK " + responses.size() + " user entries (TTL = "
                        + expirationSeconds + "s) in "
                        + (System.currentTimeMillis() - currTimeMillis) + " ms");
            }
        } else {
            LOGGER.debug("writeUserEntries(): OK using existing user entries (TTL=" + currentTTLSeconds + "s)");
        }
    }

    private void writeGroupEntries() throws Exception {
        LOGGER.debug("writeGroupEntries()");
        final long currentTTLSeconds;
        try (final Pipeline pipeline = this.jedisPool.pipelined()) {
            final Response<Long> existingTTLSeconds = pipeline.ttl(PosixCache.GID_MAP_CACHE_KEY);
            pipeline.sync();
            currentTTLSeconds = existingTTLSeconds.get();
        }

        // Redis will return -1 if the key exists but has no associated expire, and -2 if the key does not exist.
        // Either way, refresh the cache with an expiry.
        if (currentTTLSeconds < 0) {
            final long currTimeMillis = System.currentTimeMillis();
            final long expirationSeconds = K8SUtil.getPosixMapperCacheTTLSeconds();
            try (final Pipeline pipeline = this.jedisPool.pipelined();
                    final ResourceIterator<PosixGroup> posixGroupIterator = this.posixMapperClient.getGroupMap()) {
                final Set<Response<Long>> responses = new HashSet<>();
                while (posixGroupIterator.hasNext()) {
                    final PosixGroup posixGroup = posixGroupIterator.next();
                    responses.add(pipeline.sadd(PosixCache.GID_MAP_FIELD, PosixCache.gidMapping(posixGroup)));
                }

                pipeline.set(PosixCache.GID_MAP_CACHE_KEY, "GID_CACHE_EXPIRATION");
                pipeline.expire(PosixCache.GID_MAP_CACHE_KEY, expirationSeconds);
                pipeline.sync();
                LOGGER.debug("writeGroupEntries(): OK " + responses.size() + " group entries (TTL = "
                        + expirationSeconds + "s) in "
                        + (System.currentTimeMillis() - currTimeMillis) + " ms");
            }
        } else {
            LOGGER.debug("writeGroupEntries(): OK using existing group entries (TTL=" + currentTTLSeconds + "s)");
        }
    }

    private record PosixPrincipalEntry(PosixPrincipal posixPrincipal, String homeFolder) {}
}
