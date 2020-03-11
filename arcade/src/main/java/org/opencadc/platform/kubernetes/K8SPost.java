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

package org.opencadc.platform.kubernetes;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.security.auth.Subject;

import org.apache.log4j.Logger;
import org.opencadc.platform.PostAction;

import ca.nrc.cadc.auth.AuthenticationUtil;
import ca.nrc.cadc.auth.PosixPrincipal;
import ca.nrc.cadc.util.MultiValuedProperties;
import ca.nrc.cadc.util.PropertiesReader;
import ca.nrc.cadc.util.StringUtil;
import ca.nrc.cadc.uws.server.RandomStringGenerator;

public class K8SPost extends PostAction {
    
    private static final Logger log = Logger.getLogger(K8SPost.class);
    
    // variables replaced in kubernetes yaml config files for
    // launching desktop sessions and launching software
    // use in the form: ${var.name}
    public static final String ARCADE_HOSTNAME = "arcade.hostname";
    public static final String ARCADE_USERID = "arcade.userid";
    public static final String ARCADE_POSIXID = "arcade.posixid";
    public static final String DESKTOP_SESSIONID = "desktop.sessionid";
    public static final String DESKTOP_SESSIONNAME = "desktop.sessionname";
    public static final String DESKTOP_PODNAME = "desktop.podname";
    public static final String SOFTWARE_JOBNAME = "software.jobname";
    public static final String SOFTWARE_CONTAINERNAME = "software.containername";
    public static final String SOFTWARE_CONTAINERPARAM = "software.containerparam";
    public static final String SOFTWARE_TARGETIP = "software.targetip";
    public static final String SOFTWARE_IMAGEID = "software.imageid";
    
    public K8SPost() throws IOException {
        super();
    }
    
    @Override
    public void checkForExistingSession(String userid) throws Exception {
        
        String k8sNamespace = K8SUtil.getWorkloadNamespace();
        String[] getSessionsCMD = new String[] {
            "kubectl", "get", "--namespace", k8sNamespace, "pod",
            "--selector=canfar-net-userid=" + userID,
            "--no-headers=true"};
                
        String vncSessions = execute(getSessionsCMD);
        log.debug("VNC Session list: " + vncSessions);
        
        if (StringUtil.hasLength(vncSessions)) {
            throw new IllegalArgumentException("User " + userID + " has a session already running.");
        }
    }
    
    @Override
    public URL createSession(String sessionID, String name) throws Exception {
        
        String podName = K8SUtil.getPodName(sessionID, userID);
        String posixID = getPosixId();
        log.debug("Posix id: " + posixID);
        
        String launchPath = System.getProperty("user.home") + "/config/launch-novnc.yaml";
        //String exposePath = System.getProperty("user.home") + "/config/expose-novnc.yaml";
        byte[] launchBytes = Files.readAllBytes(Paths.get(launchPath));
        //byte[] exposeBytes = Files.readAllBytes(Paths.get(exposePath));
        
        String launchString = new String(launchBytes, "UTF-8");
        launchString = setConfigValue(launchString, DESKTOP_SESSIONID, sessionID);
        launchString = setConfigValue(launchString, DESKTOP_SESSIONNAME, name);
        launchString = setConfigValue(launchString, DESKTOP_PODNAME, podName);
        launchString = setConfigValue(launchString, ARCADE_HOSTNAME, K8SUtil.getHostName());
        launchString = setConfigValue(launchString, ARCADE_USERID, userID);
        launchString = setConfigValue(launchString, ARCADE_POSIXID, posixID);

        //String expose = String.format(
        //    new String(exposeBytes, "UTF-8"),
        //    podName);      // service name
        
        String jsonLaunchFile = super.stageFile(launchString);
        //String jsonExposeFile = super.stageFile(expose);
        
        String k8sNamespace = K8SUtil.getWorkloadNamespace();
          
        String[] launchCmd = new String[] {
            "kubectl", "create", "--namespace", k8sNamespace, "-f", jsonLaunchFile};
        String createResult = execute(launchCmd);
        log.debug("Create result: " + createResult);
          
        //String[] exposeCmd = new String[] {
        //    "kubectl", "create", "--namespace", k8sNamespace, "-f", jsonExposeFile};
        //String exposeResult = execute(exposeCmd);
        //log.debug("Expose result: " + exposeResult);
        
        String[] getIPCmd = new String[] {
            "kubectl", "get", "--namespace", k8sNamespace, "pod",
            "--selector=canfar-net-sessionID=" + sessionID,
            "-o", "jsonpath={.items[0].status.podIP}"};
        String ipAddress = "";
        int attempts = 0;
        while (!StringUtil.hasText(ipAddress) && attempts < 10) {          
            try {
                log.debug("Sleeping for 1 second");
                Thread.sleep(1000);
            } catch (InterruptedException e) {
            }
            log.debug("awake now");
            ipAddress = execute(getIPCmd);
            attempts++;
        }
        log.debug("pod IP: " + ipAddress);
        
        // insert the user's proxy cert in the home dir
        Subject subject = AuthenticationUtil.getCurrentSubject();   
        injectProxyCert("/cavern/home", subject, userID, posixID);
        
        // give vnc a few seconds to initialize
        try {
            log.debug("3 second wait for vnc initialization");
            Thread.sleep(2000);
        } catch (InterruptedException ignore) {
        }
        log.debug("wait over");
        
        URL sessionLink = new URL(super.getVNCURL(K8SUtil.getHostName(), sessionID, ipAddress));
        log.debug("session redirect: " + sessionLink);
        return sessionLink;
    }
    
    @Override
    public void attachSoftware(String software, List<String> params, String targetIP) throws Exception {
        
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
        String jobName = name + "-" + userID + "-" + sessionID + "-" + uniqueID;
        String containerName = name.replaceAll("\\.", "-"); // no dots in k8s names
        
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
