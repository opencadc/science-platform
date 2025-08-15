package org.opencadc.skaha.session;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.http.client.utils.URIBuilder;

/** Build a URL to a session. */
public abstract class SessionURLBuilder {
    final String host;
    final String sessionID;

    /**
     * Constructor. Use the static factory methods to create instances.
     *
     * @param host The host name.
     * @param sessionID The session ID.
     */
    private SessionURLBuilder(final String host, final String sessionID) {
        this.host = Objects.requireNonNull(host);
        this.sessionID = Objects.requireNonNull(sessionID);
    }

    /**
     * Create a builder to a VNC (Desktop) session.
     *
     * @param host The host name.
     * @param sessionID The session ID.
     * @return VNCSessionURLBuilder instance. Never null.
     */
    static VNCSessionURLBuilder vncSession(final String host, final String sessionID) {
        return new VNCSessionURLBuilder(host, sessionID);
    }

    /**
     * Create a builder to a Notebook session.
     *
     * @param host The host name.
     * @param sessionID The session ID.
     * @return NotebookSessionURLBuilder instance. Never null.
     */
    static NotebookSessionURLBuilder notebookSession(final String host, final String sessionID) {
        return new NotebookSessionURLBuilder(host, sessionID);
    }

    /**
     * Create a builder to a Carta session.
     *
     * @param host The host name.
     * @param sessionID The session ID.
     * @return CartaSessionURLBuilder instance. Never null.
     */
    static CartaSessionURLBuilder cartaSession(final String host, final String sessionID) {
        return new CartaSessionURLBuilder(host, sessionID);
    }

    /**
     * Create a builder to a contributed session.
     *
     * @param host The host name.
     * @param sessionID The session ID.
     * @return ContributedSessionURLBuilder instance. Never null.
     */
    static ContributedSessionURLBuilder contributedSession(final String host, final String sessionID) {
        return new ContributedSessionURLBuilder(host, sessionID);
    }

    /**
     * Create a builder to a Firefly session.
     *
     * @param host The host name.
     * @param sessionID The session ID.
     * @return FireflySessionURLBuilder instance. Never null.
     */
    static FireflySessionURLBuilder fireflySession(final String host, final String sessionID) {
        return new FireflySessionURLBuilder(host, sessionID);
    }

    /**
     * Abstract method to build the URL.
     *
     * @return The URL string. Never null.
     * @throws URISyntaxException If the URI cannot be created.
     */
    abstract String build() throws URISyntaxException;

    /** Construct a URL for a VNC session to a running Desktop. Used to redirect the end user to the VNC viewer. */
    static final class VNCSessionURLBuilder extends SessionURLBuilder {
        VNCSessionURLBuilder(String host, String sessionID) {
            super(host, sessionID);
        }

        /**
         * Build the URL for a VNC session. Example output: <code>
         * <a href="https://host.example.org/session/desktop/8675309?password=8675309&path=session/desktop/8675309/">...</a>
         * </code>
         *
         * @return URL string in format <code>
         *     https://${host}/session/desktop/${sessionID}?password=${sessionID}&path=session/desktop/${sessionID}
         *     </code>
         * @throws URISyntaxException If the URI cannot be created.
         */
        @Override
        String build() throws URISyntaxException {
            return new URIBuilder()
                    .setScheme("https")
                    .setHost(this.host)
                    .setPathSegments("session", "desktop", this.sessionID, "") // Extra empty string to append slash
                    .setCustomQuery("password=" + this.sessionID + "&path=session/desktop/" + this.sessionID + "/")
                    .build()
                    .toString();
        }
    }

    /** Construct a URL for a Notebook session. Used to redirect the end user to the Jupyter Notebook. */
    static final class NotebookSessionURLBuilder extends SessionURLBuilder {
        private String topLevelDirectory;
        private String userName;

        NotebookSessionURLBuilder(String host, String sessionID) {
            super(host, sessionID);
        }

        /**
         * Set the top-level directory in the Jupyter Notebook session.
         *
         * @param topLevelDirectory The top-level directory in the Jupyter Notebook session.
         * @return This builder.
         */
        NotebookSessionURLBuilder withTopLevelDirectory(String topLevelDirectory) {
            this.topLevelDirectory = topLevelDirectory;
            return this;
        }

        /**
         * Set the user's name in the Jupyter Notebook session.
         *
         * @param userName The user's name in the Jupyter Notebook session.
         * @return This builder.
         */
        NotebookSessionURLBuilder withUserName(String userName) {
            this.userName = userName;
            return this;
        }

