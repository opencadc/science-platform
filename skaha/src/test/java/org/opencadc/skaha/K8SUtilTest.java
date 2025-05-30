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

import java.util.List;
import org.junit.Assert;
import org.junit.Test;
import org.opencadc.skaha.utils.TestUtils;

public class K8SUtilTest {
    @Test
    public void sanitizeJobName() {
        Assert.assertEquals(
                "Wrong name", "skaha-carta-userid-sess", K8SUtil.getJobName("SESS", SessionType.CARTA, "USERID"));

        Assert.assertEquals(
                "Wrong name", "skaha-desktop-my-user-sess", K8SUtil.getJobName("SESS", SessionType.DESKTOP, "my_user"));

        Assert.assertEquals(
                "Wrong name",
                "skaha-notebook-my-us-e-r-sess",
                K8SUtil.getJobName("SESS", SessionType.NOTEBOOK, "my|us+e&r"));
    }

    @Test
    public void getHarborHosts() {
        Assert.assertEquals("Wrong hosts.", List.of("localhost"), K8SUtil.getHarborHosts("localhost"));
        Assert.assertEquals(
                "Wrong hosts.", List.of("localhost", "example.org"), K8SUtil.getHarborHosts("localhost example.org"));
        Assert.assertEquals(
                "Wrong hosts.", List.of("localhost,anotherhost"), K8SUtil.getHarborHosts("localhost,anotherhost"));
    }

    @Test
    public void isPrepareDataEnabled() throws Exception {
        TestUtils.setEnv("PREPARE_DATA_ENABLED", "true");
        Assert.assertTrue(K8SUtil.isPrepareDataEnabled());
    }

    @Test
    public void isPrepareDataDisabled() throws Exception {
        TestUtils.setEnv("PREPARE_DATA_ENABLED", "false");
        Assert.assertFalse(K8SUtil.isPrepareDataEnabled());
    }

    // Add test for getUserDatasetsRootPath one for success and one for failure

    @Test
    public void getGetUserDatasetsRootPathSuccess() throws Exception {
        TestUtils.setEnv("USER_DATASETS_ROOT_PATH", "/data/datasets");
        Assert.assertEquals("/data/datasets", K8SUtil.getUserDatasetsRootPath());
    }

    @Test(expected = NullPointerException.class)
    public void getGetUserDatasetsRootPathFailure() throws Exception {
        TestUtils.setEnv("USER_DATASETS_ROOT_PATH", null);
        K8SUtil.getUserDatasetsRootPath();
    }
}
