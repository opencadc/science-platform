package org.opencadc.skaha.utils;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import redis.clients.jedis.Jedis;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class RedisCache {
    private final Jedis jedis;
    private final Gson gson = new Gson();

    public RedisCache(String host, int port) {
        jedis = new Jedis(host, port);
    }

    public String put(String key, String value) {
        if (key == null) throw new RuntimeException("null key");
        try {
            return jedis.set(key, value);
        } catch (Exception e) {
            e.printStackTrace();
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
            e.printStackTrace();
            throw e;
        }
    }

    public <T> T get(String key, Class<T> className) {
        String valueInString = get(key);
        if (valueInString == null) return null;
        try {
            return gson.fromJson(valueInString, className);
        } catch (JsonSyntaxException ex) {
            ex.printStackTrace();
            throw new ClassCastException("Unable to cast value to " + className.getCanonicalName());
        } catch (Exception ex) {
            ex.printStackTrace();
            throw ex;
        }
    }

    public List<String> lrange(String key, long start, long stop) {
        return jedis.lrange(key, start, stop);
    }

    public List<String> lrange(String key) {
        return lrange(key, 0, -1);
    }

    public <T> List<T> lrange(String key, Class<T> className) {
        List<String> list = lrange(key);
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
            e.printStackTrace();
            return null;
        }
    }

    public void close() {
        jedis.close();
    }
}
