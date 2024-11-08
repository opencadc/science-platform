package org.opencadc.skaha.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.log4j.Logger;
import redis.clients.jedis.Jedis;

public class RedisCache {
    private static final Logger log = Logger.getLogger(RedisCache.class);
    private final Jedis jedis;
    private static final ObjectMapper mapper = new ObjectMapper();

    static {
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    public RedisCache() {
        jedis = null;
    }

    public RedisCache(String host, String port) {
        jedis = new Jedis(host, Integer.parseInt(port));
    }

    public List<String> getRange(String key, long start, long stop) {
        return jedis.lrange(key, start, stop);
    }

    public List<String> getAll(String key) {
        return getRange(key, 0, -1);
    }

    public <T> List<T> getAll(String key, Class<T> className) {
        List<String> list = getAll(key);
        if (list.isEmpty()) return List.of();
        return list.parallelStream()
                .map(item -> deserialize(className, item))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private <T> T deserialize(Class<T> className, String item) {
        try {
            return mapper.readValue(item, className);
        } catch (JsonProcessingException e) {
            log.error(e);
            return null;
        }
    }

    public void close() {
        jedis.close();
    }
}
