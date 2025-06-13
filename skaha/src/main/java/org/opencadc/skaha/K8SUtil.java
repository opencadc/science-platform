/*
 ************************************************************************
 *******************  CANADIAN ASTRONOMY DATA CENTRE  *******************
 **************  CENTRE CANADIEN DE DONNÉES ASTRONOMIQUES  **************
 *
 *  (c) 2019.                            (c) 2019.
 *  Government of Canada                 Gouvernement du Canada
 *  National Research Council            Conseil national de recherches
 *  Ottawa, Canada, K1A 0R6              Ottawa, Canada, K1A 0R6
 *  All rights reserved                  Tous droits réservés
 *
 *  NRC disclaims any warranties,        Le CNRC dénie toute garantie
 *  expressed, implied, or               énoncée, implicite ou légale,
 *  statutory, of any kind with          de quelque nature que ce
 *  respect to the software,             soit, concernant le logiciel,
 *  including without limitation         y compris sans restriction
 *  any warranty of merchantability      toute garantie de valeur
 *  or fitness for a particular          marchande ou de pertinence
 *  purpose. NRC shall not be            pour un usage particulier.
 *  liable in any event for any          Le CNRC ne pourra en aucun cas
 *  damages, whether direct or           être tenu responsable de tout
 *  indirect, special or general,        dommage, direct ou indirect,
 *  consequential or incidental,         particulier ou général,
 *  arising from the use of the          accessoire ou fortuit, résultant
 *  software.  Neither the name          de l'utilisation du logiciel. Ni
 *  of the National Research             le nom du Conseil National de
 *  Council of Canada nor the            Recherches du Canada ni les noms
 *  names of its contributors may        de ses  participants ne peuvent
 *  be used to endorse or promote        être utilisés pour approuver ou
 *  products derived from this           promouvoir les produits dérivés
 *  software without specific prior      de ce logiciel sans autorisation
 *  written permission.                  préalable et particulière
 *                                       par écrit.
 *
 *  This file is part of the             Ce fichier fait partie du projet
 *  OpenCADC project.                    OpenCADC.
 *
 *  OpenCADC is free software:           OpenCADC est un logiciel libre ;
 *  you can redistribute it and/or       vous pouvez le redistribuer ou le
 *  modify it under the terms of         modifier suivant les termes de
 *  the GNU Affero General Public        la “GNU Affero General Public
 *  License as published by the          License” telle que publiée
 *  Free Software Foundation,            par la Free Software Foundation
 *  either version 3 of the              : soit la version 3 de cette
 *  License, or (at your option)         licence, soit (à votre gré)
 *  any later version.                   toute version ultérieure.
 *
 *  OpenCADC is distributed in the       OpenCADC est distribué
 *  hope that it will be useful,         dans l’espoir qu’il vous
 *  but WITHOUT ANY WARRANTY;            sera utile, mais SANS AUCUNE
 *  without even the implied             GARANTIE : sans même la garantie
 *  warranty of MERCHANTABILITY          implicite de COMMERCIALISABILITÉ
 *  or FITNESS FOR A PARTICULAR          ni d’ADÉQUATION À UN OBJECTIF
 *  PURPOSE.  See the GNU Affero         PARTICULIER. Consultez la Licence
 *  General Public License for           Générale Publique GNU Affero
 *  more details.                        pour plus de détails.
 *
 *  You should have received             Vous devriez avoir reçu une
 *  a copy of the GNU Affero             copie de la Licence Générale
 *  General Public License along         Publique GNU Affero avec
 *  with OpenCADC.  If not, see          OpenCADC ; si ce n’est
 *  <http://www.gnu.org/licenses/>.      pas le cas, consultez :
 *                                       <http://www.gnu.org/licenses/>.
 *
 ************************************************************************
 */

package org.opencadc.skaha;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import ca.nrc.cadc.util.StringUtil;
import org.apache.log4j.Logger;
import org.apache.logging.log4j.core.appender.rolling.FileSize;
import org.opencadc.vospace.client.VOSpaceClient;

