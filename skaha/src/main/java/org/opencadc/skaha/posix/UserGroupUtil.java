package org.opencadc.skaha.posix;

import ca.nrc.cadc.auth.AuthenticationUtil;
import ca.nrc.cadc.auth.PosixPrincipal;
import org.apache.log4j.Logger;
import org.opencadc.skaha.posix.client.Etcd;
import org.opencadc.skaha.posix.client.PosixClient;

import javax.security.auth.Subject;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static java.lang.String.format;
import static java.lang.String.valueOf;

public class UserGroupUtil {
    private static final Logger log = Logger.getLogger(Etcd.class);
    private final String userId;
    private final PosixClient posixClient;
    private final String homeDir;
    private final List<String> groups;
    private boolean isLDAPConnected = false;
    Set<PosixPrincipal> principals;

    public UserGroupUtil(String userId,
                         String homeDir, List<String> groups,
                         PosixClient posixClient)
            throws ExecutionException, InterruptedException, IOException, ClassNotFoundException {
        this.userId = userId;
        this.posixClient = posixClient;
        this.homeDir = homeDir;
        this.groups = groups;
        log.info("setup for iam started");
        if (!isLDAPConnected())
            setupForIAM();
        log.info("setup for iam closed");
    }

    private boolean isLDAPConnected() {
        Subject s = AuthenticationUtil.getCurrentSubject();
        principals = s.getPrincipals(PosixPrincipal.class);
        if (!principals.isEmpty())
            isLDAPConnected = true;
        return isLDAPConnected;
    }

    public void setupForIAM() throws ExecutionException, InterruptedException, IOException, ClassNotFoundException {
        log.info("groups are " + groups);
        savePosixInformation(userId, homeDir);
        for (String group : groups) {
            saveGroup(group);
            addUserToGroup(userId, group);
        }
    }

    private void savePosixInformation(String userId, String homeDir) throws ExecutionException, InterruptedException, IOException, ClassNotFoundException {
        if (!posixClient.userExists(userId)) {
            int posixId = posixClient.savePosixId(userId);
            String posixEntry = format("%s:x:%d:%d::%s/%s:/bin/bash", userId, posixId, posixId, homeDir, userId);
            posixClient.savePosixEntry(userId, posixEntry);
            String groupEntry = format("%s:x:%d:%s", userId, posixId, userId);
            posixClient.saveGroupEntry(userId, groupEntry);
            posixClient.addGroupToUser(userId, userId);
        }
    }

    private void saveGroup(String groupName) throws ExecutionException, InterruptedException {
        if (!posixClient.groupExist(groupName)) {
            int groupId = posixClient.saveGroupId(groupName);
            String groupEntry = format("%s:x:%d:", groupName, groupId);
            posixClient.saveGroupEntry(groupName, groupEntry);
        }
    }

    private void addUserToGroup(String userId, String groupName) throws ExecutionException, InterruptedException, IOException, ClassNotFoundException {
        if (!posixClient.userExistsInGroup(userId, groupName)) {
            String groupEntry = posixClient.getGroupEntry(groupName);
            String format = groupEntry.endsWith(":") ? "%s%s" : "%s,%s";
            String updatedGroupEntry = format(format, groupEntry, userId);
            posixClient.saveGroupEntry(groupName, updatedGroupEntry);
            posixClient.addGroupToUser(userId, groupName);
        }
    }

    public String posixId() throws ExecutionException, InterruptedException {
        if (isLDAPConnected)
            return valueOf(principals.iterator().next().getUidNumber());
        return valueOf(posixClient.getPosixId(userId));
    }

    public String posixEntry() throws ExecutionException, InterruptedException {
        if (isLDAPConnected)
            return null;
        return posixClient.getPosixEntry(userId);
    }

    public String groupEntries() throws ExecutionException, InterruptedException, IOException, ClassNotFoundException {
        if (isLDAPConnected)
            return null;
        return posixClient.groupEntries(userId);
    }
}
