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

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import javax.security.auth.Subject;

import org.apache.log4j.Logger;

import ca.nrc.cadc.auth.AuthenticationUtil;
import ca.nrc.cadc.auth.PosixPrincipal;
import ca.nrc.cadc.util.StringUtil;
import ca.nrc.cadc.uws.server.RandomStringGenerator;

/**
 *
 * @author majorb
 */
public class PostAction extends SessionAction {
    
    private static final Logger log = Logger.getLogger(PostAction.class);
    
    // variables replaced in kubernetes yaml config files for
    // launching desktop sessions and launching software
    // use in the form: ${var.name}
    public static final String ARCADE_HOSTNAME = "arcade.hostname";
    public static final String ARCADE_USERID = "arcade.userid";
    public static final String ARCADE_POSIXID = "arcade.posixid";
    public static final String ARCADE_SESSIONID = "arcade.sessionid";
    public static final String ARCADE_SESSIONNAME = "arcade.sessionname";
    public static final String ARCADE_SESSIONTYPE = "arcade.sessiontype";
    public static final String ARCADE_JOBNAME = "arcade.jobname";
    public static final String SOFTWARE_JOBNAME = "software.jobname";
    public static final String SOFTWARE_CONTAINERNAME = "software.containername";
    public static final String SOFTWARE_CONTAINERPARAM = "software.containerparam";
    public static final String SOFTWARE_TARGETIP = "software.targetip";
    public static final String SOFTWARE_IMAGEID = "software.imageid";

    public PostAction() {
        super();
    }

