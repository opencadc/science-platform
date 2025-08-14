package org.opencadc.skaha.utils;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.opencadc.skaha.image.Image;
import redis.clients.jedis.Jedis;

@RunWith(MockitoJUnitRunner.class)
public class RedisCacheTest {
    Gson gson = new Gson();

    private static final String OK = "OK";

    @Test
    public void testGetAllWithRange() {
        // Arrange
        String key = "testKey";
        long start = 0;
        long stop = 1;
        List<String> expectedList = List.of("value1");
        final Jedis jedis = Mockito.mock(Jedis.class);

        when(jedis.lrange(key, start, stop)).thenReturn(expectedList);

        List<String> result = RedisCache.getRange(jedis, key, start, stop);

        assertEquals(expectedList, result);
    }

    @Test
    public void testGetAllWithoutRange() {
        // Arrange
        String key = "testKey";
        long start = 0;
        long stop = -1;
        List<String> expectedList = List.of("value1", "value2", "value3", "value4", "value5");
        final Jedis jedis = Mockito.mock(Jedis.class);

        when(jedis.lrange(key, start, stop)).thenReturn(expectedList);

        List<String> result = RedisCache.getAll(jedis, key);

        assertEquals(expectedList, result);
    }

    @Test
    public void testGetAllWithTypeClass() {
        // Arrange
        String key = "testKey";
        long start = 0;
        long stop = -1;

        Image image = new Image("ID", Set.of("type1", "type2"), "digest");
        List<Image> expectedList = List.of(image);
        final Jedis jedis = Mockito.mock(Jedis.class);

        when(jedis.lrange(key, start, stop)).thenReturn(List.of(gson.toJson(image)));

        List<Image> result = RedisCache.getAll(jedis, key, Image.class);

        assertEquals(expectedList, result);
    }

    @Test
    public void testGetAllWithWrongTypeClass() {
        // Arrange
        String key = "testKey";
        long start = 0;
        long stop = -1;

        Map<String, Object> image = new HashMap<>() {
            {
                put("id-", "id");
                put("types-", Set.of("type1", "type2"));
                put("digest-", "digest");
            }
        };
        List<Image> expectedList = List.of();
        final Jedis jedis = Mockito.mock(Jedis.class);

        when(jedis.lrange(key, start, stop)).thenReturn(List.of(gson.toJson(image)));

        List<Image> result = RedisCache.getAll(jedis, key, Image.class);
        assertEquals(expectedList, result);
    }
}
