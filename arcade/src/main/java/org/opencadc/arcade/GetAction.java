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

package org.opencadc.arcade;

import ca.nrc.cadc.util.StringUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;

/**
 * Process the GET request on the session(s) or app(s).
 *
 * @author majorb
 */
public class GetAction extends SessionAction {
    
    private static final Logger log = Logger.getLogger(GetAction.class);

    public GetAction() {
        super();
    }

    @Override
    public void doAction() throws Exception {
        super.initRequest();
        if (requestType.equals(REQUEST_TYPE_SESSION)) {
            if (sessionID == null) {
                // List the sessions
                String typeFilter = syncInput.getParameter("type");
                String statusFilter = syncInput.getParameter("status");
                listSessions(typeFilter, statusFilter);
            } else {
                throw new UnsupportedOperationException("Session detail viewing not supported.");
            }
            return;
        }
        if (requestType.equals(REQUEST_TYPE_APP)) {
            if (appID == null) {
                throw new UnsupportedOperationException("App listing not supported.");
            } else {
                throw new UnsupportedOperationException("App detail viewing not supported.");
            }
        }
    }
    
    public void listSessions(String typeFilter, String statusFilter) throws Exception {
        
        List<Session> sessions = getAllSessions(userID);
        StringBuilder ret = new StringBuilder();
        
        log.debug("typeFilter=" + typeFilter);
        log.debug("statusFilter=" + statusFilter);
        
        for (Session session : sessions) {
            if ((typeFilter == null || session.getType().equalsIgnoreCase(typeFilter)) &&
                (statusFilter == null || session.getStatus().equalsIgnoreCase(statusFilter))) {
                ret.append(session.getId());
                ret.append("\t");
                ret.append(session.getType());
                ret.append("\t");
                ret.append(session.getStatus());
                ret.append("\t");
                ret.append(session.getName());
                ret.append("\t");
                ret.append(session.getConnectURL());
                ret.append("\t");
                ret.append(session.getStartTime());
                ret.append("\n");
            }
        }
        syncOutput.getOutputStream().write(ret.toString().getBytes());
        return;
    }
    
    public static List<Session> getAllSessions(String forUserID) throws Exception {
        String k8sNamespace = K8SUtil.getWorkloadNamespace();
        String[] getSessionsCMD = new String[] {
            "kubectl", "get", "--namespace", k8sNamespace, "pod",
            "--selector=canfar-net-userid=" + forUserID,
            "--no-headers=true",
            "-o", "custom-columns=" +
                "SESSIONID:.metadata.labels.canfar-net-sessionID," + 
                "TYPE:.metadata.labels.canfar-net-sessionType," +
                "STATUS:.status.phase," +
                "NAME:.metadata.labels.canfar-net-sessionName," +
                "STARTED:.status.startTime," +
                "DELETION:.metadata.deletionTimestamp"};
                
        String vncSessions = execute(getSessionsCMD);
        log.debug("VNC Session list: " + vncSessions);
        
        List<Session> sessions = new ArrayList<Session>();
        
        if (StringUtil.hasLength(vncSessions)) {
            String[] lines = vncSessions.split("\n");
            for (String line : lines) {
                Session session = constructSession(line);
                sessions.add(session);
            }
        }
        
        return sessions;
    }
    
    private static Session constructSession(String k8sOutput) throws IOException {
        log.debug("line: " + k8sOutput);
        String[] parts = k8sOutput.split("\\s+");
        String id = parts[0];
        String type = parts[1];
        String status = parts[2];
        String name = parts[3];
        String startTime = "Up since " + parts[4];
        String deletionTimestamp = parts[5];
        if (deletionTimestamp != null && !"<none>".equals(deletionTimestamp)) {
            status = Session.STATUS_TERMINATING;
        }
        String host = K8SUtil.getHostName();
        String connectURL = "unknown";
        if (SessionAction.SESSION_TYPE_DESKTOP.equals(type)) {
            connectURL = SessionAction.getVNCURL(host, id);
        }
        if (SessionAction.SESSION_TYPE_CARTA.equals(type)) {
            connectURL = SessionAction.getCartaURL(host, id);
        }
        if (SessionAction.SESSION_TYPE_NOTEBOOK.equals(type)) {
            connectURL = SessionAction.getNotebookURL(host, id);
        }

        return new Session(id, type, status, name, startTime, connectURL);
        
    }

}
