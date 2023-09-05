package org.opencadc.skaha.posix.client;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static java.lang.Integer.parseInt;
import static java.lang.String.valueOf;

public class EtcdPosixClient implements PosixClient {

    private static final String SEPARATOR = ";";
    private final Etcd etcd;
    public static final int USER_POSIX_ID_START = 100000; // 1 Lakh
    public static final int GROUP_ID_START = 1000000; // 10 Lakh
    private static final String USER_KEY = "user_";
    private static final String GROUP_KEY = "group_";
    private static final String META_KEY = "meta_";
    private static final String ENTRY_KEY = "entry_";
    private static final String LIST_KEY = "list_";

    public EtcdPosixClient(Etcd etcd) {
        this.etcd = etcd;
    }

    private static String posixIdKey(String userId) {
        return USER_KEY + userId;
    }

    private static String posixIdMetaKey() {
        return USER_KEY + META_KEY;
    }

    private static String posixEntryKey(String userId) {
        return USER_KEY + ENTRY_KEY + userId;
    }

    private static String groupIdKey(String groupMame) {
        return GROUP_KEY + groupMame;
    }

    private static String groupIdMetaKey() {
        return groupIdKey(META_KEY);
    }

    private static String groupEntryKey(String groupName) {
        return GROUP_KEY + ENTRY_KEY + groupName;
    }

    private static String groupListKey(String userId) {
        return GROUP_KEY + LIST_KEY + userId;
    }

    private static final Logger log = Logger.getLogger(EtcdPosixClient.class);

    @Override
    public boolean userExists(String userId) throws ExecutionException, InterruptedException {
        if (null == userId)
            throw new RuntimeException("user name is null");
        return etcd.exists(posixIdKey(userId));
    }


    @Override
    public int getPosixId(String userId) throws ExecutionException, InterruptedException {
        Optional<String> optionalPosixId = etcd.get(posixIdKey(userId));
        if (optionalPosixId.isEmpty()) {
            throw new RuntimeException("unknown user");
        }
        return parseInt(optionalPosixId.get());
    }

    @Override
    public int savePosixId(String userId) throws ExecutionException, InterruptedException {
        Optional<String> lastUsedPosixId = etcd.get(posixIdMetaKey());
        int newPosixId = lastUsedPosixId.map(lastId -> parseInt(lastId) + 1).orElse(USER_POSIX_ID_START);
        etcd.put(posixIdKey(userId), valueOf(newPosixId));
        etcd.put(posixIdMetaKey(), valueOf(newPosixId));
        etcd.put(groupIdKey(userId), valueOf(newPosixId));
        return newPosixId;
    }

    @Override
    public String getPosixEntry(String userId) throws ExecutionException, InterruptedException {
        Optional<String> optionalUserPosixEntry = etcd.get(posixEntryKey(userId));
        if (optionalUserPosixEntry.isEmpty()) throw new RuntimeException("unknown user");
        return optionalUserPosixEntry.get();
    }


    @Override
    public void savePosixEntry(String userId, String posixEntry) throws ExecutionException, InterruptedException {
        log.debug("saving posix entry with key " + posixEntryKey(userId) + " value " + posixEntry);
        etcd.put(posixEntryKey(userId), posixEntry);
    }

    @Override
    public List<String> getGroupForUser(String userId) throws IOException, ExecutionException, InterruptedException, ClassNotFoundException {
        String groupListKey = groupListKey(userId);
        Optional<List<String>> optionalGroupList = etcd.getAsList(groupListKey);
        if (optionalGroupList.isEmpty()) throw new RuntimeException("no groups for user");
        return optionalGroupList.get();
    }
    @Override
    public void addGroupToUser(String userId, String groupId) throws IOException, ExecutionException, InterruptedException, ClassNotFoundException {
        String groupListKey = groupListKey(userId);
        Optional<List<String>> optionalGroupList = etcd.getAsList(groupListKey);
        List<String> updatedGroupList;
        updatedGroupList = optionalGroupList.orElseGet(ArrayList::new);
        updatedGroupList.add(groupId);
        etcd.put(groupListKey, updatedGroupList);
    }


    @Override
    public boolean userExistsInGroup(String userId, String groupMame) throws ExecutionException, InterruptedException, IOException, ClassNotFoundException {
        String groupListKey = groupListKey(userId);
        Optional<List<String>> optionalGroupList = etcd.getAsList(groupListKey);
        if (optionalGroupList.isEmpty()) throw new RuntimeException("no groups for user");
        return optionalGroupList.get().contains(groupMame);
    }


    @Override
    public boolean groupExist(String groupMame) throws ExecutionException, InterruptedException {
        if (null == groupMame) throw new RuntimeException("group name is null");
        return etcd.exists(groupIdKey(groupMame));
    }

    @Override
    public int getGroupId(String groupMame) throws ExecutionException, InterruptedException {
        Optional<String> optionalGroupId = etcd.get(groupIdKey(groupMame));
        if (optionalGroupId.isEmpty()) throw new RuntimeException("unknown group "+groupMame);
        return parseInt(optionalGroupId.get());
    }

    @Override
    public int saveGroupId(String groupMame) throws ExecutionException, InterruptedException {
        Optional<String> lastUsedGroupId = etcd.get(groupIdMetaKey());
        int newGroupId = lastUsedGroupId.map(lastId -> parseInt(lastId) + 1).orElse(GROUP_ID_START);
        etcd.put(groupIdKey(groupMame), valueOf(newGroupId));
        etcd.put(groupIdMetaKey(), valueOf(newGroupId));
        return newGroupId;
    }

    @Override
    public String getGroupEntry(String groupMame) throws ExecutionException, InterruptedException {
        Optional<String> optionalGroupEntry = etcd.get(groupEntryKey(groupMame));
        if (optionalGroupEntry.isEmpty()) throw new RuntimeException("unknown group");
        return optionalGroupEntry.get();
    }


    @Override
    public void saveGroupEntry(String groupMame, String groupEntry) throws ExecutionException, InterruptedException {
        log.debug("saving group entry with key " + groupEntryKey(groupMame) + " value " + groupEntry);
        etcd.put(groupEntryKey(groupMame), groupEntry);
    }

    @Override
    public String groupEntries(String userId) throws IOException, ExecutionException, InterruptedException, ClassNotFoundException {
        List<String> groups = getGroupForUser(userId);
        StringBuilder groupEntries = new StringBuilder();
        for (int i = 0; i < groups.size(); i++) {
            try {
                groupEntries.append(getGroupEntry(groups.get(i)));
                if (i != groups.size() - 1)
                    groupEntries.append(SEPARATOR);
            } catch (Exception e) {
                log.error("error is ", e);
            }
        }
        return groupEntries.toString();
    }

    @Override
    public List<Integer> userGroupIds(String userId) throws IOException, ExecutionException, InterruptedException, ClassNotFoundException {
        List<String> groups = getGroupForUser(userId);
        List<Integer> userGroupIds = new ArrayList<>();
        for (String group : groups)
            userGroupIds.add(getGroupId(group));
        return userGroupIds;
    }

}
