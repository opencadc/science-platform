package org.opencadc.skaha.utils;

import java.lang.reflect.Field;
import java.util.Map;

public class TestUtils {
    public static <T> void set(T object, String propertyName, Object value) {
        set(object, object.getClass(), propertyName, value);
    }

    public static <T> void set(T object, Class<?> className, String propertyName, Object value) {
        try {
            // Get the field by name
            Field field = className.getDeclaredField(propertyName);
            // Make the field accessible if it's private
            field.setAccessible(true);
            // Set the value of the field
            field.set(object, value);
        } catch (NoSuchFieldException e) {
            System.err.println("No such field: " + propertyName);
        } catch (IllegalAccessException e) {
            System.err.println("Cannot access field: " + propertyName);
        }
    }

    public static void setEnv(String key, String value) {
        try {
            Map<String, String> env = System.getenv();
            Class<?> clazz = env.getClass();
            Field field = clazz.getDeclaredField("m");
            field.setAccessible(true);
            Map<String, String> writableEnv = (Map<String, String>) field.get(env);
            writableEnv.put(key, value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to set environment variable", e);
        }
    }
}
