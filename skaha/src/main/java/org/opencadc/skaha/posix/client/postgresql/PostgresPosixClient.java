package org.opencadc.skaha.posix.client.postgresql;
import org.apache.log4j.Logger;
import org.opencadc.skaha.posix.client.PosixClient;
import org.opencadc.skaha.posix.client.postgresql.enitities.Groups;
import org.opencadc.skaha.posix.client.postgresql.enitities.Users;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
public class PostgresPosixClient implements PosixClient {
    static Postgress postgress = new Postgress();
    private static final Logger log = Logger.getLogger(PostgresPosixClient.class);

    @Override
    public boolean userExists ( String userId ) {

        try {
            Users user = postgress.getUsers(userId);
            if (user != null) {
                log.info("User Exist with Posixid=" + user.getPosixid() + " for user " + userId);
                return true;
            } else {
                log.info("User does not exist");
                return false;
            }
        } catch (Exception e) {
            // Handle exceptions gracefully, log them, and return false if something goes wrong.
            log.error("Error while checking user existence: " + e.getMessage());
            return true;
        }
    }

    @Override
    public int savePosixId ( String userId ) throws ExecutionException, InterruptedException {

        try {
            if (!userExists(userId) && !groupExist(userId)) {
                Users newUser = new Users(); // Create a new Users object
                newUser.setUserid(userId);
                newUser.setUserActive(true);
                postgress.saveUser(newUser);
                int posixid = getPosixId(userId);
                Groups privateGroup = new Groups();//Creating Private group for each new user
                privateGroup.setGroupName(userId);
                privateGroup.setGroupActive(true);
                postgress.saveGroups(privateGroup);
                addGroupToUser(userId, userId); //Saving the Private group in User
                log.info("Saving Posixid=" + posixid + " for user " + userId);
                return posixid;
            } else {
                log.info("User " + userId + " Already Exist with PosixId " + getPosixId(userId) + "and user active status is " + postgress.getUsers(userId).getUserActive());
                return getPosixId(userId);
            }
        } catch (Exception e) {
            log.error("User " + userId + " Already Exist with PosixId " + getPosixId(userId) + "and user active status is " + postgress.getUsers(userId).getUserActive());
            return getPosixId(userId);
        }
    }

    @Override
    public boolean groupExist ( String groupMame ) {

        try {
            Groups groups = postgress.getGroups(groupMame);
            if (groups != null) {
                log.info("Group Exist with GroupId=" + groups.getGroupId() + " for user " + groupMame);
                return true;
            } else {
                log.info("Group does not exist");
                return false;
            }
        } catch (Exception e) {
            log.error("Error while checking Group existence: " + e.getMessage());
            return false;
        }
    }

    @Override
    public int getGroupId ( String groupMame ) {

        try {
            Groups groups = postgress.getGroups(groupMame);
            if (groups != null) {
                log.info("Group Exist with GroupId=" + groups.getGroupId() + " for user " + groupMame);
                return groups.getGroupId();
            } else {
                log.info("Group does not exist");
                return -1;
            }
        } catch (Exception e) {
            log.error("Error while checking Group existence: " + e.getMessage());
            return -1;
        }
    }

    @Override
    public int saveGroupId ( String groupMame ) throws ExecutionException, InterruptedException {

        try {
            if (!groupExist(groupMame)) {
                Groups newGroup = new Groups(); // Create a new Users object
                newGroup.setGroupName(groupMame);
                newGroup.setGroupActive(true);
                postgress.saveGroups(newGroup);
                int groupId = getGroupId(groupMame);
                log.info("Saving GroupId=" + groupId + " for group " + groupMame);
                return groupId;
            } else {
                log.info("Group " + groupMame + " Already Exist with GroupId " + getGroupId(groupMame) + "and Group active status is " + postgress.getGroups(groupMame).getGroupActive());
                return getGroupId(groupMame);
            }
        } catch (Exception e) {
            log.info("Group " + groupMame + " Already Exist with GroupId " + getGroupId(groupMame) + "and Group active status is " + postgress.getGroups(groupMame).getGroupActive());
            return getGroupId(groupMame);
        }
    }