public class K8SUtil {
    static final String SKAHA_USER_STORAGE_QUOTA_IN_GB = "SKAHA_DEFAULT_STORAGE_QUOTA_GB";
    static final String SKAHA_SESSIONS_IMAGE_REGISTRY_HOSTS = "SKAHA_SESSIONS_IMAGE_REGISTRY_HOSTS";
    static final String SKAHA_SESSIONS_HOSTNAME = "SKAHA_SESSIONS_HOSTNAME";
    static final String SKAHA_HOSTNAME = "SKAHA_HOSTNAME";
    static final String SKAHA_USERS_GROUP = "SKAHA_USERS_GROUP";
    static final String SKAHA_HEADLESS_GROUP = "SKAHA_HEADLESS_GROUP";
    static final String SKAHA_WORKLOAD_NAMESPACE = "SKAHA_WORKLOAD_NAMESPACE";
    static final String SKAHA_HEADLESS_PRIORITY_GROUP = "SKAHA_HEADLESS_PRIORITY_GROUP";
    static final String SKAHA_HEADLESS_PRIORITY_CLASS = "SKAHA_HEADLESS_PRIORITY_CLASS";
    static final String SKAHA_ADMINS_GROUP = "SKAHA_ADMINS_GROUP";
    static final String SKAHA_SESSIONS_MAX_COUNT = "SKAHA_SESSIONS_MAX_COUNT";
    static final String SKAHA_POSIX_MAPPER_RESOURCE_ID = "SKAHA_POSIX_MAPPER_RESOURCE_ID";
    static final String SKAHA_SESSIONS_EXPIRY_SECONDS = "SKAHA_SESSIONS_EXPIRY_SECONDS";
    static final String SKAHA_SESSIONS_GPU_ENABLED = "SKAHA_SESSIONS_GPU_ENABLED";
    static final String SKAHA_CAVERN_SERVICE_URI = "SKAHA_CAVERN_SERVICE_URI";

    private static final Logger log = Logger.getLogger(K8SUtil.class);


    public static String getSessionsHostName() {
        return System.getenv(K8SUtil.SKAHA_SESSIONS_HOSTNAME);
    }

    public static String getSkahaHostName() {
        return System.getenv(K8SUtil.SKAHA_HOSTNAME);
    }

    public static String getWorkloadNamespace() {
        return System.getenv(K8SUtil.SKAHA_WORKLOAD_NAMESPACE);
    }

    /**
     * Filter out anything not in the alphanumeric or hyphen character set.
     *
     * @see <a href="https://kubernetes.io/docs/concepts/overview/working-with-objects/names/">Kubernetes Object
     *     names</a>
     * @param sessionID The provided session ID.
     * @param type The defined type (desktop, notebook, etc.)
     * @param userID The running User's ID.
     * @return String sanitized name. Never null.
     */
    public static String getJobName(String sessionID, SessionType type, String userID) {
        // Replace values that are NOT alphanumeric or a hyphen.
        final String userJobID = userID.replaceAll("[^0-9a-zA-Z-]", "-");
        return ("skaha-" + type.name().toLowerCase() + "-" + userJobID + "-" + sessionID).toLowerCase();
    }

    public static String getHomeDir() {
        return System.getenv("skaha.homedir");
    }

    public static String getScratchDir() {
        return System.getenv("skaha.scratchdir");
    }

    /**
     * Get the configured session expiry time in seconds.  This need never be an integer as it will always be
     * sent to Kubernetes configuration as a string.
     *
     * @return String representing the number of seconds.
     */
    public static String getSessionExpiry() {
        return System.getenv(K8SUtil.SKAHA_SESSIONS_EXPIRY_SECONDS);
    }

    /**
     * Get the configured default quota size in Gigabytes.
     *
     * @return String representing the quota size in GB.
     */
    public static String getDefaultQuota() {
        return System.getenv(K8SUtil.SKAHA_USER_STORAGE_QUOTA_IN_GB);
    }

    public static String getDefaultQuotaBytes() {
        final String defaultQuotaGB = K8SUtil.getDefaultQuota();
        return Long.toString(FileSize.parse(defaultQuotaGB + "GB", 0L)); // Validate the value is a valid size.
    }

    public static String getPreAuthorizedTokenSecretName() {
        return "pre-auth-token-skaha";
    }

