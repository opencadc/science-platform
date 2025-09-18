/*
 ************************************************************************
 *******************  CANADIAN ASTRONOMY DATA CENTRE  *******************
 **************  CENTRE CANADIEN DE DONNÉES ASTRONOMIQUES  **************
 *
 *  (c) 2023.                            (c) 2023.
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
 *
 ************************************************************************
 */

package org.opencadc.skaha;

import ca.nrc.cadc.auth.AuthMethod;
import ca.nrc.cadc.net.HttpDelete;
import ca.nrc.cadc.net.HttpGet;
import ca.nrc.cadc.net.HttpPost;
import ca.nrc.cadc.reg.Standards;
import ca.nrc.cadc.reg.client.RegistryClient;
import ca.nrc.cadc.uws.server.RandomStringGenerator;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Type;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.apache.log4j.Logger;
import org.junit.Assert;
import org.opencadc.skaha.image.Image;
import org.opencadc.skaha.session.Session;
import org.opencadc.skaha.session.SessionAction;
import org.opencadc.skaha.session.SessionType;

public class SessionUtil {
    private static final Logger LOGGER = Logger.getLogger(SessionUtil.class);
    private static final long ONE_SECOND = 1000L;
    private static final long TIMEOUT_WAIT_FOR_SESSION_STARTUP_MS = 180L * SessionUtil.ONE_SECOND;
    private static final long TIMEOUT_WAIT_FOR_SESSION_TERMINATE_MS = 180L * SessionUtil.ONE_SECOND;

    static void initializeCleanup(final URL sessionURL) throws Exception {
        for (Session session : SessionUtil.getSessions(sessionURL, Session.STATUS_TERMINATING)) {
            if (session.getType().equals(SessionAction.TYPE_DESKTOP_APP)) {
                // delete desktop-app
                String sessionID = session.getId();
                final URL desktopAppURL = new URL(sessionURL.toString() + "/" + sessionID + "/app");
                SessionUtil.deleteDesktopApplicationSession(desktopAppURL, session.getAppId());
            } else {
                // delete session
                SessionUtil.deleteSession(sessionURL, session.getId());
            }
        }

        int count = 0;
        for (Session s : SessionUtil.getSessions(sessionURL, Session.STATUS_TERMINATING)) {
            if (!s.getType().equals(SessionAction.TYPE_DESKTOP_APP)) {
                count++;
            }
        }
        Assert.assertEquals("zero sessions #1", 0, count);
    }

    /**
     * Create a Session and return the Session ID. Call waitForSession() after to obtain the Session object.
     *
     * @param sessionURL The Session URL to use.
     * @param name The name of the Session.
     * @param image The image URI to use.
     * @return String session ID, never null.
     */
    static String createSession(final URL sessionURL, final String name, String image, String type) {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", name);
        params.put("image", image);
        params.put("cores", 1);
        params.put("ram", 1);
        params.put("type", type);

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        final HttpPost post = new HttpPost(sessionURL, params, outputStream);
        post.setFollowRedirects(false);
        post.run();

        Assert.assertNull("create session error", post.getThrowable());

        return outputStream.toString().trim();
    }

    static String createHeadlessSession(final String image, final URL sessionURL) {
        final Map<String, Object> params = new HashMap<>();
        final String name = new RandomStringGenerator(16).getID();

        params.put("name", name);
        params.put("image", image);
        params.put("cores", 1);
        params.put("ram", 1);
        params.put("cmd", "sleep");
        params.put("args", "160");

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        final HttpPost post = new HttpPost(sessionURL, params, outputStream);
        post.setFollowRedirects(false);
        post.run();

        Assert.assertNull("create headless session error", post.getThrowable());

        return outputStream.toString().trim();
    }

    static String createDesktopAppSession(final String image, final URL desktopSessionURL) throws Exception {
        return SessionUtil.createDesktopAppSession(image, desktopSessionURL, 1, 1);
    }

    static String createDesktopAppSession(
            final String image, final URL desktopSessionURL, final int cores, final int ram) throws Exception {
        final Map<String, Object> params = new HashMap<>();
        final String name = new RandomStringGenerator(16).getID();

        params.put("name", name);
        params.put("image", image);
        params.put("cores", cores);
        params.put("ram", ram);
        params.put("type", SessionAction.TYPE_DESKTOP_APP);
        params.put("cmd", "sleep");
        params.put("args", "260");

        LOGGER.info("Creating desktop app session with image " + image + " and name " + name);

        try (final ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            final HttpPost post = new HttpPost(desktopSessionURL, params, outputStream);
            post.setFollowRedirects(false);
            post.run();

            Assert.assertNull("create session error", post.getThrowable());

            outputStream.flush();
            return outputStream.toString().trim();
        }
    }

