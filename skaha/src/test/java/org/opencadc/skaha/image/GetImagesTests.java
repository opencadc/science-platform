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
package org.opencadc.skaha.image;

import ca.nrc.cadc.util.Log4jInit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.Assert;
import org.junit.Test;

/**
 * @author majorb
 *
 */
public class GetImagesTests {
    
    private static final Logger log = Logger.getLogger(GetImagesTests.class);
    
    static {
        Log4jInit.setLevel("org.opencadc.skaha", Level.DEBUG);
    }
    
    private static final String PROJECT_LIST =
        "[  {" +
          "\"project_id\": 7," + 
          "\"owner_id\": 1," +
          "\"name\": \"skaha-carta\"," +
          "\"creation_time\": \"2020-12-07T19:21:29Z\"," +
          "\"update_time\": \"2020-12-07T19:21:29Z\"," +
          "\"deleted\": false," +
          "\"owner_name\": \"\"," +
          "\"current_user_role_id\": 1," +
          "\"current_user_role_ids\": [" +
              "1" +
           " ]," +
            "\"repo_count\": 1," +
            "\"chart_count\": 0," +
            "\"metadata\": {" +
                "\"auto_scan\": \"false\"," +
                "\"enable_content_trust\": \"false\"," +
                "\"prevent_vul\": \"false\"," +
                "\"public\": \"true\"," +
                "\"reuse_sys_cve_whitelist\": \"true\"," +
                "\"severity\": \"low\"" +
            "}," +
            "\"cve_whitelist\": {" +
                "\"id\": 0," +
                "\"project_id\": 0," +
                "\"items\": null," +
                "\"creation_time\": \"0001-01-01T00:00:00Z\"," +
                "\"update_time\": \"0001-01-01T00:00:00Z\"" +
            "}" +
        "} ]";
    
    private static final String REPO_LIST = 
        "[{\"artifact_count\":2,\"creation_time\":\"2021-02-08T23:11:54.208Z\",\"id\":13,\"name\":\"petuan/new-earth-snap\"," + 
        "\"project_id\":3,\"pull_count\":1,\"update_time\":\"2021-02-10T21:40:09.897Z\"},{\"artifact_count\":1,\"creation_time\"" +
        ":\"2021-02-08T20:40:35.686Z\",\"id\":12,\"name\":\"petuan/starnet-notebook\",\"project_id\":3,\"update_time\":" +
        "\"2021-02-08T20:40:35.686Z\"},{\"artifact_count\":1,\"creation_time\":\"2021-02-02T23:36:57.250Z\",\"id\":11,\"name\":" +
        "\"petuan/casa61demo\",\"project_id\":3,\"pull_count\":1,\"update_time\":\"2021-02-03T00:55:17.379Z\"},{\"artifact_count\":1," +
        "\"creation_time\":\"2021-02-02T22:43:26.675Z\",\"id\":10,\"name\":\"petuan/jwstpipe\",\"project_id\":3,\"pull_count\":1," +
        "\"update_time\":\"2021-02-02T22:59:02.809Z\"},{\"artifact_count\":1,\"creation_time\":\"2020-10-23T22:55:28.696Z\",\"id\":4," +
        "\"name\":\"petuan/notebook-scipy\",\"project_id\":3,\"pull_count\":52,\"update_time\":\"2021-02-11T22:13:26.723Z\"}]";
    
