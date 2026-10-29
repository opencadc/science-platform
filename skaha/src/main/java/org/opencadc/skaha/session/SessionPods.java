package org.opencadc.skaha.session;

import ca.nrc.cadc.net.ResourceNotFoundException;
import ca.nrc.cadc.util.StringUtil;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.http.HttpRequest;
import io.fabric8.kubernetes.client.http.HttpResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import org.apache.log4j.Logger;
import org.opencadc.skaha.SkahaAction;
import org.opencadc.skaha.SkahaKubernetesClient;

/** What a session's Pods show: events, logs and the desktop's IP. */
final class SessionPods {
    private static final Logger LOGGER = Logger.getLogger(SessionPods.class);

    static final String DEFAULT_CONTAINER_ANNOTATION = "kubectl.kubernetes.io/default-container";
    private static final String[] EVENT_COLUMNS = {"TYPE", "REASON", "MESSAGE", "FIRST-TIME", "LAST-TIME"};

    // kubectl's tabwriter settings (k8s.io/cli-runtime printers): minimum cell width 6, padding 3.
    private static final int MIN_CELL_WIDTH = 6;
    private static final int CELL_PADDING = 3;

    private SessionPods() {}

    static List<Pod> pods(final String username, final String sessionID) {
        return SkahaKubernetesClient.get()
                .pods()
                .withLabelSelector(SessionLabels.forSession(username, sessionID))
                .list()
                .getItems();
    }

    /**
     * Events on the session's Jobs and Pods, as a table: type, reason, message, first and last time. Job events matter
     * when no Pod exists, e.g. a Pod rejected at admission ({@code FailedCreate}).
     *
     * @return The table, or an empty string when there are no events.
     * @throws ResourceNotFoundException When the session has neither a Job nor a Pod.
     */
    static String events(final String username, final String sessionID) throws ResourceNotFoundException {
        final KubernetesClient client = SkahaKubernetesClient.get();
        final List<String> names = Stream.concat(
                        SessionDAO.liveJobs(username, sessionID, false).stream(), pods(username, sessionID).stream())
                .map(resource -> resource.getMetadata().getName())
                .toList();
        if (names.isEmpty()) {
            throw new ResourceNotFoundException("session " + sessionID + " not found.");
        }

        final List<Event> events = new ArrayList<>();
        for (final String name : names) {
            events.addAll(client.v1()
                    .events()
                    .withField("involvedObject.name", name)
                    .list()
                    .getItems());
        }
        events.sort(Comparator.comparing(event -> event.getMetadata().getName()));
        return format(events);
    }

    static String format(final List<Event> events) {
        if (events.isEmpty()) {
            return "";
        }
        final List<String[]> rows = new ArrayList<>();
        rows.add(EVENT_COLUMNS);
        for (final Event event : events) {
            rows.add(new String[] {
                valueOrNone(event.getType()),
                valueOrNone(event.getReason()),
                valueOrNone(event.getMessage()),
                valueOrNone(event.getFirstTimestamp()),
                valueOrNone(event.getLastTimestamp())
            });
        }

        // Every column but the last is padded to its widest cell, as text/tabwriter does.
        final int[] widths = new int[EVENT_COLUMNS.length - 1];
        for (final String[] row : rows) {
            for (int column = 0; column < widths.length; column++) {
                widths[column] =
                        Math.max(widths[column], Math.max(MIN_CELL_WIDTH, row[column].length() + CELL_PADDING));
            }
        }

        final StringBuilder table = new StringBuilder();
        for (final String[] row : rows) {
            for (int column = 0; column < widths.length; column++) {
                table.append(row[column]).append(" ".repeat(widths[column] - row[column].length()));
            }
            table.append(row[EVENT_COLUMNS.length - 1]).append('\n');
        }
        return table.toString().trim();
    }

    /** Write each session Pod's log from its default container. Pods whose container has not started are skipped. */
    static void streamLogs(final String username, final String sessionID, final OutputStream out) throws IOException {
        for (final Pod pod : pods(username, sessionID)) {
            final HttpResponse<InputStream> response = getLog(pod.getMetadata().getName(), defaultContainer(pod));
            try (InputStream log = response.body()) {
                if (response.isSuccessful()) {
                    log.transferTo(out);
                } else {
                    LOGGER.debug("No logs for " + pod.getMetadata().getName() + ": HTTP " + response.code());
                }
            }
        }
        out.flush();
    }

    /**
     * The IP of the session's running desktop Pod.
     *
     * @throws ResourceNotFoundException When no desktop Pod has an IP and is not being deleted.
     */
    static String desktopIP(final String username, final String sessionID) throws ResourceNotFoundException {
        return pods(username, sessionID).stream()
                .filter(pod -> pod.getMetadata().getDeletionTimestamp() == null)
                .filter(pod -> SkahaAction.SESSION_TYPE_DESKTOP.equals(SessionLabels.get(pod, SessionLabels.Key.KIND)))
                .map(pod -> pod.getStatus() == null ? null : pod.getStatus().getPodIP())
                .filter(StringUtil::hasText)
                .reduce((first, last) -> last)
                .orElseThrow(() -> new ResourceNotFoundException("session " + sessionID + " not found"));
    }

    /**
     * One GET of the Pod log through the client's authenticated HTTP client. Fabric8's log DSL first reads the Pod and
     * may watch it until ready, which needs more permissions and API calls.
     */
    private static HttpResponse<InputStream> getLog(final String podName, final String container) throws IOException {
        final KubernetesClient client = SkahaKubernetesClient.get();
        final String path = "api/v1/namespaces/" + encode(client.getNamespace()) + "/pods/" + encode(podName)
                + "/log?container=" + encode(container);
        final HttpRequest request = client.getHttpClient()
                .newHttpRequestBuilder()
                .url(new URL(client.getMasterUrl(), path))
                .build();
        try {
            return client.getHttpClient().sendAsync(request, InputStream.class).get();
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted reading logs of " + podName);
        } catch (ExecutionException executionException) {
            throw new IOException("Unable to read logs of " + podName, executionException.getCause());
        }
    }

    private static String defaultContainer(final Pod pod) {
        final String annotated = pod.getMetadata().getAnnotations().get(DEFAULT_CONTAINER_ANNOTATION);
        return annotated != null
                ? annotated
                : pod.getSpec().getContainers().get(0).getName();
    }

    private static String valueOrNone(final String value) {
        return value == null || value.isEmpty() ? "<none>" : value;
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
