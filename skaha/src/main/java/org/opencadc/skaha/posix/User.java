package org.opencadc.skaha.posix;

import jakarta.persistence.*;

import java.util.List;


@NamedQueries({
        @NamedQuery(name = "findUserByUsername", query = "SELECT u FROM Users u WHERE u.username = :username"),
        @NamedQuery(name = "findAllUsersForGroupId", query = "SELECT u FROM Users u JOIN u.groups g WHERE g.gid = :gid")
})
@Entity(name = "Users")
@Table(name = "Users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY, generator = "users_uid_seq1")
    @SequenceGenerator(name = "users_uid_seq1", sequenceName = "users_uid_seq1", allocationSize = 1, initialValue = 10000)
    private Integer uid;

    private String username;

    @ManyToMany(fetch = FetchType.EAGER)
    private List<Group> groups;

    public User() {
    }

    public User(String username) {
        this.username = username;
    }

    public Integer getUid() {
        return uid;
    }

    public void setUid(Integer uid) {
        this.uid = uid;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public List<Group> getGroups() {
        return groups;
    }

    public void setGroups(List<Group> groups) {
        this.groups = groups;
    }

    @Override
    public String toString() {
        return "User{" +
                "uid=" + uid +
                ", username='" + username + '\'' +
                ", groups=" + groups +
                '}';
    }
}