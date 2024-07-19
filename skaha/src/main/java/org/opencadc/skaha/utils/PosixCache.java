package org.opencadc.skaha.utils;

import ca.nrc.cadc.auth.PosixPrincipal;
import ca.nrc.cadc.io.ResourceIterator;
import java.nio.file.Path;
import java.util.List;
import org.apache.log4j.Logger;
import org.opencadc.auth.PosixGroup;
import org.opencadc.auth.PosixMapperClient;
import redis.clients.jedis.AbstractTransaction;
import redis.clients.jedis.JedisPooled;


public class PosixCache {
    private static final Logger LOGGER = Logger.getLogger(PosixCache.class);

    private static final String UID_MAP_KEY = "users:posix";
    private static final String GID_MAP_FIELD = "groups:posix";

    private final JedisPooled jedisPool;
    private final String rootHomeFolder;
    private final PosixMapperClient posixMapperClient;

    /**
     * Construct a new Cache.  This will initialize the Redis Pool (JediPooled) with the given URL and client to the POSIX Mapper API.
     *
     * @param cacheURL          The Redis URL.
     * @param posixMapperClient The Client to the POSIX Mapper API.
     */
    public PosixCache(final String cacheURL, final String rootHomeFolder, final PosixMapperClient posixMapperClient) {
        this.jedisPool = new JedisPooled(cacheURL);
        this.rootHomeFolder = rootHomeFolder;
        this.posixMapperClient = posixMapperClient;
    }

    /**
     * Obtain the POSIX entry for the provided POSIX Principal in POSIX form.
     * Example output:
     * "username1:x:1000:1000"
     *
     * @param posixPrincipalEntry The POSIX Principal wrapper to transform.
     * @return String POSIX entry, never null;
     */
    private static String uidMapping(final PosixPrincipalEntry posixPrincipalEntry) {
        return String.format("%s:x:%d:%d::%s:",
                             posixPrincipalEntry.posixPrincipal.username,
                             posixPrincipalEntry.posixPrincipal.getUidNumber(),
                             posixPrincipalEntry.posixPrincipal.getUidNumber(),
                             posixPrincipalEntry.homeFolder);
    }

    private static String gidMapping(final PosixGroup posixGroup) {
        return String.format("%s:x:%d:",
                             posixGroup.getGroupURI().getURI().getQuery(),
                             posixGroup.getGID());
    }

    public void writePOSIXEntries() throws Exception {
        writeUserEntries();
        writeGroupEntries();
    }

    private void writeUserEntries() throws Exception {
        LOGGER.debug("writeUserEntries()");
        try (final ResourceIterator<PosixPrincipal> posixPrincipalIterator = this.posixMapperClient.getUserMap();
             final AbstractTransaction transaction = this.jedisPool.transaction(true)) {
            while (posixPrincipalIterator.hasNext()) {
                final PosixPrincipal posixPrincipal = posixPrincipalIterator.next();
                transaction.sadd(PosixCache.UID_MAP_KEY, PosixCache.uidMapping(
                    new PosixPrincipalEntry(posixPrincipal, Path.of(this.rootHomeFolder, posixPrincipal.username).toString())));
            }

            final List<Object> setEntries = transaction.exec();
            LOGGER.debug("writeUserEntries(): OK " + setEntries.size() + " user entries");
        }
    }

    private void writeGroupEntries() throws Exception {
        LOGGER.debug("writeGroupEntries()");
        try (final ResourceIterator<PosixGroup> posixGroupIterator = this.posixMapperClient.getGroupMap();
             final AbstractTransaction transaction = this.jedisPool.transaction(true)) {
            while (posixGroupIterator.hasNext()) {
                final PosixGroup posixGroup = posixGroupIterator.next();
                transaction.sadd(PosixCache.GID_MAP_FIELD, PosixCache.gidMapping(posixGroup));
            }

            final List<Object> setEntries = transaction.exec();
            LOGGER.debug("writeGroupEntries(): OK " + setEntries.size() + " group entries");
        }
    }

    private static final class PosixPrincipalEntry {
        private final PosixPrincipal posixPrincipal;
        private final String homeFolder;

        PosixPrincipalEntry(final PosixPrincipal posixPrincipal, final String homeFolder) {
            this.posixPrincipal = posixPrincipal;
            this.homeFolder = homeFolder;
        }
    }
}
