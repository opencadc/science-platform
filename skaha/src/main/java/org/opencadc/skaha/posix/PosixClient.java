package org.opencadc.skaha.posix;

import java.util.List;

public interface PosixClient {
    default boolean userExists(String userId) throws Exception {
        return getUser(userId) != null;
    }

    User getUser(String userId) throws Exception;

    User saveUser(User user) throws Exception;

    User updateUser(User user) throws Exception;

    Group getGroup(String groupMame) throws Exception;

    Group saveGroup(Group group) throws Exception;

    boolean groupExist(String groupMame) throws Exception;

    List<User> getUsersForGroup(int gid) throws Exception;
}
