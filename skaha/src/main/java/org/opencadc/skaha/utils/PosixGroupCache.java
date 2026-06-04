package org.opencadc.skaha.utils;

import ca.nrc.cadc.auth.AuthenticationUtil;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
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
 */
public class PosixGroupCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(PosixGroupCache.class);
    private static final int POSIX_MAPPER_GID_QUERY_BATCH_SIZE = 20;
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
        if (groupURIsToQuery.isEmpty()) {
            return;
        }

        final Subject currentSubject = AuthenticationUtil.getCurrentSubject();
        if (groupURIsToQuery.size() <= PosixGroupCache.POSIX_MAPPER_GID_QUERY_BATCH_SIZE) {
            final List<PosixGroup> fetchedGroups =
                    Subject.doAs(currentSubject, (PrivilegedExceptionAction<List<PosixGroup>>)
                            () -> posixMapperClient.getGID(groupURIsToQuery));
            fetchedGroups.forEach(posixGroup ->
                    PosixGroupCache.GROUP_URI_POSIX_GROUP_CACHE.put(posixGroup.getGroupURI(), posixGroup));
            return;
        }

        final List<CompletableFuture<List<PosixGroup>>> batchFutures = new ArrayList<>();
        for (int offset = 0;
                offset < groupURIsToQuery.size();
                offset += PosixGroupCache.POSIX_MAPPER_GID_QUERY_BATCH_SIZE) {
            final List<GroupURI> batch = List.copyOf(groupURIsToQuery.subList(
                    offset,
                    Math.min(offset + PosixGroupCache.POSIX_MAPPER_GID_QUERY_BATCH_SIZE, groupURIsToQuery.size())));
            batchFutures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return Subject.doAs(currentSubject, (PrivilegedExceptionAction<List<PosixGroup>>)
                            () -> posixMapperClient.getGID(batch));
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            }));
        }

        LOGGER.debug("Issuing {} posix group requests.", batchFutures.size());
        for (final CompletableFuture<List<PosixGroup>> batchFuture : batchFutures) {
            try {
                batchFuture
                        .get()
                        .forEach(posixGroup ->
                                PosixGroupCache.GROUP_URI_POSIX_GROUP_CACHE.put(posixGroup.getGroupURI(), posixGroup));
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw interruptedException;
            } catch (ExecutionException executionException) {
                final Throwable cause = executionException.getCause();
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                throw new Exception(cause.getMessage(), cause);
            }
        }
    }
}
