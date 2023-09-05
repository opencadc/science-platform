package org.opencadc.skaha.posix.client;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;

public interface PosixClient {
    boolean userExists(String userId) throws ExecutionException, InterruptedException;

    int savePosixId(String userId) throws ExecutionException, InterruptedException;

    boolean groupExist(String groupMame) throws ExecutionException, InterruptedException;

    int getGroupId(String groupMame) throws ExecutionException, InterruptedException;

    int saveGroupId(String groupMame) throws ExecutionException, InterruptedException;

    void saveGroupEntry(String groupMame, String groupEntry) throws ExecutionException, InterruptedException;

    int getPosixId(String userId) throws ExecutionException, InterruptedException;

    boolean userExistsInGroup(String userId,String groupMame) throws ExecutionException, InterruptedException, IOException, ClassNotFoundException;

    String getGroupEntry(String groupMame) throws ExecutionException, InterruptedException;

    void savePosixEntry(String userId, String posixEntry) throws ExecutionException, InterruptedException;

    String getPosixEntry(String userId) throws ExecutionException, InterruptedException;

    List<String> getGroupForUser(String userId) throws IOException, ExecutionException, InterruptedException, ClassNotFoundException;

    void addGroupToUser(String userId, String groupId) throws IOException, ExecutionException, InterruptedException, ClassNotFoundException;

    String groupEntries(String userId) throws IOException, ExecutionException, InterruptedException, ClassNotFoundException;

    List<Integer> userGroupIds(String userId) throws IOException, ExecutionException, InterruptedException, ClassNotFoundException;
}
