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

import ca.nrc.cadc.ac.Group;
import ca.nrc.cadc.auth.AuthenticationUtil;
import ca.nrc.cadc.auth.PosixPrincipal;
import ca.nrc.cadc.net.HttpGet;
import ca.nrc.cadc.net.ResourceNotFoundException;
import ca.nrc.cadc.util.StringUtil;
import ca.nrc.cadc.uws.server.RandomStringGenerator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.security.auth.Subject;

import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.opencadc.skaha.K8SUtil;
import org.opencadc.skaha.context.ResourceContexts;
import org.opencadc.skaha.image.Image;

/**
 *
 * @author majorb
 */
public class PostAction extends SessionAction {
    
    private static final Logger log = Logger.getLogger(PostAction.class);
    
    // k8s rejects label size > 63. Since k8s appends a maximum of six characters
    // to a job name to form a pod name, we limit the job name length to 57 characters.
    private static final int MAX_JOB_NAME_LENGTH = 57;
    
    // variables replaced in kubernetes yaml config files for
    // launching desktop sessions and launching software
    // use in the form: ${var.name}
    public static final String SKAHA_HOSTNAME = "skaha.hostname";
    public static final String SKAHA_USERID = "skaha.userid";
    public static final String SKAHA_POSIXID = "skaha.posixid";
    public static final String SKAHA_SUPPLEMENTALGROUPS = "skaha.supgroups";
    public static final String SKAHA_SESSIONID = "skaha.sessionid";
    public static final String SKAHA_SESSIONNAME = "skaha.sessionname";
    public static final String SKAHA_SESSIONTYPE = "skaha.sessiontype";
    public static final String SKAHA_SESSIONEXPIRY = "skaha.sessionexpiry";
    public static final String SKAHA_JOBNAME = "skaha.jobname";
    public static final String SKAHA_SCHEDULEGPU = "skaha.schedulegpu";
    public static final String SOFTWARE_JOBNAME = "software.jobname";
    public static final String SOFTWARE_HOSTNAME = "software.hostname";
    public static final String SOFTWARE_CONTAINERNAME = "software.containername";
    public static final String SOFTWARE_CONTAINERPARAM = "software.containerparam";
    public static final String SOFTWARE_TARGETIP = "software.targetip";
    public static final String SOFTWARE_IMAGEID = "software.imageid";
    public static final String SOFTWARE_IMAGESECRET = "software.imagesecret";
    public static final String SOFTWARE_REQUESTS_CORES = "software.requests.cores";
    public static final String SOFTWARE_REQUESTS_RAM = "software.requests.ram";
    public static final String SOFTWARE_LIMITS_CORES = "software.limits.cores";
    public static final String SOFTWARE_LIMITS_RAM = "software.limits.ram";
    public static final String SOFTWARE_LIMITS_GPUS = "software.limits.gpus";
    public static final String HEADLESS_IMAGE_BUNDLE = "headless.image.bundle";


    public PostAction() {
        super();
    }

