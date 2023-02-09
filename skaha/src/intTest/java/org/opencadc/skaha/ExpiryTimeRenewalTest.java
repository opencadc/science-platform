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

package org.opencadc.skaha;

import ca.nrc.cadc.auth.AuthMethod;
import ca.nrc.cadc.auth.SSLUtil;
import ca.nrc.cadc.net.HttpDelete;
import ca.nrc.cadc.net.HttpGet;
import ca.nrc.cadc.net.HttpPost;
import ca.nrc.cadc.reg.Standards;
import ca.nrc.cadc.reg.client.RegistryClient;
import ca.nrc.cadc.util.FileUtil;
import ca.nrc.cadc.util.Log4jInit;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.security.PrivilegedExceptionAction;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
 *
 */
public class ExpiryTimeRenewalTest {
    
    private static final Logger log = Logger.getLogger(ExpiryTimeRenewalTest.class);
    public static final URI SKAHA_SERVICE_ID = URI.create("ivo://cadc.nrc.ca/skaha");
    public static final String PROC_SESSION_STDID = "vos://cadc.nrc.ca~vospace/CADC/std/Proc#sessions-1.0";
    public static final String DESKTOP_IMAGE = "images.canfar.net/skaha/desktop:1.0.2";
    public static final String TERMINAL_IMAGE = "images.canfar.net/skaha/terminal:1.1.2";
    public static final String CARTA_IMAGE = "images.canfar.net/skaha/carta:2.0";
    public static final int SLEEP_TIME = 10;
    
    static {
        Log4jInit.setLevel("org.opencadc.skaha", Level.INFO);
    }
    
    protected URL sessionURL;
    protected Subject userSubject;
    
    public ExpiryTimeRenewalTest() {
        try {
            RegistryClient regClient = new RegistryClient();
            sessionURL = regClient.getServiceURL(SKAHA_SERVICE_ID, Standards.PROC_SESSIONS_10, AuthMethod.CERT);
            sessionURL = new URL(sessionURL.toString() + "/session");
            log.info("sessions URL: " + sessionURL);
    
            File cert = FileUtil.getFileFromResource("skaha-test.pem", ExpiryTimeRenewalTest.class);
            userSubject = SSLUtil.createSubject(cert);
            log.debug("userSubject: " + userSubject);
        } catch (Exception e) {
            log.error("init exception", e);
            throw new RuntimeException("init exception", e);
        }
    }
    
    @Test
    public void testRenewCARTA() {
        try {
            Subject.doAs(userSubject, new PrivilegedExceptionAction<Object>() {

                public Object run() throws Exception {
                    
                    // ensure that there is no active session
                    initialize();
                    
                    // create carta session
                    createSession(CARTA_IMAGE);
                    
                    TimeUnit.SECONDS.sleep(SLEEP_TIME);
                    
                    // get time to live (start time - stop time) before renewal
                    int count = 0;
                    String cartaSessionID = null;
                    long timeToLive = 0L;
                    List<Session> sessions = getSessions();
                    for (Session session : sessions) {
                        Assert.assertNotNull("session type", session.getType());
                        if (SessionAction.SESSION_TYPE_CARTA.equals(session.getType())) {
                            Assert.assertNotNull("no carta session status", session.getStatus());
                            if (session.getStatus().equals("Running")) {
                                count++;
                                cartaSessionID = session.getId();
                                Assert.assertNotNull("no carta session", cartaSessionID);
                                Instant startTime = Instant.parse(session.getStartTime());
                                Instant expiryTime = Instant.parse(session.getExpiryTime());
                                timeToLive = startTime.until(expiryTime, ChronoUnit.SECONDS);
                            }
                        }
                    }
                    Assert.assertTrue("one sessions", count == 1);
    
                    //renew session
                    renewSession(sessionURL, cartaSessionID);
                    
                    // get time to live (start time - stop time) after renewal
                    count = 0;
                    long timeToLiveAfterRenewal = 0L;
                    sessions = getSessions();
                    for (Session session : sessions) {
                        Assert.assertNotNull("session type", session.getType());
                        if (SessionAction.SESSION_TYPE_CARTA.equals(session.getType())) {
                            Assert.assertNotNull("no carta session status", session.getStatus());
                            if (session.getStatus().equals("Running")) {
                                count++;
                                Assert.assertNotNull("no carta session", session.getId());
                                Assert.assertEquals("wrong session", cartaSessionID, session.getId());
                                Instant startTime = Instant.parse(session.getStartTime());
                                Instant expiryTime = Instant.parse(session.getExpiryTime());
                                timeToLiveAfterRenewal = startTime.until(expiryTime, ChronoUnit.SECONDS);
                            }
                        }
                    }
                    Assert.assertTrue("one sessions", count == 1);
    
                    // Pre-condition: activeDeadlineSeconds == skaha.sessionexpiry
                    // If the pre-condition has changed, the conditional code below needs to be updated
                    long changedTime = timeToLiveAfterRenewal - timeToLive;
                    if (!(changedTime > SLEEP_TIME) && (changedTime < SLEEP_TIME * 2)) {
                        // renew failed
                        Assert.fail("activeDeadlineSeconds and/or skaha.sessionexpiry for a CARTA session has been changed, please update the test.");
                    }

                    // delete carta session
                    deleteSession(sessionURL, cartaSessionID);
                    
                    TimeUnit.SECONDS.sleep(10);
                    
                    // verify that there is no session left
                    verifyNoSession(cartaSessionID, SessionAction.SESSION_TYPE_CARTA);

                    return null;
                }
            });
            
        } catch (Exception t) {
            log.error("unexpected throwable", t);
            Assert.fail("unexpected throwable: " + t);
        }
    }
    
