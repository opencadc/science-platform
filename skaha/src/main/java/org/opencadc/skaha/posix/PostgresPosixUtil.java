package org.opencadc.skaha.posix;

import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

import static java.lang.String.format;
import static java.lang.String.valueOf;

public class PostgresPosixUtil implements PosixUtil {
    private static final Logger log = Logger.getLogger(PostgresPosixUtil.class);
    private static final String SEPARATE = ";";

    private String userName;
    private String homeDir;
    private List<String> groupNames;
    private PosixClient posixClient;
    private User user;

    @Override
    public PosixUtil userName(String userName) {
        this.userName = userName;
        return this;
    }

    @Override
    public PosixUtil homeDir(String homeDir) {
        this.homeDir = homeDir;
        return this;
    }

    @Override
    public PosixUtil groupNames(List<String> groupNames) {
        this.groupNames = null != groupNames ? groupNames : new ArrayList<>();
        return this;
    }

    @Override
    public PosixUtil useClient(PosixClient posixClient) {
        this.posixClient = posixClient;
        return this;
    }

    @Override
    public void load() throws Exception {
        if (posixClient.userExists(userName)) {
            user = posixClient.getUser(userName);
        } else {
            user = new User(userName);
            posixClient.saveUser(user);
        }
        List<Group> groups = new ArrayList<>();
        for (String groupName : groupNames) {
            Group group;
            if (posixClient.groupExist(groupName)) {
                group = posixClient.getGroup(groupName);
            } else {
                group = new Group(groupName);
                posixClient.saveGroup(group);
            }
            groups.add(group);
        }
        user.setGroups(groups);
        posixClient.updateUser(user);
    }

    @Override
    public String posixId() throws Exception {
        return valueOf(user.getUid());
    }

    @Override
    public String posixEntry() throws Exception {
        String posixId = posixId();
        return format("%s:x:%s:%s::%s/%s:/bin/bash", this.userName, posixId, posixId, homeDir, this.userName);
    }

    @Override
    public String groupEntries() throws Exception {
        List<Group> groups = user.getGroups();
        List<String> groupEntries = new ArrayList<>();
        String userPrivateGroupEntry = user.getUsername() + ":x:" + user.getUid() + ":" + user.getUsername();
        groupEntries.add(userPrivateGroupEntry);
        for (Group group : groups) {
            List<User> userPerGroup = posixClient.getUsersForGroup(group.getGid());
            String concatenatedUserName = userPerGroup
                    .stream()
                    .map(User::getUsername)
                    .reduce((i, j) -> i + "," + j)
                    .orElse("");
            String entry = group.getGroupname() + ":x" + ":" + group.getGid() + ":" + concatenatedUserName;
            groupEntries.add(entry);
        }
        return groupEntries.stream().reduce((i, j) -> i + SEPARATE + j).orElse("");
    }

    @Override
    public String userGroupIds() throws Exception {
        return user.getGroups()
                .stream()
                .map(Group::getGid)
                .map(Object::toString)
                .reduce((i, j) -> i + "," + j)
                .orElse("");
    }

}