        /**
         * Build the URL for a Notebook session. Example output: <code>
         *     <a href="https://host.example.org/session/notebook/8675309/lab/tree/top-level-dir/home/username?token=8675309">...</a>
         * </code>
         *
         * @return URL string in format <code>
         *     https://${host}/session/notebook/${sessionID}/lab/tree/${topLevelDirInSkaha}/home/username?token=${sessionID}
         *     </code>
         * @throws URISyntaxException If the URI cannot be created.
         */
        @Override
        String build() throws URISyntaxException {
            final Path topLevelDirectoryPath = Path.of(Objects.requireNonNull(this.topLevelDirectory));
            final List<String> topLevelDirectoryPathSegments = new ArrayList<>();
            topLevelDirectoryPathSegments.add("session");
            topLevelDirectoryPathSegments.add("notebook");
            topLevelDirectoryPathSegments.add(this.sessionID);
            topLevelDirectoryPathSegments.add("lab");
            topLevelDirectoryPathSegments.add("tree");
            topLevelDirectoryPath
                    .iterator()
                    .forEachRemaining(name -> topLevelDirectoryPathSegments.add(name.toString()));
            topLevelDirectoryPathSegments.add("home");
            topLevelDirectoryPathSegments.add(Objects.requireNonNull(this.userName));

            return new URIBuilder()
                    .setScheme("https")
                    .setHost(this.host)
                    .setPathSegments(topLevelDirectoryPathSegments)
                    .setParameter("token", this.sessionID)
                    .build()
                    .toString();
        }
    }

    /** Construct a URL for a Carta session. Used to redirect the end user to the Carta viewer. */
    static final class CartaSessionURLBuilder extends SessionURLBuilder {
        private boolean useAlternateSocketURL = false;
        private boolean useVersion5Path = false;

        /**
         * Constructor.
         *
         * @param host The host name.
         * @param sessionID The session ID.
         */
        CartaSessionURLBuilder(String host, String sessionID) {
            super(host, sessionID);
        }

        /**
         * Set the use of an alternate socket for the Carta session. This only
         *
         * @param useAlternateSocketURL Specify the alternate socket URL, omit it otherwise.
         * @return This builder.
         */
        CartaSessionURLBuilder withAlternateSocket(boolean useAlternateSocketURL) {
            this.useAlternateSocketURL = useAlternateSocketURL;
            return this;
        }

        /**
         * With CARTA 5, the path to the session has changed. This method allows that to be passed through properly.
         *
         * @param useVersion5Path Specify whether to use the CARTA 5 path format.
         * @return This builder.
         */
        CartaSessionURLBuilder withVersion5Path(boolean useVersion5Path) {
            this.useVersion5Path = useVersion5Path;
            return this;
        }

        /**
         * Build the URL for a Carta session. Example output: <code>
         *     <a href="https://host.example.org/session/carta/8675309">...</a>?
         * </code> or <code>
         *     https://host.example.org/session/carta/8675309?socketUrl=wss://host.example.org/session/carta/ws/8675309/
         * </code>
         *
         * @return URL string in format <code>
         *     https://${host}/session/carta/${sessionID}?token=${sessionID}
         * </code>
         * @throws URISyntaxException If the URI cannot be created.
         */
        @Override
        String build() throws URISyntaxException {
            final String[] pathSegments;

            if (this.useVersion5Path) {
                // CARTA 5 path format
                pathSegments = new String[] {"session", "carta", this.sessionID, ""};
            } else {
                // CARTA <5 path format
                pathSegments = new String[] {"session", "carta", "http", this.sessionID, ""};
            }

            final URIBuilder builder =
                    new URIBuilder().setScheme("https").setHost(this.host).setPathSegments(pathSegments);
            final URIBuilder uriBuilder;

            if (this.useAlternateSocketURL) {
                uriBuilder = builder.setCustomQuery(
                        String.format("socketUrl=wss://%s/session/carta/ws/%s/", this.host, this.sessionID));
            } else {
                uriBuilder = builder;
            }

            return uriBuilder.build().toString();
        }
    }

    /** Construct a URL for a contributed session. Used to redirect the end user to the contributed session. */
    static final class ContributedSessionURLBuilder extends SessionURLBuilder {
        /**
         * Constructor.
         *
         * @param host The host name.
         * @param sessionID The session ID.
         */
        ContributedSessionURLBuilder(String host, String sessionID) {
            super(host, sessionID);
        }

        /**
         * Build the URL for a contributed session. Example output: <code>
         *     <a href="https://host.example.org/session/contrib/8675309">...</a>
         * </code>
         *
         * @return URL string in format <code>
         *     https://${host}/session/contrib/${sessionID}
         *     </code>
         * @throws URISyntaxException If the URI cannot be created.
         */
        @Override
        String build() throws URISyntaxException {
            return new URIBuilder()
                    .setScheme("https")
                    .setHost(this.host)
                    .setPathSegments("session", "contrib", this.sessionID, "")
                    .build()
                    .toString();
        }
    }

    /** Construct a URL for a Firefly session. Used to redirect the end user to the Firefly viewer. */
    static final class FireflySessionURLBuilder extends SessionURLBuilder {
        /**
         * Constructor.
         *
         * @param host The host name.
         * @param sessionID The session ID.
         */
        FireflySessionURLBuilder(String host, String sessionID) {
            super(host, sessionID);
        }

        /**
         * Build the URL for a Firefly session. Example output: <code>
         *     <a href="https://host.example.org/session/firefly/8675309/firefly/">...</a>
         * </code>
         *
         * @return URL string in format <code>
         *     https://${host}/session/firefly/${sessionID}/firefly/
         *     </code>
         * @throws URISyntaxException If the URI cannot be created.
         */
        @Override
        String build() throws URISyntaxException {
            return new URIBuilder()
                    .setScheme("https")
                    .setHost(this.host)
                    .setPathSegments("session", "firefly", this.sessionID, "firefly", "")
                    .build()
                    .toString();
        }
    }
}