    public static String getSessionsUserStorageTopLevelDir() {
        final String configuredUserStorageTopLevelDir = System.getenv("SKAHA_USER_STORAGE_TOP_LEVEL_DIR");
        if (!StringUtil.hasText(configuredUserStorageTopLevelDir)) {
            throw new IllegalStateException("Environment variable SKAHA_USER_STORAGE_TOP_LEVEL_DIR is not set as expected.");
        } else {
            return configuredUserStorageTopLevelDir;
        }
    }

    public static URI getUserHomeURI() {
        final String configuredUserHomeURI = System.getenv("SKAHA_USER_HOME_URI");
        if (!StringUtil.hasText(configuredUserHomeURI)) {
            throw new IllegalStateException("Environment variable SKAHA_USER_HOME_URI is not set as expected.");
        } else {
            return URI.create(System.getenv("SKAHA_USER_HOME_URI"));
        }
    }

    public static URI getCavernServiceURI() {
        final String configuredCavernServiceURI = System.getenv(K8SUtil.SKAHA_CAVERN_SERVICE_URI);
        if (!StringUtil.hasText(configuredCavernServiceURI)) {
            throw new IllegalStateException("Environment variable SKAHA_CAVERN_SERVICE_URI is not set as expected.");
        } else {
            return URI.create(configuredCavernServiceURI);
        }
    }

    /**
     * Get a VOSpaceClient instance configured to connect to the Cavern service.
     *
     * @return VOSpaceClient instance.
     */
    public static VOSpaceClient getVOSpaceClient() {
        return new VOSpaceClient(K8SUtil.getCavernServiceURI());
    }

    public static boolean isGpuEnabled() {
        return Boolean.parseBoolean(System.getenv(K8SUtil.SKAHA_SESSIONS_GPU_ENABLED));
    }

    public static List<String> getSessionsImageRegistryHosts() {
        final String rawHosts = System.getenv(K8SUtil.SKAHA_SESSIONS_IMAGE_REGISTRY_HOSTS);
        if (rawHosts == null) {
            log.warn("No harbor hosts configured.");
            return List.of();
        }

        return K8SUtil.getSessionsImageRegistryHosts(rawHosts);
    }

    static List<String> getSessionsImageRegistryHosts(final String rawHosts) {
        return Arrays.stream(rawHosts.split(" ")).map(String::trim).collect(Collectors.toList());
    }

    public static String getSkahaUsersGroup() {
        return System.getenv(K8SUtil.SKAHA_USERS_GROUP);
    }

    public static String getSkahaAdminsGroup() {
        return System.getenv(K8SUtil.SKAHA_ADMINS_GROUP);
    }

    public static String getSkahaHeadlessGroup() {
        return System.getenv(K8SUtil.SKAHA_HEADLESS_GROUP);
    }

    public static String getSkahaHeadlessPriorityGroup() {
        return System.getenv(K8SUtil.SKAHA_HEADLESS_PRIORITY_GROUP);
    }

    public static String getSkahaHeadlessPriorityClass() {
        return System.getenv(K8SUtil.SKAHA_HEADLESS_PRIORITY_CLASS);
    }

    public static Integer getMaxUserSessions() {
        String noOfSessions = System.getenv(K8SUtil.SKAHA_SESSIONS_MAX_COUNT);
        if (noOfSessions == null) {
            log.warn("no max user sessions value configured.");
            return 1;
        }
        return Integer.parseInt(noOfSessions);
    }

    public static String getPosixCacheUrl(String packageName) {
        return System.getProperty(packageName + ".posixCache.url");
    }

    public static String getPosixMapperResourceId() {
        return System.getenv(K8SUtil.SKAHA_POSIX_MAPPER_RESOURCE_ID);
    }

    public static String getRedisHost() {
        return System.getenv("REDIS_HOST");
    }

    public static String getRedisPort() {
        return System.getenv("REDIS_PORT");
    }

    /**
     * Obtain the working directory for the current process.
     *
     * @return String working directory from Java's System properties.
     */
    public static String getWorkingDirectory() {
        return System.getProperty("user.home");
    }
}
