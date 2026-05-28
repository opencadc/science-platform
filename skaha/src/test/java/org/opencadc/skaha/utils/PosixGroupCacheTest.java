package org.opencadc.skaha.utils;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Test;
import org.opencadc.auth.PosixGroup;
import org.opencadc.auth.PosixMapperClient;
import org.opencadc.gms.GroupURI;

public class PosixGroupCacheTest {
    private static GroupURI groupUri(final String query) {
        return new GroupURI(URI.create("ivo://example.org/gms?" + query));
    }

    private static PosixGroup posixGroup(final GroupURI groupURI, final int gid) {
        final PosixGroup posixGroup = mock(PosixGroup.class);
        when(posixGroup.getGroupURI()).thenReturn(groupURI);
        when(posixGroup.getGID()).thenReturn(gid);
        return posixGroup;
    }

    @Test
    public void testToGIDsQueriesOnlyMissingGroups() throws Exception {
        final GroupURI cachedUri = groupUri("cached-group");
        final GroupURI missingUri = groupUri("missing-group");
        final PosixGroup cachedGroup = posixGroup(cachedUri, 1001);
        final PosixGroup missingGroup = posixGroup(missingUri, 1002);

        final AtomicInteger queryCount = new AtomicInteger();
        final PosixMapperClient client = mock(PosixMapperClient.class);
        when(client.getGID(anyList())).thenAnswer(invocation -> {
            queryCount.incrementAndGet();
            @SuppressWarnings("unchecked")
            final List<GroupURI> groupURIs = invocation.getArgument(0);
            if (groupURIs.equals(List.of(cachedUri))) {
                return List.of(cachedGroup);
            }
            if (groupURIs.equals(List.of(missingUri))) {
                return List.of(missingGroup);
            }
            throw new AssertionError("Unexpected batch: " + groupURIs);
        });

        final PosixGroupCache cache = new PosixGroupCache(client);
        cache.toGIDs(List.of(cachedUri));
        queryCount.set(0);

        final List<PosixGroup> results = cache.toGIDs(List.of(cachedUri, missingUri, cachedUri));

        Assert.assertEquals(1, queryCount.get());
        Assert.assertEquals(List.of(cachedGroup, missingGroup, cachedGroup), results);
    }

    @Test
    public void testToGIDsUsesCacheWithoutQuerying() throws Exception {
        final GroupURI groupOne = groupUri("group-one");
        final GroupURI groupTwo = groupUri("group-two");
        final PosixGroup posixGroupOne = posixGroup(groupOne, 2001);
        final PosixGroup posixGroupTwo = posixGroup(groupTwo, 2002);

        final AtomicInteger queryCount = new AtomicInteger();
        final PosixMapperClient client = mock(PosixMapperClient.class);
        when(client.getGID(anyList())).thenAnswer(invocation -> {
            queryCount.incrementAndGet();
            @SuppressWarnings("unchecked")
            final List<GroupURI> groupURIs = invocation.getArgument(0);
            if (groupURIs.equals(List.of(groupOne, groupTwo))) {
                return List.of(posixGroupOne, posixGroupTwo);
            }
            throw new AssertionError("Unexpected batch: " + groupURIs);
        });

        final PosixGroupCache cache = new PosixGroupCache(client);
        cache.toGIDs(List.of(groupOne, groupTwo));
        queryCount.set(0);

        final List<PosixGroup> results = cache.toGIDs(List.of(groupOne, groupTwo));

        Assert.assertEquals(0, queryCount.get());
        Assert.assertEquals(List.of(posixGroupOne, posixGroupTwo), results);
    }

    @Test
    public void testToGIDsBatchesMissingGroupQueries() throws Exception {
        final List<GroupURI> groupURIs = new ArrayList<>();
        final List<PosixGroup> expectedGroups = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            final GroupURI groupURI = groupUri("batch-group-" + index);
            groupURIs.add(groupURI);
            expectedGroups.add(posixGroup(groupURI, 3000 + index));
        }

        final AtomicInteger queryCount = new AtomicInteger();
        final PosixMapperClient client = mock(PosixMapperClient.class);
        when(client.getGID(anyList())).thenAnswer(invocation -> {
            queryCount.incrementAndGet();
            @SuppressWarnings("unchecked")
            final List<GroupURI> requestedGroupURIs = invocation.getArgument(0);
            final List<PosixGroup> batchResults = new ArrayList<>();
            for (final GroupURI requestedGroupURI : requestedGroupURIs) {
                final int index =
                        Integer.parseInt(requestedGroupURI.getURI().getQuery().substring("batch-group-".length()));
                batchResults.add(posixGroup(requestedGroupURI, 3000 + index));
            }
            return batchResults;
        });

        final List<PosixGroup> results = new PosixGroupCache(client).toGIDs(groupURIs);

        Assert.assertEquals(2, queryCount.get());
        Assert.assertEquals(expectedGroups.size(), results.size());
        for (int index = 0; index < expectedGroups.size(); index++) {
            Assert.assertEquals(
                    expectedGroups.get(index).getGroupURI(), results.get(index).getGroupURI());
            Assert.assertEquals(
                    expectedGroups.get(index).getGID(), results.get(index).getGID());
        }
    }
}
