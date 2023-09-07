package org.opencadc.skaha.posix.client.postgresql;
import org.apache.log4j.Logger;
import org.opencadc.skaha.posix.client.PosixClient;
import org.opencadc.skaha.posix.client.postgresql.enitities.CommonGroup;
import org.opencadc.skaha.posix.client.postgresql.enitities.Groups;
import org.opencadc.skaha.posix.client.postgresql.enitities.Users;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static java.lang.String.format;
public class PostgresPosixClient implements PosixClient {
    static Postgress postgress = new Postgress(Users.class, Groups.class, CommonGroup.class);
    private static final Logger log = Logger.getLogger(PostgresPosixClient.class);


    @Override
    public boolean userExists(String userId) {
        try {
            Users user = postgress.getUsers(userId);
            if(user != null) {
                log.info("User Exist with Posixid=" + user.getPosixid() + " for user " + userId);
                return true;
            } else {
                log.info("User does not exist");
                return false;
            }
        } catch(Exception e) {
            // Handle exceptions gracefully, log them, and return false if something goes wrong.
            log.error("Error while checking user existence: " + e.getMessage());
            return true;
        }
    }

    @Override
    public int savePosixId(String userId) throws ExecutionException, InterruptedException {
        try {
            if(!userExists(userId)) {
                Users newUser = new Users(); // Create a new Users object
                newUser.setUserid(userId);
                postgress.saveUser(newUser);
                int posixid = getPosixId(userId);

                //Creating Private group  for each new user
                CommonGroup genricCommonGroup = new CommonGroup(posixid, userId);
                postgress.saveCommonGroup(genricCommonGroup);
                List< CommonGroup > commonGroupList = new ArrayList<>();
                commonGroupList.add(genricCommonGroup);
                newUser = postgress.getUsers(userId);
                newUser.setCommonGroups(commonGroupList);//Adding the User into the Private Group
                postgress.saveUser(newUser);
                log.info("Saving Posixid=" + posixid + " for user " + userId);
                return posixid;
            } else {
                log.info("User " + userId + " Already Exist with PosixId " + getPosixId(userId));
                return getPosixId(userId);
            }
        } catch(Exception e) {
            log.error("User " + userId + " Already Exist with PosixId " + getPosixId(userId));
            return getPosixId(userId);
        }
    }

    @Override
    public boolean groupExist(String groupMame) {
        try {
            Groups groups = postgress.getGroups(groupMame);
            if(groups != null) {
                log.info("Group Exist with GroupId=" + groups.getGroupId() + " for user " + groupMame);
                return true;
            } else {
                log.info("Group does not exist");
                return false;
            }
        } catch(Exception e) {
            log.error("Error while checking Group existence: " + e.getMessage());
            return false;
        }
    }

    @Override
    public int getGroupId(String groupMame) {
        try {
            Groups groups = postgress.getGroups(groupMame);
            if(groups != null) {
                log.info("Group Exist with GroupId=" + groups.getGroupId() + " for user " + groupMame);
                return groups.getGroupId();
            } else {
                log.info("Group does not exist");
                return -1;
            }
        } catch(Exception e) {
            log.error("Error while checking Group existence: " + e.getMessage());
            return -1;
        }
    }

    @Override
    public int saveGroupId(String groupMame) throws ExecutionException, InterruptedException {
        try {
            if(!groupExist(groupMame)) {
                Groups newGroup = new Groups(); // Create a new Users object
                newGroup.setGroupName(groupMame);
                postgress.saveGroups(newGroup);
                int groupId = getGroupId(groupMame);
                log.info("Saving GroupId=" + groupId + " for group " + groupMame);
                return groupId;
            } else {
                log.info("Group " + groupMame + " Already Exist with GroupId " + getGroupId(groupMame));
                return getGroupId(groupMame);
            }
        } catch(Exception e) {
            log.info("Group " + groupMame + " Already Exist with GroupId " + getGroupId(groupMame));
            return getGroupId(groupMame);
        }
    }

    @Override
    public void saveGroupEntry(String groupMame, String groupEntry) throws ExecutionException, InterruptedException {
    }

    @Override
    public int getPosixId(String userId) throws ExecutionException, InterruptedException {
        if(userExists(userId)) {
            Users savedUser = postgress.getUsers(userId);
            int posixid = savedUser.getPosixid();
            log.info("Saving Posixid=" + posixid + " for user " + userId);
            return posixid;
        } else {
            log.error("Failed to retrieve the saved user with userId=" + userId);
            return -1;
        }
    }

    @Override
    public boolean userExistsInGroup(String userId, String groupMame) throws ExecutionException, InterruptedException, IOException, ClassNotFoundException {
        try {
            if(userExists(userId) && groupExist(groupMame)) {
                Users savedUser = postgress.getUsers(userId);
                Groups savedGroup = postgress.getGroups(groupMame);
                return savedUser.getGroupDetailList().contains(savedGroup);
            } else {
                log.error("Failed to retrieve the saved user and saved Group with userId=" + userId + "and Groupname=" + groupMame);
                return false;
            }
        } catch(NullPointerException e) {
            log.error("User is not present in any group userid= " + userId);
            return false;
        }
    }

