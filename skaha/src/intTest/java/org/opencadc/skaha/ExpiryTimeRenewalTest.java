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
import ca.nrc.cadc.net.HttpDelete;
import ca.nrc.cadc.net.HttpPost;
import ca.nrc.cadc.reg.Standards;
import ca.nrc.cadc.reg.client.RegistryClient;
import ca.nrc.cadc.util.Log4jInit;

import java.net.MalformedURLException;
import java.net.URL;
import java.security.PrivilegedExceptionAction;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
public class ExpiryTimeRenewalTest {

    private static final Logger log = Logger.getLogger(ExpiryTimeRenewalTest.class);
    private static final String HOST_PROPERTY = RegistryClient.class.getName() + ".host";
    public static final String CARTA_IMAGE_SUFFIX = "/skaha/carta:3.0";
    public static final String PROD_IMAGE_HOST = "images.canfar.net";
    public static final String DEV_IMAGE_HOST = "images-rc.canfar.net";
    public static final int SLEEP_TIME_SECONDS = 5;

    static {
        Log4jInit.setLevel("org.opencadc.skaha", Level.INFO);
    }

    protected final URL sessionURL;
    protected final Subject userSubject;
    protected final String imageHost;

    public ExpiryTimeRenewalTest() throws Exception {
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
        final URL sessionServiceURL = regClient.getServiceURL(SessionUtil.getSkahaServiceID(), Standards.PROC_SESSIONS_10, AuthMethod.TOKEN);
        this.sessionURL = new URL(sessionServiceURL.toString() + "/session");
        log.info("sessions URL: " + sessionURL);

        this.userSubject = SessionUtil.getCurrentUser(sessionURL, false);
        log.debug("userSubject: " + userSubject);
    }

    @Test
    public void testRenewCARTA() throws Exception {
        Subject.doAs(userSubject, (PrivilegedExceptionAction<Void>) () -> {
            // ensure that there is no active session
            initialize();

            // create carta session
            final String cartaSessionID = SessionUtil.createSession(this.sessionURL, "inttest-" + SessionAction.SESSION_TYPE_CARTA,
                                                                    imageHost + CARTA_IMAGE_SUFFIX);
            Session cartaSession = SessionUtil.waitForSession(this.sessionURL, cartaSessionID, Session.STATUS_RUNNING);

            // Sleep to force time to pass before renewal
            TimeUnit.SECONDS.sleep(SLEEP_TIME_SECONDS);

            final Instant expiryTime = Instant.parse(cartaSession.getExpiryTime());
            final Instant startTime = Instant.parse(cartaSession.getStartTime());
            final long timeToLive = startTime.until(expiryTime, ChronoUnit.SECONDS);

            // renew session
            renewSession(sessionURL, cartaSessionID);

            cartaSession = SessionUtil.waitForSession(this.sessionURL, cartaSessionID, Session.STATUS_RUNNING);
            final Instant expiryTimeAfterRenewal = Instant.parse(cartaSession.getExpiryTime());
            final Instant startTimeAfterRenewal = Instant.parse(cartaSession.getStartTime());
            final long timeToLiveAfterRenewal = startTimeAfterRenewal.until(expiryTimeAfterRenewal, ChronoUnit.SECONDS);

            // Pre-condition: activeDeadlineSeconds == skaha.sessionexpiry
            // If the pre-condition has changed, the conditional code below needs to be updated
            long changedTime = timeToLiveAfterRenewal - timeToLive;
            if (changedTime <= SLEEP_TIME_SECONDS) {
                // renew failed
                Assert.fail("activeDeadlineSeconds and/or skaha.sessionexpiry for a CARTA session has been changed, please update the test.");
            }

            SessionUtil.deleteSession(this.sessionURL, cartaSessionID);

            return null;
        });
    }

