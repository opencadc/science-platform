package org.opencadc.skaha.posix.client.postgresql.enitities;
import jakarta.persistence.*;

import java.util.List;
@Entity
@Table ( name = "Users" )
public class Users {
    private String userid;
    @Id
    @GeneratedValue ( strategy = GenerationType.IDENTITY, generator = "users_posixid_seq1" )
    @SequenceGenerator ( name = "users_posixid_seq1", sequenceName = "users_posixid_seq1", allocationSize = 1, initialValue = 10000 )
    private Integer posixid;
    private Boolean isUserActive;
    @ManyToMany ( fetch = FetchType.EAGER )
    private List < Groups > groupsList;

    public List < Groups > getGroupsList ( ) {

        return groupsList;
    }

    public void setGroupsList ( List < Groups > groupsList ) {

        this.groupsList = groupsList;
    }

    public Boolean getUserActive ( ) {

        return isUserActive;
    }

    public void setUserActive ( Boolean userActive ) {

        isUserActive = userActive;
    }

    public Users ( String userid, Integer posixid, List < Groups > groupsList ) {

        this.userid = userid;
        this.posixid = posixid;
        this.groupsList = groupsList;
    }

    public Users ( ) {

    }

    public String getUserid ( ) {

        return userid;
    }

    public void setUserid ( String userid ) {

        this.userid = userid;
    }

    public Integer getPosixid ( ) {

        return posixid;
    }

    public void setPosixid ( Integer posixid ) {

        this.posixid = posixid;
    }

    public List < Groups > getGroupDetailList ( ) {

        return groupsList;
    }

    public void setGroupDetailList ( List < Groups > groupsList ) {

        this.groupsList = groupsList;
    }
}