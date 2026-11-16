/*
 ************************************************************************
 *******************  CANADIAN ASTRONOMY DATA CENTRE  *******************
 **************  CENTRE CANADIEN DE DONNÉES ASTRONOMIQUES  **************
 *
 *  (c) 2020.                            (c) 2020.
 *  Government of Canada                 Gouvernement du Canada
 *  National Research Council            Conseil national de recherches
 *  Ottawa, Canada, K1A 0R6              Ottawa, Canada, K1A 0R6
 *  All rights reserved                  Tous droits réservés
 *
 *  NRC disclaims any warranties,        Le CNRC dénie toute garantie
 *  expressed, implied, or               énoncée, implicite ou légale,
 *  statutory, of any kind with          de quelque nature que ce
 *  respect to the software,             soit, concernant le logiciel,
 *  including without limitation         y compris sans restriction
 *  any warranty of merchantability      toute garantie de valeur
 *  or fitness for a particular          marchande ou de pertinence
 *  purpose. NRC shall not be            pour un usage particulier.
 *  liable in any event for any          Le CNRC ne pourra en aucun cas
 *  damages, whether direct or           être tenu responsable de tout
 *  indirect, special or general,        dommage, direct ou indirect,
 *  consequential or incidental,         particulier ou général,
 *  arising from the use of the          accessoire ou fortuit, résultant
 *  software.  Neither the name          de l'utilisation du logiciel. Ni
 *  of the National Research             le nom du Conseil National de
 *  Council of Canada nor the            Recherches du Canada ni les noms
 *  names of its contributors may        de ses  participants ne peuvent
 *  be used to endorse or promote        être utilisés pour approuver ou
 *  products derived from this           promouvoir les produits dérivés
 *  software without specific prior      de ce logiciel sans autorisation
 *  written permission.                  préalable et particulière
 *                                       par écrit.
 *
 *  This file is part of the             Ce fichier fait partie du projet
 *  OpenCADC project.                    OpenCADC.
 *
 *  OpenCADC is free software:           OpenCADC est un logiciel libre ;
 *  you can redistribute it and/or       vous pouvez le redistribuer ou le
 *  modify it under the terms of         modifier suivant les termes de
 *  the GNU Affero General Public        la “GNU Affero General Public
 *  License as published by the          License” telle que publiée
 *  Free Software Foundation,            par la Free Software Foundation
 *  either version 3 of the              : soit la version 3 de cette
 *  License, or (at your option)         licence, soit (à votre gré)
 *  any later version.                   toute version ultérieure.
 *
 *  OpenCADC is distributed in the       OpenCADC est distribué
 *  hope that it will be useful,         dans l’espoir qu’il vous
 *  but WITHOUT ANY WARRANTY;            sera utile, mais SANS AUCUNE
 *  without even the implied             GARANTIE : sans même la garantie
 *  warranty of MERCHANTABILITY          implicite de COMMERCIALISABILITÉ
 *  or FITNESS FOR A PARTICULAR          ni d’ADÉQUATION À UN OBJECTIF
 *  PURPOSE.  See the GNU Affero         PARTICULIER. Consultez la Licence
 *  General Public License for           Générale Publique GNU Affero
 *  more details.                        pour plus de détails.
 *
 *  You should have received             Vous devriez avoir reçu une
 *  a copy of the GNU Affero             copie de la Licence Générale
 *  General Public License along         Publique GNU Affero avec
 *  with OpenCADC.  If not, see          OpenCADC ; si ce n’est
 *  <http://www.gnu.org/licenses/>.      pas le cas, consultez :
 *                                       <http://www.gnu.org/licenses/>.
 *
 ************************************************************************
 */

package org.opencadc.skaha.session;

import ca.nrc.cadc.net.ResourceNotFoundException;
import ca.nrc.cadc.rest.SyncInput;
import ca.nrc.cadc.util.StringUtil;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.log4j.Logger;
import org.opencadc.skaha.SkahaAction;
import org.opencadc.skaha.metrics.SessionMetrics;

public abstract class SessionAction extends SkahaAction {

    protected static final String REQUEST_TYPE_SESSION = "session";
    protected static final String REQUEST_TYPE_APP = "app";
    protected static final String SESSION_LIST_VIEW_ALL = "all";
    protected static final String SESSION_VIEW_EVENTS = "events";
    protected static final String SESSION_VIEW_LOGS = "logs";
    protected static final String SESSION_VIEW_STATS = "stats";
    protected static final String SESSION_VIEW_INTERACTIVE = "interactive";
    protected static final String NONE = "<none>";

    /** Writes the Metrics-style documents of the batch API (the Batch document and its failure Status). */
    static final ObjectMapper DOCUMENTS = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
            .addMixIn(SessionMetrics.Resource.class, MetricsRow.class);

    private static final Logger log = Logger.getLogger(SessionAction.class);

    protected String requestType;
    protected String sessionID;
    protected String appID;

    /** Whether the session a GET names runs as a batch. GetAction decides it once, from the session's Job. */
    protected boolean batchSession;

