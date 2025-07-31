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
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static {
        OBJECT_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    public static List<String> getRange(String host, int port, String key, long start, long stop) {
        try (final Jedis jedis = new Jedis(host, port)) {
            return RedisCache.getRange(jedis, key, start, stop);
        }
    }

    static List<String> getRange(Jedis jedis, String key, long start, long stop) {
        return jedis.lrange(key, start, stop);
    }

    public static List<String> getAll(String host, int port, String key) {
        return RedisCache.getRange(host, port, key, 0, -1);
    }

    static List<String> getAll(Jedis jedis, String key) {
        return RedisCache.getRange(jedis, key, 0, -1);
    }

    public static <T> List<T> getAll(String host, int port, String key, Class<T> className) {
        List<String> list = RedisCache.getAll(host, port, key);
        if (list.isEmpty()) {
            return List.of();
        } else {
            return list.parallelStream()
                    .map(item -> RedisCache.deserialize(className, item))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
    }

    static <T> List<T> getAll(Jedis jedis, String key, Class<T> className) {
        List<String> list = RedisCache.getAll(jedis, key);
        if (list.isEmpty()) {
            return List.of();
        } else {
            return list.parallelStream()
                    .map(item -> RedisCache.deserialize(className, item))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
    }

    private static <T> T deserialize(Class<T> className, String item) {
        try {
            return RedisCache.OBJECT_MAPPER.readValue(item, className);
        } catch (JsonProcessingException e) {
            log.error(e);
            return null;
        }
    }
}
