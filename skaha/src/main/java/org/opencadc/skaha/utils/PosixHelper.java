package org.opencadc.skaha.utils;

import ca.nrc.cadc.auth.PosixPrincipal;
import ca.nrc.cadc.date.DateUtil;
import ca.nrc.cadc.io.ResourceIterator;
import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Calendar;
import java.util.Date;
import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.opencadc.auth.PosixGroup;
import org.opencadc.auth.PosixMapperClient;
import org.opencadc.skaha.K8SUtil;


public class PosixHelper {
    private static final Logger LOGGER = Logger.getLogger(PosixHelper.class);

    static final String POSIX_DELIMITER = ";";
    static final String POSIX_MAPPING_SECRET_LAST_MOD_KEY = "lastModified";

    // TODO: Make this configurable, if this solution proves successful.
    // TODO: jenkinsd 2024.06.02
    //
    static final long ONE_MINUTE_MS = 60L * 1000L;

    public static final String POSIX_MAPPINGS_SECRET_NAME = "posix-mappings";

    /**
     * Obtain the POSIX entry for the provided POSIX Principal in POSIX form.
     * Example output:
     * "username1:x:1000:1000"
     *
     * @param posixPrincipal The POSIX Principal to transform.
     * @return String POSIX entry, never null;
     */
    public static String uidMapping(final PosixPrincipal posixPrincipal) {
        return String.format("%s:x:%d:%d:::\n", posixPrincipal.username,
                             posixPrincipal.getUidNumber(),
                             posixPrincipal.getUidNumber());
    }

    static Date currentDateUTC() {
        return Calendar.getInstance(DateUtil.UTC).getTime();
    }

    static boolean posixMappingSecretRequiresUpdate() throws Exception {
        final JSONObject jsonObject = CommandExecutioner.getSecretData(PosixHelper.POSIX_MAPPINGS_SECRET_NAME, K8SUtil.getWorkloadNamespace());
        if (jsonObject.has(PosixHelper.POSIX_MAPPING_SECRET_LAST_MOD_KEY)) {
            final String lastModDateString = CommonUtils.decodeBase64(jsonObject.getString(PosixHelper.POSIX_MAPPING_SECRET_LAST_MOD_KEY));
            final Date lastModDate = DateUtil.getDateFormat(DateUtil.ISO8601_DATE_FORMAT_LOCAL, DateUtil.UTC).parse(lastModDateString);
            final long currentAgeInMilliseconds = PosixHelper.currentDateUTC().getTime() - lastModDate.getTime();
            LOGGER.debug("current age of " + PosixHelper.POSIX_MAPPINGS_SECRET_NAME + " is " + currentAgeInMilliseconds + " milliseconds");
            return currentAgeInMilliseconds >= PosixHelper.ONE_MINUTE_MS;
        } else {
            LOGGER.debug("secret " + PosixHelper.POSIX_MAPPINGS_SECRET_NAME + " not found");
            return true;
        }
    }

    public static void ensurePosixMappingSecret(final PosixMapperClient posixMapperClient) throws Exception {
        final String posixMappingSecretName = PosixHelper.POSIX_MAPPINGS_SECRET_NAME;

        if (PosixHelper.posixMappingSecretRequiresUpdate()) {
            final Path holdingDir = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), posixMappingSecretName);
            final File uidMappingFile = new File(holdingDir.toString(), "uidmap.txt");
            final File gidMappingFile = new File(holdingDir.toString(), "gidmap.txt");
            final String lastModDateString = DateUtil.getDateFormat(DateUtil.ISO8601_DATE_FORMAT_LOCAL, DateUtil.UTC).format(PosixHelper.currentDateUTC());

            try (final FileWriter uidMappingWriter = new FileWriter(uidMappingFile)) {
                uidMappingWriter.write(getUserEntries(posixMapperClient));
                uidMappingWriter.flush();
            }

            try (final FileWriter gidMappingWriter = new FileWriter(gidMappingFile)) {
                gidMappingWriter.write(getGroupEntries(posixMapperClient));
                gidMappingWriter.flush();
            }

            // create new secret, or update the existing one.
            final String[] createSecretCommand = new String[] {
                "kubectl", "--namespace", K8SUtil.getWorkloadNamespace(), "create", "secret", "generic",
                posixMappingSecretName,
                "--from-file=" + uidMappingFile.getAbsolutePath(),
                "--from-file=" + gidMappingFile.getAbsolutePath(),
                "--from-literal=" + PosixHelper.POSIX_MAPPING_SECRET_LAST_MOD_KEY + "=" + lastModDateString,
                "-o",
                "yaml",
                "--dry-run=client",
                "|",
                "kubectl",
                "apply",
                "-f",
                "-"
            };

            final String createResult = CommandExecutioner.executeInShell(createSecretCommand, false);
            LOGGER.debug("create secret result: " + createResult);
        } else {
            LOGGER.debug("secret " + PosixHelper.POSIX_MAPPINGS_SECRET_NAME + " does not require an update");
        }
    }

    static String getUserEntries(final PosixMapperClient posixMapperClient) throws Exception {
        final StringBuilder userEntryBuilder = new StringBuilder();
        try (final ResourceIterator<PosixPrincipal> posixPrincipalIterator = posixMapperClient.getUserMap()) {
            posixPrincipalIterator.forEachRemaining(pp -> userEntryBuilder.append(PosixHelper.uidMapping(pp)));
        }

        final String userEntriesString = userEntryBuilder.toString();
        if (userEntriesString.lastIndexOf(PosixHelper.POSIX_DELIMITER) > 0) {
            return userEntryBuilder.substring(0, userEntriesString.lastIndexOf(PosixHelper.POSIX_DELIMITER));
        } else {
            return userEntryBuilder.toString();
        }
    }

    static String getGroupEntries(final PosixMapperClient posixMapperClient) throws Exception {
        final StringBuilder groupEntryBuilder = new StringBuilder();
        try (final ResourceIterator<PosixGroup> posixGroupIterator = posixMapperClient.getGroupMap()) {
            posixGroupIterator.forEachRemaining(pg -> groupEntryBuilder.append(
                String.format("%s:x:%d:\n",
                              pg.getGroupURI().getURI().getQuery(),
                              pg.getGID())));
        }

        final String groupEntriesString = groupEntryBuilder.toString();
        if (groupEntriesString.lastIndexOf(PosixHelper.POSIX_DELIMITER) > 0) {
            return groupEntryBuilder.substring(0, groupEntriesString.lastIndexOf(PosixHelper.POSIX_DELIMITER));
        } else {
            return groupEntryBuilder.toString();
        }
    }
}
