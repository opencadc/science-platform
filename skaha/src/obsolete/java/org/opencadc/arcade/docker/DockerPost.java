/*
************************************************************************
*******************  CANADIAN ASTRONOMY DATA CENTRE  *******************
**************  CENTRE CANADIEN DE DONNÉES ASTRONOMIQUES  **************
*
*  (c) 2019.                            (c) 2019.
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

package org.opencadc.arcade.docker;

import java.net.URL;
import java.util.List;

import javax.security.auth.Subject;

import org.apache.log4j.Logger;
import org.opencadc.platform.PostAction;

import ca.nrc.cadc.auth.AuthenticationUtil;
import ca.nrc.cadc.util.StringUtil;

public class DockerPost extends PostAction {

    private static final Logger log = Logger.getLogger(DockerPost.class);

    @Override
    public void checkForExistingSession(String userid) throws Exception {
        String[] getVNCSessions = new String[] {"docker", "ps", "--format", "{{.Names}}\\t{{.Status}}", "--filter", "label=canfar-net-userid=" + userID};
        String vncSessions = execute(getVNCSessions);
        if (StringUtil.hasLength(vncSessions)) {
            String[] lines = vncSessions.split("\n");
            if (lines.length > 0) {
                throw new IllegalArgumentException("User " + userID + " has a session already running.");
            }
        }
    }

    @Override
    public URL createSession(String sessionID, String name) throws Exception {

        String[] runNoVNCCmd = new String[] {"/scripts/docker/run-desktop.sh", userID, sessionID, name, homedir, scratchdir};
        String imageID = execute(runNoVNCCmd);

        // insert the user's proxy cert on the container
        Subject subject = AuthenticationUtil.getCurrentSubject();
        //injectProxyCert("/home", subject, userID);

        String[] getIpCmd = new String[] {
            "docker", "inspect", "--format", "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}", imageID};
        String ipAddress = execute(getIpCmd);

        // give vnc a few seconds to initialize
        try {
            log.debug("3 second wait for vnc initialization");
            Thread.sleep(2000);
        } catch (InterruptedException ignore) {
        }
        log.debug("wait over");

        String redirectPath = super.getVNCURL(server, sessionID, ipAddress);
        return new URL(redirectPath);
    }

    @Override
    public void attachSoftware(String software, List<String> params, String targetIP) throws Exception {

        confirmSoftware(software);

        // only one parameter supported for now
        String param = "xterm";
        if (params != null && params.size() > 0) {
            param = params.get(0);
        }
        log.debug("Using parameter: " + param);

        String[] runAppCmd = new String[] {"/scripts/docker/software.sh", software, targetIP, userID, homedir, scratchdir,  param};
        String imageID = execute(runAppCmd);

        // refresh the user's proxy cert
        Subject subject = AuthenticationUtil.getCurrentSubject();
        //injectProxyCert("/home", subject, userID);
    }

}
