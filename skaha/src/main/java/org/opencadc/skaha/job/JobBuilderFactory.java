package org.opencadc.skaha.job;

import org.opencadc.skaha.SessionType;

public class JobBuilderFactory {
    public static <T extends JobBuilder> T fromType(final SessionType sessionType) {
        switch (sessionType) {
            case DESKTOP:
                return (T) DesktopSessionJob.builder();
            case HEADLESS:
                return (T) HeadlessJob.builder();
            default:
                throw new IllegalArgumentException("Unsupported session type: " + sessionType);
        }
    }
}
