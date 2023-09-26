package org.opencadc.skaha.utils;

import java.util.Collection;
import java.util.Map;

public class CollectionUtils {
    public static boolean isNotEmpty(Collection<?> collection) {
        return null != collection && !collection.isEmpty();
    }

    private static boolean isNotEmpty(Map<?, ?> map) {
        return null != map && !map.isEmpty();
    }
}
