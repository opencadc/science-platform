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

package org.opencadc.skaha.session;

import ca.nrc.cadc.auth.HttpPrincipal;
import ca.nrc.cadc.cred.client.CredUtil;
import ca.nrc.cadc.net.HttpGet;
import ca.nrc.cadc.rest.InlineContentHandler;
import ca.nrc.cadc.util.MultiValuedProperties;
import ca.nrc.cadc.util.PropertiesReader;
import ca.nrc.cadc.util.StringUtil;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.AccessControlException;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.security.auth.Subject;

import org.apache.log4j.Logger;
import org.opencadc.skaha.K8SUtil;
import org.opencadc.skaha.SkahaAction;

public abstract class SessionAction extends SkahaAction {
    
    private static final Logger log = Logger.getLogger(SessionAction.class);
    
    protected static final String REQUEST_TYPE_SESSION = "session";
    protected static final String REQUEST_TYPE_APP = "app";
    
    protected static final String SESSION_DETAIL_MAX = "max";
    
    protected String requestType;
    protected String sessionID;
    protected String appID;
    
    public SessionAction() {
        super();
    }
    
    @Override
    protected InlineContentHandler getInlineContentHandler() {
        return null;
    }

    protected void initRequest() throws Exception {
        super.initRequest();
        
        String path = syncInput.getPath();
        log.debug("request path: " + path);
        requestType = REQUEST_TYPE_SESSION;
        
        if (path == null) {
            return;
        }
        
        String[] parts = path.split("/");
        if (parts.length > 0) {
            sessionID = parts[0];
        }
        if (parts.length > 1) {
            requestType = REQUEST_TYPE_APP;
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
        log.debug("userID: " + userID);
    }
    
    protected static String readStream(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[1024];
        while ((nRead = in.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toString("UTF-8");
    }
    
    public static String execute(String[] command) throws IOException, InterruptedException {
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
    
    public static String getVNCURL(String host, String sessionID) throws MalformedURLException {
        // vnc_light.html accepts title and resize
        //return "https://" + host + "/desktop/" + ipAddress + "/" + sessionID + "/connect?" +
        //    "title=skaha&resize=true&path=desktop/" + ipAddress + "/" + sessionID + "/websockify&password=" + sessionID;
        
        // vnc.html does not...
        return "https://" + host + "/desktop/" + sessionID + "/?password=" + sessionID + "&path=desktop/" + sessionID + "/";
    }
    
    public static String getCartaURL(String host, String sessionID, boolean altSocketUrl) throws MalformedURLException {
        String url = "https://" + host + "/carta/http/" + sessionID + "/";
        if (altSocketUrl) {
            url = url + "?socketUrl=wss://" + host + "/carta/ws/" + sessionID + "/";
        }
        return url;
    }
    
    public static String getNotebookURL(String host, String sessionID) throws MalformedURLException {
        return "https://" + host + "/notebook/" + sessionID + "/lab?token=" + sessionID;
    }
    
    protected void injectProxyCert(final Subject subject, String userid, String posixID)
            throws PrivilegedActionException, IOException, InterruptedException {
        
        // creating cert home dir
        execute(new String[] {"mkdir", "-p", homedir + "/" + userid + "/.ssl"});
        
        // get the proxy cert
        Subject opsSubject = CredUtil.createOpsSubject();
        String proxyCert = Subject.doAs(opsSubject, new PrivilegedExceptionAction<String>() {
            @Override
            public String run() throws Exception {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                String userid = subject.getPrincipals(HttpPrincipal.class).iterator().next().getName();
                HttpGet download = new HttpGet(
                        new URL("https://ws.cadc-ccda.hia-iha.nrc-cnrc.gc.ca/cred/priv/userid/" + userid), out);
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
        String[] injectCert = new String[] {"cp",  "-rp", tmpFileName, homedir + "/" + userid + "/.ssl/cadcproxy.pem"};
        execute(injectCert);
        
        
//        String[] chown = new String[] {"chown", "-R", "guest:guest", "/home/" + userid + "/.ssl"};
//        execute(chown);
    }
    
    protected String getImageName(String image) {
        PropertiesReader pr = new PropertiesReader("skaha-software.properties");
        MultiValuedProperties mp = pr.getAllProperties();
        Set<String> names = mp.keySet();
        Iterator<String> it = names.iterator();
        while (it.hasNext()) {
            String next = it.next();
            log.debug("Next key: " + next);
            String value = mp.getProperty(next).get(0);
            log.debug("Next value: " + value);
            if (image.trim().equals(value)) {
                return next;
            }
        }
        try {
            // return the last segment of the path
            int lastSlash = image.lastIndexOf("/");
            String name = image.substring(lastSlash + 1, image.length());
            // replace colons and dots with dash
            name = name.replaceAll(":", "-");
            name = name.replaceAll(".", "-");
            return name.toLowerCase();
        } catch (Exception e) {
            log.warn("failed to determine name for image: " + image);
            return "unknown";
        }

    }
    
    protected String stageFile(String data) throws IOException {
        String tmpFileName = "/tmp/" + UUID.randomUUID();
        File file = new File(tmpFileName);
        if (!file.setExecutable(true, true)) {
            log.warn("Failed to set execution permssion on file " + tmpFileName);
        }
        BufferedWriter writer = new BufferedWriter(new FileWriter(file));
        writer.write(data + "\n");
        writer.flush();
        writer.close();
        return tmpFileName;
    }
    
    public List<Session> getAllSessions(String forUserID) throws Exception {
        String k8sNamespace = K8SUtil.getWorkloadNamespace();
        List<String> getSessionsCMD = new ArrayList<String>();
        getSessionsCMD.add("kubectl");
        getSessionsCMD.add("get");
        getSessionsCMD.add("--namespace");
        getSessionsCMD.add(k8sNamespace);
        getSessionsCMD.add("pod");
        if (forUserID != null) {
            getSessionsCMD.add("--selector=canfar-net-userid=" + forUserID);
        }
        getSessionsCMD.add("--no-headers=true");
        getSessionsCMD.add("-o");
        
        String customColumns = "custom-columns=" +
            "SESSIONID:.metadata.labels.canfar-net-sessionID," + 
            "USERID:.metadata.labels.canfar-net-userid," +
            "IMAGE:.spec.containers[0].image," +
            "TYPE:.metadata.labels.canfar-net-sessionType," +
            "STATUS:.status.phase," +
            "NAME:.metadata.labels.canfar-net-sessionName," +
            "STARTED:.status.startTime," +
            "DELETION:.metadata.deletionTimestamp";
        
        getSessionsCMD.add(customColumns);
                
        String vncSessions = execute(getSessionsCMD.toArray(new String[0]));
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
    
    protected Session constructSession(String k8sOutput) throws IOException {
        log.debug("line: " + k8sOutput);
        String[] parts = k8sOutput.split("\\s+");
        String id = parts[0];
        String userid = parts[1];
        String image = parts[2];
        String type = parts[3];
        String status = parts[4];
        String name = parts[5];
        String startTime = "Up since " + parts[6];
        String deletionTimestamp = parts[7];
        if (deletionTimestamp != null && !"<none>".equals(deletionTimestamp)) {
            status = Session.STATUS_TERMINATING;
        }
        String host = K8SUtil.getHostName();
        String connectURL = "not-applicable";
        if (SessionAction.SESSION_TYPE_DESKTOP.equals(type)) {
            connectURL = SessionAction.getVNCURL(host, id);
        }
        if (SessionAction.SESSION_TYPE_CARTA.equals(type)) {
            if (image.endsWith(":1.4")) {
                // support alt web socket path for 1.4 carta 
                connectURL = SessionAction.getCartaURL(host, id, true); 
            } else {
                connectURL = SessionAction.getCartaURL(host, id, false);
            }
        }
        if (SessionAction.SESSION_TYPE_NOTEBOOK.equals(type)) {
            connectURL = SessionAction.getNotebookURL(host, id);
        }

        return new Session(id, userid, image, type, status, name, startTime, connectURL);
        
    }
    
}
