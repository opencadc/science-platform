package org.opencadc.skaha.utils;

import ca.nrc.cadc.auth.PosixPrincipal;
import ca.nrc.cadc.io.ResourceIterator;
import java.io.FileWriter;
import java.io.Writer;
import org.apache.log4j.Logger;
import org.opencadc.auth.PosixGroup;
import org.opencadc.auth.PosixMapperClient;


public class PosixHelper {
    private static final Logger LOGGER = Logger.getLogger(PosixHelper.class);

    public static void writePOSIXEntries(final String userHomeDir, final PosixPrincipal posixPrincipal,
                                         final PosixMapperClient posixMapperClient) throws Exception {
        final int posixID = posixPrincipal.getUidNumber();

        // Create the ${HOME}/.config/skaha folder, or ignore if exists already.
        final String userConfigurationFolder = CommandExecutioner.createDirectoryIfNotExist(userHomeDir, ".config");
        CommandExecutioner.changeOwnership(userConfigurationFolder, posixID);

        final String skahaUserConfigurationFolder = CommandExecutioner.createDirectoryIfNotExist(userConfigurationFolder, "skaha");
        CommandExecutioner.changeOwnership(skahaUserConfigurationFolder, posixID);

        final String skahaUserConfigurationPOSIXUsers = CommandExecutioner.createOrOverrideFile(skahaUserConfigurationFolder, "passwd");
        final String skahaGroupConfigurationPOSIXUsers = CommandExecutioner.createOrOverrideFile(skahaUserConfigurationFolder, "group");

        try (final FileWriter fileWriter = new FileWriter(skahaUserConfigurationPOSIXUsers)) {
            PosixHelper.writeUserEntries(posixMapperClient, fileWriter);
            fileWriter.flush();
        }

        try (final FileWriter fileWriter = new FileWriter(skahaGroupConfigurationPOSIXUsers)) {
            PosixHelper.writeGroupEntries(posixMapperClient, fileWriter);
            fileWriter.flush();
        }

        CommandExecutioner.changeOwnership(skahaUserConfigurationPOSIXUsers, posixID);
        CommandExecutioner.changeOwnership(skahaGroupConfigurationPOSIXUsers, posixID);
    }

    /**
     * Obtain the POSIX entry for the provided POSIX Principal in POSIX form.
     * Example output:
     * "username1:x:1000:1000"
     *
     * @param posixPrincipal The POSIX Principal to transform.
     * @return String POSIX entry, never null;
     */
    private static String uidMapping(final PosixPrincipal posixPrincipal) {
        return String.format("%s:x:%d:%d:::\n", posixPrincipal.username,
                             posixPrincipal.getUidNumber(),
                             posixPrincipal.getUidNumber());
    }

    private static String gidMapping(final PosixGroup posixGroup) {
        return String.format("%s:x:%d:\n",
                             posixGroup.getGroupURI().getURI().getQuery(),
                             posixGroup.getGID());
    }

    private static void writeUserEntries(final PosixMapperClient posixMapperClient, final Writer writer) throws Exception {
        try (final ResourceIterator<PosixPrincipal> posixPrincipalIterator = posixMapperClient.getUserMap()) {
            while (posixPrincipalIterator.hasNext()) {
                final PosixPrincipal posixPrincipal = posixPrincipalIterator.next();
                writer.write(PosixHelper.uidMapping(posixPrincipal));
            }
        }
    }

    private static void writeGroupEntries(final PosixMapperClient posixMapperClient, final Writer writer) throws Exception {
        try (final ResourceIterator<PosixGroup> posixGroupIterator = posixMapperClient.getGroupMap()) {
            while (posixGroupIterator.hasNext()) {
                final PosixGroup posixGroup = posixGroupIterator.next();
                writer.write(PosixHelper.gidMapping(posixGroup));
            }
        }
    }
}
