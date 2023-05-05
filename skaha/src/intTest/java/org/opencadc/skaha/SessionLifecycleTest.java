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
public class LifecycleTest {
    
    private static final Logger log = Logger.getLogger(LifecycleTest.class);
    public static final URI SKAHA_SERVICE_ID = URI.create("ivo://cadc.nrc.ca/skaha");
    public static final String PROC_SESSION_STDID = "vos://cadc.nrc.ca~vospace/CADC/std/Proc#sessions-1.0";
    public static final String DESKTOP_IMAGE = "images.canfar.net/skaha/desktop:1.0.2";
    public static final String CARTA_IMAGE = "images.canfar.net/skaha/carta:2.0";
    
    static {
        Log4jInit.setLevel("org.opencadc.skaha", Level.INFO);
    }
    
    protected URL sessionURL;
    protected Subject userSubject;
    
    public LifecycleTest() {
        try {
            RegistryClient regClient = new RegistryClient();
            sessionURL = regClient.getServiceURL(SKAHA_SERVICE_ID, Standards.PROC_SESSIONS_10, AuthMethod.CERT);
            sessionURL = new URL(sessionURL.toString() + "/session");
            log.info("sessions URL: " + sessionURL);
    
            File cert = FileUtil.getFileFromResource("skaha-test.pem", LifecycleTest.class);
            userSubject = SSLUtil.createSubject(cert);
            log.debug("userSubject: " + userSubject);
        } catch (Exception e) {
            log.error("init exception", e);
            throw new RuntimeException("init exception", e);
        }
    }
    
    @Test
    public void testCreateDeleteSessions() {
        try {
            
            Subject.doAs(userSubject, new PrivilegedExceptionAction<Object>() {

                public Object run() throws Exception {
                    
                    // ensure that there is no active session
                    initialize();
                    
                    // create desktop session
                    createSession(DESKTOP_IMAGE);
                    
                    // until issue 4 (https://github.com/opencadc/skaha/issues/4) has been 
                    // addressed, just wait for a bit.
                    TimeUnit.SECONDS.sleep(10);
                    
                    // verify desktop session
                    verifyOneSession(SessionAction.SESSION_TYPE_DESKTOP, "#1");
                    
                    // create carta session
                    createSession(CARTA_IMAGE);
                    
                    TimeUnit.SECONDS.sleep(10);
                    
                    // verify both desktop and carta sessions
                    int count = 0;
                    List<Session> sessions = getSessions();
                    String desktopSessionID = null;
                    String cartaSessionID = null;
                    for (Session s : sessions) {
                        Assert.assertNotNull("session type", s.getType());
                        Assert.assertNotNull("session has no status", s.getStatus());
                        if (s.getStatus().equals("Running")) {
                            if (s.getType().equals(SessionAction.SESSION_TYPE_DESKTOP)) {
                                count++;
                                desktopSessionID = s.getId();
                            } else if (s.getType().equals(SessionAction.SESSION_TYPE_CARTA)) {
                                count++;
                                cartaSessionID = s.getId();
                            } else if (!s.getType().equals(SessionAction.TYPE_DESKTOP_APP)){
                                throw new AssertionError("invalid session type: " + s.getType());
                            }
                            Assert.assertEquals("session name", "inttest", s.getName());
                            Assert.assertNotNull("session id", s.getId());
                            Assert.assertNotNull("connect URL", s.getConnectURL());
                            Assert.assertNotNull("up since", s.getStartTime());
                        }
                    }
                    Assert.assertTrue("two sessions", count == 2);
                    Assert.assertNotNull("no desktop session", desktopSessionID);
                    Assert.assertNotNull("no carta session", cartaSessionID);

                    // delete desktop session
                    deleteSession(sessionURL, desktopSessionID);
                    
                    TimeUnit.SECONDS.sleep(10);
                    
                    // verify remaining carta session
                    verifyOneSession(SessionAction.SESSION_TYPE_CARTA, "#2");
                    
                    // delete carta session
                    deleteSession(sessionURL, cartaSessionID);
                    
                    TimeUnit.SECONDS.sleep(10);
                    
                    // verify that there is no session left
                    count = 0;
                    sessions = getSessions();
                    for (Session s : sessions) {
                        Assert.assertNotNull("session ID", s.getId());
                        if (s.getId().equals(cartaSessionID) || 
                                s.getId().equals(desktopSessionID)) {
                            count++;
                        }
                    }
                    Assert.assertTrue("zero sessions #2", count == 0);

                    return null;
                }
            });
            
        } catch (Exception t) {
            log.error("unexpected throwable", t);
            Assert.fail("unexpected throwable: " + t);
        }
        
    }
    
    private void initialize() throws MalformedURLException {
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
    private void createSession(String image) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("name", "inttest");
        params.put("image", image);
        HttpPost post = new HttpPost(sessionURL, params, false);
        post.run();
        Assert.assertNull("create session error", post.getThrowable());
    }

    private void verifyOneSession(String expectedSessionType, String sessionNumber) {
        int count = 0;
        List<Session> sessions = getSessions();
        for (Session session : sessions) {
            Assert.assertNotNull("no session type", session.getType());
            if (session.getType().equals(expectedSessionType)) {
                Assert.assertNotNull("no session ID", session.getId());
                if (session.getStatus().equals("Running"))  {
                    count++;
                    Assert.assertEquals("session name", "inttest", session.getName());
                    Assert.assertNotNull("connect URL", session.getConnectURL());
                    Assert.assertNotNull("up since", session.getStartTime());
                }
            }
            
        }
        Assert.assertTrue("one session " + sessionNumber, count == 1);
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
    

}
