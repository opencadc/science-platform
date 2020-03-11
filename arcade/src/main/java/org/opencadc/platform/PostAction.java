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

package org.opencadc.platform;

import java.net.URL;
import java.util.List;

import org.apache.log4j.Logger;

import ca.nrc.cadc.util.StringUtil;
import ca.nrc.cadc.uws.server.RandomStringGenerator;

/**
 *
 * @author majorb
 */
public abstract class PostAction extends SessionAction {
    
    private static final Logger log = Logger.getLogger(PostAction.class);
    
    public abstract void checkForExistingSession(String userID) throws Exception;
    public abstract URL createSession(String sessionID, String name) throws Exception;
    public abstract void attachSoftware(String software, List<String> params, String targetIP) throws Exception;

    public PostAction() {
        super();
    }

    @Override
    public void doAction() throws Exception {
        
        super.initRequest();
        createUserMountSpace(userID);
        
        if (requestType.equals(SESSION_REQUEST)) {
            if (sessionID == null) {
                
                // check for no existing session for this user
                // (rule: only 1 session per user allowed)
                checkForExistingSession(userID);
                
                String name = syncInput.getParameter("name");
                if (name == null) {
                    throw new IllegalArgumentException("Missing parameter 'name'");
                }
                validateName(name);
                
                // create a new NoVNC session
                // VNC passwords are only good up to 8 characters
                sessionID = new RandomStringGenerator(8).getID();
                URL sessionURL = createSession(sessionID, name);
                
                syncOutput.setHeader("Location", sessionURL.toString());
                syncOutput.setCode(303);
                
            } else {
                throw new UnsupportedOperationException("Cannot modify an existing session.");
            }
            return;
        }
        if (requestType.equals(APP_REQUEST)) {
            if (appID == null) {
                // create an app
                
                // gather job parameters
                String software = syncInput.getParameter("software");
                String targetIP = syncInput.getParameter("target-ip");
                List<String> params = syncInput.getParameters("param");
                
                if (software == null) {
                    throw new IllegalArgumentException("Missing parameter 'software'");
                }
                if (targetIP == null) {
                    throw new IllegalArgumentException("Missing parameter 'target-ip'");
                }
                
                attachSoftware(software, params, targetIP);
                
            } else {
                throw new UnsupportedOperationException("Cannot modify an existing app.");
            }
        }
    }
    
    private void validateName(String name) {
        if (!StringUtil.hasText(name)) {
            throw new IllegalArgumentException("name must have a value");
        }
        if (!name.matches("[A-Za-z0-9\\-]+")) {
            throw new IllegalArgumentException("name can only contain alpha-numeric chars and '-'");
        }
    }

}
