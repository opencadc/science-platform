package org.opencadc.skaha.utils;

import ca.nrc.cadc.reg.client.LocalAuthority;
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
     * @return A single URI (first matching).  Never null.
     */
    public static URI firstLocalServiceURI(final URI baseStandardID) {
        final Set<URI> serviceURIs = new LocalAuthority().getServiceURIs(baseStandardID);
        return serviceURIs.stream().findFirst().orElseThrow(IllegalStateException::new);
    }
}