    @Override
    public void doAction() throws Exception {
        
        super.initRequest();
        
        if (requestType.equals(REQUEST_TYPE_SESSION)) {
            if (sessionID == null) {
                                
                String name = syncInput.getParameter("name");
                String image = syncInput.getParameter("image");
                String type = syncInput.getParameter("type");
                String coresParam = syncInput.getParameter("cores");
                String ramParam = syncInput.getParameter("ram");
                String gpusParam = syncInput.getParameter("gpus");
                String cmd = syncInput.getParameter("cmd");
                String args = syncInput.getParameter("args");
                List<String> envs = syncInput.getParameters("env");
                if (name == null) {
                    throw new IllegalArgumentException("Missing parameter 'name'");
                }
                if (image == null) {
                    throw new IllegalArgumentException("Missing parameter 'image'");
                }
                validateName(name);
                String validatedType = validateImage(image, type);
                
                // check for no existing session for this user
                // (rule: only 1 session of same type per user allowed)
                checkExistingSessions(userID, validatedType);
                
                // create a new session id
                // (VNC passwords are only good up to 8 characters)
                sessionID = new RandomStringGenerator(8).getID();

                ResourceContexts rc = new ResourceContexts();
                Integer cores = rc.getDefaultCores(validatedType);
                Integer ram = rc.getDefaultRAM(validatedType);
                Integer gpus = 0;
                
                if (coresParam != null) {
                    try {
                        cores = Integer.valueOf(coresParam);
                        if (!rc.getAvailableCores().contains(cores)) {
                            throw new IllegalArgumentException("Unavailable option for 'cores': " + coresParam);
                        }
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Invalid value for 'cores': " + coresParam);
                    }
                }
                
                if (ramParam != null) {
                    try {
                        ram = Integer.valueOf(ramParam);
                        if (!rc.getAvailableRAM().contains(ram)) {
                            throw new IllegalArgumentException("Unavailable option for 'ram': " + ramParam);
                        }
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Invalid value for 'ram': " + ramParam);
                    }
                }
                
                if (gpusParam != null) {
                    try {
                        gpus = Integer.valueOf(gpusParam);
                        if (!rc.getAvailableGPUs().contains(gpus)) {
                            throw new IllegalArgumentException("Unavailable option for 'gpus': " + gpusParam);
                        }
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Invalid value for 'gpus': " + gpusParam);
                    }
                }
                
                createSession(sessionID, validatedType, image, name, cores, ram, gpus, cmd, args, envs);
                // return the session id
                syncOutput.setHeader("Content-Type", "text/plain");
                syncOutput.getOutputStream().write((sessionID + "\n").getBytes());
                
            } else {
                String action = syncInput.getParameter("action");
                if (StringUtil.hasLength(action)) {
                    if (action.equalsIgnoreCase("renew")) {
                        Map<String, List<String>> jobNameToAttributesMap = getJobsToRenew(userID, sessionID);
                        if (!jobNameToAttributesMap.isEmpty()) {
                            for (Map.Entry<String, List<String>> entry : jobNameToAttributesMap.entrySet()) {
                                renew(entry);
                            }
                        } else {
                            throw new IllegalArgumentException("No active job for user " + userID + " with session " + sessionID);
                        }
                    } else {
                        throw new UnsupportedOperationException("unrecognized action");
                    }
                } else {
                    throw new UnsupportedOperationException("Cannot modify an existing session");
                }
            }
            return;
        }
        if (requestType.equals(REQUEST_TYPE_APP)) {
            if (appID == null) {
                // create an app
                
                // gather job parameters
                String image = syncInput.getParameter("image");
                List<String> params = syncInput.getParameters("param");
                
                if (image == null) {
                    throw new IllegalArgumentException("Missing parameter 'image'");
                }
                
                attachDesktopApp(image, params);
                
            } else {
                throw new UnsupportedOperationException("Cannot modify an existing app.");
            }
        }
    }
    