    @Test
    public void testRenewHeadless() {
        try {
            
            Subject.doAs(userSubject, new PrivilegedExceptionAction<Object>() {

                public Object run() throws Exception {
                    
                    // ensure that there is no active session
                    initialize();
                    
                    // create headless session
                    createHeadlessSession(TERMINAL_IMAGE);
                    
                    TimeUnit.SECONDS.sleep(SLEEP_TIME);
                    
                    // get time to live (start time - stop time) before renewal
                    String headlessSessionID = null;
                    Instant headlessExpiryTime = null;
                    int count = 0;
                    List<Session> sessions = getSessions();
                    for (Session s : sessions) {
                        Assert.assertNotNull("session type", s.getType());
                        if (SessionAction.SESSION_TYPE_HEADLESS.equals(s.getType())) {
                            Assert.assertNotNull("no headless session status", s.getStatus());
                            if (s.getStatus().equals("Running")) {
                                count++;
                                headlessSessionID = s.getId();
                                Assert.assertNotNull("no headless session", headlessSessionID);
                                headlessExpiryTime = Instant.parse(s.getExpiryTime());
                            }
                        }
                    }
                    Assert.assertTrue("one session", count == 1);
                    
                    //renew session
                    renewSession(sessionURL, headlessSessionID);
                    
                    // get time to live (start time - stop time) after renewal
                    count = 0;
                    sessions = getSessions();
                    Instant headlessExpiryTimeAfterRenewal = null;
                    for (Session s : sessions) {
                        Assert.assertNotNull("session type", s.getType());
                        if (SessionAction.SESSION_TYPE_HEADLESS.equals(s.getType())) {
                            Assert.assertNotNull("no headless session status", s.getStatus());
                            if (s.getStatus().equals("Running")) {
                                count++;
                                Assert.assertNotNull("no headless session", s.getId());
                                Assert.assertEquals("different headless sessions", headlessSessionID, s.getId());
                                headlessExpiryTimeAfterRenewal = Instant.parse(s.getExpiryTime());
                            }
                        }
                    }
                    Assert.assertTrue("one session", count == 1);
                    
                    // Pre-condition: activeDeadlineSeconds > skaha.sessionexpiry
                    // If the pre-condition has changed, this test needs to be updated
                    Assert.assertEquals("headless session was renewed", headlessExpiryTime, headlessExpiryTimeAfterRenewal);

                    // delete headless session
                    deleteSession(sessionURL, headlessSessionID);
                    
                    TimeUnit.SECONDS.sleep(10);
                    
                    // verify that there is no session left
                    verifyNoSession(headlessSessionID, SessionAction.SESSION_TYPE_HEADLESS);

                    return null;
                }
            });
            
        } catch (Exception t) {
            log.error("unexpected throwable", t);
            Assert.fail("unexpected throwable: " + t);
        }
    }
    
