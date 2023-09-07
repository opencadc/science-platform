package org.opencadc.skaha.posix.client.postgresql.enitities;
import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;
@Entity
@Table ( name = "Users" )
public class Users {
    private String userid;
    @Id
    @GeneratedValue ( strategy = GenerationType.IDENTITY, generator = "users_posixid_seq1" )
    @SequenceGenerator ( name = "users_posixid_seq1", sequenceName = "users_posixid_seq1", allocationSize = 1, initialValue = 10000 )
    private Integer posixid;
    @ManyToMany ( fetch = FetchType.EAGER )
    private List < Groups > groupsList;
    @ManyToMany ( fetch = FetchType.EAGER )
    private List < CommonGroup > commonGroups;

    public List < CommonGroup > getCommonGroups ( ) {
        return commonGroups;
    }

    public void setCommonGroups ( List < CommonGroup > commonGroups ) {
        this.commonGroups = commonGroups;
    }

    public List < Groups > getGroupsList ( ) {
        return groupsList;
    }

    public void setGroupsList ( List < Groups > groupsList ) {
        this.groupsList = groupsList;
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