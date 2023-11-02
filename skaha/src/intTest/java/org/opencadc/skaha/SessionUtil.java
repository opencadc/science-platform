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
import ca.nrc.cadc.net.HttpGet;
import ca.nrc.cadc.net.HttpPost;
import ca.nrc.cadc.reg.Standards;
import ca.nrc.cadc.reg.client.RegistryClient;
import ca.nrc.cadc.uws.server.RandomStringGenerator;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.Assert;
import org.opencadc.skaha.image.Image;
import org.opencadc.skaha.session.Session;
import org.opencadc.skaha.session.SessionAction;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SessionUtil {
    public static final URI SKAHA_SERVICE_ID = URI.create("ivo://cadc.nrc.ca/skaha");

    /**
     * Start a session and return the ID.
     *
     * @param image      The Image to spin up.
     * @param sessionURL The base URL for sessions
     * @return String sessionID.
     * @throws Exception For any badness.
     */
    protected static Session createSession(final String image, final URL sessionURL) throws Exception {
        final Map<String, Object> params = new HashMap<>();
        final String name = new RandomStringGenerator(16).getID();
        params.put("name", name);
        params.put("image", image);
        params.put("cores", 1);
        params.put("ram", 1);

        final HttpPost post = new HttpPost(sessionURL, params, false);
        post.prepare();

        final String sessionID;
        try (final BufferedReader reader = new BufferedReader(new InputStreamReader(post.getInputStream()))) {
            sessionID = reader.readLine();
        }

        final HttpGet httpGet = new HttpGet(new URL(sessionURL.toExternalForm() + "/" + sessionID), true);
        httpGet.prepare();

        final Gson gson = new Gson();
        try (final BufferedReader reader = new BufferedReader(new InputStreamReader(httpGet.getInputStream()))) {
            return gson.fromJson(reader, Session.class);
        }
    }

    protected static void createHeadlessSession(final String image, final URL sessionURL) throws Exception {
        final Map<String, Object> params = new HashMap<>();
        final String name = new RandomStringGenerator(16).getID();

        params.put("name", name);
        params.put("image", image);
        params.put("cores", 1);
        params.put("ram", 1);
        params.put("cmd", "sleep");
        params.put("args", "160");

        final HttpPost post = new HttpPost(sessionURL, params, false);
        post.prepare();
    }

    protected static Session createDesktopAppSession(final String image, final URL desktopSesionURL) throws Exception {
        final Map<String, Object> params = new HashMap<>();
        final String name = new RandomStringGenerator(16).getID();

        params.put("name", name);
        params.put("image", image);
        params.put("cores", 1);
        params.put("ram", 1);
        params.put("cmd", "sleep");
        params.put("args", "260");

        final HttpPost post = new HttpPost(desktopSesionURL, params, false);
        post.prepare();

        final String appID;
        try (final BufferedReader reader = new BufferedReader(new InputStreamReader(post.getInputStream()))) {
            appID = reader.readLine();
        }

        final HttpGet httpGet = new HttpGet(new URL(desktopSesionURL.toExternalForm() + "/" + appID), true);
        httpGet.prepare();

        final Gson gson = new Gson();
        try (final BufferedReader reader = new BufferedReader(new InputStreamReader(httpGet.getInputStream()))) {
            return gson.fromJson(reader, Session.class);
        }
    }

    protected static List<Session> getSessions(final URL sessionURL, String... omitStatuses) throws Exception {
        final List<Session> sessions = SessionUtil.getAllSessions(sessionURL);
        final List<Session> active = new ArrayList<>();
        for (final Session s : sessions) {
            if (!Arrays.asList(omitStatuses).contains(s.getStatus())) {
                active.add(s);
            }
        }

        return active;
    }

    protected static List<Session> getSessionsOfType(final URL sessionURL, final String type, String... omitStatuses)
            throws Exception {
        return SessionUtil.getSessions(sessionURL, omitStatuses).stream()
                          .filter(session -> session.getType().equals(type))
                          .collect(Collectors.toList());
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

        final Type listType = new TypeToken<List<Session>>() {
        }.getType();
        final Gson gson = new Gson();
        return gson.fromJson(json, listType);
    }

    protected static Image getImageOfType(final String type) throws Exception {
        return SessionUtil.getImagesOfType(type).stream().findFirst().orElseThrow();
    }

    protected static List<Image> getImagesOfType(final String type) throws Exception {
        final RegistryClient registryClient = new RegistryClient();
        final URL imageServiceURL = registryClient.getServiceURL(SessionUtil.SKAHA_SERVICE_ID,
                                                                 Standards.PROC_SESSIONS_10, AuthMethod.TOKEN);
        final URL imageURL = new URL(imageServiceURL.toExternalForm() + "/image");

        final List<Image> allImagesList = ImagesTest.getImages(imageURL);
        return allImagesList.stream().filter(image -> image.getTypes().contains(type)).collect(Collectors.toList());
    }

    protected static Image getDesktopAppImageOfType(final String fuzzySearch) throws Exception {
        return SessionUtil.getImagesOfType(SessionAction.TYPE_DESKTOP_APP).stream()
                          .filter(image -> image.getId().contains(fuzzySearch))
                          .findFirst().orElseThrow();
    }
}