    @Test
    public void testRenewHeadless() throws Exception {
        Subject.doAs(userSubject, (PrivilegedExceptionAction<Void>) () -> {

            // ensure that there is no active session
            initialize();

            // create headless session
            final String headlessSessionID = SessionUtil.createHeadlessSession(
                    SessionUtil.getDesktopAppImageOfType("/skaha/terminal").getId(), this.sessionURL);
            Session headlessSession = SessionUtil.waitForSession(this.sessionURL, headlessSessionID, Session.STATUS_RUNNING);
            final Instant headlessExpiryTime = Instant.parse(headlessSession.getExpiryTime());

            //renew session
            renewSession(sessionURL, headlessSessionID);

            headlessSession = SessionUtil.waitForSession(this.sessionURL, headlessSessionID, Session.STATUS_RUNNING);
            final Instant headlessExpiryTimeAfterRenewal = Instant.parse(headlessSession.getExpiryTime());

            // Pre-condition: activeDeadlineSeconds > skaha.sessionexpiry
            // If the pre-condition has changed, this test needs to be updated
            Assert.assertEquals("headless session was renewed", headlessExpiryTime, headlessExpiryTimeAfterRenewal);

            // delete headless session
            SessionUtil.deleteSession(sessionURL, headlessSessionID);

            return null;
        });
    }

    @Test
    public void testRenewDesktop() throws Exception {
        Subject.doAs(userSubject, (PrivilegedExceptionAction<Object>) () -> {
            // ensure that there is no active session
            initialize();

            // create desktop session
            final String desktopSessionID = SessionUtil.createSession(this.sessionURL, "inttest-" + SessionAction.SESSION_TYPE_DESKTOP,
                                                                      SessionUtil.getImageOfType(SessionAction.SESSION_TYPE_DESKTOP).getId());
            SessionUtil.waitForSession(this.sessionURL, desktopSessionID, Session.STATUS_RUNNING);

            // create desktop app
            final URL desktopAppURL = new URL(sessionURL.toString() + "/" + desktopSessionID + "/app");
            final String desktopApplicationSessionID =
                SessionUtil.createDesktopAppSession(SessionUtil.getDesktopAppImageOfType("/skaha/terminal").getId(), desktopAppURL);
            Session desktopApplicationSession = SessionUtil.waitForDesktopApplicationSession(desktopAppURL, desktopApplicationSessionID,
                                                                                             Session.STATUS_RUNNING);
            Instant desktopAppTimeToLiveStartTime = Instant.parse(desktopApplicationSession.getStartTime());
            Instant desktopAppTimeToLiveExpiryTime = Instant.parse(desktopApplicationSession.getExpiryTime());
            final long desktopAppTimeToLive = desktopAppTimeToLiveStartTime.until(desktopAppTimeToLiveExpiryTime, ChronoUnit.SECONDS);

            Assert.assertNotEquals("failed to calculate desktop app time-to-live", 0L, desktopAppTimeToLive);

            //renew desktop session, the associated desktop-app should also be renewed
            renewSession(this.sessionURL, desktopSessionID);

            desktopApplicationSession = SessionUtil.waitForDesktopApplicationSession(desktopAppURL, desktopApplicationSessionID, Session.STATUS_RUNNING);
            Instant appStartTimeAfterRenewal = Instant.parse(desktopApplicationSession.getStartTime());
            Instant appExpiryTimeAfterRenewal = Instant.parse(desktopApplicationSession.getExpiryTime());
            final long desktopAppTimeToLiveAfterRenewal = appStartTimeAfterRenewal.until(appExpiryTimeAfterRenewal, ChronoUnit.SECONDS);

            Assert.assertNotEquals("failed to calculate the renewed desktop app time-to-live", 0L, desktopAppTimeToLiveAfterRenewal);

            // delete desktop session, no need to delete the desktop-app
            SessionUtil.deleteSession(this.sessionURL, desktopSessionID);

            return null;
        });
    }

    private void initialize() throws Exception {
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
        Assert.assertEquals("zero sessions #1", 0, count);
    }

    private void deleteSession(URL sessionURL, String sessionID) throws MalformedURLException {
        HttpDelete delete = new HttpDelete(new URL(sessionURL.toString() + "/" + sessionID), true);
        delete.run();
        Assert.assertNull("delete session error", delete.getThrowable());
    }

    private void renewSession(URL sessionURL, String sessionID) throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("action", "renew");
        HttpPost post = new HttpPost(new URL(sessionURL.toString() + "/" + sessionID), params, false);
        post.prepare();
    }

    private List<Session> getSessions() throws Exception {
        return SessionUtil.getSessions(this.sessionURL, Session.STATUS_TERMINATING);
    }
}
