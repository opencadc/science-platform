package org.opencadc.skaha.session;

import ca.nrc.cadc.auth.PosixPrincipal;
import ca.nrc.cadc.log.WebServiceLogInfo;
import ca.nrc.cadc.rest.SyncInput;
import ca.nrc.cadc.rest.SyncOutput;
import ca.nrc.cadc.util.FileUtil;
import ca.nrc.cadc.util.PropertiesReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.rules.ExternalResource;
import org.mockito.Mockito;
import org.opencadc.auth.PosixGroup;
import org.opencadc.gms.GroupURI;
import org.opencadc.skaha.SkahaAction;
import org.opencadc.skaha.image.Image;

/**
 * Requests to the session actions as an authenticated user, with the user's groups, POSIX entries and user storage
 * stubbed. Everything else runs for real, against the Kubernetes API that {@link org.opencadc.skaha.KubernetesApi}
 * mocks.
 */
final class SessionRequests extends ExternalResource {
    static final String REGISTRY = "images.example.org";
    private static final String CONFIG_DIR = PropertiesReader.class.getName() + ".dir";

    final UserStorageClient userStorage = Mockito.mock(UserStorageClient.class);
    int posixWrites;
    int maxUserSessions = 10;
    private final Map<String, String> savedProperties = new HashMap<>();
    private Path workingDirectory;

    @Override
    protected void before() throws Exception {
        this.workingDirectory = SessionFixtures.workingDirectory(Files.createTempDirectory("skaha-home"));
        setProperty("user.home", this.workingDirectory.toString());
        setProperty(
                CONFIG_DIR,
                Path.of(SessionRequests.class
                                .getResource("/resource-context-test")
                                .toURI())
                        .toString());
        TestUtil.setupUserStorageEnvironment();
        SkahaCallbackTokenTool.forget();
    }

    @Override
    protected void after() {
        SkahaCallbackTokenTool.forget();
        TestUtil.tearDownUserStorageEnvironment();
        this.savedProperties.forEach((name, value) -> {
            if (value == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, value);
            }
        });
        try {
            FileUtil.delete(this.workingDirectory.toFile(), true);
        } catch (IOException ioException) {
            throw new UncheckedIOException(ioException);
        }
    }

    private void setProperty(final String name, final String value) {
        this.savedProperties.putIfAbsent(name, System.getProperty(name));
        System.setProperty(name, value);
    }

    /** A request: path, parameters (repeatable), and headers. */
    static final class Request {
        private final String path;
        private final Map<String, List<String>> parameters = new HashMap<>();
        private final Map<String, String> headers = new HashMap<>();

        Request(final String path) {
            this.path = path;
        }

        Request param(final String name, final String value) {
            this.parameters.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
            return this;
        }

        /** Registry credentials, as a private image requires. */
        Request registryAuth(final String username, final String secret) {
            this.headers.put(
                    "x-skaha-registry-auth",
                    Base64.getEncoder().encodeToString((username + ":" + secret).getBytes(StandardCharsets.UTF_8)));
            return this;
        }
    }

    /** What the action wrote, and the HTTP code when run through {@link SkahaAction#run()}. */
    static final class Response {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        int code = 200;

        String text() {
            return this.body.toString(StandardCharsets.UTF_8);
        }
    }

    Response post(final Request request) throws Exception {
        return doAction(new Post(), request);
    }

    Response get(final Request request) throws Exception {
        return doAction(new Get(), request);
    }

    Response delete(final Request request) throws Exception {
        return doAction(new Delete(), request);
    }

    /** Run the request through SkahaAction's and cadc-rest's error handling, as the servlet does. */
    Response run(final String method, final Request request) throws Exception {
        final SessionAction action = "GET".equals(method) ? new Get() : new Post();
        final Response response = bind(action, request);
        action.setLogInfo(new WebServiceLogInfo() {});
        action.setInitParams(Map.of());
        action.run();
        return response;
    }

    private Response doAction(final SessionAction action, final Request request) throws Exception {
        final Response response = bind(action, request);
        action.doAction();
        return response;
    }

    private static Response bind(final SessionAction action, final Request request) {
        final Response response = new Response();
        action.setSyncInput(new Input(request));
        action.setSyncOutput(new Output(response));
        return response;
    }

    static PosixPrincipal alice() {
        final PosixPrincipal alice = new PosixPrincipal(20001);
        alice.username = SessionFixtures.USER;
        return alice;
    }

    private final class Post extends PostAction {
        Post() {
            super(SessionRequests.this.userStorage);
            this.harborHosts = List.of(REGISTRY);
            this.maxUserSessions = SessionRequests.this.maxUserSessions;
        }

        @Override
        protected void initRequest() {
            this.posixPrincipal = alice();
            parseRequestPath();
        }

        @Override
        public Image getPublicImage(final String imageID) {
            return null;
        }

        @Override
        protected Set<PosixGroup> getUserPosixGroups() {
            return Set.of(new PosixGroup(30001, new GroupURI(URI.create("ivo://example.org/gms?astro"))));
        }

        @Override
        protected void injectPOSIXDetails() {
            SessionRequests.this.posixWrites++;
        }
    }

    private static final class Get extends GetAction {
        @Override
        protected void initRequest() {
            this.posixPrincipal = alice();
            parseRequestPath();
        }
    }

    private static final class Delete extends DeleteAction {
        @Override
        protected void initRequest() {
            this.posixPrincipal = alice();
            parseRequestPath();
        }
    }

    private static final class Input extends SyncInput {
        private final Request request;

        Input(final Request request) {
            this.request = request;
        }

        @Override
        public void init() {}

        @Override
        public String getPath() {
            return this.request.path;
        }

        @Override
        public String getContextPath() {
            return "/skaha";
        }

        @Override
        public String getRequestURI() {
            return "https://skaha.example.org/skaha/v1/session";
        }

        @Override
        public String getRequestPath() {
            return "/skaha/v1/session";
        }

        @Override
        public String getComponentPath() {
            return "/v1/session";
        }

        @Override
        public String getHeader(final String name) {
            return this.request.headers.get(name);
        }

        @Override
        public String getParameter(final String name) {
            final List<String> values = this.request.parameters.get(name);
            return values == null ? null : values.get(0);
        }

        @Override
        public List<String> getParameters(final String name) {
            return this.request.parameters.get(name);
        }

        @Override
        public Set<String> getParameterNames() {
            return this.request.parameters.keySet();
        }
    }

    private static final class Output extends SyncOutput {
        private final Response response;

        Output(final Response response) {
            this.response = response;
        }

        private boolean open;

        @Override
        public boolean isOpen() {
            return this.open;
        }

        @Override
        public void setCode(final int code) {
            this.response.code = code;
        }

        @Override
        public void setHeader(final String name, final Object value) {}

        @Override
        public void addHeader(final String name, final Object value) {}

        @Override
        public OutputStream getOutputStream() {
            this.open = true;
            return this.response.body;
        }
    }
}