    @Override
    public String getGroupEntry(String groupMame) throws ExecutionException, InterruptedException {
        List< Users > users = postgress.getUserFromGroup(getGroupId(groupMame));
        String userEntry = users.stream().map(Users::getUserid).reduce("", (prev, curr) -> prev + curr + ",");
        userEntry = userEntry.substring(0, userEntry.length() - 1);
        return format("%s:x:%d:%s", groupMame, getGroupId(groupMame), userEntry);
    }

    @Override
    public void savePosixEntry(String userId, String posixEntry) throws ExecutionException, InterruptedException {
    }

    @Override
    public String getPosixEntry(String userId) throws ExecutionException, InterruptedException {
        return null;
    }

    @Override
    public List< String > getGroupForUser(String userId) throws IOException, ExecutionException, InterruptedException, ClassNotFoundException {
        if(userExists(userId)) {
            Users user = postgress.getUsers(userId);
            return user.getGroupDetailList().stream().map(Groups::getGroupName).collect(Collectors.toList());
        } else {
            log.error("Failed to retrieve the saved user Groups=" + userId);
            return new ArrayList< String >();
        }
    }

    @Override
    public void addGroupToUser(String userId, String groupMame) throws IOException, ExecutionException, InterruptedException, ClassNotFoundException {
        if(userExists(userId) && groupExist(groupMame) && !userExistsInGroup(userId, groupMame)) {
            Users savedUser = postgress.getUsers(userId);
            Groups savedGroup = postgress.getGroups(groupMame);
            List< Groups > groups = new ArrayList<>();
            groups.add(savedGroup);
            savedUser.setGroupDetailList(groups);
            postgress.saveUser(savedUser);
        } else {
            log.error("Failed to add the user inside the saved Group with userId=" + userId + "and Groupname=" + groupMame);
        }
    }

    @Override
    public void addGroupsToUser(String userId, List< String > groupMames) throws IOException, ExecutionException, InterruptedException, ClassNotFoundException {
        if(userExists(userId)) {
            Users savedUser = postgress.getUsers(userId);
            List< Groups > groups = new ArrayList<>();
            groupMames.forEach(groupMame -> groups.add(postgress.getGroups(groupMame)));
            savedUser.setGroupDetailList(groups);
            postgress.saveUser(savedUser);
        } else {
            log.error("Failed to add the user inside the saved Group with userId=" + userId + "and Groupname=" + groupMames);
        }
    }

    public void removeUsersGroup(String userid, String groupName) throws IOException, ExecutionException, InterruptedException, ClassNotFoundException {
        if(userExists(userid) && groupExist(groupName) && userExistsInGroup(userid, groupName)) {
            Users savedUser = postgress.getUsers(userid);
            Groups savedGroup = postgress.getGroups(groupName);
            savedUser.getGroupDetailList().remove(savedGroup);
            postgress.saveUser(savedUser);
        } else if(userExists(userid) && groupExist(groupName) && !userExistsInGroup(userid, groupName)) {
            log.error("userId= " + userid + " dont exist in the Group= " + groupName);
        } else {
            log.error("Either userId=" + userid + " or Groupname= " + groupName + " dont exist ");
        }
    }

    @Override
    public String groupEntries(String userId) throws IOException, ExecutionException, InterruptedException, ClassNotFoundException {
        List< String > groups = getGroupForUser(userId);
        StringBuilder groupEntries = new StringBuilder();
        groups.forEach(group -> {
            try {
                groupEntries.append(getGroupEntry(group));
                groupEntries.append(";");
            } catch(Exception e) {
                log.error("error is ", e);
            }
        });
        return groupEntries.toString();
    }

    @Override
    public List< Integer > userGroupIds(String userId) throws IOException, ExecutionException, InterruptedException, ClassNotFoundException {
        List< Integer > groupidList = new ArrayList<>();
        try {
            if(userExists(userId)) {
                Users savedUser = postgress.getUsers(userId);
                savedUser.getGroupDetailList().stream().forEach(group -> groupidList.add(group.getGroupId()));
                return groupidList;
            } else {
                log.error("User dont exits=" + userId);
                return groupidList;
            }
        } catch(NullPointerException e) {
            log.error("User is not present in any group " + e.getMessage());
            return groupidList;
        }
    }

    public static void main(String[] args) throws ExecutionException, InterruptedException, IOException, ClassNotFoundException {
        PostgresPosixClient postgresPosixClient = new PostgresPosixClient();
        postgresPosixClient.savePosixId("user1");
        postgresPosixClient.savePosixId("user2");

/*        postgresPosixClient.saveGroupId("Admin");
        postgresPosixClient.saveGroupId("root");

        postgresPosixClient.addGroupToUser("user1","Admin");
        postgresPosixClient.addGroupToUser("user2","root");
        */
        System.out.println(postgresPosixClient.userGroupIds("user1"));
        postgress.closeSession();
    }
}
