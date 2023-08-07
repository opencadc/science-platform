package org.opencadc.skaha;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FileParser {

    public static Stream<String[]> load(String filePath) throws IOException {
        return Files.readAllLines(Paths.get(filePath))
                .stream()
                .filter(line -> line.contains(":"))
                .map(line -> line.split(":"));
    }

    public static Map<String, UserInfo> loadUserInfo(String filePath) throws IOException {
        List<UserInfo> userInfoList = load(filePath)
                .map(FileParser::getUserInfo)
                .collect(Collectors.toList());
        Map<String, UserInfo> userInfoMap = new HashMap<>();
        for (UserInfo userInfo : userInfoList)
            userInfoMap.put(userInfo.getName(), userInfo);
        return userInfoMap;
    }

    private static UserInfo getUserInfo(String[] info) {
        return new UserInfo(info[0], info[2], info[3]);
    }

    public static Map<String, GroupInfo> loadGroupInfo(String filePath) throws IOException {
        List<GroupInfo> groupInfoList = load(filePath)
                .map(FileParser::getGroupInfo)
                .collect(Collectors.toList());
        Map<String, GroupInfo> groupInfoMap = new HashMap<>();
        for (GroupInfo groupInfo : groupInfoList)
            groupInfoMap.put(groupInfo.getName(), groupInfo);
        return groupInfoMap;
    }

    private static GroupInfo getGroupInfo(String[] info) {
        List<String> users = info.length >= 4 && info[3] != null && !"".equals(info[3]) ?
                Arrays.stream(info[3].split(",")).collect(Collectors.toList()) : null;
        return new GroupInfo(info[0], info[2], users);
    }

}

class UserInfo {
    private String name;
    private String uid;
    private String gid;

    public UserInfo(String name, String uid, String gid) {
        this.name = name;
        this.uid = uid;
        this.gid = gid;
    }

    public String getName() {
        return name;
    }

    public String getUid() {
        return uid;
    }

    public String getGid() {
        return gid;
    }
}

class GroupInfo {
    private String name;
    private String gid;
    private List<String> users = new ArrayList<>();

    public GroupInfo(String name, String gid, List<String> users) {
        this.name = name;
        this.gid = gid;
        if (null != users)
            this.users.addAll(users);
    }

    public String getName() {
        return name;
    }

    public String getGid() {
        return gid;
    }

    public List<String> getUsers() {
        return users;
    }
}