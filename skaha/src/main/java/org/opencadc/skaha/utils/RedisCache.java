package org.opencadc.skaha.utils;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.apache.log4j.Logger;
import redis.clients.jedis.Jedis;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class RedisCache {
    private static final Logger log = Logger.getLogger(RedisCache.class);
    private final Jedis jedis;
    private final Gson gson = new Gson();

    public RedisCache() {
        jedis = null;
    }

    public RedisCache(String host, String port) {
        jedis = new Jedis(host, Integer.parseInt(port));
    }

    public String put(String key, String value) {
        if (key == null) throw new RuntimeException("null key");
        try {
            return jedis.set(key, value);
        } catch (Exception e) {
            log.error(e);
            throw e;
        }
    }

    public String put(String key, Object value) {
        if (value == null) throw new RuntimeException("null value");
        String valueInString = gson.toJson(value);
        return put(key, valueInString);
    }

    public String get(String key) {
        if (key == null) throw new RuntimeException("null key");
        try {
            return jedis.get(key);
        } catch (Exception e) {
            log.error(e);
            throw e;
        }
    }

    public <T> T get(String key, Class<T> className) {
        String valueInString = get(key);
        if (valueInString == null) return null;
        try {
            return gson.fromJson(valueInString, className);
        } catch (JsonSyntaxException e) {
            log.error(e);
            throw new ClassCastException("Unable to cast value to " + className.getCanonicalName());
        } catch (Exception e) {
            log.error(e);
            throw e;
        }
    }

    public List<String> getAll(String key, long start, long stop) {
        return jedis.lrange(key, start, stop);
    }

    public List<String> getAll(String key) {
        return getAll(key, 0, -1);
    }

    public <T> List<T> getAll(String key, Class<T> className) {
        List<String> list = getAll(key);
        if (list.isEmpty()) return List.of();
        return list
                .stream()
                .map(item -> deserialize(className, item))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private <T> T deserialize(Class<T> className, String item) {
        try {
            return gson.fromJson(item, className);
        } catch (JsonSyntaxException e) {
            log.error(e);
            return null;
        }
    }

    public void close() {
        jedis.close();
    }
}
