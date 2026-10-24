package org.opencadc.skaha.session;

import ca.nrc.cadc.net.ResourceNotFoundException;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.opencadc.skaha.SkahaKubernetesClient;

/** What a session's Pods show: events. */
final class SessionPods {
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
     * Events on the session's Jobs and Pods, in the columns of the former {@code kubectl get event -o custom-columns}.
     * Job events matter when no Pod exists, e.g. a Pod rejected at admission ({@code FailedCreate}).
     *
     * @return The table, or an empty string when there are no events.
     * @throws ResourceNotFoundException When the session has neither a Job nor a Pod.
     */
    static String events(final String username, final String sessionID) throws ResourceNotFoundException {
        final KubernetesClient client = SkahaKubernetesClient.get();
        final List<String> names = Stream.concat(
                        client
                                .batch()
                                .v1()
                                .jobs()
                                .withLabelSelector(SessionLabels.forSession(username, sessionID))
                                .list()
                                .getItems()
                                .stream(),
                        pods(username, sessionID).stream())
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

    private static String valueOrNone(final String value) {
        return value == null || value.isEmpty() ? "<none>" : value;
    }
}
