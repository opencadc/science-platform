package org.opencadc.skaha.session;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.utils.Serialization;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Session templates and launch parameters as PostAction supplies them. The templates come from the Skaha ConfigMap that
 * Gradle's {@code renderSessionTemplates} renders from the chart before the tests run.
 */
final class SessionFixtures {
    static final String USER = "alice";
    static final String SESSION_ID = "abc123";

    private SessionFixtures() {}

    /** The session templates in the Skaha ConfigMap that Gradle renders from the chart, by file name. */
    private static Map<String, String> templates() throws IOException {
        final Path configMap = Path.of(System.getProperty("skaha.sessionTemplates"));
        return Serialization.unmarshal(Files.readString(configMap), ConfigMap.class)
                .getData();
    }

    static Path template(final String name) throws IOException {
        final String template = templates().get(name);
        if (template == null) {
            throw new IOException("No session template " + name);
        }
        return Files.writeString(Files.createTempFile(name, ".yaml"), template);
    }

    /** A working directory whose config/ holds every session template, as the Skaha container has them. */
    static Path workingDirectory(final Path root) throws IOException {
        final Path config = Files.createDirectories(root.resolve("config"));
        for (final Map.Entry<String, String> entry : templates().entrySet()) {
            Files.writeString(config.resolve(entry.getKey()), entry.getValue());
        }
        return root;
    }

    static String image(final String kind) {
        return "images.example.org/skaha/" + kind + ":1.0";
    }

    static SessionJobBuilder builder(final String kind) throws IOException {
        final Map<String, String> parameters = parameters(kind);
        if ("desktop-app".equals(kind)) {
            parameters.putAll(Map.of(
                    PostAction.SOFTWARE_APPID, "app1",
                    PostAction.SOFTWARE_JOBNAME, SESSION_ID + "-app1-alice-terminal",
                    PostAction.SOFTWARE_TARGETIP, "10.0.0.5:1",
                    PostAction.SOFTWARE_CONTAINERNAME, "terminal",
                    PostAction.SOFTWARE_CONTAINERPARAM, "terminal",
                    PostAction.SKAHA_JOBUID, "parent-uid"));
        }
        return SessionJobBuilder.fromPath(template("launch-" + kind + ".yaml"))
                .withParameters(parameters)
                .withImage(image(kind));
    }

    /** The trusted parameters PostAction supplies when launching a session; request values are set separately. */
    static Map<String, String> parameters(final String kind) {
        final Map<String, String> parameters = new HashMap<>();
        parameters.put(PostAction.SKAHA_SESSIONID, SESSION_ID);
        parameters.put(PostAction.SKAHA_SESSIONNAME, "analysis");
        parameters.put(PostAction.SKAHA_SESSIONEXPIRY, "345600");
        parameters.put(PostAction.SKAHA_JOBNAME, "skaha-" + kind + "-alice-" + SESSION_ID);
        parameters.put(PostAction.SKAHA_HOSTNAME, "skaha.example.org");
        parameters.put(PostAction.SKAHA_SESSIONS_HOSTNAME, "sessions.example.org");
        parameters.put(PostAction.SKAHA_USERID, USER);
        parameters.put(PostAction.SKAHA_POSIXID, "20001");
        parameters.put(PostAction.SKAHA_SESSIONTYPE, kind);
        parameters.put(PostAction.SKAHA_SUPPLEMENTALGROUPS, "30001,30002");
        parameters.put(PostAction.SKAHA_SESSIONURLPATH, "/session/carta/" + SESSION_ID);
        parameters.put(PostAction.SOFTWARE_HOSTNAME, "analysis");
        parameters.put(PostAction.SOFTWARE_REQUESTS_CORES, "1.0");
        parameters.put(PostAction.SOFTWARE_LIMITS_CORES, "2.0");
        parameters.put(PostAction.SOFTWARE_REQUESTS_RAM, "4.0Gi");
        parameters.put(PostAction.SOFTWARE_LIMITS_RAM, "8.0Gi");
        parameters.put(PostAction.SOFTWARE_THREAD_COUNT, "1");
        parameters.put(PostAction.SKAHA_USER_HOME_DIR, "/cavern/home/alice");
        parameters.put(PostAction.SKAHA_TOP_LEVEL_DIR, "/cavern");
        parameters.put(PostAction.SKAHA_PROJECTS_DIR, "/cavern/projects");
        parameters.put("software.desktop.app.token", "token");
        return parameters;
    }
}
