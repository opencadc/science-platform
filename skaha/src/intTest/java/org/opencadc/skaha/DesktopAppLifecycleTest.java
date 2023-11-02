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
 ************************************************************************
 */

package org.opencadc.skaha;

import ca.nrc.cadc.auth.AuthMethod;
import ca.nrc.cadc.auth.AuthorizationToken;
import ca.nrc.cadc.net.HttpDelete;
import ca.nrc.cadc.net.HttpGet;
import ca.nrc.cadc.net.HttpPost;
import ca.nrc.cadc.net.NetUtil;
import ca.nrc.cadc.reg.Standards;
import ca.nrc.cadc.reg.client.RegistryClient;
import ca.nrc.cadc.util.FileUtil;
import ca.nrc.cadc.util.Log4jInit;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.security.auth.Subject;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.Assert;
import org.junit.Test;
import org.opencadc.skaha.session.Session;
import org.opencadc.skaha.session.SessionAction;

/**
 * @author majorb
 */
public class DesktopAppLifecycleTest {

    private static final Logger log = Logger.getLogger(DesktopAppLifecycleTest.class);
    public static final URI SKAHA_SERVICE_ID = URI.create("ivo://cadc.nrc.ca/skaha");
    public static final String TERMINAL_IMAGE = "images-rc.canfar.net/skaha/terminal:1.1.2";

    private static final long DEFAULT_TIMEOUT_WAIT_FOR_SESSION_STARTUP_MS = 25 * 1000;

    static {
        Log4jInit.setLevel("org.opencadc.skaha", Level.INFO);
    }

    protected final URL sessionURL;
    protected URL desktopAppURL;
    protected final Subject userSubject;

    public DesktopAppLifecycleTest() {
        try {
            RegistryClient regClient = new RegistryClient();
            final URL sessionServiceURL = regClient.getServiceURL(SKAHA_SERVICE_ID, Standards.PROC_SESSIONS_10,
                                                                  AuthMethod.TOKEN);
            sessionURL = new URL(sessionServiceURL.toString() + "/session");
            log.info("sessions URL: " + sessionURL);

            final File bearerTokenFile = FileUtil.getFileFromResource("skaha-test.token",
                                                                      ImagesTest.class);
            final String bearerToken = new String(Files.readAllBytes(bearerTokenFile.toPath()));
            userSubject = new Subject();
            userSubject.getPublicCredentials().add(
                    new AuthorizationToken("Bearer", bearerToken.replaceAll("\n", ""),
                                           List.of(NetUtil.getDomainName(sessionURL))));
            log.debug("userSubject: " + userSubject);
        } catch (Exception e) {
            log.error("init exception", e);
            throw new RuntimeException("init exception", e);
        }
    }

    @Test
    public void testCreateDeleteDesktopApp() throws Exception {
        Subject.doAs(userSubject, (PrivilegedExceptionAction<Object>) () -> {
            // ensure that there is no active session
            initialize();

            // create desktop session
            final Session session = SessionUtil.createSession(
                    SessionUtil.getImageOfType(SessionAction.SESSION_TYPE_DESKTOP).getId(), sessionURL);
            desktopAppURL = new URL(sessionURL.toString() + "/" + session.getId() + "/app");
            log.info("desktop-app URL: " + desktopAppURL);

            // until issue 4 (https://github.com/opencadc/skaha/issues/4) has been
            // addressed, just wait for a bit.
            long millisecondCount = 0L;
            final int pollIntervalInSeconds = 3;
            while (SessionUtil.getSessionsOfType(sessionURL, SessionAction.SESSION_TYPE_DESKTOP,
                                                 Session.STATUS_TERMINATING, Session.STATUS_SUCCEEDED).size() != 1
                   && millisecondCount < DesktopAppLifecycleTest.DEFAULT_TIMEOUT_WAIT_FOR_SESSION_STARTUP_MS) {
                TimeUnit.SECONDS.sleep(pollIntervalInSeconds);
                millisecondCount += pollIntervalInSeconds * 1000;
            }

            // verify desktop session
            verifyOneSession(SessionAction.SESSION_TYPE_DESKTOP, "#1", session.getName());

            // create a terminal desktop-app
            final Session appSession =
                    SessionUtil.createDesktopAppSession(
                            SessionUtil.getDesktopAppImageOfType("/skaha/terminal").getId(), desktopAppURL);

            TimeUnit.SECONDS.sleep(10);

            // verify desktop session and desktop-app
            int sessionCount = 0;
            int appCount = 0;
            String desktopSessionID = null;
            String desktopAppID = null;
            for (Session s : getSessions()) {
                Assert.assertNotNull("session type", s.getType());
                Assert.assertNotNull("session has no status", s.getStatus());
                if (s.getStatus().equals("Running") && s.getType().equals(SessionAction.SESSION_TYPE_DESKTOP)) {
                    sessionCount++;
                    desktopSessionID = s.getId();
                    Assert.assertEquals("session name", session.getName(), s.getName());
                } else if (Arrays.asList("Succeeded", "Running").contains(s.getStatus())
                           && s.getType().equals(SessionAction.TYPE_DESKTOP_APP)) {
                    appCount++;
                    desktopAppID = s.getAppId();
                    Assert.assertNotNull("app id", s.getAppId());
                } else {
                    throw new AssertionError("invalid session type: " + s.getType());
                }

                Assert.assertNotNull("session id", s.getId());
                Assert.assertNotNull("connect URL", s.getConnectURL());
                Assert.assertNotNull("up since", s.getStartTime());
            }
            Assert.assertEquals("one session", 1, sessionCount);
            Assert.assertEquals("one desktop-app", 1, appCount);
            Assert.assertNotNull("no desktop session", desktopSessionID);
            Assert.assertNotNull("no desktop app", desktopAppID);
            Assert.assertEquals("Wrong app session ID", appSession.getAppId(), desktopAppID);

            // get desktop-app
            List<Session> desktopApps = getAllDesktopApp();
            Assert.assertFalse("no desktop-app", desktopApps.isEmpty());
            Assert.assertEquals("more than one desktop-app", 1, desktopApps.size());

            // delete desktop-app
            deleteDesktopApp(desktopAppURL, desktopAppID);
            TimeUnit.SECONDS.sleep(10);
            desktopApps = getAllDesktopApp();
            Assert.assertTrue("should have no active desktop-app", desktopApps.isEmpty());

            // create desktop-app specifying resources
            String cores = "1";
            String ram = "4";
            desktopAppID = createDesktopApp(SessionUtil.getDesktopAppImageOfType("/skaha/terminal").getId(),
                                            cores, ram);
            TimeUnit.SECONDS.sleep(10);
            desktopApps = getAllDesktopApp();
            Assert.assertFalse("no desktop-app", desktopApps.isEmpty());
            Assert.assertEquals("more than one desktop-app", 1, desktopApps.size());
            Session desktopApp = desktopApps.get(0);
            Assert.assertEquals("wrong number of cores", cores, desktopApp.getRequestedCPUCores());
            Assert.assertEquals("wrong amount of ram", ram + "G", desktopApp.getRequestedRAM());

            // delete desktop-app
            deleteDesktopApp(desktopAppURL, desktopAppID);
            TimeUnit.SECONDS.sleep(10);
            desktopApps = getAllDesktopApp();
            Assert.assertTrue("should have no active desktop-app", desktopApps.isEmpty());

            // verify remaining desktop session
            verifyOneSession(SessionAction.SESSION_TYPE_DESKTOP, "#2", session.getName());

            // delete desktop session
            deleteSession(sessionURL, desktopSessionID);

            TimeUnit.SECONDS.sleep(10);

            // verify that there is no session left
            sessionCount = 0;
            for (Session s : getSessions()) {
                Assert.assertNotNull("session ID", s.getId());
                if (s.getId().equals(desktopSessionID)) {
                    sessionCount++;
                }
            }
            Assert.assertEquals("zero sessions #2", 0, sessionCount);

            return null;
        });
    }

