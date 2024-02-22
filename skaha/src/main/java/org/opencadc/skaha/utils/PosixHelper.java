package org.opencadc.skaha.utils;

import ca.nrc.cadc.auth.PosixPrincipal;

public class PosixHelper {
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

    /**
     * The name of the secret that contains the complete mapping of UIDs and GIDs.  This is mounted into the launched
     * containers, and the init-users-groups.sh script uses it to populate the system files (/etc/passwd and
     * /etc/group).
     *
     * @param sessionId The session identifier to mark the secret.
     * @return String secret name, never null.
     */
    public static String getPosixMapperSecretName(String sessionId) {
        return "posix-mapping-" + sessionId;
    }
}
