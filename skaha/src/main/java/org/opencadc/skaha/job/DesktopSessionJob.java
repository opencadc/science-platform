package org.opencadc.skaha.job;

import org.opencadc.skaha.SessionType;

public class DesktopSessionJob extends Job {
    private String appToken;

    DesktopSessionJob(final DesktopSessionJobBuilder builder) {
        super(builder);
        this.appToken = builder.appToken;
    }

    public String getAppToken() {
        return appToken;
    }

    @Override
    public SessionType getType() {
        return SessionType.DESKTOP;
    }

    public static DesktopSessionJobBuilder builder() {
        return new DesktopSessionJobBuilder();
    }

    public static class DesktopSessionJobBuilder extends JobBuilder {
        private String appToken;

        public DesktopSessionJobBuilder withAppToken(String appToken) {
            this.appToken = appToken;
            return this;
        }

        public DesktopSessionJob build() {
            return new DesktopSessionJob(this);
        }
    }
}