    static Session waitForDesktopApplicationSession(
            final URL desktopApplicationURL, final String desktopAppID, final String expectedState) throws Exception {
        Session requestedSession =
                SessionUtil.getDesktopApplicationSessionWithoutWait(desktopApplicationURL, desktopAppID, expectedState);
        long currentWaitTime = 0L;
        while (requestedSession == null) {
            LOGGER.info("Waiting for Desktop Application Session " + desktopAppID + " to reach " + expectedState);
            Thread.sleep(SessionUtil.ONE_SECOND);
            currentWaitTime += SessionUtil.ONE_SECOND;

            if (currentWaitTime > SessionUtil.TIMEOUT_WAIT_FOR_SESSION_STARTUP_MS) {
                throw new TimeoutException("Timed out waiting for Desktop Application session " + desktopAppID
                        + " and status " + expectedState + " after " + currentWaitTime + "ms");
            }

            requestedSession = SessionUtil.getDesktopApplicationSessionWithoutWait(
                    desktopApplicationURL, desktopAppID, expectedState);
        }

        LOGGER.info("Desktop Application Session " + desktopAppID + " reached " + expectedState);

        return requestedSession;
    }

    static void deleteDesktopApplicationSession(URL desktopApplicationSessionURL, String desktopApplicationSessionID)
            throws Exception {
        LOGGER.info("Deleting desktop application session " + desktopApplicationSessionID);
        HttpDelete delete = new HttpDelete(
                new URL(desktopApplicationSessionURL.toString() + "/" + desktopApplicationSessionID), true);
        delete.run();

        SessionUtil.waitForSessionToTerminate(desktopApplicationSessionURL, desktopApplicationSessionID);
        Assert.assertNull("delete desktop application session error", delete.getThrowable());
    }

    static void deleteSession(URL sessionURL, String sessionID) throws Exception {
        LOGGER.info("Deleting session " + sessionID);
        HttpDelete delete = new HttpDelete(new URL(sessionURL.toString() + "/" + sessionID), true);
        delete.run();

        SessionUtil.waitForSessionToTerminate(sessionURL, sessionID);
        Assert.assertNull("delete session error", delete.getThrowable());
    }

    static List<Session> getSessions(final URL sessionURL, String... omitStatuses) throws Exception {
        final List<Session> sessions = SessionUtil.getAllSessions(sessionURL);
        final List<Session> active = new ArrayList<>();
        for (final Session s : sessions) {
            if (!Arrays.asList(omitStatuses).contains(s.getStatus())) {
                active.add(s);
            }
        }

        return active;
    }

    private static Session getDesktopApplicationSessionWithoutWait(
            final URL desktopApplicationURL, final String desktopApplicationSessionID, final String expectedState) {
        return SessionUtil.getAllDesktopApplicationSessions(desktopApplicationURL).stream()
                .filter(session -> session.getAppId().equals(desktopApplicationSessionID)
                        && session.getStatus().equals(expectedState))
                .findFirst()
                .orElse(null);
    }

    static List<Session> getAllDesktopApplicationSessions(final URL desktopAppURL) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpGet get = new HttpGet(desktopAppURL, out);
        get.run();
        Assert.assertNull("get desktop app error", get.getThrowable());
        Assert.assertEquals("content-type", "application/json", get.getContentType());
        String json = out.toString();
        Type listType = new TypeToken<List<Session>>() {}.getType();
        Gson gson = new Gson();
        List<Session> sessions = gson.fromJson(json, listType);
        List<Session> active = new ArrayList<>();
        for (Session s : sessions) {
            if (!s.getStatus().equals(Session.STATUS_TERMINATING)) {
                active.add(s);
            }
        }