    @Test
    public void testRenewDesktop() {
        try {
            
            Subject.doAs(userSubject, new PrivilegedExceptionAction<Object>() {

                public Object run() throws Exception {
                    
                    // ensure that there is no active session
                    initialize();
                    
                    // create desktop session
                    createSession(DESKTOP_IMAGE);
                    
                    TimeUnit.SECONDS.sleep(SLEEP_TIME);
                    
                    // get time to live (start time - stop time) before renewal
                    int count = 0;
                    String desktopSessionID = null;
                    long desktopTimeToLive = 0L;
                    List<Session> sessions = getSessions();
                    for (Session s : sessions) {
                        Assert.assertNotNull("session type", s.getType());
                        if (s.getType().equals(SessionAction.SESSION_TYPE_DESKTOP)) {
                            Assert.assertNotNull("no desktop session status", s.getStatus());
                            if (s.getStatus().equals("Running")) {
                                count++;
                                Assert.assertNotNull("no desktop session", s.getId());
                                desktopSessionID = s.getId();
                                Instant startTime = Instant.parse(s.getStartTime());
                                Instant expiryTime = Instant.parse(s.getExpiryTime());
                                desktopTimeToLive = startTime.until(expiryTime, ChronoUnit.SECONDS);
                            }
                        }
                    }
                    Assert.assertTrue("one session", count == 1);
                    
                    // create desktop app
                    URL desktopAppURL = new URL(sessionURL.toString() + "/" + desktopSessionID + "/app");
                    createApp(TERMINAL_IMAGE, desktopAppURL);

                    // get time to live (start time - stop time) before renewal
                    count = 0;
                    long desktopAppTimeToLive = 0L;
                    sessions = getSessions();
                    for (Session session : sessions) {
                        Assert.assertNotNull("no desktop session", session.getId());
                        if (session.getId().equals(desktopSessionID)) {
                            count++;
                            Assert.assertNotNull("session type", session.getType());
                            if (session.getType().equals(SessionAction.TYPE_DESKTOP_APP)) {
                                Instant startTime = Instant.parse(session.getStartTime());
                                Instant expiryTime = Instant.parse(session.getExpiryTime());
                                desktopAppTimeToLive = startTime.until(expiryTime, ChronoUnit.SECONDS);
                            }
                        }
                    }
                
                    // verify that we found the desktop-app
                    Assert.assertTrue("two sessions", count == 2);
                    Assert.assertTrue("failed to calculate desktop app time-to-live", desktopAppTimeToLive != 0L);

                    TimeUnit.SECONDS.sleep(SLEEP_TIME);
                    
                    //renew desktop session, the associated desktop-app should also be renewed
                    renewSession(sessionURL, desktopSessionID);
                    
                    // get time to live (start time - stop time) after renewal
                    count = 0;
                    long desktopTimeToLiveAfterRenewal = 0L;
                    long desktopAppTimeToLiveAfterRenewal = 0L;
                    sessions = getSessions();
                    for (Session session : sessions) {
                        Assert.assertNotNull("no desktop session", session.getId());
                        if (session.getId().equals(desktopSessionID)) {
                            count++;
                            Assert.assertNotNull("session type", session.getType());
                            if (session.getType().equals(SessionAction.SESSION_TYPE_DESKTOP)) {
                                Instant startTime = Instant.parse(session.getStartTime());
                                Instant expiryTime = Instant.parse(session.getExpiryTime());
                                desktopTimeToLiveAfterRenewal = startTime.until(expiryTime, ChronoUnit.SECONDS);
                            } else if (session.getType().equals(SessionAction.TYPE_DESKTOP_APP)) {
                                Instant startTime = Instant.parse(session.getStartTime());
                                Instant expiryTime = Instant.parse(session.getExpiryTime());
                                desktopAppTimeToLiveAfterRenewal = startTime.until(expiryTime, ChronoUnit.SECONDS);
                            } else {
                                throw new AssertionError("invalid session type: " + session.getType());
                            }
                        }
                    }
                        
                    // verify that we found the renewed desktop and desktop-app
                    Assert.assertTrue("two sessions", count == 2);
                    Assert.assertTrue("failed to calculate the renewed desktop time-to-live", desktopAppTimeToLiveAfterRenewal != 0L);
                    Assert.assertTrue("failed to calculate the renewed desktop app time-to-live", desktopAppTimeToLiveAfterRenewal != 0L);

                    // Pre-condition: activeDeadlineSeconds == skaha.sessionexpiry
                    // If the pre-condition has changed, the conditional code below needs to be updated
                    long desktopChangedTime = desktopTimeToLiveAfterRenewal - desktopTimeToLive;
                    if (!((desktopChangedTime > SLEEP_TIME * 2) && (desktopChangedTime < SLEEP_TIME * 4))) {
                        // renew failed
                        Assert.fail("if activeDeadlineSeconds and/or skaha.sessionexpiry for a Desktop session has been changed, please update this test.");
                    }
                    long desktopAppChangedTime = desktopAppTimeToLiveAfterRenewal - desktopAppTimeToLive;
                    if (!((desktopAppChangedTime > SLEEP_TIME * 1) && (desktopAppChangedTime < SLEEP_TIME * 2))) {
                        // renew failed
                        Assert.fail("if activeDeadlineSeconds and/or skaha.sessionexpiry for a Desktop app has been changed, please update this test.");
                    }

                    // delete desktop session, no need to delete the desktop-app
                    deleteSession(sessionURL, desktopSessionID);
                    
                    TimeUnit.SECONDS.sleep(10);
                    
                    // verify that there is no session left
                    verifyNoSession(desktopSessionID, SessionAction.SESSION_TYPE_DESKTOP);

                    return null;
                }
            });
            
        } catch (Exception t) {
            log.error("unexpected throwable", t);
            Assert.fail("unexpected throwable: " + t);
        }
    }
    
