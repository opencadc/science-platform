package org.opencadc.skaha.posix;

import org.opencadc.skaha.posix.client.PosixClient;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static java.lang.String.format;

public class UserGroupUtil {
    private final String userId;
    private final PosixClient posixClient;
    private final String primaryGroup;
    private final String homeDir;
    private final List<String> groups;


    public UserGroupUtil(String userId, String primaryGroup,
                         String homeDir, List<String> groups,
                         PosixClient posixClient)
            throws ExecutionException, InterruptedException, IOException, ClassNotFoundException {
        this.userId = userId;
        this.primaryGroup = primaryGroup;
        this.posixClient = posixClient;
        this.homeDir = homeDir;
        this.groups = groups;
        setup();
    }

    public void setup() throws ExecutionException, InterruptedException, IOException, ClassNotFoundException {
        savePosixInformation(userId, homeDir);
        saveGroup(primaryGroup);
        for (String group : groups) addUserToGroup(userId, group);
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
            posixClient.addGroupToUser(userId, userId);
        }
    }

    public int posixId() throws ExecutionException, InterruptedException {
        return posixClient.getPosixId(userId);
    }

    public String posixEntry() throws ExecutionException, InterruptedException {
        return posixClient.getPosixEntry(userId);
    }

    public String groupEntries() throws ExecutionException, InterruptedException, IOException, ClassNotFoundException {
        List<String> groups = posixClient.getGroupForUser(userId);
        List<String> groupEntries = groups.stream().map(group -> {
                    try {
                        return posixClient.getGroupEntry(primaryGroup);
                    } catch (Exception e) {
                        return null;
                    }
                }).filter(Objects::nonNull)
                .collect(Collectors.toList());
        return String.join("\n", groupEntries);
    }
}
