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

package org.opencadc.skaha.session;

import ca.nrc.cadc.util.Log4jInit;
import org.apache.log4j.Level;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.UUID;


public class PostActionTest {
    static {
        Log4jInit.setLevel("org.opencadc.skaha", Level.DEBUG);
    }

    final UUID jobUUID = UUID.randomUUID();

    @Test
    public void processCommandInput() throws Exception {
        final PostAction testSubject = new PostAction() {
            @Override
            String getUserID() {
                return "TESTUSER";
            }

            @Override
            String getCephUser() {
                return "TESTCEPHUSER";
            }

            @Override
            String getCephPath() {
                return "/my/ceph/path";
            }

            @Override
            String readAddUserConfig() {
                return "      - name: \"skaha-add-user-{skaha.adduser.uuid}\""
                       + "        image: images.canfar.net/skaha-system/add-user:1.2"
                       + "        imagePullPolicy: Always"
                       + "        # Userid for allocation goes in this argument."
                       + "        # Second argument is user quota in GB"
                       + "        # TODO: automate the setting of this in the calling script"
                       + "        command: [\"/usr/bin/add-user\"]"
                       + "        args: [\"{skaha.userid}\", \"{skaha.userquotagb}\"]"
                       + "        volumeMounts:"
                       + "        - mountPath: \"/config\""
                       + "          name: add-user-config"
                       + "        - mountPath: /root/.ssl/"
                       + "          name: servops-cert"
                       + "          readOnly: true"
                       + "        volumes:"
                       + "        - name: cavern-volume"
                       + "          cephfs:"
                       + "            monitors:"
                       + "            - 10.30.201.3:6789"
                       + "            - 10.30.202.3:6789"
                       + "            - 10.30.203.3:6789"
                       + "            path: \"{skaha.cephfs.path}\""
                       + "            user: \"{skaha.cephfs.user}\"";
            }
        };

        final String expectedConfig =
                "      - name: \"skaha-add-user-" + jobUUID + "\""
                + "        image: images.canfar.net/skaha-system/add-user:1.2"
                + "        imagePullPolicy: Always"
                + "        # Userid for allocation goes in this argument."
                + "        # Second argument is user quota in GB"
                + "        # TODO: automate the setting of this in the calling script"
                + "        command: [\"/usr/bin/add-user\"]"
                + "        args: [\"TESTUSER\", \"10\"]"
                + "        volumeMounts:"
                + "        - mountPath: \"/config\""
                + "          name: add-user-config"
                + "        - mountPath: /root/.ssl/"
                + "          name: servops-cert"
                + "          readOnly: true"
                + "        volumes:"
                + "        - name: cavern-volume"
                + "          cephfs:"
                + "            monitors:"
                + "            - 10.30.201.3:6789"
                + "            - 10.30.202.3:6789"
                + "            - 10.30.203.3:6789"
                + "            path: \"/my/ceph/path\""
                + "            user: \"TESTCEPHUSER\"";

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        testSubject.processCommandInput(outputStream, jobUUID);

        final String resultConfig = outputStream.toString();
        Assert.assertEquals("Wrong output.", String.join("", expectedConfig), resultConfig);
    }
}
