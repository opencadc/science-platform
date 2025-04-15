package org.opencadc.skaha.utils;

import ca.nrc.cadc.reg.client.LocalAuthority;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Base64;
import java.util.Collection;
import java.util.Set;

public class CommonUtils {
    public static boolean isNotEmpty(Collection<?> collection) {
        return null != collection && !collection.isEmpty();
    }

    public static boolean isNotEmpty(String str) {
        return null != str && !str.isBlank();
    }

    public static String encodeBase64(final byte[] encodedValue) {
        final byte[] encodedBytes = Base64.getEncoder().encode(encodedValue);
        return new String(encodedBytes).strip();
    }

    /**
     * Obtain the first configured Service URI for the given base standard ID.
     *
     * @param baseStandardID The URI to lookup.
     * @return A single URI (first matching). Never null.
     */
    public static URI firstLocalServiceURI(final URI baseStandardID) {
        final Set<URI> serviceURIs = new LocalAuthority().getServiceURIs(baseStandardID);
        return serviceURIs.stream().findFirst().orElseThrow(IllegalStateException::new);
    }

    /**
     * Format to Binary SI values, which users powers of 1000, not 1024.
     *
     * @param bytes Bytes reported from Kubernetes.
     * @return String formatted, never null.
     */
    public static String formatMemoryFromBytes(final BigDecimal bytes) {
        final long longBytes = bytes.longValue();
        final long absB = longBytes == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(longBytes);

        if (absB < 1024) {
            return longBytes + " B";
        }

        final String[] units = {"Ki", "Mi", "Gi", "Ti", "Pi", "Ei"};
        int unitIndex = -1;
        double value = longBytes;

        do {
            value = value / 1024.0D;
            unitIndex++;
        } while (value >= 1024.0D && unitIndex < units.length - 1);

        return String.format("%d%s", Double.valueOf(value).longValue(), units[unitIndex]);
    }

    /**
     * Format the given number of cores, likely in decimal form, as a String integer.
     *
     * @param cores Cores in BigDecimal format.
     * @return String formatted, never null.
     */
    public static String formatCPUCores(final BigDecimal cores) {
        return Integer.toString(cores.intValue());
    }
}
