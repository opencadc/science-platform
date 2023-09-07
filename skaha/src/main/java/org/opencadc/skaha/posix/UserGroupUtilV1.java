package org.opencadc.skaha.posix;
import ca.nrc.cadc.auth.AuthenticationUtil;
import ca.nrc.cadc.auth.PosixPrincipal;
import org.apache.log4j.Logger;
import org.opencadc.skaha.posix.client.Etcd;
import org.opencadc.skaha.posix.client.PosixClient;
import org.opencadc.skaha.posix.client.postgresql.PostgresPosixClient;

import javax.security.auth.Subject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static java.lang.String.format;
import static java.lang.String.valueOf;
public class UserGroupUtilV1 {
    private static final Logger log = Logger.getLogger(Etcd.class);
    private final String userId;
    private final PosixClient posixClient;
    private final String homeDir;
    private final List< String > groups;
    private boolean isLDAPConnected = false;
    PosixPrincipal principal;

    public UserGroupUtilV1(String userId, String homeDir, List< String > groups, PosixClient posixClient) throws ExecutionException, InterruptedException, IOException, ClassNotFoundException {
        this.userId = userId;
        this.posixClient = posixClient;
        this.homeDir = homeDir;
        this.groups = groups;
        log.info("setup for iam started");
        setupForIAM();
        log.info("setup for iam closed");
    }

//    private boolean isLDAPConnected() {
//        Subject s = AuthenticationUtil.getCurrentSubject();
//        Set< PosixPrincipal > principals = s.getPrincipals(PosixPrincipal.class);
//        if(principals.isEmpty()) return false;
//        principal = principals.iterator().next();
//        isLDAPConnected = true;
//        return true;
//    }

    public void setupForIAM() throws ExecutionException, InterruptedException, IOException, ClassNotFoundException {
        log.info("groups are " + groups);
        savePosixInformation(userId, homeDir);
        groups.forEach(group -> saveGroup(group));
        posixClient.addGroupsToUser(userId, groups);
    }

    private void savePosixInformation(String userId, String homeDir) throws ExecutionException, InterruptedException, IOException, ClassNotFoundException {
        if(!posixClient.userExists(userId)) {
            int posixId = posixClient.savePosixId(userId);
        }
    }

    private void saveGroup(String groupName) {
        try {
            if(!posixClient.groupExist(groupName)) {
                int groupId = posixClient.saveGroupId(groupName);
            }
        } catch(Exception e) {
            log.error("Groups Not able to save or already exist " + e.getMessage());
        }
    }

    private void addUserToGroup(String userId, String groupName) throws ExecutionException, InterruptedException, IOException, ClassNotFoundException {
        if(!posixClient.userExistsInGroup(userId, groupName)) {
            posixClient.addGroupToUser(userId, groupName);
        }
    }

    public String posixId() throws ExecutionException, InterruptedException {
        if(isLDAPConnected) return valueOf(principal.getUidNumber());
        return valueOf(posixClient.getPosixId(userId));
    }

    public String posixEntry() throws ExecutionException, InterruptedException {
        if(isLDAPConnected) {
            return null;
        }
        int posixId = Integer.parseInt(posixId());
        return format("%s:x:%d:%d::%s/%s:/bin/bash", userId, posixId, posixId, homeDir, userId);
    }

    public String groupEntries() throws ExecutionException, InterruptedException, IOException, ClassNotFoundException {
        if(isLDAPConnected) return null;
        return posixClient.groupEntries(userId);

    }

    public String userGroupIds() throws ExecutionException, InterruptedException, IOException, ClassNotFoundException {
        if(isLDAPConnected) return null;
        return posixClient.userGroupIds(userId).stream().map(Object::toString).reduce((i, j) -> i + ", " + j).orElse("");
    }

    public static void main(String args[]) throws IOException, ExecutionException, InterruptedException, ClassNotFoundException {
        List< String > groups = new ArrayList<>();
        groups.add("admin");
        groups.add("teacher");
        groups.add("cool");
        PosixClient posixClient1 = new PostgresPosixClient();
        UserGroupUtilV1 userGroupUtil = new UserGroupUtilV1("Abhishek", "/root/usr", groups, posixClient1);
        System.out.println(userGroupUtil.posixEntry());
        System.out.println(userGroupUtil.userGroupIds());
        System.out.println(userGroupUtil.groupEntries());
    }
}