    @Override
    public void doAction() throws Exception {
        
        super.initRequest();
        
        if (requestType.equals(REQUEST_TYPE_SESSION)) {
            if (sessionID == null) {
                                
                String name = syncInput.getParameter("name");
                String type = syncInput.getParameter("type");
                if (name == null) {
                    throw new IllegalArgumentException("Missing parameter 'name'");
                }
                if (type == null) {
                    throw new IllegalArgumentException("Missing parameter 'type'");
                }
                validateName(name);
                validateType(type);
                
                // check for no existing session for this user
                // (rule: only 1 session per user allowed)
                checkForExistingSession(userID, type);
                
                // create a new session id
                // (VNC passwords are only good up to 8 characters)
                sessionID = new RandomStringGenerator(8).getID();
                URL sessionURL = createSession(sessionID, type, name);
                
                syncOutput.setHeader("Location", sessionURL.toString());
                syncOutput.setCode(303);
                
            } else {
                throw new UnsupportedOperationException("Cannot modify an existing session.");
            }
            return;
        }
        if (requestType.equals(REQUEST_TYPE_APP)) {
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
    
    private void validateType(String type) {
        if (!StringUtil.hasText(type)) {
            throw new IllegalArgumentException("type must have a value");
        }
        if (SessionAction.SESSION_TYPE_DESKTOP.equals(type) ||
            SessionAction.SESSION_TYPE_CARTA.equals(type) ||
            SessionAction.SESSION_TYPE_NOTEBOOK.equals(type)) {
            return;
        }
        throw new IllegalArgumentException("type must be one of " + SessionAction.SESSION_TYPE_DESKTOP
            + ", " + SessionAction.SESSION_TYPE_NOTEBOOK + " or " + SessionAction.SESSION_TYPE_CARTA);
    }
    
    public void checkForExistingSession(String userid, String type) throws Exception {
        List<Session> sessions = GetAction.getAllSessions(userid);
        for (Session session : sessions) {
            if (session.getType().equals(type) && !session.getStatus().equals(Session.STATUS_TERMINATING)) {
                throw new IllegalArgumentException("User " + userID + " has a session already running.");
            }
        }
    }
    
    public URL createSession(String sessionID, String type, String name) throws Exception {
        
        String jobName = K8SUtil.getJobName(sessionID, type, userID);
        String posixID = getPosixId();
        log.debug("Posix id: " + posixID);
        
        String launchPath = null;
        switch (type) {
            case SessionAction.SESSION_TYPE_DESKTOP:
                launchPath = System.getProperty("user.home") + "/config/launch-novnc.yaml";
                break;
            case SessionAction.SESSION_TYPE_CARTA:
                launchPath = System.getProperty("user.home") + "/config/launch-carta.yaml";
                break;
            case SessionAction.SESSION_TYPE_NOTEBOOK:
                launchPath = System.getProperty("user.home") + "/config/launch-notebook.yaml";
                break;
            default:
                throw new IllegalStateException("Bug: unknown session type: " + type);
        }
        byte[] launchBytes = Files.readAllBytes(Paths.get(launchPath));
        String launchString = new String(launchBytes, "UTF-8");
        
        launchString = setConfigValue(launchString, ARCADE_SESSIONID, sessionID);
        launchString = setConfigValue(launchString, ARCADE_SESSIONNAME, name);
        launchString = setConfigValue(launchString, ARCADE_SESSIONTYPE, type);
        launchString = setConfigValue(launchString, ARCADE_JOBNAME, jobName);
        launchString = setConfigValue(launchString, ARCADE_HOSTNAME, K8SUtil.getHostName());
        launchString = setConfigValue(launchString, ARCADE_USERID, userID);
        launchString = setConfigValue(launchString, ARCADE_POSIXID, posixID);
        
        String jsonLaunchFile = super.stageFile(launchString);
        String k8sNamespace = K8SUtil.getWorkloadNamespace();
          
        String[] launchCmd = new String[] {
            "kubectl", "create", "--namespace", k8sNamespace, "-f", jsonLaunchFile};
        String createResult = execute(launchCmd);
        log.debug("Create result: " + createResult);
        
        // insert the user's proxy cert in the home dir
        Subject subject = AuthenticationUtil.getCurrentSubject();   
        injectProxyCert("/cavern/home", subject, userID, posixID);
        
        // give the container a few seconds to initialize
        try {
            log.debug("3 second wait for vnc initialization");
            Thread.sleep(3000);
        } catch (InterruptedException ignore) {
        }
        log.debug("wait over");
        
        URL sessionLink = null;
        switch (type) {
            case SessionAction.SESSION_TYPE_DESKTOP:
                sessionLink = new URL(super.getVNCURL(K8SUtil.getHostName(), sessionID));
                break;
            case SessionAction.SESSION_TYPE_CARTA:
                sessionLink = new URL(super.getCartaURL(K8SUtil.getHostName(), sessionID));
                break;
            case SessionAction.SESSION_TYPE_NOTEBOOK:
                sessionLink = new URL(super.getNotebookURL(K8SUtil.getHostName(), sessionID));
                break;
            default:
                throw new IllegalStateException("Bug: unknown session type: " + type);
        }
        
        log.debug("session redirect: " + sessionLink);
        return sessionLink;
    }
    
    public void attachSoftware(String software, List<String> params, String targetIP) throws Exception {
        
        // TODO: Ensure session at targetIP is of type desktop
        
        String name = confirmSoftware(software);
        
        String posixID = getPosixId();

        String launchSoftwarePath = System.getProperty("user.home") + "/config/launch-software.yaml";
        byte[] launchBytes = Files.readAllBytes(Paths.get(launchSoftwarePath));
        
        // default parameter: xterm
        String param = "xterm\n        - -T\n        - " + name;
        
        // only one parameter supported for now
        if (params != null && params.size() > 0) {
            param = params.get(0);
        }
        log.debug("Using parameter: " + param);
        
        String uniqueID = new RandomStringGenerator(8).getID();
        String jobName = name.toLowerCase() + "-" + userID.toLowerCase() + "-" + sessionID + "-" + uniqueID;
        String containerName = name.toLowerCase().replaceAll("\\.", "-"); // no dots in k8s names
        
        String launchString = new String(launchBytes, "UTF-8");
        launchString = setConfigValue(launchString, SOFTWARE_JOBNAME, jobName);
        launchString = setConfigValue(launchString, SOFTWARE_CONTAINERNAME, containerName);
        launchString = setConfigValue(launchString, SOFTWARE_CONTAINERPARAM, param);
        launchString = setConfigValue(launchString, ARCADE_USERID, userID);
        launchString = setConfigValue(launchString, SOFTWARE_TARGETIP, targetIP + ":1");
        launchString = setConfigValue(launchString, ARCADE_POSIXID, posixID);
        launchString = setConfigValue(launchString, SOFTWARE_IMAGEID, software);
                       
        String launchFile = super.stageFile(launchString);
        
        String k8sNamespace = K8SUtil.getWorkloadNamespace();
        
        String[] launchCmd = new String[] {
            "kubectl", "create", "--namespace", k8sNamespace, "-f", launchFile};
        String createResult = execute(launchCmd);
        log.debug("Create result: " + createResult);
        
        // refresh the user's proxy cert
        Subject subject = AuthenticationUtil.getCurrentSubject();
        injectProxyCert("/cavern/home", subject, userID, posixID);
    }
    
    private String getPosixId() {
        Subject s = AuthenticationUtil.getCurrentSubject();
        Set<PosixPrincipal> principals = s.getPrincipals(PosixPrincipal.class);
        int uidNumber = principals.iterator().next().getUidNumber();
        return Integer.toString(uidNumber);
    }
    
    private String setConfigValue(String doc, String key, String value) {
        String regKey = key.replace(".", "\\.");
        String regex = "\\$[{]" + regKey + "[}]";
        return doc.replaceAll(regex, value);
    }

}
