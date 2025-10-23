package org.opencadc.skaha.utils;

import org.jetbrains.annotations.NotNull;
import org.opencadc.skaha.session.Session;
import org.opencadc.skaha.session.SessionType;

public class TestUtils {
    public static Session createSession(
            @NotNull final String id, @NotNull final SessionType type, @NotNull final String status) {
        return new Session(
                id,
                "owner",
                "88",
                "88",
                new Integer[0],
                "example.org/image",
                type.applicationName,
                status,
                "name-" + id,
                null,
                "https://example.org/connect",
                null);
    }
}
