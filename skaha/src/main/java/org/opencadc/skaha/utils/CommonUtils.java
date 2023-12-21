package org.opencadc.skaha.utils;

import java.util.Base64;
import java.util.Collection;


public class CommonUtils {
    public static boolean isNotEmpty(Collection<?> collection) {
        return null != collection && !collection.isEmpty();
    }

    public static boolean isNotEmpty(String str) {
        return null != str && !str.isBlank();
    }

    public static String decodeBase64(String encodedString) {
        byte[] decodedBytes = Base64.getDecoder().decode(encodedString);
        return new String(decodedBytes).strip();
    }

    public static String encodeBase64(final byte[] encodedValue) {
        final byte[] encodedBytes = Base64.getEncoder().encode(encodedValue);
        return new String(encodedBytes).strip();
    }
}
