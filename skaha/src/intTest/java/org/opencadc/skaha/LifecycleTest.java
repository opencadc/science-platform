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

import ca.nrc.cadc.auth.SSLUtil;
import ca.nrc.cadc.net.HttpDelete;
import ca.nrc.cadc.net.HttpGet;
import ca.nrc.cadc.net.HttpPost;
import ca.nrc.cadc.reg.client.RegistryClient;
import ca.nrc.cadc.util.FileUtil;
import ca.nrc.cadc.util.Log4jInit;
import ca.nrc.cadc.util.StringUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URL;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.security.auth.Subject;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.Assert;
import org.junit.Test;

/**
 * @author majorb
 *
 */
public class LifecycleTest {
    
    private static final Logger log = Logger.getLogger(LifecycleTest.class);
    public static final String SKAHA_SERVICE_ID = "ivo://cadc.nrc.ca/skaha";
    public static final String PROC_SESSION_STDID = "vos://cadc.nrc.ca~vospace/CADC/std/Proc#sessions-1.0";
    
    static {
        Log4jInit.setLevel("org.opencadc.skaha", Level.INFO);
    }
    
    protected URL sessionURL;
    protected Subject userSubject;
    
    public LifecycleTest() {
        try {
            RegistryClient regClient = new RegistryClient();
            // enable when in registry
            //sessionURL = regClient.getServiceURL(SKAHA_SERVICE_ID, Standards.PROC_SESSIONS_10, AuthMethod.CERT);
            sessionURL = new URL("https://proto.canfar.net/skaha/session");
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
                    
                    // get sessions
                    Skaha skaha = getSessions();
                    Assert.assertTrue("no sessions to start", skaha.sessions.size() == 0);
                    
                    // create desktop session
                    Map<String, Object> params = new HashMap<String, Object>();
                    params.put("name", "intTest");
                    params.put("type", SessionAction.SESSION_TYPE_DESKTOP);
                    HttpPost post = new HttpPost(sessionURL, params, true);
                    post.run();
                    Assert.assertNull("create session error", post.getThrowable());
                    
                    // get sessions
                    skaha = getSessions();
                    Assert.assertTrue("one session", skaha.sessions.size() == 1);
                    SkahaSession session = skaha.sessions.get(0);
                    Assert.assertEquals("session name", "intTest", session.sessionName);
                    Assert.assertEquals("session type", SessionAction.SESSION_TYPE_DESKTOP, session.sessionType);
                    Assert.assertNotNull("session id", session.sessionID);
                    Assert.assertNotNull("connect URL", session.connectURL);
                    Assert.assertNotNull("up since", session.upSince);
                    
                    // create carta session
                    params = new HashMap<String, Object>();
                    params.put("name", "intTest");
                    params.put("type", SessionAction.SESSION_TYPE_CARTA);
                    post = new HttpPost(sessionURL, params, true);
                    post.run();
                    Assert.assertNull("create session error", post.getThrowable());
                    
                    // get sessions
                    skaha = getSessions();
                    Assert.assertTrue("two sessions", skaha.sessions.size() == 2);
                    String desktopSessionID = null;
                    String cartaSessionID = null;
                    for (SkahaSession s : skaha.sessions) {
                        Assert.assertNotNull("session type", s.sessionType);
                        if (s.sessionType.equals(SessionAction.SESSION_TYPE_DESKTOP)) {
                            desktopSessionID = s.sessionID;
                            desktopSessionID = session.sessionID;
                        } else if (s.sessionType.equals(SessionAction.SESSION_TYPE_CARTA)) {
                            cartaSessionID = s.sessionID;
                        } else {
                            throw new AssertionError("invalid session type: " + s.sessionType);
                        }
                        Assert.assertEquals("session name", "intTest", session.sessionName);
                        Assert.assertNotNull("session id", session.sessionID);
                        Assert.assertNotNull("connect URL", session.connectURL);
                        Assert.assertNotNull("up since", session.upSince);
                    }
                    Assert.assertNotNull("no desktop session", desktopSessionID);
                    Assert.assertNotNull("no carta session", cartaSessionID);

                    // delete desktop session
                    HttpDelete delete = new HttpDelete(new URL(sessionURL.toString() + "/" + desktopSessionID), true);
                    delete.run();
                    Assert.assertNull("delete session error", delete.getThrowable());
                    
                    // get sessions
                    skaha = getSessions();
                    Assert.assertTrue("one session", skaha.sessions.size() == 1);
                    session = skaha.sessions.get(0);
                    Assert.assertEquals("session name", "intTest", session.sessionName);
                    Assert.assertEquals("session type", SessionAction.SESSION_TYPE_CARTA, session.sessionType);
                    Assert.assertNotNull("session id", session.sessionID);
                    Assert.assertNotNull("connect URL", session.connectURL);
                    Assert.assertNotNull("up since", session.upSince);
                    
                    // delete carta session
                    delete = new HttpDelete(new URL(sessionURL.toString() + "/" + cartaSessionID), true);
                    delete.run();
                    Assert.assertNull("delete session error", delete.getThrowable());
                    
                    // get sessions
                    skaha = getSessions();
                    Assert.assertTrue("no sessions to start", skaha.sessions.size() == 0);

                    return null;
                }
                
            });
            
        } catch (Exception t) {
            log.error("unexpected throwable", t);
            Assert.fail("unexpected throwable: " + t);
        }
        
    }
    
    private Skaha getSessions() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpGet get = new HttpGet(sessionURL, out);
        get.run();
        Assert.assertNull("get sessions error", get.getThrowable());
        return new Skaha(out.toString());
    }
    
    public class Skaha {
        
        List<SkahaSession> sessions = new ArrayList<SkahaSession>();
        
        public Skaha(String output) {
            if (StringUtil.hasLength(output)) {
                String[] lines = output.split("\n");
                for (String line : lines) {
                    SkahaSession session = new SkahaSession(line);
                    if (session.status.equals("Running")) {
                        sessions.add(session);
                    }
                }
            }
        }
    }
    
    public class SkahaSession {
        
        public SkahaSession(String line) {
            String[] parts = line.split("\t");
            sessionID = parts[0];
            sessionType = parts[1];
            status = parts[2];
            sessionName = parts[3];
            connectURL = parts[4];
            upSince = parts[5];
        }

        public String sessionID;
        public String sessionType;
        public String status;
        public String sessionName;
        public String connectURL;
        public String upSince;
    }

}