    private static final String ARTIFACT_LIST =
        "[{\"addition_links\":{\"build_history\":{\"absolute\":false,\"href\":\"/api/v2.0/projects/petuan/repositories/new-earth-snap/" +
        "artifacts/sha256:439cadced5731d0946018fa3e2371444309c7cb8b6fc762c8f96ef915fc49ae4/additions/build_history\"}," +
        "\"vulnerabilities\":{\"absolute\":false,\"href\":\"/api/v2.0/projects/petuan/repositories/new-earth-snap/artifacts/" +
        "sha256:439cadced5731d0946018fa3e2371444309c7cb8b6fc762c8f96ef915fc49ae4/additions/vulnerabilities\"}},\"digest\":\"" +
        "sha256:439cadced5731d0946018fa3e2371444309c7cb8b6fc762c8f96ef915fc49ae4\",\"extra_attrs\":{\"architecture\":\"amd64\"," +
        "\"author\":null,\"created\":\"2021-02-08T22:59:32.7023262Z\",\"os\":\"linux\"},\"id\":34,\"labels\":[{\"color\":\"#1D5100\"," +
        "\"creation_time\":\"2021-02-09T23:42:48.367Z\",\"description\":\"Jupyter Notebook Image\",\"id\":3,\"name\":\"notebook\"," +
        "\"scope\":\"g\",\"update_time\":\"2021-02-11T00:57:31.917Z\"}],\"" +
        "manifest_media_type\":\"application/vnd.docker.distribution.manifest.v2+json\",\"media_type\":\"application/vnd.docker" +
        ".container.image.v1+json\",\"project_id\":3,\"pull_time\":\"2021-02-10T21:40:09.899Z\",\"push_time\":\"2021-02-08T23:50:" +
        "02.743Z\",\"references\":null,\"repository_id\":13,\"size\":1837261373,\"tags\":[{\"artifact_id\":34,\"id\":16,\"immutable\"" +
        ":false,\"name\":\"0.1.1\",\"pull_time\":\"2021-02-10T21:40:09.899Z\",\"push_time\":\"2021-02-08T23:50:02.906Z\",\"" +
        "repository_id\":13,\"signed\":false}],\"type\":\"IMAGE\"},{\"addition_links\":{\"build_history\":{\"absolute\":false," +
        "\"href\":\"/api/v2.0/projects/petuan/repositories/new-earth-snap/artifacts/sha256:79663b73473125899d569aa602f600abf10c254" +
        "d328dc6093822c2ed24803ad5/additions/build_history\"},\"vulnerabilities\":{\"absolute\":false,\"href\":\"/api/v2.0/projects" +
        "/petuan/repositories/new-earth-snap/artifacts/sha256:79663b73473125899d569aa602f600abf10c254d328dc6093822c2ed24803ad5/" +
        "additions/vulnerabilities\"}},\"digest\":\"sha256:79663b73473125899d569aa602f600abf10c254d328dc6093822c2ed24803ad5\",\"" +
        "extra_attrs\":{\"architecture\":\"amd64\",\"author\":null,\"created\":\"2021-02-07T18:56:51.0637533Z\",\"os\":\"linux\"},\"" +
        "id\":33,\"labels\":null,\"manifest_media_type\":\"application/vnd.docker.distribution.manifest.v2+json\",\"media_type\":\"" +
        "application/vnd.docker.container.image.v1+json\",\"project_id\":3,\"pull_time\":\"0001-01-01T00:00:00.000Z\",\"push_time\":\"" +
        "2021-02-08T23:11:54.713Z\",\"references\":null,\"repository_id\":13,\"size\":1840597691,\"tags\":[{\"artifact_id\":33,\"id\":15," +
        "\"immutable\":false,\"name\":\"0.1.0\",\"pull_time\":\"0001-01-01T00:00:00.000Z\",\"push_time\":\"2021-02-08T23:11:54.876Z\"," +
        "\"repository_id\":13,\"signed\":false}],\"type\":\"IMAGE\"}]";

    public GetImagesTests() {
    }
    
    @Test
    public void testGetImages() {
        try {
            GetAction get = new TestGetAction();
            get.harborHosts.add("test");
            List<Image> images = get.getImages(null, null);
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String json = gson.toJson(images);
            log.info(json);
            Assert.assertEquals("image count",  5, images.size());
            Set<String> types = new HashSet<String>();
            types.add("notebook");
            Image test = new Image("test/petuan/new-earth-snap:0.1.1", types,
                "sha256:439cadced5731d0946018fa3e2371444309c7cb8b6fc762c8f96ef915fc49ae4");
            Assert.assertTrue("exists", images.contains(test));
            
        } catch (Throwable t) {
            log.error("Unexpected", t);
            Assert.fail("Unexpected: " + t.getMessage());
        }
    }
    
    @Test
    public void testGetImage() {
        try {
            GetAction get = new TestGetAction();
            Image image = get.getImage("test/petuan/new-earth-snap:0.1.1");
            Assert.assertEquals("imageID",  "test/petuan/new-earth-snap:0.1.1", image.getId());
        } catch (Throwable t) {
            log.error("Unexpected", t);
            Assert.fail("Unexpected: " + t.getMessage());
        }
        
    }
    
    class TestGetAction extends GetAction {
        
        @Override
        protected String callHarbor(String idToken, String harborHost, String project, String repo) throws Exception {
            if (project == null) {
                log.debug("project ouutput: " + PROJECT_LIST);
                return PROJECT_LIST;
            } else if (repo == null) {
                log.debug("repo ouutput: " + REPO_LIST);
                return REPO_LIST;
            } else {    
                log.debug("artifact output: " + ARTIFACT_LIST);
                return ARTIFACT_LIST;
            }
        }
        
        @Override
        protected String getIdToken() {
            return "";
        }
    }
}
