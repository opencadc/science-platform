/*
 ************************************************************************
 *******************  CANADIAN ASTRONOMY DATA CENTRE  *******************
 **************  CENTRE CANADIEN DE DONNÉES ASTRONOMIQUES  **************
 *
 *  (c) 2021.                            (c) 2021.
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

import ca.nrc.cadc.util.Log4jInit;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit Test for GetAction for sessions.
 *
 * @author yeunga
 */
public class GetActionTests {

    private static final Logger log = Logger.getLogger(GetActionTests.class);

    static {
        Log4jInit.setLevel("org.opencadc.skaha", Level.DEBUG);
    }

    private static final long K_UNIT = 1024;
    private static final long M_UNIT = K_UNIT * K_UNIT;
    private static final long G_UNIT = K_UNIT * M_UNIT;
    private static final long T_UNIT = K_UNIT * G_UNIT;

    private static final long NO_UNIT_VALUE = 100;
    private static final long K_VALUE = 2 * K_UNIT;
    private static final long M_VALUE = 3 * M_UNIT;
    private static final long G_VALUE = 4 * G_UNIT;
    private static final long T_VALUE = 5 * T_UNIT;
    private static final long INVALID_VALUE = 6;

    private static final String NO_UNIT_VALUE_STR = String.valueOf(NO_UNIT_VALUE);
    private static final String K_VALUE_STR = 2 + "K";
    private static final String M_VALUE_STR = 3 + "M";
    private static final String G_VALUE_STR = 4 + "G";
    private static final String T_VALUE_STR = 5 + "T";
    private static final String INVALID_VALUE_STR = 5 + "A";

    private static final List<Session> SESSIONS_LIST = new ArrayList<>(Arrays.asList(
            new Session(
                    "pud05npw",
                    "owner-1",
                    "1001",
                    "1001",
                    new Integer[] {23, 24, 25},
                    "imageID",
                    "carta",
                    "Running",
                    "brian",
                    "2021-02-02T17:49:55Z",
                    null,
                    null),
            new Session(
                    "e37lmx4m",
                    "owner-1",
                    "1001",
                    "1001",
                    new Integer[] {23, 24, 25},
                    "imageID",
                    "desktop",
                    "Terminating",
                    "brian",
                    "2021-02-02T17:49:55Z",
                    null,
                    null),
            new Session(
                    "gspc0n8m",
                    "owner-1",
                    "1001",
                    "1001",
                    new Integer[] {23, 24, 25},
                    "imageID",
                    "notebook",
                    "Running",
                    "brian",
                    "2021-02-02T17:49:55Z",
                    null,
                    null),
            new Session(
                    "abcd0n8m",
                    "owner-1",
                    "1001",
                    "1001",
                    new Integer[] {23, 25},
                    "imageID",
                    "notebook",
                    "Terminating",
                    "brian",
                    "2021-02-02T17:49:55Z",
                    null,
                    null),
            new Session(
                    "defg0n8m",
                    "owner-1",
                    "1001",
                    "1001",
                    new Integer[] {1992},
                    "imageID",
                    "notebook",
                    "Running",
                    "brian",
                    "2021-02-02T17:49:55Z",
                    null,
                    null),
            new Session(
                    "shd89sfg",
                    "owner-1",
                    "1001",
                    "1001",
                    new Integer[0],
                    "imageID",
                    "notebook",
                    "Running",
                    "brian",
                    "2021-02-02T17:49:55Z",
                    null,
                    null),
            new Session(
                    "bbn3829s",
                    "owner-1",
                    "1001",
                    "1001",
                    null,
                    "imageID",
                    "notebook",
                    "Running",
                    "brian",
                    "2021-02-02T17:49:55Z",
                    null,
                    null)));

    public GetActionTests() {}

    @Before
    public void setupHomePath() {
        TestUtil.setupUserStorageEnvironment();
    }

    @After
    public void removeHomePath() {
        TestUtil.tearDownUserStorageEnvironment();
    }

    @Test
    public void testListSessions() throws Exception {
        GetAction get = new TestGetAction();
        String json = get.listSessions(null, null, false);
        log.info("json: \n" + json);
        List<Session> sessions1 = get.getAllSessions(null);
        Gson gson = new Gson();
        Type listType = new TypeToken<List<Session>>() {}.getType();
        List<Session> sessions2 = gson.fromJson(json, listType);
        Assert.assertEquals("Wrong session size.", sessions1.size(), GetActionTests.SESSIONS_LIST.size());
        Assert.assertEquals("session count", sessions1.size(), sessions2.size());
        for (Session s : sessions1) {
            Assert.assertTrue(s.getId(), sessions2.contains(s));

            // All start times should be parsable.
            log.info("Parsed start time: " + Instant.parse(s.getStartTime()));
        }
    }

    @Test
    public void testFilterType() {
        try {
            GetAction get = new TestGetAction();
            List<Session> sessions = get.getAllSessions(null);
            List<Session> filtered = get.filter(sessions, "notebook", null);
            for (Session s : filtered) {
                Assert.assertEquals(s.getId(), "notebook", s.getType());
            }
        } catch (Throwable t) {
            log.error("Unexpected", t);
            Assert.fail("Unexpected: " + t.getMessage());
        }
    }

    @Test
    public void testFilterStatus() throws Exception {
        GetAction get = new TestGetAction();
        List<Session> sessions = get.getAllSessions(null);
        List<Session> filtered = get.filter(sessions, null, "Running");
        for (Session s : filtered) {
            Assert.assertEquals(s.getId(), "Running", s.getStatus());
        }

        System.getProperties().remove("SKAHA_USER_STORAGE_HOME_DIRECTORY");
    }

    @Test
    public void testFilterTypeStatus() {
        try {
            GetAction get = new TestGetAction();
            List<Session> sessions = get.getAllSessions(null);
            List<Session> filtered = get.filter(sessions, "notebook", "Running");
            for (Session s : filtered) {
                Assert.assertEquals(s.getId(), "notebook", s.getType());
                Assert.assertEquals(s.getId(), "Running", s.getStatus());
            }
        } catch (Throwable t) {
            log.error("Unexpected", t);
            Assert.fail("Unexpected: " + t.getMessage());
        }
    }

    static class TestGetAction extends GetAction {

        @Override
        public List<Session> getAllSessions(String forUserID) {
            return GetActionTests.SESSIONS_LIST;
        }

        @Override
        protected String getUsername() {
            return null;
        }
    }
}
