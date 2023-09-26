package org.opencadc.skaha.posix;

import org.apache.log4j.Logger;
import org.opencadc.skaha.utils.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.lang.String.valueOf;

public class PostgresPosixUtil implements PosixUtil {
    private static final Logger log = Logger.getLogger(PostgresPosixUtil.class);
    private static final String SEPARATOR = ";";

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
    public List<String> posixEntries() throws Exception {
//        return posixEntries(posixClient.getAllUser(), homeDir);
        return posixEntries(List.of(user), homeDir);
    }

    public List<String> posixEntries(List<User> users, String homeDir) throws Exception {
        return users.stream()
                .map(oneUser -> posixEntry(oneUser, homeDir))
                .collect(Collectors.toList());
    }


    private String posixEntry(User user, String homeDir) {
        return format("%s:x:%d:%d::%s/%s:/bin/bash", user.getUsername(), user.getUid(), user.getUid(), homeDir, user.getUsername());
    }

    @Override
    public String posixEntriesAsString() throws Exception {
        return listToString(posixEntries(), SEPARATOR);
    }

    @Override
    public List<String> groupEntries() throws Exception {
        List<Group> groups = user.getGroups();
        List<String> groupEntries = new ArrayList<>();
        groupEntries.add(userPrivateGroupEntry(user));
        for (Group group : groups)
            groupEntries.add(supplementalGroupsEntry(group, List.of(user)));
//            groupEntries.add(supplementalGroupsEntry(group, posixClient.getUsersForGroup(group.getGid())));
        return groupEntries;
    }

    private String userPrivateGroupEntry(User user) {
        return user.getUsername() + ":x:" + user.getUid() + ":" + user.getUsername();
    }

    private String supplementalGroupsEntry(Group group, List<User> users) {
        return group.getGroupname() + ":x" + ":" + group.getGid() + ":" + concatenateUserNames(users);
    }

    private static String concatenateUserNames(List<User> users) {
        return users
                .stream()
                .map(User::getUsername)
                .reduce((i, j) -> i + "," + j)
                .orElse("");
    }

    @Override
    public String groupEntriesAsString() throws Exception {
        return listToString(groupEntries(), SEPARATOR);
    }

    public String listToString(List<String> lines, String separator) {
        if (CollectionUtils.isNotEmpty(lines))
            return lines.stream()
                    .reduce((i, j) -> i + separator + j)
                    .orElse("");
        return "";
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