    @Override
    public void saveGroupEntry ( String groupMame, String groupEntry ) throws ExecutionException, InterruptedException {

    }

    @Override
    public int getPosixId ( String userId ) throws ExecutionException, InterruptedException {

        if (userExists(userId)) {
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
    public boolean userExistsInGroup ( String userId, String groupMame ) throws ExecutionException, InterruptedException, IOException, ClassNotFoundException {

        try {
            if (userExists(userId) && groupExist(groupMame)) {
                Users savedUser = postgress.getUsers(userId);
                Groups savedGroup = postgress.getGroups(groupMame);
                return savedUser.getGroupDetailList().contains(savedGroup);
            } else {
                log.error("Failed to retrieve the saved user and saved Group with userId=" + userId + "and Groupname=" + groupMame);
                return false;
            }
        } catch (NullPointerException e) {
            log.error("User is not present in any group userid= " + userId);
            return false;
        }
    }

    @Override
    public String getGroupEntry ( String groupMame ) throws ExecutionException, InterruptedException {

        return null;
    }

    @Override
    public void savePosixEntry ( String userId, String posixEntry ) throws ExecutionException, InterruptedException {

    }

    @Override
    public String getPosixEntry ( String userId ) throws ExecutionException, InterruptedException {

        return null;
    }

    @Override
    public List < String > getGroupForUser ( String userId ) throws IOException, ExecutionException, InterruptedException, ClassNotFoundException {

        if (userExists(userId)) {
            Users savedUser = postgress.getUsers(userId);
            return Collections.singletonList(savedUser.getGroupDetailList().stream().toString());
        } else {
            log.error("Failed to retrieve the saved user Groups=" + userId);
            return new ArrayList < String >();
        }
    }

    @Override
    public void addGroupToUser ( String userId, String groupMame ) throws IOException, ExecutionException, InterruptedException, ClassNotFoundException {

        if (userExists(userId) && groupExist(groupMame) && !userExistsInGroup(userId, groupMame)) {
            Users savedUser = postgress.getUsers(userId);
            Groups savedGroup = postgress.getGroups(groupMame);
            List < Groups > grouplist = new ArrayList <>();
            grouplist.add(savedGroup);
            savedUser.setGroupsList(grouplist);
            postgress.saveUser(savedUser);
        } else {
            log.error("Failed to add the user inside the saved Group with userId=" + userId + "and Groupname=" + groupMame);
        }
    }

    public void removeUsersGroup ( String userid, String groupName ) throws IOException, ExecutionException, InterruptedException, ClassNotFoundException {

        if (userExists(userid) && groupExist(groupName) && userExistsInGroup(userid, groupName)) {
            Users savedUser = postgress.getUsers(userid);
            Groups savedGroup = postgress.getGroups(groupName);
            savedUser.getGroupDetailList().remove(savedGroup);
            postgress.saveUser(savedUser);
        } else if (userExists(userid) && groupExist(groupName) && !userExistsInGroup(userid, groupName)) {
            log.error("userId= " + userid + " dont exist in the Group= " + groupName);
        } else {
            log.error("Either userId=" + userid + " or Groupname= " + groupName + " dont exist ");
        }
    }

    @Override
    public String groupEntries ( String userId ) throws IOException, ExecutionException, InterruptedException, ClassNotFoundException {

        return null;
    }

    public static void main ( String args[] ) throws ExecutionException, InterruptedException, IOException, ClassNotFoundException {

        PostgresPosixClient postgresPosixClient = new PostgresPosixClient();
        postgresPosixClient.savePosixId("user1");
        postgresPosixClient.savePosixId("user2");

/*        postgresPosixClient.saveGroupId("Admin");
        postgresPosixClient.saveGroupId("root");

        postgresPosixClient.addGroupToUser("user1","Admin");
        postgresPosixClient.addGroupToUser("user2","root");
        */
        postgress.closeSession();
    }
}
