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
import ca.nrc.cadc.net.HttpPost;
import ca.nrc.cadc.reg.Standards;
import ca.nrc.cadc.reg.client.RegistryClient;
import ca.nrc.cadc.util.Log4jInit;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.security.PrivilegedExceptionAction;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
public class SessionLifecycleTest {

    public static final String PROD_IMAGE_HOST = "images.canfar.net";
    public static final String DEV_IMAGE_HOST = "images-rc.canfar.net";
    private static final Logger log = Logger.getLogger(SessionLifecycleTest.class);
    private static final String HOST_PROPERTY = RegistryClient.class.getName() + ".host";

    static {
        Log4jInit.setLevel("org.opencadc.skaha", Level.INFO);
    }

    protected final URL sessionURL;
    protected final Subject userSubject;
    protected final String imageHost;

    public SessionLifecycleTest() throws Exception {
        // determine image host
        String hostP = System.getProperty(HOST_PROPERTY);
        if (hostP == null || hostP.trim().isEmpty()) {
            throw new IllegalArgumentException("missing server host, check " + HOST_PROPERTY);
        } else {
            hostP = hostP.trim();
            if (hostP.startsWith("rc-")) {
                imageHost = DEV_IMAGE_HOST;
            } else {
                imageHost = PROD_IMAGE_HOST;
            }
        }

        RegistryClient regClient = new RegistryClient();
        final URL sessionServiceURL = regClient.getServiceURL(SessionUtil.getSkahaServiceID(), Standards.PROC_SESSIONS_10, AuthMethod.COOKIE);
        sessionURL = new URL(sessionServiceURL.toString() + "/session");
        log.info("sessions URL: " + sessionURL);

        this.userSubject = SessionUtil.getCurrentUser(sessionURL, false);
        log.debug("userSubject: " + userSubject);
    }

    @Test
    public void testCreateDeleteSessions() throws Exception {
        Subject.doAs(userSubject, (PrivilegedExceptionAction<Object>) () -> {

            // ensure that there is no active session
            initialize();


            // create desktop session
            final String desktopSessionID = createSession("inttest" + SessionAction.SESSION_TYPE_DESKTOP,
                                                          SessionUtil.getImageOfType(SessionAction.SESSION_TYPE_DESKTOP).getId());

            final Session desktopSession = SessionUtil.waitForSession(sessionURL, desktopSessionID, Session.STATUS_RUNNING);
            verifySession(desktopSession, SessionAction.SESSION_TYPE_DESKTOP, "inttest" + SessionAction.SESSION_TYPE_DESKTOP);

            // create carta session
            final String cartaSessionID = createSession("inttest" + SessionAction.SESSION_TYPE_CARTA,
                                                        SessionUtil.getImageOfType(SessionAction.SESSION_TYPE_CARTA).getId());
            Session cartaSession = SessionUtil.waitForSession(sessionURL, cartaSessionID, Session.STATUS_RUNNING);
            verifySession(desktopSession, SessionAction.SESSION_TYPE_CARTA, "inttest" + SessionAction.SESSION_TYPE_CARTA);

            Assert.assertNotNull("CARTA session not running.", cartaSession);
            Assert.assertEquals("CARTA session name is wrong", "inttest" + SessionAction.SESSION_TYPE_CARTA, cartaSession.getName());
            Assert.assertNotNull("CARTA session id is null", cartaSession.getId());
            Assert.assertNotNull("CARTA connect URL is null", cartaSession.getConnectURL());
            Assert.assertNotNull("CARTA up since is null", cartaSession.getStartTime());

            // verify both desktop and carta sessions
            Assert.assertNotNull("no desktop session", desktopSessionID);
            Assert.assertNotNull("no carta session", cartaSessionID);

            // delete desktop session
            SessionUtil.deleteSession(sessionURL, desktopSessionID);

            cartaSession = SessionUtil.waitForSession(sessionURL, cartaSessionID, Session.STATUS_RUNNING);
            // verify remaining carta session
            Assert.assertNotNull("CARTA Session should still be running.", cartaSession);

            // delete carta session
            SessionUtil.deleteSession(sessionURL, cartaSessionID);

            return null;
        });
    }

    private void initialize() throws Exception {
        List<Session> sessions = getSessions();
        for (Session session : sessions) {
            // skip dekstop-app, deletion of desktop-app is not supported
            if (!session.getType().equals(SessionAction.TYPE_DESKTOP_APP)) {
                SessionUtil.deleteSession(sessionURL, session.getId());
            }
        }

        int count = 0;
        sessions = getSessions();
        for (Session s : sessions) {
            if (!s.getType().equals(SessionAction.TYPE_DESKTOP_APP)) {
                count++;
            }
        }
        Assert.assertEquals("zero sessions #1", 0, count);
    }

    private String createSession(final String name, String image) {
        Map<String, Object> params = new HashMap<>();
        params.put("name", name);
        params.put("image", image);
        params.put("cores", 1);
        params.put("ram", 1);
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        HttpPost post = new HttpPost(sessionURL, params, outputStream);
        post.setFollowRedirects(false);
        post.run();
        Assert.assertNull("create session error", post.getThrowable());

        return outputStream.toString().trim();
    }

    private void verifySession(final Session session, final String expectedSessionType, final String expectedName) {
        Assert.assertNotNull("no session type", session.getType());
        if (session.getType().equals(expectedSessionType)) {
            Assert.assertNotNull("no session ID", session.getId());
            Assert.assertEquals("wrong session name", expectedName, session.getName());
            Assert.assertNotNull("missing connect URL", session.getConnectURL());
            Assert.assertNotNull("missing up since", session.getStartTime());
        }
    }

    private List<Session> getSessions() throws Exception {
        return SessionUtil.getSessions(sessionURL, Session.STATUS_TERMINATING, Session.STATUS_SUCCEEDED);
    }
}