    private void renew(Map.Entry<String, List<String>> entry) throws Exception {
        Long newExpiryTime = calculateExpiryTime(entry.getValue());
        if (newExpiryTime > 0) {
            String k8sNamespace = K8SUtil.getWorkloadNamespace();
            List<String> renewExpiryTimeCmd = new ArrayList<String>();
            renewExpiryTimeCmd.add("kubectl");
            renewExpiryTimeCmd.add("--namespace");
            renewExpiryTimeCmd.add(k8sNamespace);
            renewExpiryTimeCmd.add("patch");
            renewExpiryTimeCmd.add("job");
            renewExpiryTimeCmd.add(entry.getKey());
            renewExpiryTimeCmd.add("--type=json");
            renewExpiryTimeCmd.add("-p");
            renewExpiryTimeCmd.add("[{\"op\":\"add\",\"path\":\"/spec/activeDeadlineSeconds\", \"value\":" + newExpiryTime + "}]");
            execute(renewExpiryTimeCmd.toArray(new String[0]));
        }
    }
    
    
    private Long calculateExpiryTime(List<String> jobAttributes) throws Exception {
        String uid = jobAttributes.get(0);
        String startTimeStr = jobAttributes.get(1);

        String configuredExpiryStr = K8SUtil.getSessionExpiry();
        if (!StringUtil.hasLength(configuredExpiryStr)) {
            throw new IllegalStateException("missing configuration item " + SKAHA_SESSIONEXPIRY);
        }
        Long configuredExpiryTime = Long.parseLong(configuredExpiryStr);

        String k8sNamespace = K8SUtil.getWorkloadNamespace();
        Map<String, String> jobExpiryTimeMap = getJobExpiryTimes(k8sNamespace, userID);
        String activeDeadlineSecondsStr = jobExpiryTimeMap.get(uid);
        if (StringUtil.hasLength(activeDeadlineSecondsStr)) {
            Long activeDeadlineSeconds = Long.parseLong(activeDeadlineSecondsStr);
            Instant startTime = Instant.parse(startTimeStr);
            
            Long timeUsed = startTime.until(Instant.now(), ChronoUnit.SECONDS);
            Long timeRemaining = activeDeadlineSeconds - timeUsed;
            // default to no need to extend the expiry time
            Long calculatedTime = 0L;
            if (configuredExpiryTime > timeRemaining) {
                // add elapsed time (from startTime to now) to the configured expiry time
                calculatedTime = startTime.until(Instant.now(), ChronoUnit.SECONDS) + configuredExpiryTime;
            }

            return calculatedTime;
        } else {
            throw new IllegalStateException("missing configuration item activeDeadlineSeconds");
        }
    }
    
    private Map<String, List<String>> getJobsToRenew(String forUserID, String sessionID) throws Exception {
        String k8sNamespace = K8SUtil.getWorkloadNamespace();
        List<String> getRenewJobNamesCmd = new ArrayList<String>();
        getRenewJobNamesCmd.add("kubectl");
        getRenewJobNamesCmd.add("get");
        getRenewJobNamesCmd.add("--namespace");
        getRenewJobNamesCmd.add(k8sNamespace);
        getRenewJobNamesCmd.add("job");
        getRenewJobNamesCmd.add("-l");
        getRenewJobNamesCmd.add("canfar-net-sessionID=" + sessionID + ",canfar-net-userid=" + forUserID);
        getRenewJobNamesCmd.add("--no-headers=true");
        getRenewJobNamesCmd.add("-o");
        
        String customColumns = "custom-columns=" +
            "NAME:.metadata.name," + 
            "UID:.metadata.uid," + 
            "STATUS:.status.active," + 
            "START:.status.startTime";
        
        getRenewJobNamesCmd.add(customColumns);
                
        String renewJobNamesStr = execute(getRenewJobNamesCmd.toArray(new String[0]));
        log.debug("jobs for user " + forUserID + " with session ID=" + sessionID + ":\n" + renewJobNamesStr);
       
        Map<String, List<String>> renewJobMap = new HashMap<String, List<String>>();
        if (StringUtil.hasLength(renewJobNamesStr)) {
            String[] lines = renewJobNamesStr.split("\n");
            if (lines.length > 0) {
                for (String line : lines) {
                    List<String> renewJobAttributes = new ArrayList<String>();
                    String[] parts = line.replaceAll("\\s+", " ").trim().split(" ");
                    String jobName = parts[0];
                    String uid = parts[1];
                    String isActive= parts[2];
                    String startTime= parts[3];
                    // look for the job ID of an active session
                    if (!NONE.equalsIgnoreCase(isActive) && (Integer.parseInt(isActive) == 1)) {
                        renewJobAttributes.add(uid);
                        renewJobAttributes.add(startTime);
                        renewJobMap.put(jobName, renewJobAttributes);
                    }
                }
            }
        }

        return renewJobMap;
    }
    
    private void validateName(String name) {
        if (!StringUtil.hasText(name)) {
            throw new IllegalArgumentException("name must have a value");
        }
        if (!name.matches("[A-Za-z0-9\\-]+")) {
            throw new IllegalArgumentException("name can only contain alpha-numeric chars and '-'");
        }
    }
    
