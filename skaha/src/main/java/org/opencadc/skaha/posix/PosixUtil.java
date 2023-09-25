package org.opencadc.skaha.posix;


import java.util.List;

public interface PosixUtil {

    PosixUtil userName(String userName);

    PosixUtil homeDir(String homeDir);

    PosixUtil groupNames(List<String> groupNames);

    PosixUtil useClient(PosixClient posixClient);

    void load() throws Exception;

    String posixId() throws Exception;

    List<String> posixEntries() throws Exception;

    String posixEntriesAsString() throws Exception;

    List<String> groupEntries() throws Exception;

    String groupEntriesAsString() throws Exception;

    String userGroupIds() throws Exception;
}

