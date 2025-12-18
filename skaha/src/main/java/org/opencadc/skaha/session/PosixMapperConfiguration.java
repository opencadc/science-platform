package org.opencadc.skaha.session;

import ca.nrc.cadc.util.InvalidConfigException;
import ca.nrc.cadc.util.StringUtil;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import org.opencadc.auth.PosixMapperClient;

/**
 * It's important to use the correct constructor for the PosixMapperClient, this class will wrap the logic based on how
 * the Resource ID of the POSIX mapper was set (URI or URL).
 */
public class PosixMapperConfiguration {
    private static final String SKAHA_POSIX_MAPPER_RESOURCE_ID_ENV = "SKAHA_POSIX_MAPPER_RESOURCE_ID";
    final URI resourceID;
    final URL baseURL;

    public static PosixMapperConfiguration fromEnv() throws IOException {
        final String configuredPosixMapperResourceID =
                System.getenv(PosixMapperConfiguration.SKAHA_POSIX_MAPPER_RESOURCE_ID_ENV);
        if (StringUtil.hasLength(configuredPosixMapperResourceID)) {
            return new PosixMapperConfiguration(URI.create(configuredPosixMapperResourceID));
        }

        throw new InvalidConfigException("PosixMapper resource ID not set ("
                + PosixMapperConfiguration.SKAHA_POSIX_MAPPER_RESOURCE_ID_ENV + ")");
    }

    private PosixMapperConfiguration(final URI configuredPosixMapperID) throws IOException {
        if ("ivo".equals(configuredPosixMapperID.getScheme())) {
            resourceID = configuredPosixMapperID;
            baseURL = null;
        } else if ("https".equals(configuredPosixMapperID.getScheme())) {
            resourceID = null;
            baseURL = configuredPosixMapperID.toURL();
        } else {
            throw new IllegalStateException(
                    "Incorrect configuration for specified posix mapper service (" + configuredPosixMapperID + ").");
        }
    }

    public PosixMapperClient getPosixMapperClient() {
        if (resourceID == null) {
            return new PosixMapperClient(baseURL);
        } else {
            return new PosixMapperClient(resourceID);
        }
    }
}