    /**
     * Validate and return the session type
     * 
     * @param imageID The image to validate
     * @param type User-provided session type (optional)
     * @return The system recognized session type
     * @throws ResourceNotFoundException 
     */
    private String validateImage(String imageID, String type) throws Exception {
        if (!StringUtil.hasText(imageID)) {
            throw new IllegalArgumentException("image is required");
        }
        
        for (String harborHost : harborHosts) {
            if (imageID.startsWith(harborHost)) {
                Image image = getImage(imageID);
                if (image == null) {
                    throw new ResourceNotFoundException("image not found or not labelled: " + imageID);
                }
                if (type != null && !type.equals(image.getType())) {
                    throw new IllegalArgumentException("image/type mismatch: " + imageID + "/" + type);
                }
                return image.getType();
            }
        }
                
        if (adminUser && type != null) {
            if (!SESSION_TYPES.contains(type)) {
                throw new IllegalArgumentException("Illegal session type: " + type);
            }
            return type;
        }
        
        StringBuilder hostList = new StringBuilder("[").append(harborHosts.get(0));
        for (String next : harborHosts.subList(1, harborHosts.size())) {
            hostList.append(",").append(next);
        }
        hostList.append("]");
        
        throw new IllegalArgumentException("session image must come from one of " + hostList);
        
    }
    
    public void checkExistingSessions(String userid, String type) throws Exception {
        // multiple 
        if (SESSION_TYPE_HEADLESS.equals(type)) {
            return;
        }
        List<Session> sessions = super.getAllSessions(userid);
        int count = 0;
        for (Session session : sessions) {
            log.debug("checking session: " + session);
            if (!SESSION_TYPE_HEADLESS.equalsIgnoreCase(session.getType()) &&
                !TYPE_DESKTOP_APP.equals(session.getType())) {
                String status = session.getStatus();
                if (!(status.equalsIgnoreCase(Session.STATUS_TERMINATING) ||
                      status.equalsIgnoreCase(Session.STATUS_SUCCEEDED))) {
                    count++;
                }
            }
        }
        log.debug("active interactive sessions: " + count);
        if (count >= maxUserSessions) {
            throw new IllegalArgumentException("User " + userID + " has reached the maximum of " +
                maxUserSessions + " active sessions.");
        }
    }
    
