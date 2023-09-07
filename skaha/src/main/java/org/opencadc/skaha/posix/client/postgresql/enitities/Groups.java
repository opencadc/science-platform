package org.opencadc.skaha.posix.client.postgresql.enitities;
import jakarta.persistence.*;

import java.util.List;
@Entity
@Table ( name = "Groups" )
public class Groups {
    private String groupName;
    @Id
    @GeneratedValue ( strategy = GenerationType.IDENTITY, generator = "groups_groupid_seq1" )
    @SequenceGenerator ( name = "groups_groupid_seq1", sequenceName = "groups_groupid_seq1", allocationSize = 1, initialValue = 1000000 )
    private int groupId;

    public String getGroupName ( ) {
        return groupName;
    }

    public void setGroupName ( String groupName ) {
        this.groupName = groupName;
    }

    public int getGroupId ( ) {
        return groupId;
    }

    public void setGroupId ( int groupId ) {
        this.groupId = groupId;
    }
}
