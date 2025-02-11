package org.opencadc.skaha;

public class Job {
    private final String name;
    private final String uid;
    private final String sessionID;
    private final SessionType sessionType;

    public Job(final String name, final String uid, final String sessionID, final SessionType sessionType) {
        this.name = name;
        this.uid = uid;
        this.sessionID = sessionID;
        this.sessionType = sessionType;
    }

    public SessionType getSessionType() {
        return sessionType;
    }

    public String getSessionID() {
        return sessionID;
    }

    public String getName() {
        return name;
    }

    public String getUID() {
        return uid;
    }
}