    public void createSession(String sessionID, String type, String image, String name,
        Integer cores, Integer ram, Integer gpus, String cmd, String args, List<String> envs) throws Exception {
        
        String jobName = K8SUtil.getJobName(sessionID, type, userID);
        String posixID = getPosixId();
        log.debug("Posix id: " + posixID);
        
        String imageSecret = getHarborSecret(image);            
        log.debug("image secret: " + imageSecret);
        if (imageSecret == null) {
            imageSecret = "notused";
        }
        
        String supplementalGroups = getSupplementalGroupsList();
        
        String jobLaunchPath = null;
        String servicePath = null;
        String ingressPath = null;
        switch (type) {
            case SessionAction.SESSION_TYPE_DESKTOP:
                jobLaunchPath = System.getProperty("user.home") + "/config/launch-desktop.yaml";
                servicePath = System.getProperty("user.home") + "/config/service-desktop.yaml";
                ingressPath = System.getProperty("user.home") + "/config/ingress-desktop.yaml";
                break;
            case SessionAction.SESSION_TYPE_CARTA:
                jobLaunchPath = System.getProperty("user.home") + "/config/launch-carta.yaml";
                servicePath = System.getProperty("user.home") + "/config/service-carta.yaml";
                ingressPath = System.getProperty("user.home") + "/config/ingress-carta.yaml";
                break;
            case SessionAction.SESSION_TYPE_NOTEBOOK:
                jobLaunchPath = System.getProperty("user.home") + "/config/launch-notebook.yaml";
                servicePath = System.getProperty("user.home") + "/config/service-notebook.yaml";
                ingressPath = System.getProperty("user.home") + "/config/ingress-notebook.yaml";
                break;
            case SessionAction.SESSION_TYPE_CONTRIB:
                jobLaunchPath = System.getProperty("user.home") + "/config/launch-contributed.yaml";
                servicePath = System.getProperty("user.home") + "/config/service-contributed.yaml";
                ingressPath = System.getProperty("user.home") + "/config/ingress-contributed.yaml";
                break;
            case SessionAction.SESSION_TYPE_HEADLESS:
                jobLaunchPath = System.getProperty("user.home") + "/config/launch-headless.yaml";
                break;
            default:
                throw new IllegalStateException("Bug: unknown session type: " + type);
        }
        byte[] jobLaunchBytes = Files.readAllBytes(Paths.get(jobLaunchPath));
        String jobLaunchString = new String(jobLaunchBytes, "UTF-8");
        String headlessImageBundle = getHeadlessImageBundle(image, cmd, args, envs);
        String gpuScheduling = getGPUScheduling(gpus);
        
        jobLaunchString = setConfigValue(jobLaunchString, SKAHA_SESSIONID, sessionID);
        jobLaunchString = setConfigValue(jobLaunchString, SKAHA_SESSIONNAME, name.toLowerCase());
        jobLaunchString = setConfigValue(jobLaunchString, SKAHA_SESSIONEXPIRY, K8SUtil.getSessionExpiry());
        jobLaunchString = setConfigValue(jobLaunchString, SKAHA_JOBNAME, jobName);
        jobLaunchString = setConfigValue(jobLaunchString, SKAHA_HOSTNAME, K8SUtil.getHostName());
        jobLaunchString = setConfigValue(jobLaunchString, SKAHA_USERID, userID);
        jobLaunchString = setConfigValue(jobLaunchString, SKAHA_POSIXID, posixID);
        jobLaunchString = setConfigValue(jobLaunchString, SKAHA_SUPPLEMENTALGROUPS, supplementalGroups); 
        jobLaunchString = setConfigValue(jobLaunchString, SKAHA_SESSIONTYPE, type);
        jobLaunchString = setConfigValue(jobLaunchString, SKAHA_SCHEDULEGPU, gpuScheduling);
        jobLaunchString = setConfigValue(jobLaunchString, SOFTWARE_IMAGEID, image);
        jobLaunchString = setConfigValue(jobLaunchString, SOFTWARE_HOSTNAME, name.toLowerCase());
        jobLaunchString = setConfigValue(jobLaunchString, SOFTWARE_IMAGESECRET, imageSecret);
        jobLaunchString = setConfigValue(jobLaunchString, HEADLESS_IMAGE_BUNDLE, headlessImageBundle);
        jobLaunchString = setConfigValue(jobLaunchString, SOFTWARE_REQUESTS_CORES, cores.toString());
        jobLaunchString = setConfigValue(jobLaunchString, SOFTWARE_REQUESTS_RAM, ram.toString() + "Gi");
        jobLaunchString = setConfigValue(jobLaunchString, SOFTWARE_LIMITS_CORES, cores.toString());
        jobLaunchString = setConfigValue(jobLaunchString, SOFTWARE_LIMITS_RAM, ram.toString() + "Gi");
        jobLaunchString = setConfigValue(jobLaunchString, SOFTWARE_LIMITS_GPUS, gpus.toString());
        
        String jsonLaunchFile = super.stageFile(jobLaunchString);
        String k8sNamespace = K8SUtil.getWorkloadNamespace();
          
        String[] launchCmd = new String[] {
            "kubectl", "create", "--namespace", k8sNamespace, "-f", jsonLaunchFile};
        String createResult = execute(launchCmd);
        log.debug("Create job result: " + createResult);
        
        // insert the user's proxy cert in the home dir
        Subject subject = AuthenticationUtil.getCurrentSubject();   
        injectProxyCert(subject, userID, posixID);
        
        if (servicePath != null) {
            byte[] serviceBytes = Files.readAllBytes(Paths.get(servicePath));
            String serviceString = new String(serviceBytes, "UTF-8");
            serviceString = setConfigValue(serviceString, SKAHA_SESSIONID, sessionID);
            jsonLaunchFile = super.stageFile(serviceString);
            launchCmd = new String[] {
                "kubectl", "create", "--namespace", k8sNamespace, "-f", jsonLaunchFile};
            createResult = execute(launchCmd);
            log.debug("Create service result: " + createResult);
        }
        
        if (ingressPath != null) {
            byte[] ingressBytes = Files.readAllBytes(Paths.get(ingressPath));
            String ingressString = new String(ingressBytes, "UTF-8");
            ingressString = setConfigValue(ingressString, SKAHA_SESSIONID, sessionID);
            jsonLaunchFile = super.stageFile(ingressString);
            launchCmd = new String[] {
                "kubectl", "create", "--namespace", k8sNamespace, "-f", jsonLaunchFile};
            createResult = execute(launchCmd);
            log.debug("Create ingress result: " + createResult);
        }
        
        // Removed 3 second delay--container initialization cannot be quantified
        // so this is pointless.
        //try {
        //    log.debug("3 second wait for container initialization");
        //    Thread.sleep(3000);
        //} catch (InterruptedException ignore) {
        //}

    }
    