    private void initialize() throws MalformedURLException, InterruptedException {
        List<Session> sessions = getSessions();
        for (Session session : sessions) {
            // skip dekstop-app, deletion of desktop-app is not supported
            if (!session.getType().equals(SessionAction.TYPE_DESKTOP_APP)) {
                deleteSession(sessionURL, session.getId());
            }
        }

        int count = 0;
        sessions = getSessions();
        for (Session s : sessions) {
            if (!s.getType().equals(SessionAction.TYPE_DESKTOP_APP)) {
                count++;
            }
        }
        Assert.assertTrue("zero sessions #1", count == 0);
    }
    private void createApp(String image, URL appURL) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("name", "inttest");
        params.put("image", image);
        HttpPost post = new HttpPost(appURL, params, false);
        post.run();
        Assert.assertNull("create session error", post.getThrowable());
    }

    private void createSession(String image) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("name", "inttest");
        params.put("image", image);
        HttpPost post = new HttpPost(sessionURL, params, false);
        post.run();
        Assert.assertNull("create session error", post.getThrowable());
    }

    private void createHeadlessSession(String image) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("name", "inttest");
        params.put("image", image);
        params.put("cmd", "sleep");
        params.put("args", "60");
        HttpPost post = new HttpPost(sessionURL, params, false);
        post.run();
        Assert.assertNull("create session error", post.getThrowable());
    }

    private void deleteSession(URL sessionURL, String sessionID) throws MalformedURLException {
        HttpDelete delete = new HttpDelete(new URL(sessionURL.toString() + "/" + sessionID), true);
        delete.run();
        Assert.assertNull("delete session error", delete.getThrowable());
    }
    
    private List<Session> getSessions() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpGet get = new HttpGet(sessionURL, out);
        get.run();
        Assert.assertNull("get sessions error", get.getThrowable());
        Assert.assertEquals("content-type", "application/json", get.getContentType());
        String json = out.toString();
        Type listType = new TypeToken<List<Session>>(){}.getType();
        Gson gson = new Gson();
        List<Session> sessions = gson.fromJson(json, listType);
        List<Session> active = new ArrayList<Session>();
        for (Session s : sessions) {
            if (!s.getStatus().equals(Session.STATUS_TERMINATING)) {
                active.add(s);
            }
        }
        return active;
    }
    
    private void renewSession(URL sessionURL, String sessionID) throws MalformedURLException {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("action", "renew");
        HttpPost post = new HttpPost(new URL(sessionURL.toString() + "/" + sessionID), params, false);
        post.run();
        Assert.assertNull("renew session error", post.getThrowable());
    }

    private void verifyNoSession(String id, String type) {
        int count = 0;
        List<Session> sessions = getSessions();
        for (Session session : sessions) {
            Assert.assertNotNull("no session", session.getId());
            // only count the specified session
            if ((session.getId().equals(id)) && (session.getType().equals(type))) {
                count++;
            }
        }
        Assert.assertTrue("zero session", count == 0);
    }
}
