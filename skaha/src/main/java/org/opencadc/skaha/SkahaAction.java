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

package org.opencadc.skaha;

import ca.nrc.cadc.auth.AuthenticationUtil;
import ca.nrc.cadc.auth.HttpPrincipal;
import ca.nrc.cadc.cred.client.CredUtil;
import ca.nrc.cadc.net.HttpGet;
import ca.nrc.cadc.reg.client.LocalAuthority;
import ca.nrc.cadc.rest.InlineContentHandler;
import ca.nrc.cadc.rest.RestAction;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.security.AccessControlException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import javax.security.auth.Subject;

import org.apache.log4j.Logger;
import org.opencadc.gms.GroupClient;
import org.opencadc.gms.GroupURI;
import org.opencadc.gms.GroupUtil;

public abstract class SkahaAction extends RestAction {
    
    private static final Logger log = Logger.getLogger(SkahaAction.class);
    
    protected static final String SESSION_TYPE_DESKTOP = "desktop";
    protected static final String SESSION_TYPE_CARTA = "carta";
    protected static final String SESSION_TYPE_NOTEBOOK = "notebook";
    protected static List<String> SESSION_TYPES = Arrays.asList(
        new String[] {SESSION_TYPE_DESKTOP, SESSION_TYPE_CARTA, SESSION_TYPE_NOTEBOOK});
    
    protected String userID;
    protected boolean adminUser = false;
    protected String server;
    protected String homedir;
    protected String scratchdir;
    public List<String> harborHosts = new ArrayList<String>();
    protected String skahaUsersGroup;
    protected String skahaAdminsGroup;
    
    public SkahaAction() {
        server = System.getenv("skaha.hostname");
        homedir = System.getenv("skaha.homedir");
        scratchdir = System.getenv("skaha.scratchdir");
        String harborHostList = System.getenv("skaha.harborhosts");
        if (harborHostList == null) {
            log.warn("no harbor host list configured!");
        } else {
            harborHosts = Arrays.asList(harborHostList.split(" "));
        }
        skahaUsersGroup = System.getenv("skaha.usersgroup");
        skahaAdminsGroup = System.getenv("skaha.adminsgroup");
        log.debug("skaha.hostname=" + server);
        log.debug("skaha.homedir=" + homedir);
        log.debug("skaha.scratchdir=" + scratchdir);
        log.debug("skaha.harborHosts=" + harborHostList);
        log.debug("skaha.usersgroup=" + skahaUsersGroup);
        log.debug("skaha.adminsgroup=" + skahaAdminsGroup);
    }
    
    @Override
    protected InlineContentHandler getInlineContentHandler() {
        return null;
    }

    protected void initRequest() throws AccessControlException, IOException {
        
        final Subject subject = AuthenticationUtil.getCurrentSubject();
        log.debug("Subject: " + subject);
        
        if (subject == null || subject.getPrincipals().isEmpty()) {
            throw new AccessControlException("Unauthorized");
        }
        Set<HttpPrincipal> httpPrincipals = subject.getPrincipals(HttpPrincipal.class);
        if (httpPrincipals.isEmpty()) {
            throw new AccessControlException("No HTTP Principal");
        }
        userID = httpPrincipals.iterator().next().getName();
        log.debug("userID: " + userID);
        
        // ensure user is a part of the skaha group
        if (skahaUsersGroup == null) {
            throw new IllegalStateException("skaha.usersgroup not defined in system properties");
        }
        LocalAuthority localAuthority = new LocalAuthority();
        URI gmsSearchURI = localAuthority.getServiceURI("ivo://ivoa.net/std/GMS#search-0.1");
        GroupClient gmsClient = GroupUtil.getGroupClient(gmsSearchURI);
        GroupURI membershipGroup = null;
        try {
            membershipGroup = new GroupURI(skahaUsersGroup);
            CredUtil.checkCredentials();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (!gmsClient.isMember(membershipGroup)) {
            throw new AccessControlException("Not authorized to use the skaha system");
        }
        
        if (skahaAdminsGroup == null) {
            log.warn("skaha.adminsgroup not defined in system properties");
        } else {
            try {
                GroupURI adminMembershipGroup = new GroupURI(skahaAdminsGroup);
                if (gmsClient.isMember(adminMembershipGroup)) {
                    adminUser = true;
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

    }
    
    protected String getIdToken() throws Exception {
        log.debug("verfifying delegated credentials");
        if (!CredUtil.checkCredentials()) {
            throw new IllegalStateException("cannot access delegated credentials");
        }
        
        log.debug("getting idToken from ac");
        URL acURL = new URL("https://proto.canfar.net/ac/authorize?response_type=id_token&client_id=arbutus-harbor&scope=cli");
        OutputStream out = new ByteArrayOutputStream();
        HttpGet get = new HttpGet(acURL, out);
        get.run();
        if (get.getThrowable() != null) {
            log.warn("error obtaining idToken", get.getThrowable());
            return null;
        }
        String idToken = out.toString();
        log.debug("idToken: " + idToken);
        return idToken;
    }
}