    public void attachDesktopApp(String image, List<String> params) throws Exception {
        
        String k8sNamespace = K8SUtil.getWorkloadNamespace();
        
        // Get the IP address based on the session
        String[] getIPCommand = new String[] {
            "kubectl", "-n", k8sNamespace, "get", "pod", "--selector=canfar-net-sessionID=" + sessionID,
                "--no-headers=true", 
                "-o", "custom-columns=" +
                    "IPADDR:.status.podIP," +
                    "DT:.metadata.deletionTimestamp," +
                    "TYPE:.metadata.labels.canfar-net-sessionType"};
        String ipResult = execute(getIPCommand);
        log.debug("GET IP result: " + ipResult);
        
        String targetIP = null;
        String[] ipLines = ipResult.split("\n");
        for (String ipLine : ipLines) {
            log.debug("ipLine: " + ipLine);
            String[] parts = ipLine.split("\\s+");
            if (log.isDebugEnabled()) {
                for (String part : parts) {
                    log.debug("part: " + part);
                }
            }
            if (parts.length > 1 && parts[1].trim().equals(NONE) && 
                    SESSION_TYPE_DESKTOP.equals(parts[2])) {
                targetIP = parts[0].trim();
            }
        }
        
        if (targetIP == null) {
            throw new ResourceNotFoundException("session " + sessionID + " not found");
        }
        
        log.debug("attaching desktop app: " + image + " to " + targetIP);
        
        String name = getImageName(image);
        log.debug("name: " + name);
        String imageSecret = getHarborSecret(image);            
        log.debug("image secret: " + imageSecret);
        if (imageSecret == null) {
            imageSecret = "notused";
        }
        log.debug("image secret: " + imageSecret);
        
        String posixID = getPosixId();
        String supplementalGroups = getSupplementalGroupsList();

        String launchSoftwarePath = System.getProperty("user.home") + "/config/launch-desktop-app.yaml";
        byte[] launchBytes = Files.readAllBytes(Paths.get(launchSoftwarePath));

        // incoming params ignored for the time being.  set to the 'name' so
        // that it becomes the xterm title
        String param = name;
        log.debug("Using parameter: " + param);
        
        String uniqueID = new RandomStringGenerator(3).getID();
        String jobName = sessionID + "-" + uniqueID + "-" + userID.toLowerCase() + "-" + name.toLowerCase();
        String containerName = name.toLowerCase().replaceAll("\\.", "-"); // no dots in k8s names
        // trim job name if necessary
        if (jobName.length() > MAX_JOB_NAME_LENGTH) {
            int pos = MAX_JOB_NAME_LENGTH;
            jobName = jobName.substring(0, pos--);
            // ensure that the trimmed job name is valid, i.e. starts and ends with 
            // an alphanumeric character
            while (!jobName.matches("(([A-Za-z0-9][-A-Za-z0-9_.]*)?[A-Za-z0-9])?")) {
                // invalid job name, continue to trim
                jobName = jobName.substring(0, pos--);
            }
        }

        String gpuScheduling = getGPUScheduling(0);
        
        String launchString = new String(launchBytes, "UTF-8");
        launchString = setConfigValue(launchString, SKAHA_SESSIONID, sessionID);
        launchString = setConfigValue(launchString, SOFTWARE_JOBNAME, jobName);
        launchString = setConfigValue(launchString, SOFTWARE_HOSTNAME, containerName);
        launchString = setConfigValue(launchString, SOFTWARE_CONTAINERNAME, containerName);
        launchString = setConfigValue(launchString, SOFTWARE_CONTAINERPARAM, param);
        launchString = setConfigValue(launchString, SKAHA_USERID, userID);
        launchString = setConfigValue(launchString, SKAHA_SESSIONTYPE, SessionAction.TYPE_DESKTOP_APP);
        launchString = setConfigValue(launchString, SKAHA_SESSIONEXPIRY, K8SUtil.getSessionExpiry());
        launchString = setConfigValue(launchString, SOFTWARE_TARGETIP, targetIP + ":1");
        launchString = setConfigValue(launchString, SKAHA_POSIXID, posixID);
        launchString = setConfigValue(launchString, SKAHA_SUPPLEMENTALGROUPS, supplementalGroups); 
        launchString = setConfigValue(launchString, SKAHA_SCHEDULEGPU, gpuScheduling);
        launchString = setConfigValue(launchString, SOFTWARE_IMAGEID, image);
        launchString = setConfigValue(launchString, SOFTWARE_IMAGESECRET, imageSecret);
                       
        String launchFile = super.stageFile(launchString);
        
        String[] launchCmd = new String[] {
            "kubectl", "create", "--namespace", k8sNamespace, "-f", launchFile};
        String createResult = execute(launchCmd);
        log.debug("Create result: " + createResult);
        
        // refresh the user's proxy cert
        Subject subject = AuthenticationUtil.getCurrentSubject();
        injectProxyCert(subject, userID, posixID);
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
    
    private String getHarborSecret(String image) throws Exception {
        // get the user's cli secret:
        //  1. get the idToken from /ac/authorize
        //  2. call harbor with idToken to get user info and secret
        
        String harborHost = null; 
        for (String next : harborHosts) {
            if (image.startsWith(next)) {
                harborHost = next;
            }
        }
        if (harborHost == null) {
            throw new IllegalArgumentException("not a skaha harbor image: " + image);
        }
        
        String idToken = super.getIdToken();
        
        log.debug("getting secret from harbor");
        URL harborURL = new URL("https://" + harborHost + "/api/v2.0/users/current");
        OutputStream out = new ByteArrayOutputStream();
        HttpGet get = new HttpGet(harborURL, out);
        get.setRequestProperty("Authorization", "Bearer " + idToken);
        get.run();
        log.debug("harbor response code: " + get.getResponseCode());
        if (get.getResponseCode() == 404) {
            if (get.getThrowable() != null) {
                log.warn("user not found in harbor", get.getThrowable());
            } else {
                log.warn("user not found in harbor");
            }
            return null;
        }
        if (get.getThrowable() != null) {
            log.warn("error obtaining harbor secret. response code: " + get.getResponseCode());
            return null;
        }
        String userJson = out.toString();
        log.debug("harbor user info: " + userJson);
        JSONTokener tokener = new JSONTokener(userJson);
        JSONObject obj = new JSONObject(tokener);
        String cliSecret = obj.getJSONObject("oidc_user_meta").getString("secret");
        log.debug("cliSecret: " + cliSecret);
        String harborUsername = obj.getString("username");
        
        String secretName = "harbor-secret-" + userID.toLowerCase();
        
        // delete any old secret by this name
        String[] deleteCmd = new String[] {
            "kubectl", "--namespace", K8SUtil.getWorkloadNamespace(), "delete", "secret", secretName};
        try {
            String deleteResult = execute(deleteCmd);
            log.debug("delete secret result: " + deleteResult);
        } catch (IOException notfound) {
            log.debug("no secret to delete", notfound);
        }
        
        // harbor invalidates secrets with the unicode replacement characters 'fffd'.
        if (cliSecret != null && !cliSecret.startsWith("\ufffd")) {
            // create new secret
            String[] createCmd = new String[] {
                "kubectl", "--namespace", K8SUtil.getWorkloadNamespace(), "create", "secret", "docker-registry", secretName,
                 "--docker-server=" + harborHost,
                 "--docker-username=" + harborUsername,
                 "--docker-password=" + cliSecret};
            try {
                String createResult = execute(createCmd);
                log.debug("create secret result: " + createResult);
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().toLowerCase().contains("already exists")) {
                    // This can happen with concurrent posts by same user.
                    // Considered making secrets unique with the session id,
                    // but that would lead to a large number of secrets and there
                    // is no k8s option to have them cleaned up automatically.
                    // Should look at supporting multiple job creations on a post,
                    // specifically for the headless use case.  That way only one
                    // secret per post.
                    log.debug("secret creation failed, moving on: " + e);
                } else {
                    log.error(e.getMessage(), e);
                    throw new IOException("error creating image pull secret");
                }
            }
        } else {
            log.warn("image repository 'CLI Secret' is invalid and needs resetting.");
            return null;
        }
        
        return secretName;
        
    }
    
    
    private String getSupplementalGroupsList() {
        Subject subject = AuthenticationUtil.getCurrentSubject();
        Class c = (Class<List<Group>>)(Class<?>)List.class;
        Set<List<Group>> groupCreds = subject.getPublicCredentials(c);
        if (groupCreds.size() == 1) {
            List<Group> memberships = groupCreds.iterator().next();
            log.debug("Adding " + memberships.size() + " supplemental groups");
            if (memberships.size() > 0) {
                StringBuilder sb = new StringBuilder();
                for (Group g : memberships) {
                    sb.append(g.gid).append(", ");
                }
                sb.setLength(sb.length() - 2);
                return sb.toString();
            }
        }
        return "";
    }
    
