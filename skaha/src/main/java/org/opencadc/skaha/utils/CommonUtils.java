package org.opencadc.skaha.utils;

import ca.nrc.cadc.reg.client.LocalAuthority;
import ca.nrc.cadc.util.StringUtil;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.log4j.Logger;

public class CommonUtils {
    private static final Logger LOGGER = Logger.getLogger(CommonUtils.class);

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
        final Set<URI> serviceURIs = new LocalAuthority().getResourceIDs(baseStandardID);
        return serviceURIs.stream().findFirst().orElse(null);
    }

    /**
     * Generate an expiry time string based on a given start time string and an expiry time in seconds.
     *
     * @param startTimeString The start time string in ISO 8601 format (e.g., "2025-04-10T15:45Z" or
     *     "2025-04-10T15:45:33.900Z").
     * @param expiryTimeInSeconds The expiry time in seconds to be added to the start time.
     * @return String representing the calculated expiry time in ISO 8601 format, or null if the start time string is
     *     unparsable. Never null.
     */
    public static String getExpiryTimeString(final String startTimeString, final Long expiryTimeInSeconds) {
        final String outputTemplate = "%s-%s-%sT%s:%s:%sZ";
        final Pattern expectedFormat = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):?(\\d{2})?.*Z");
        final Matcher matcher = expectedFormat.matcher(startTimeString);
        final List<String> captureGroups = new ArrayList<>();
        if (matcher.find()) {
            for (int i = 0; i < matcher.groupCount(); i++) {
                final String nextMatch = matcher.group(i + 1);
                if (StringUtil.hasLength(nextMatch)) {
                    captureGroups.add(nextMatch);
                }
            }
        }

        final int capturedGroupCount = captureGroups.size();

        // Expected order of the groups resulting in count:
        // Calendar.YEAR, Calendar.MONTH, Calendar.DAY_OF_MONTH, Calendar.HOUR, Calendar.MINUTE, Calendar.SECOND
        //
        // Some dates, however, are missing some elements if the value is 0.
        final int expectedCount = 6;
        final int missingGroups = expectedCount - capturedGroupCount;
        if (missingGroups > 3) {
            LOGGER.warn("Unparsable start time: " + startTimeString);
            return null;
        } else {
            if (missingGroups > 0) {
                for (int i = 0; i < missingGroups; i++) {
                    captureGroups.add("00");
                }
            }
        }

        final String[] captureGroupsArray = captureGroups.toArray(new String[0]);
        final String instantTime = String.format(outputTemplate, (Object[]) captureGroupsArray);
        final Instant instant = Instant.parse(instantTime);
        return instant.plusSeconds(expiryTimeInSeconds).toString();
    }
}
