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
import ca.nrc.cadc.util.StringUtil;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.Assert;
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

    private static final String K8S_LIST =
            "pud05npw   majorb   1001   1001   [23 24 25]   imageID   carta   Running   brian   2021-02-02T17:49:55Z   <none>   <none>\n"
                    + "e37lmx4m   majorb   1001   1001   [23 24 25]   imageID   desktop    Terminating   brian   2021-01-28T21:52:51Z   <none>   <none>\n"
                    + "gspc0n8m   majorb   1001   1001   [23 24 25]   imageID   notebook   Running   brian   2021-01-29T22:56:21Z   <none>   <none>\n"
                    + "abcd0n8m   majorb   1001   1001   [23 25]   imageID   notebook   Terminating   brian   2021-01-29T22:56:21Z   <none>   <none>\n"
                    + "defg0n8m   majorb   1001   1001   [1992]   imageID   notebook   Running   brian    2021-01-29T22:56:21Z   <none>   <none>\n"
                    + "shd89sfg   majorb   1001   1001   []   imageID   notebook   Running   brian    2021-02-09T22:56:21Z   <none>   <none>\n"
                    + "bbn3829s   majorb   1001   1001   <none>   imageID   notebook   Running   brian    2021-02-27T22:56:21Z   <none>   <none>\n";

    public GetActionTests() {}

    @Test
    public void testNormalizeToLong() {
        try {
            GetAction get = new TestGetAction();
            Assert.assertEquals(NO_UNIT_VALUE, get.normalizeToLong(NO_UNIT_VALUE_STR));
            Assert.assertEquals(K_VALUE, get.normalizeToLong(K_VALUE_STR));
            Assert.assertEquals(M_VALUE, get.normalizeToLong(M_VALUE_STR));
            Assert.assertEquals(G_VALUE, get.normalizeToLong(G_VALUE_STR));
            Assert.assertEquals(T_VALUE, get.normalizeToLong(T_VALUE_STR));
            Assert.assertEquals(INVALID_VALUE, get.normalizeToLong(INVALID_VALUE_STR));
        } catch (IllegalStateException ex) {
            if (!ex.getMessage().contains("unknown RAM unit")) {
                Assert.fail("Unexpected: " + ex.getMessage());
            }
        } catch (Throwable t) {
            log.error("Unexpected", t);
            Assert.fail("Unexpected: " + t.getMessage());
        }
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
        Assert.assertEquals(sessions1.size(), K8S_LIST.split("\n").length);
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
        public List<Session> getAllSessions(String forUserID) throws Exception {
            // A bit of a hack to emulate the state.
            this.skahaTld = "/cavern-vospace";

            List<Session> sessions = new ArrayList<>();
            String[] lines = K8S_LIST.split("\n");
            for (String line : lines) {
                Session session = constructSession(line);
                sessions.add(session);
            }
            return sessions;
        }

        Session constructSession(String k8sOutput) {
            final List<SessionDAO.CustomColumns> allColumns = Arrays.asList(SessionDAO.CustomColumns.values());

            // Items are separated by 3 or more spaces.  We can't separate on all spaces because the supplemental groups
            // are in a space-delimited array.
            final String[] parts = k8sOutput.trim().split(" {3,}");

            String id = parts[allColumns.indexOf(SessionDAO.CustomColumns.SESSION_ID)];
            String userid = parts[allColumns.indexOf(SessionDAO.CustomColumns.USERID)];
            String image = parts[allColumns.indexOf(SessionDAO.CustomColumns.IMAGE)];
            SessionType type =
                    SessionType.fromApplicationStringType(parts[allColumns.indexOf(SessionDAO.CustomColumns.TYPE)]);
            String deletionTimestamp = parts[allColumns.indexOf(SessionDAO.CustomColumns.DELETION)];
            final String status = (deletionTimestamp != null && !NONE.equals(deletionTimestamp))
                    ? Session.STATUS_TERMINATING
                    : parts[allColumns.indexOf(SessionDAO.CustomColumns.STATUS)];
            final String connectURL = String.format("https://example.org/session/test/%s", id);

            final Session session = new Session(
                    id,
                    userid,
                    parts[allColumns.indexOf(SessionDAO.CustomColumns.RUN_AS_UID)],
                    parts[allColumns.indexOf(SessionDAO.CustomColumns.RUN_AS_GID)],
                    fromStringArray(parts[allColumns.indexOf(SessionDAO.CustomColumns.SUPPLEMENTAL_GROUPS)]),
                    image,
                    type.applicationName,
                    status,
                    parts[allColumns.indexOf(SessionDAO.CustomColumns.NAME)],
                    parts[allColumns.indexOf(SessionDAO.CustomColumns.STARTED)],
                    connectURL,
                    parts[allColumns.indexOf(SessionDAO.CustomColumns.APP_ID)]);

            // Check if all columns were requested (set by forUserId)
            final int requestedRamIndex = allColumns.indexOf(SessionDAO.CustomColumns.REQUESTED_RAM);
            if (parts.length > requestedRamIndex) {
                session.setRequestedRAM(toCommonUnit(parts[requestedRamIndex]));
            }

            final int requestedCPUIndex = allColumns.indexOf(SessionDAO.CustomColumns.REQUESTED_CPU);
            if (parts.length > requestedCPUIndex) {
                session.setRequestedCPUCores(toCoreUnit(parts[requestedCPUIndex]));
            }

            final int requestedGPUIndex = allColumns.indexOf(SessionDAO.CustomColumns.REQUESTED_GPU);
            if (parts.length > requestedGPUIndex) {
                session.setRequestedGPUCores(toCoreUnit(parts[requestedGPUIndex]));
            }

            return session;
        }

        /**
         * Example input is [4444 5555 6666]. Convert to an actual integer array.
         *
         * @param inputArray Kubernetes output of an array of integers.
         * @return integer array, never null.
         */
        private static Integer[] fromStringArray(final String inputArray) {
            if (inputArray.equals(SessionDAO.NONE)) {
                return new Integer[0];
            } else {
                final Object[] parsedArray = Arrays.stream(inputArray
                                .replace("[", "")
                                .replace("]", "")
                                .trim()
                                .split(" "))
                        .filter(StringUtil::hasText)
                        .map(Integer::parseInt)
                        .toArray();
                return Arrays.copyOf(parsedArray, parsedArray.length, Integer[].class);
            }
        }

        @Override
        protected String getUsername() {
            return null;
        }

        @Override
        protected int getUID() {
            return 997;
        }
    }
}