        return active;
    }

    private static Session getSessionWithoutWait(
            final URL sessionURL, final String sessionID, final String expectedState) throws Exception {
        return SessionUtil.getAllSessions(sessionURL).stream()
                .filter(session -> session.getId().equals(sessionID)
                        && session.getStatus().equals(expectedState)
                        && SessionType.fromApplicationStringType(session.getType()) != SessionType.DESKTOP_APP)
                .findFirst()
                .orElse(null);
    }

    static void waitForSessionToTerminate(final URL sessionURL, final String sessionID) throws Exception {
        Session requestedSession = SessionUtil.getSessionWithoutWait(sessionURL, sessionID, Session.STATUS_TERMINATING);
        long currentWaitTime = 0L;
        while (requestedSession != null) {
            LOGGER.info("Waiting for Session " + sessionID + " to terminate.");
            Thread.sleep(SessionUtil.ONE_SECOND);
            currentWaitTime += SessionUtil.ONE_SECOND;

            if (currentWaitTime > SessionUtil.TIMEOUT_WAIT_FOR_SESSION_TERMINATE_MS) {
                throw new TimeoutException("Timed out waiting for session " + sessionID + " and status "
                        + Session.STATUS_TERMINATING + " after " + currentWaitTime + "ms");
            }

            requestedSession = SessionUtil.getSessionWithoutWait(sessionURL, sessionID, Session.STATUS_TERMINATING);
        }

        LOGGER.info("Session " + sessionID + " terminated.");
    }

    static Session waitForSession(final URL sessionURL, final String sessionID) throws Exception {
        final String expectedState = Session.STATUS_RUNNING;
        Session requestedSession = SessionUtil.getSessionWithoutWait(sessionURL, sessionID, expectedState);
        long currentWaitTime = 0L;
        while (requestedSession == null) {
            LOGGER.info("Waiting for Session " + sessionID + " to reach " + expectedState);
            Thread.sleep(SessionUtil.ONE_SECOND);
            currentWaitTime += SessionUtil.ONE_SECOND;

            if (currentWaitTime > SessionUtil.TIMEOUT_WAIT_FOR_SESSION_STARTUP_MS) {
                throw new TimeoutException("Timed out waiting for session " + sessionID + " and status " + expectedState
                        + " after " + currentWaitTime + "ms");
            }

            requestedSession = SessionUtil.getSessionWithoutWait(sessionURL, sessionID, expectedState);
        }

        LOGGER.info("Session " + sessionID + " reached " + expectedState);

        return requestedSession;
    }

    static void verifySession(final Session session, final String expectedSessionType, final String expectedName) {
        Assert.assertNotNull("no session type", session.getType());
        if (session.getType().equals(expectedSessionType)) {
            Assert.assertNotNull("no session ID", session.getId());
            Assert.assertEquals("wrong session name", expectedName, session.getName());
            Assert.assertNotNull("missing connect URL", session.getConnectURL());
            Assert.assertNotNull("missing up since", session.getStartTime());
        }
    }

    private static List<Session> getAllSessions(final URL sessionURL) throws Exception {
        final HttpGet get = new HttpGet(sessionURL, true);
        get.prepare();

        Assert.assertEquals("content-type", "application/json", get.getContentType());
        final String json;
        try (final Writer writer = new StringWriter();
                final BufferedReader reader = new BufferedReader(new InputStreamReader(get.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                writer.write(line);
            }
            writer.flush();
            json = writer.toString();
        }

        final Type listType = new TypeToken<List<Session>>() {}.getType();
        final Gson gson = new Gson();
        return gson.fromJson(json, listType);
    }

    protected static Image getImageOfType(final String type) {
        return SessionUtil.getImagesOfType(type).stream().findFirst().orElseThrow();
    }

    protected static List<Image> getImagesOfType(final String type) {
        final RegistryClient registryClient = new RegistryClient();
        final URL imageServiceURL = registryClient.getServiceURL(
                TestConfiguration.getSkahaServiceID(), Standards.PLATFORM_IMAGE_1, AuthMethod.TOKEN);

        final List<Image> allImagesList = ImagesTest.getImages(imageServiceURL);
        return allImagesList.stream()
                .filter(image -> image.getTypes().contains(type))
                .collect(Collectors.toList());
    }

    protected static Image getDesktopAppImageOfType(final String fuzzySearch) {
        return SessionUtil.getImagesOfType(SessionAction.TYPE_DESKTOP_APP).stream()
                .filter(image -> image.getId().contains(fuzzySearch))
                .findFirst()
                .orElseThrow();
    }
}