    public SessionAction() {
        super();
    }

    /**
     * A failed batch request: the Metrics failure document, {@code application/json}.
     *
     * @param reason {@code BadRequest}, {@code NotFound} or {@code ServiceUnavailable}.
     */
    record FailureStatus(String apiVersion, String kind, String status, String reason, String message, int code) {
        private static final Map<Integer, String> REASONS =
                Map.of(400, "BadRequest", 404, "NotFound", 503, "ServiceUnavailable");

        FailureStatus(final String reason, final String message, final int code) {
            this("v1", "Status", "Failure", reason, message, code);
        }
    }

    /** A Batch document copies each Metrics row as Metrics wrote it: a value the report left out stays out. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private interface MetricsRow {}

    /**
     * Write a batch request's 400, 404 or 503 as a {@link FailureStatus}; every other error keeps the
     * {@code text/plain} body cadc-rest writes. A batch request sends {@code type=batch} or {@code maxfail}, or names a
     * batch session.
     */
    @Override
    protected void handleException(
            final Throwable ex, final int code, final String message, final boolean showError, final boolean showCause)
            throws IOException {
        final String reason = FailureStatus.REASONS.get(code);
        if (reason == null || !(this.batchSession || sendsBatchParameters())) {
            super.handleException(ex, code, message, showError, showCause);
            return;
        }
        this.logInfo.setMessage(message);
        if (showError) {
            log.error(message, ex);
        } else {
            log.debug(message, ex);
        }
        if (!this.syncOutput.isOpen()) {
            final byte[] body = DOCUMENTS
                    .writeValueAsString(new FailureStatus(reason, message, code))
                    .getBytes(StandardCharsets.UTF_8);
            this.syncOutput.setCode(code);
            this.syncOutput.setHeader("Content-Type", "application/json");
            this.syncOutput.setHeader("Content-Length", Integer.toString(body.length));
            this.syncOutput.getOutputStream().write(body);
        }
    }

    private boolean sendsBatchParameters() {
        if (this.syncInput == null) {
            return false;
        }
        final String type = this.syncInput.getParameter("type");
        return (type != null && SessionType.BATCH.applicationName.equalsIgnoreCase(type.trim()))
                || this.syncInput.getParameter("maxfail") != null;
    }

    protected void initRequest() throws Exception {
        super.initRequest();
        parseRequestPath();
    }

    /** Set the request type, session ID and app ID from the request path. */
    void parseRequestPath() {
        String path = syncInput.getPath();
        log.debug("request path: " + path);
        requestType = REQUEST_TYPE_SESSION;

        if (path == null) {
            return;
        }

        String[] parts = path.split("/");
        if (parts.length > 0) {
            sessionID = parts[0];
        }
        if (parts.length > 1) {
            requestType = REQUEST_TYPE_APP;
        }
        if (parts.length > 2) {
            appID = parts[2];
        }
        if (parts.length > 3) {
            throw new IllegalArgumentException("Invalid request: " + path);
        }
        log.debug("request type: " + requestType);
        log.debug("sessionID: " + sessionID);
        log.debug("appID: " + appID);
        log.debug("userID: " + posixPrincipal);
    }

    protected String getImageName(String image) {
        try {
            // return the last segment of the path
            int lastSlash = image.lastIndexOf("/");
            String name = image.substring(lastSlash + 1);
            // The name becomes part of Kubernetes object names and labels.
            return name.toLowerCase().replaceAll("[^a-z0-9-]", "-");
        } catch (Exception e) {
            log.warn("failed to determine name for image: " + image);
            return "unknown";
        }
    }

    public Session getDesktopApp(String sessionID, String appID) throws Exception {
        List<Session> sessions = SessionDAO.getUserSessions(posixPrincipal.username, sessionID, true);
        if (!sessions.isEmpty()) {
            for (Session session : sessions) {
                // only include 'desktop-app'
                if (SkahaAction.TYPE_DESKTOP_APP.equalsIgnoreCase(session.getType())
                        && (sessionID.equals(session.getId()))
                        && (appID.equals(session.getAppId()))) {
                    return session;
                }
            }
        }

        throw new ResourceNotFoundException(
                "desktop app with session " + sessionID + " and app ID " + appID + " was not found");
    }

    public Session getSession(String forUserID, String sessionID) throws Exception {
        return SessionDAO.getSession(forUserID, sessionID);
    }

    List<Session> getInteractiveSessions(final String forUserID) throws Exception {
        return SessionDAO.getUserSessions(forUserID, null, true);
    }

    List<Session> getAllSessions(final String forUserID) throws Exception {
        return SessionDAO.getUserSessions(forUserID, null, false);
    }

    /**
     * Obtain the current requested session type from the input parameters.
     *
     * @return String representing the requested session type. Possibly null.
     */
    protected static String getRequestedSessionType(final SyncInput syncInput) {
        final String suppliedType = syncInput.getParameter("type");
        return StringUtil.hasText(suppliedType) ? suppliedType : PostAction.SESSION_TYPE_HEADLESS;
    }
}