    private void initialize() throws Exception {
        List<Session> sessions = getSessions();
        boolean wait = false;
        for (Session session : sessions) {
            if (session.getType().equals(SessionAction.TYPE_DESKTOP_APP)) {
                // delete desktop-app
                wait = true;
                String sessionID = session.getId();
                desktopAppURL = new URL(sessionURL.toString() + "/" + sessionID + "/app");
                deleteDesktopApp(desktopAppURL, session.getAppId());
            } else {
                // delete session
                wait = true;
                deleteSession(sessionURL, session.getId());
            }
        }

        if (wait) {
            TimeUnit.SECONDS.sleep(10);
        }

        sessions = getSessions();
        Assert.assertEquals("zero sessions #1", 0, sessions.size());
    }

    private String createDesktopApp(Map<String, Object> params) throws Exception {
        final HttpPost post = new HttpPost(desktopAppURL, params, false);
        post.prepare();

        try (final BufferedReader reader = new BufferedReader(new InputStreamReader(post.getInputStream()))) {
            return reader.readLine();
        }
    }

    private String createDesktopApp(String image) throws Exception {
        return createDesktopApp(image, "1", "1");
    }

    private String createDesktopApp(String image, String cores, String ram) throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("cores", cores);
        params.put("ram", ram);
        params.put("image", image);
        params.put("cmd", "sleep");
        params.put("args", "260");
        return createDesktopApp(params);
    }

    private void verifyOneSession(String expectedSessionType, String sessionNumber, String expectedSessionName)
            throws Exception {
        int count = 0;
        List<Session> sessions = getSessions();
        for (Session session : sessions) {
            Assert.assertNotNull("no session type", session.getType());
            if (session.getType().equals(expectedSessionType)) {
                Assert.assertNotNull("no session ID", session.getId());
                if (session.getStatus().equals("Running")) {
                    count++;
                    Assert.assertEquals("session name", expectedSessionName, session.getName());
                    Assert.assertNotNull("connect URL", session.getConnectURL());
                    Assert.assertNotNull("up since", session.getStartTime());
                }
            }

        }
        Assert.assertEquals("one session " + sessionNumber, 1, count);
    }

    private void deleteSession(URL sessionURL, String sessionID) throws Exception {
        HttpDelete delete = new HttpDelete(new URL(sessionURL.toString() + "/" + sessionID), true);
        delete.prepare();
    }

    private void deleteDesktopApp(URL desktopAppURL, String appID) throws Exception {
        HttpDelete delete = new HttpDelete(new URL(desktopAppURL.toString() + "/" + appID), true);
        delete.prepare();
    }

    private List<Session> getAllDesktopApp() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
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

    private List<Session> getSessions() throws Exception {
        return SessionUtil.getSessions(sessionURL, Session.STATUS_TERMINATING);
    }
}
