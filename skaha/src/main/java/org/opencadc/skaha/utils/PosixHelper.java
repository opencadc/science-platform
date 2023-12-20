package org.opencadc.skaha.utils;

import ca.nrc.cadc.ac.Group;
import ca.nrc.cadc.auth.PosixPrincipal;
import org.opencadc.gms.GroupURI;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.opencadc.skaha.utils.CommandExecutioner.execute;
import static org.opencadc.skaha.utils.CommonUtils.decodeBase64;

public class PosixHelper {
    public static String uidMapping(String secretName, String workloadNamespaceName) throws IOException, InterruptedException {
        String[] cmd = {"kubectl", "get", "secret", secretName, "-n", workloadNamespaceName, "-o", "jsonpath=\"{.data.uidmap\\.txt}\""};
        String result = execute(cmd).replaceAll("^\"|\"$", "");
        return decodeBase64(result);
    }

    public static String[] gidMappings(String secretName, String workloadNamespaceName) throws IOException, InterruptedException {
        String[] cmd = {"kubectl", "get", "secret", secretName, "-n", workloadNamespaceName, "-o", "jsonpath=\"{.data.gidmap\\.txt}\""};
        String result = execute(cmd).replaceAll("^\"|\"$", "");
        String decodedString = decodeBase64(result);
        return decodedString.split("\\r?\\n");
    }

    public static String supplementalGroups(String[] gidMappings) {
        List<String> supplementalGroups = new ArrayList<>(gidMappings.length);
        for (String gidMapping : gidMappings)
            supplementalGroups.add(gidMapping.split(":")[2]);
        return String.join(",", supplementalGroups);
    }

    public static PosixPrincipal buildPosixPrincipal(String uidMapping) {
        String[] uidMappingParts = uidMapping.split(":");
        String userName = uidMappingParts[0];
        int uid = Integer.parseInt(uidMappingParts[2]);
        PosixPrincipal posixPrincipal = new PosixPrincipal(uid);
        posixPrincipal.username = userName;
        return posixPrincipal;
    }

    public static List<Group> buildGroup(String[] gidMappings, URI groupParentUri) {
        List<Group> supplementalGroups = new ArrayList<>(gidMappings.length);
        for (String gidMapping : gidMappings) {
            String groupName = gidMapping.split(":")[0];
            supplementalGroups.add(new Group(new GroupURI(URI.create(groupParentUri.toString() + "?" + groupName))));
        }
        return supplementalGroups;
    }

    public static String getPosixMapperSecretName(String sessionId) {
        return "posix-mapping-" + sessionId;
    }
}
