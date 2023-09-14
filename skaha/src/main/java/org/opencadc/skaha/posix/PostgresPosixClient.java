package org.opencadc.skaha.posix;


import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class PostgresPosixClient implements PosixClient {

    private final Postgress postgress;

    public PostgresPosixClient(Postgress postgress) {
        this.postgress = postgress;
    }

    @Override
    public User getUser(String userId) throws Exception {
        Map<String, Object> criteria = new HashMap<>();
        criteria.put("username", userId);
        return postgress.find(User.class, "findUserByUsername", criteria);
    }

    @Override
    public User saveUser(User user) throws Exception {
        return postgress.save(user);
    }

    @Override
    public User updateUser(User user) throws Exception {
        return postgress.update(user);
    }

    @Override
    public Group getGroup(String groupMame) throws Exception {
        Map<String, Object> criteria = new HashMap<>();
        criteria.put("groupname", groupMame);
        return postgress.find(Group.class, "findGroupByName", criteria);
    }

    @Override
    public Group saveGroup(Group group) throws Exception {
        return postgress.save(group);
    }

    @Override
    public boolean groupExist(String groupMame) throws Exception {
        return getGroup(groupMame) != null;
    }

    @Override
    public List<User> getUsersForGroup(int gid) throws Exception {
        Map<String, Object> criteria = new HashMap<>();
        criteria.put("gid", gid);
        return postgress.findAll(User.class, "findAllUsersForGroupId", criteria);
    }
}
