package org.opencadc.skaha.posix.client.postgresql.enitities;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
@Entity
public class CommonGroup {
    @Id
    private Integer groupId;
    private String groupName;

    public CommonGroup ( Integer groupId, String groupName ) {
        this.groupId = groupId;
        this.groupName = groupName;
    }

    public CommonGroup ( ) {
    }

    public Integer getGroupId ( ) {
        return groupId;
    }

    public void setGroupId ( Integer groupId ) {
        this.groupId = groupId;
    }

    public String getGroupName ( ) {
        return groupName;
    }

    public void setGroupName ( String groupName ) {
        this.groupName = groupName;
    }
}