    /**
      Create the image, command, args, and env sections of the job launch yaml.  Example:
      
        image: "${software.imageid}"
        command: ["/skaha-system/start-desktop-software.sh"]
        args: [arg1, arg2]
        env:
        - name: HOME
          value: "/cavern/home/${skaha.userid}"
        - name: SHELL
          value: "/bin/bash"   
     */
    private String getHeadlessImageBundle(String image, String cmd, String args, List<String> envs) {
        StringBuilder sb = new StringBuilder();
        sb.append("image: \"").append(image).append("\"");
        if (cmd != null) {
            sb.append("\n        command: [\"").append(cmd).append("\"]");
        }
        if (args != null) {
            String[] argList = args.split(" ");
            if (argList.length > 0) {
                sb.append("\n        args: [");
                for (String arg : argList) {
                    sb.append("\"").append(arg).append("\", ");
                }
                sb.setLength(sb.length() - 2);
                sb.append("]");
            }
        }
        sb.append("\n        env:");
        sb.append("\n        - name: HOME");
        sb.append("\n          value: \"").append(homedir).append("/").append(userID).append("\"");
        sb.append("\n        - name: PWD");
        sb.append("\n          value: \"").append(homedir).append("/").append(userID).append("\"");
        if (envs != null && !envs.isEmpty()) {
            for (String env : envs) {
                String[] keyVal = env.split("=");
                if (keyVal.length == 2) {
                    sb.append("\n        - name: ").append(keyVal[0]);
                    sb.append("\n          value: \"").append(keyVal[1]).append("\"");
                } else {
                    log.debug("invalid key/value env var: " + env);
                }
            }
        }
        
        return sb.toString();
    }
    
    private String getGPUScheduling(Integer gpus) {
        StringBuilder sb = new StringBuilder();
        sb.append("affinity:\n");
        sb.append("          nodeAffinity:\n");
        sb.append("            requiredDuringSchedulingIgnoredDuringExecution:\n");
        sb.append("              nodeSelectorTerms:\n");
        sb.append("              - matchExpressions:\n");
        if (gpus == null || gpus == 0) {
            sb.append("                - key: nvidia.com/gpu.count\n");
            sb.append("                  operator: DoesNotExist\n");
        } else {
            sb.append("                - key: nvidia.com/gpu.count\n");
            sb.append("                  operator: Gt\n");
            sb.append("                  values:\n");
            sb.append("                  - \"0\"\n");
            return sb.toString();
        }
        return sb.toString();
    }
    
}
