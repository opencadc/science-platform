package org.opencadc.skaha.utils;

import com.google.gson.Gson;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.opencadc.skaha.image.Image;
import redis.clients.jedis.Jedis;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.when;
import static org.opencadc.skaha.utils.TestUtils.set;

@RunWith(MockitoJUnitRunner.class)
public class RedisCacheTest {
    private final RedisCache redisCache = new RedisCache();

    Jedis jedis;

    Gson gson = new Gson();


    private static final String OK = "OK";

    @Before
    public void setUp() throws Exception {
        jedis = Mockito.mock(Jedis.class);
        set(redisCache, "jedis", jedis);
    }


    @Test
    public void testGetAllWithRange() {
        // Arrange
        String key = "testKey";
        long start = 0;
        long stop = 1;
        List<String> expectedList = List.of("value1");

        when(jedis.lrange(key, start, stop)).thenReturn(expectedList);

        List<String> result = redisCache.getRange(key, start, stop);

        assertEquals(expectedList, result);
    }

    @Test
    public void testGetAllWithoutRange() {
        // Arrange
        String key = "testKey";
        long start = 0;
        long stop = -1;
        List<String> expectedList = List.of("value1", "value2", "value3", "value4", "value5");

        when(jedis.lrange(key, start, stop)).thenReturn(expectedList);

        List<String> result = redisCache.getAll(key);

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

        when(jedis.lrange(key, start, stop)).thenReturn(List.of(gson.toJson(image)));

        List<Image> result = redisCache.getAll(key, Image.class);

        assertEquals(expectedList, result);
    }

    @Test
    public void testGetAllWithWrongTypeClass() {
        // Arrange
        String key = "testKey";
        long start = 0;
        long stop = -1;

        Map<String, Object> image = new HashMap<>() {{
            put("id-", "id");
            put("types-", Set.of("type1", "type2"));
            put("digest-", "digest");
        }};
        List<Image> expectedList = List.of();

        when(jedis.lrange(key, start, stop)).thenReturn(List.of(gson.toJson(image)));

        List<Image> result = redisCache.getAll(key, Image.class);
        assertEquals(expectedList, result);
    }
}