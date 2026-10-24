package org.opencadc.skaha.utils;

import ca.nrc.cadc.auth.PosixPrincipal;
import ca.nrc.cadc.net.TransientException;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.opencadc.auth.PosixGroup;
import org.opencadc.auth.PosixMapperClient;
import org.opencadc.gms.GroupURI;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisConnectionException;

public class PosixCacheTest {
    @Test
    public void testRequestingUserEntriesAlwaysWritten() throws Exception {
        final JedisPooled jedis = Mockito.mock(JedisPooled.class);
        final PosixCache testSubject = new PosixCache(jedis, "/cavern/home", Mockito.mock(PosixMapperClient.class));

        testSubject.writePOSIXEntries(
                alice(), List.of(new PosixGroup(3001, new GroupURI(URI.create("ivo://example.org/gms?astro")))));

        Mockito.verify(jedis).sadd("users:posix", "alice:x:1000:1000::/cavern/home/alice:/sbin/nologin");
        Mockito.verify(jedis).sadd("groups:posix", "astro:x:3001:");
    }

    @Test
    public void testRequestingUserWriteFailure() {
        final JedisPooled jedis = Mockito.mock(JedisPooled.class);
        Mockito.when(jedis.sadd(Mockito.anyString(), Mockito.any(String[].class)))
                .thenThrow(new JedisConnectionException("connection refused"));
        final PosixCache testSubject = new PosixCache(jedis, "/cavern/home", Mockito.mock(PosixMapperClient.class));

        Assert.assertThrows(TransientException.class, () -> testSubject.writePOSIXEntries(alice(), Set.of()));
    }

    @Test
    public void testRefreshFailureDoesNotFailLaunch() throws Exception {
        // pipelined() returns null on the mock, so both full-map refreshes fail; the launch proceeds.
        final JedisPooled jedis = Mockito.mock(JedisPooled.class);
        final PosixCache testSubject = new PosixCache(jedis, "/cavern/home", Mockito.mock(PosixMapperClient.class));

        testSubject.writePOSIXEntries(alice(), Set.of());

        Mockito.verify(jedis).sadd("users:posix", "alice:x:1000:1000::/cavern/home/alice:/sbin/nologin");
        Mockito.verify(jedis, Mockito.never()).sadd(Mockito.eq("groups:posix"), Mockito.any(String[].class));
    }

    private static PosixPrincipal alice() {
        final PosixPrincipal posixPrincipal = new PosixPrincipal(1000);
        posixPrincipal.username = "alice";
        return posixPrincipal;
    }
}
