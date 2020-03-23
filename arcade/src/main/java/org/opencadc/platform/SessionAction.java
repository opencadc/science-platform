/*
************************************************************************
*******************  CANADIAN ASTRONOMY DATA CENTRE  *******************
**************  CENTRE CANADIEN DE DONNÉES ASTRONOMIQUES  **************
*
*  (c) 2018.                            (c) 2018.
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

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.AccessControlException;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

import javax.security.auth.Subject;

import org.apache.log4j.Logger;
import org.opencadc.gms.GroupClient;
import org.opencadc.gms.GroupURI;
import org.opencadc.gms.GroupUtil;

import ca.nrc.cadc.auth.AuthenticationUtil;
import ca.nrc.cadc.auth.HttpPrincipal;
import ca.nrc.cadc.cred.client.CredUtil;
import ca.nrc.cadc.net.HttpDownload;
import ca.nrc.cadc.reg.client.LocalAuthority;
import ca.nrc.cadc.rest.InlineContentHandler;
import ca.nrc.cadc.rest.RestAction;
import ca.nrc.cadc.util.MultiValuedProperties;
import ca.nrc.cadc.util.PropertiesReader;

public abstract class SessionAction extends RestAction {
    
    private static final Logger log = Logger.getLogger(SessionAction.class);
    
    protected static final String SESSION_REQUEST = "session";
    protected static final String APP_REQUEST = "app";
    
    protected String userID;
    protected String requestType;  // session or app
    protected String sessionID;
    protected String appID;
    protected String server;
    protected String homedir;
    protected String scratchdir;
    
    public SessionAction() {
        server = System.getenv("arcade.hostname");
        homedir = System.getenv("arcade.homedir");
        scratchdir = System.getenv("arcade.scratchdir");
        log.debug("arcade.hostname=" + server);
        log.debug("arcade.homedir=" + homedir);
        log.debug("arcade.scratchdir=" + scratchdir);;
    }
    
    @Override
    protected InlineContentHandler getInlineContentHandler() {
        return null;
    }

    protected void initRequest() throws AccessControlException, IOException {
        
        final Subject subject = AuthenticationUtil.getCurrentSubject();
        log.debug("Subject: " + subject);
        
        // authorization, for now, is simply being authenticated
        if (subject == null || subject.getPrincipals().isEmpty()) {
            throw new AccessControlException("Unauthorized");
        }
        Set<HttpPrincipal> httpPrincipals = subject.getPrincipals(HttpPrincipal.class);
        if (httpPrincipals.isEmpty()) {
            throw new AccessControlException("No HTTP Principal");
        }
        userID = httpPrincipals.iterator().next().getName();
        
        // ensure user is a part of the arcade group
        String arcadeGroup = super.initParams.get("arcade-users-group");
        if (arcadeGroup == null) {
            throw new IllegalStateException("No arcade-users-group defined in web.xml");
        }
        LocalAuthority localAuthority = new LocalAuthority();
        URI gmsSearchURI = localAuthority.getServiceURI("ivo://ivoa.net/std/GMS#search-0.1");
        GroupClient gmsClient = GroupUtil.getGroupClient(gmsSearchURI);
        GroupURI membershipGroup = new GroupURI(arcadeGroup);
        try {
            CredUtil.checkCredentials();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (!gmsClient.isMember(membershipGroup)) {
            throw new AccessControlException("Not authorized to use the ARCADE system");
        }
        
        String path = syncInput.getPath();
        log.debug("request path: " + path);
        requestType = SESSION_REQUEST;
        
        if (path == null) {
            return;
        }
        
        String[] parts = path.split("/");
        if (parts.length > 0) {
            sessionID = parts[0];
        }
        if (parts.length > 1) {
            requestType = APP_REQUEST;
        }
        if (parts.length > 2) {
            appID = parts[2];
        }
        if (parts.length > 3) {
            throw new IllegalArgumentException("Invalid request: " + path);
        }
        log.debug("request type: " + requestType);
        log.debug("sessionID: " + sessionID);
        log.debug("appID: " + appID);
    }
    
    protected String readStream(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[1024];
        while ((nRead = in.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toString("UTF-8");
    }
    
    protected String execute(String[] command) throws IOException, InterruptedException {
        Process p = Runtime.getRuntime().exec(command);
        int status = p.waitFor();
        log.debug("Status=" + status + " for command: " + Arrays.toString(command));
        String stdout = readStream(p.getInputStream());
        String stderr = readStream(p.getErrorStream());
        log.debug("stdout: " + stdout);
        log.debug("stderr: " + stderr);
        if (status != 0) {
            String message = "Error executing command: " + Arrays.toString(command) + " Error: " + stderr;
            throw new IOException(message);
        } 
        return stdout.trim();
    }
    
    protected void createUserMountSpace(String userid) throws Exception {
        File scratch = new File("/home/" + userid);
        if (!scratch.exists()) {
            scratch.mkdir();
        }
        File home = new File("/scratch/" + userid);
        if (!home.exists()) {
            home.mkdir();
        }
    }
    
    public String getVNCURL(String host, String sessionID, String ipAddress) throws MalformedURLException {
        // vnc_light.html accepts title and resize
        //return "https://" + host + "/desktop/" + ipAddress + "/" + sessionID + "/connect?" +
        //    "title=ARCADE&resize=true&path=desktop/" + ipAddress + "/" + sessionID + "/websockify&password=" + sessionID;
        
        // vnc.html does not...
        return "https://" + host + "/desktop/" + ipAddress + "/" + sessionID + "/connect?password=" + sessionID +
            "&path=desktop/" + ipAddress + "/" + sessionID + "/websockify";
    }
    
    protected void injectProxyCert(String baseHomeDir, final Subject subject, String userid, String posixID)
            throws PrivilegedActionException, IOException, InterruptedException {
        
        // creating cert home dir
        execute(new String[] {"mkdir", "-p", baseHomeDir + "/" + userid + "/.ssl"});
        
        // get the proxy cert
        Subject opsSubject = CredUtil.createOpsSubject();
        String proxyCert = Subject.doAs(opsSubject, new PrivilegedExceptionAction<String>() {
            @Override
            public String run() throws Exception {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                String userid = subject.getPrincipals(HttpPrincipal.class).iterator().next().getName();
                HttpDownload download = new HttpDownload(
                        new URL("https://www.cadc-ccda.hia-iha.nrc-cnrc.gc.ca/cred/priv/userid/" + userid), out);
                download.run();
                String proxyCert = out.toString();
                    
                return proxyCert;
            } 
        });
        log.debug("Proxy cert: " + proxyCert);
        // inject the proxy cert
        log.debug("Running docker exec to insert cert");
        
        String tmpFileName = stageFile(proxyCert);
        String[] chown = new String[] {"chown", posixID + ":" + posixID, tmpFileName};
        execute(chown);
        String[] injectCert = new String[] {"cp",  "-rp", tmpFileName, baseHomeDir + "/" + userid + "/.ssl/cadcproxy.pem"};
        execute(injectCert);
        
        
//        String[] chown = new String[] {"chown", "-R", "guest:guest", "/home/" + userid + "/.ssl"};
//        execute(chown);
    }
    
    protected String confirmSoftware(String software) {
        PropertiesReader pr = new PropertiesReader("arcade-software.properties");
        MultiValuedProperties mp = pr.getAllProperties();
        Set<String> names = mp.keySet();
        Iterator<String> it = names.iterator();
        while (it.hasNext()) {
            String next = it.next();
            log.debug("Next: " + next);
            String value = mp.getProperty(next).get(0);
            if (value.equals(software)) {
                return next;
            }
        }
        throw new IllegalArgumentException("Software with ID " + software + " is not available.");
    }
    
    protected String stageFile(String data) throws IOException {
        String tmpFileName = "/tmp/" + UUID.randomUUID();
        File file = new File(tmpFileName);
        if (!file.setExecutable(true, false)) {
            log.warn("Failed to set execution permssion on file " + tmpFileName);
        }
        BufferedWriter writer = new BufferedWriter(new FileWriter(file));
        writer.write(data + "\n");
        writer.flush();
        writer.close();
        return tmpFileName;
    }
    
}
