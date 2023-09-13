package org.opencadc.skaha.posix;


import java.util.List;

public interface PosixUtil {

    PosixUtil userName(String userName);

    PosixUtil homeDir(String homeDir);

    PosixUtil groupNames(List<String> groupNames);

    PosixUtil useClient(PosixClient posixClient);

    void load() throws Exception;

    String posixId() throws Exception;

    String posixEntry() throws Exception;

    String groupEntries() throws Exception;

    String userGroupIds() throws Exception;
}

