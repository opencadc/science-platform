package org.opencadc.skaha.utils;

import ca.nrc.cadc.auth.AuthenticationUtil;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.security.auth.Subject;
import org.opencadc.auth.PosixGroup;
import org.opencadc.auth.PosixMapperClient;
import org.opencadc.gms.GroupURI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-JVM cache of POSIX group mappings for {@link GroupURI}s.
 *
 * <p>This service runs in Kubernetes and can be scaled horizontally. The cache is scoped to a single JVM (pod) and
 * avoids repeated POSIX Mapper lookups when resolving supplemental groups for session jobs.
 *
 * <p>The GROUP_LOAD_LOCK with double-check before querying. Concurrent requests on an empty cache now serialize: the
 * first thread loads, the others wait and then read from cache.
 *
 * <p>uncachedGroupURIs() filters out entries populated while waiting, so redundant mapper calls are avoided.
 *
 * <p>If the mapper returns fewer groups than requested (e.g. empty body on first call), one retry runs before toGIDs
 * throws.
 */
public class PosixGroupCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(PosixGroupCache.class);
    private static final int POSIX_MAPPER_GID_QUERY_BATCH_SIZE = 20;
    private static final Object GROUP_LOAD_LOCK = new Object();
    static final ConcurrentHashMap<GroupURI, PosixGroup> GROUP_URI_POSIX_GROUP_CACHE = new ConcurrentHashMap<>();

    private final PosixMapperClient posixMapperClient;

    public PosixGroupCache(PosixMapperClient posixMapperClient) {
        this.posixMapperClient = posixMapperClient;
    }

    /**
     * Capture the GIDs associated with each group. This will be used to pass to the Job as supplemental groups added to
     * the image. Query this in batches as it needs to use them in a GET request as a query string.
     *
     * @param groupURIS The GroupURIs the current user belongs to.
     * @return One {@link PosixGroup} per input {@link GroupURI}, in the same order.
     * @throws Exception If the query could not be completed or a mapping is missing after lookup.
     */
    public Set<PosixGroup> toGIDs(final List<GroupURI> groupURIS) throws Exception {
        if (groupURIS.isEmpty()) {
            return Set.of();
        }

        final Set<PosixGroup> results = new HashSet<>(groupURIS.size());
        final List<GroupURI> missingGroupURIs = new ArrayList<>();

        groupURIS.stream().filter(Objects::nonNull).forEach(groupURI -> {
            final PosixGroup cachedGroup = PosixGroupCache.GROUP_URI_POSIX_GROUP_CACHE.get(groupURI);
            if (cachedGroup != null) {
                results.add(cachedGroup);
            } else {
                missingGroupURIs.add(groupURI);
            }
        });

        if (!missingGroupURIs.isEmpty()) {
            PosixGroupCache.queryAndCacheGroupGids(
                    new ArrayList<>(new LinkedHashSet<>(missingGroupURIs)), this.posixMapperClient);

            missingGroupURIs.forEach(missingGroupURI -> {
                final PosixGroup fetchedGroup = PosixGroupCache.GROUP_URI_POSIX_GROUP_CACHE.get(missingGroupURI);
                if (fetchedGroup == null) {
                    throw new IllegalStateException(
                            "POSIX Mapper did not return a group mapping for " + missingGroupURI);
                }
                results.add(fetchedGroup);
            });
        }

        return results;
    }

    private static void queryAndCacheGroupGids(
            final List<GroupURI> groupURIsToQuery, final PosixMapperClient posixMapperClient) throws Exception {
        List<GroupURI> uncachedGroupURIs = PosixGroupCache.uncachedGroupURIs(groupURIsToQuery);
        if (uncachedGroupURIs.isEmpty()) {
            return;
        }

        synchronized (PosixGroupCache.GROUP_LOAD_LOCK) {
            uncachedGroupURIs = PosixGroupCache.uncachedGroupURIs(groupURIsToQuery);
            if (uncachedGroupURIs.isEmpty()) {
                return;
            }
            PosixGroupCache.fetchAndCacheGroupBatches(uncachedGroupURIs, posixMapperClient);

            uncachedGroupURIs = PosixGroupCache.uncachedGroupURIs(groupURIsToQuery);
            if (!uncachedGroupURIs.isEmpty()) {
                LOGGER.debug(
                        "Retrying POSIX group lookup for {} uncached group(s) after partial mapper response.",
                        uncachedGroupURIs.size());
                PosixGroupCache.fetchAndCacheGroupBatches(uncachedGroupURIs, posixMapperClient);
            }
        }
    }

    private static List<GroupURI> uncachedGroupURIs(final List<GroupURI> groupURIs) {
        final List<GroupURI> uncachedGroupURIs = new ArrayList<>();
        for (final GroupURI groupURI : groupURIs) {
            if (!PosixGroupCache.GROUP_URI_POSIX_GROUP_CACHE.containsKey(groupURI)) {
                uncachedGroupURIs.add(groupURI);
            }
        }
        return uncachedGroupURIs;
    }

    private static void fetchAndCacheGroupBatches(
            final List<GroupURI> groupURIsToQuery, final PosixMapperClient posixMapperClient) throws Exception {
        final Subject currentSubject = AuthenticationUtil.getCurrentSubject();
        for (int offset = 0;
                offset < groupURIsToQuery.size();
                offset += PosixGroupCache.POSIX_MAPPER_GID_QUERY_BATCH_SIZE) {
            final List<GroupURI> batch = List.copyOf(groupURIsToQuery.subList(
                    offset,
                    Math.min(offset + PosixGroupCache.POSIX_MAPPER_GID_QUERY_BATCH_SIZE, groupURIsToQuery.size())));
            final List<GroupURI> uncachedBatch = PosixGroupCache.uncachedGroupURIs(batch);
            if (uncachedBatch.isEmpty()) {
                continue;
            }
            final List<PosixGroup> fetchedGroups =
                    Subject.doAs(currentSubject, (PrivilegedExceptionAction<List<PosixGroup>>)
                            () -> posixMapperClient.getGID(uncachedBatch));
            fetchedGroups.forEach(posixGroup ->
                    PosixGroupCache.GROUP_URI_POSIX_GROUP_CACHE.put(posixGroup.getGroupURI(), posixGroup));
        }
    }
}
