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
import ca.nrc.cadc.net.ResourceNotFoundException;
import ca.nrc.cadc.rest.InlineContentHandler;
import ca.nrc.cadc.util.StringUtil;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.security.auth.Subject;

import org.apache.log4j.Logger;
import org.opencadc.skaha.K8SUtil;
import org.opencadc.skaha.SkahaAction;

public abstract class SessionAction extends SkahaAction {
    
    private static final Logger log = Logger.getLogger(SessionAction.class);
    
    protected static final String REQUEST_TYPE_SESSION = "session";
    protected static final String REQUEST_TYPE_APP = "app";
    
    protected static final String SESSION_LIST_VIEW_ALL = "all";
    protected static final String SESSION_VIEW_EVENTS = "events";
    protected static final String SESSION_VIEW_LOGS = "logs";
    protected static final String SESSION_VIEW_STATS = "stats";
    
    protected static final String NONE = "<none>";
    
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
        return execute(command, false);
    }
    
    public static void execute(String[] command, OutputStream out) throws IOException, InterruptedException {
        Process p = Runtime.getRuntime().exec(command);

        WritableByteChannel wbc = Channels.newChannel(out);
        ReadableByteChannel rbc = Channels.newChannel(p.getInputStream());

        int count = 0;
        ByteBuffer buffer = ByteBuffer.allocate(512);
        while (count != -1) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            count = rbc.read(buffer);
            if (count != -1) {
                wbc.write((ByteBuffer)buffer.flip());
                buffer.flip();
            }
        }
    }
    
    public static String execute(String[] command, boolean allowError) throws IOException, InterruptedException {
        Process p = Runtime.getRuntime().exec(command);
        String stdout = readStream(p.getInputStream());
        String stderr = readStream(p.getErrorStream());
        log.debug("stdout: " + stdout);
        log.debug("stderr: " + stderr);
        int status = p.waitFor();
        log.debug("Status=" + status + " for command: " + Arrays.toString(command));
        if (status != 0) {
            if (allowError) {
                return stderr;
            } else {
                String message = "Error executing command: " + Arrays.toString(command) + " Error: " + stderr;
                throw new IOException(message);
            }
        } 
        return stdout.trim();
    }
    
    public static String getVNCURL(String host, String sessionID) throws MalformedURLException {
        // vnc_light.html accepts title and resize
        //return "https://" + host + "/desktop/" + ipAddress + "/" + sessionID + "/connect?" +
        //    "title=skaha&resize=true&path=desktop/" + ipAddress + "/" + sessionID + "/websockify&password=" + sessionID;
        
        // vnc.html does not...
        return "https://" + host + "/session/desktop/" + sessionID + "/?password=" + sessionID + "&path=session/desktop/" + sessionID + "/";
    }
    
    public static String getCartaURL(String host, String sessionID, boolean altSocketUrl) throws MalformedURLException {
        String url = "https://" + host + "/session/carta/http/" + sessionID + "/";
        if (altSocketUrl) {
            url = url + "?socketUrl=wss://" + host + "/session/carta/ws/" + sessionID + "/";
        }
        return url;
    }
    
    public static String getNotebookURL(String host, String sessionID, String userid) throws MalformedURLException {
        return "https://" + host + "/session/notebook/" + sessionID + "/lab/tree/arc/home/" + userid + "?token=" + sessionID;
    }
    
    public static String getContributedURL(String host, String sessionID) throws MalformedURLException {
        return "https://" + host + "/session/contrib/" + sessionID + "/";
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
        
        injectFile(proxyCert, posixID, userid);
    }
    
    protected String getImageName(String image) {
        try {
            // return the last segment of the path
            int lastSlash = image.lastIndexOf("/");
            String name = image.substring(lastSlash + 1, image.length());
            log.debug("cleaning up name: " + name);
            // replace colons and dots and underscores with dash
            name = name.replaceAll(":", "-");
            name = name.replaceAll("\\.", "-");
            name = name.replaceAll("_", "-");
            return name.toLowerCase();
        } catch (Exception e) {
            log.warn("failed to determine name for image: " + image);
            return "unknown";
        }

    }
    
    protected void injectFile(String data, String posixID, String userid) throws IOException, InterruptedException {
        // stage file
        String tmpFileName = "/tmp/" + UUID.randomUUID();
        File file = new File(tmpFileName);
        if (!file.setExecutable(true, true)) {
            log.debug("Failed to set execution permssion on file " + tmpFileName);
        }
        BufferedWriter writer = new BufferedWriter(new FileWriter(file));
        writer.write(data + "\n");
        writer.flush();
        writer.close();
        
        // update file permissions
        String[] chown = new String[] {"chown", posixID + ":" + posixID, tmpFileName};
        execute(chown);
//        String[] chown = new String[] {"chown", "-R", "guest:guest", "/home/" + userid + "/.ssl"};
//        execute(chown);
        
        // inject file
        String[] injectCert = new String[] {"mv",  "-f", tmpFileName, homedir + "/" + userid + "/.ssl/cadcproxy.pem"};
        execute(injectCert);
    }
    
    protected String stageFile(String data) throws IOException {
        String tmpFileName = "/tmp/" + UUID.randomUUID();
        File file = new File(tmpFileName);
        if (!file.setExecutable(true, true)) {
            log.debug("Failed to set execution permssion on file " + tmpFileName);
        }
        BufferedWriter writer = new BufferedWriter(new FileWriter(file));
        writer.write(data + "\n");
        writer.flush();
        writer.close();
        return tmpFileName;
    }
    
    public String getPodID(String forUserID, String sessionID) throws Exception {
        String k8sNamespace = K8SUtil.getWorkloadNamespace();
        List<String> getPodCMD = new ArrayList<String>();
        getPodCMD.add("kubectl");
        getPodCMD.add("--namespace");
        getPodCMD.add(k8sNamespace);
        getPodCMD.add("get");
        getPodCMD.add("pod");
        getPodCMD.add("-l");
        getPodCMD.add("canfar-net-sessionID=" + sessionID + ",canfar-net-userid=" + forUserID);
        getPodCMD.add("--no-headers=true");
        getPodCMD.add("-o");
        getPodCMD.add("custom-columns=NAME:.metadata.name");
        String podID = execute(getPodCMD.toArray(new String[0]));
        log.debug("podID: " + podID);
        if (!StringUtil.hasLength(podID)) {
            throw new ResourceNotFoundException("session " + sessionID + " not found.");
        } 
        return podID;
    }
    
    public String getEvents(String forUserID, String sessionID) throws Exception {

        String podID = getPodID(forUserID, sessionID);

        String k8sNamespace = K8SUtil.getWorkloadNamespace();
        List<String> getEventsCmd = new ArrayList<String>();
        getEventsCmd.add("kubectl");
        getEventsCmd.add("--namespace");
        getEventsCmd.add(k8sNamespace);
        getEventsCmd.add("get");
        getEventsCmd.add("event");
        getEventsCmd.add("--field-selector");
        getEventsCmd.add("involvedObject.name=" + podID);
        //getEventsCmd.add("--no-headers=true");
        getEventsCmd.add("-o");
        String customColumns = "TYPE:.type,REASON:.reason,MESSAGE:.message,FIRST-TIME:.firstTimestamp,LAST-TIME:.lastTimestamp";
        getEventsCmd.add("custom-columns=" + customColumns);
        String events = execute(getEventsCmd.toArray(new String[0]));
        log.debug("events: " + events);
        if (events != null) {
            String[] lines = events.split("\n");
            if (lines.length > 1) {  // header row returned
                return events;
            }
        }
        return "";
        
        //kw get event --field-selector involvedObject.name=k-pop-aydanmckay-vg11vvhm-kl2n7vxw-t5d25 --no-headers=true
        //-o custom-columns=MESSAGE:.message,TYPE:.type,REASON:.reason,FIRST-TIME:.firstTimestamp,LAST-TIME:.lastTimestamp 
        
    }
    
    public void streamPodLogs(String forUserID, String sessionID, OutputStream out) throws Exception {
        String k8sNamespace = K8SUtil.getWorkloadNamespace();
        List<String> getLogsCmd = new ArrayList<String>();
        getLogsCmd.add("kubectl");
        getLogsCmd.add("--namespace");
        getLogsCmd.add(k8sNamespace);
        getLogsCmd.add("logs");
        getLogsCmd.add("-l");
        getLogsCmd.add("canfar-net-sessionID=" + sessionID + ",canfar-net-userid=" + forUserID);
        getLogsCmd.add("--tail");
        getLogsCmd.add("-1");
        execute(getLogsCmd.toArray(new String[0]), out);
    }
    
    public Session getSession(String forUserID, String sessionID) throws Exception {
        List<Session> sessions = getSessions(forUserID, sessionID);
        if (sessions.size() >0) {
            for (Session session : sessions) {
                // exclude 'desktop-app'
                if (!SkahaAction.TYPE_DESKTOP_APP.equalsIgnoreCase(session.getType())) {
                    return session;
                }
            }
        } 

        throw new ResourceNotFoundException("session " + sessionID + " not found");
    }
    
    public List<Session> getAllSessions(String forUserID) throws Exception {
        return getSessions(forUserID, null);
    }

    private List<Session> getSessions(String forUserID, String sessionID) throws Exception {
        String k8sNamespace = K8SUtil.getWorkloadNamespace();
        List<String> sessionsCMD = getSessionsCMD(k8sNamespace, forUserID, sessionID);
        String sessionList = execute(sessionsCMD.toArray(new String[0]));
        log.debug("Session list: " + sessionList);
        
        List<Session> sessions = new ArrayList<Session>();
        if (StringUtil.hasLength(sessionList)) {
            Map<String, String> jobExpiryTimes = null;
            Map<String, String[]> resourceUsages = null;
            if (forUserID != null) {
                jobExpiryTimes = getJobExpiryTimes(k8sNamespace, forUserID);
                resourceUsages = getResourceUsages(k8sNamespace, forUserID);
            }

            String[] lines = sessionList.split("\n");
            for (String line : lines) {
                Session session = constructSession(line);
                if (forUserID != null) {
                    // get expiry time 
                    String uid = getUID(line);
                    String startTimeStr = session.getStartTime();
                    if (startTimeStr.equalsIgnoreCase(NONE)) {
                        session.setExpiryTime(startTimeStr);
                    } else {
                        Instant instant = Instant.parse(startTimeStr);
                        String jobExpiryTimesStr = jobExpiryTimes.get(uid);
                        if (jobExpiryTimesStr == null) {
                            session.setExpiryTime(NONE);
                        } else {
                            instant = instant.plus(Integer.parseInt(jobExpiryTimesStr), ChronoUnit.SECONDS);
                            session.setExpiryTime(instant.toString());
                        }
                    }

                    // get RAM and CPU usage
                    String fullName = getFullName(line);
                    if (resourceUsages.isEmpty()) {
                        // no job in 'Running' state
                        session.setCPUCoresInUse(NONE);
                        session.setRAMInUse(NONE);
                        
                    } else {
                        // at least one job is in 'Running' state
                        String resourceUsage[] = resourceUsages.get(fullName);
                        if (resourceUsage == null) {
                            // job not in 'Running' state
                            session.setCPUCoresInUse(NONE);
                            session.setRAMInUse(NONE);
                        } else {
                            session.setCPUCoresInUse(toCoreUnit(resourceUsage[0]));
                            session.setRAMInUse(toCommonUnit(resourceUsage[1]));
                        }
                        
                        // if this session usages GPU, get the GPU usage
                        if (StringUtil.hasText(session.getRequestedGPUCores()) &&
                                !NONE.equals(session.getRequestedGPUCores()) &&
                                Double.parseDouble(session.getRequestedGPUCores()) > 0.0) {
                            List<String> sessionGPUUsageCMD = getSessionGPUUsageCMD(k8sNamespace, fullName);
                            String sessionGPUUsage = execute(sessionGPUUsageCMD.toArray(new String[0]));
                            List<String> gpuUsage = getGPUUsage(sessionGPUUsage);
                            session.setGPURAMInUse(gpuUsage.get(0));
                            session.setGPUUtilization(gpuUsage.get(1));
                        } else {
                            session.setGPURAMInUse(NONE);
                            session.setGPUUtilization(NONE);
                        }
                    }
                }

                sessions.add(session);
            }
        }
        
        return sessions;
    }
    
    protected String toCoreUnit(String cores) {
        String ret = NONE;
        if (StringUtil.hasLength(cores)) {
            if ("m".equals(cores.substring(cores.length() - 1, cores.length()))) {
                // in "m" (millicore) unit, covert to cores
                Integer milliCores = Integer.parseInt(cores.substring(0, cores.length() - 1)); 
                ret = ((Double) (milliCores/Math.pow(10, 3))).toString();
            } else {
                // use value as is, can be '<none>' or some value
                ret = cores;
            }
        } 
        
        return ret;
    }
    
    protected String toCommonUnit(String inK8sUnit) {
        String ret = NONE;
        if (StringUtil.hasLength(inK8sUnit)) {
            if ("i".equals(inK8sUnit.substring(inK8sUnit.length() - 1, inK8sUnit.length()))) {
                // unit is in Ki, Mi, Gi, etc., remove the i
                ret = inK8sUnit.substring(0, inK8sUnit.length() - 1);
            } else {
                // use value as is, can be '<none>' or some value
                ret = inK8sUnit;
            }
        } 
        
        return ret;
    }
    
    private Map<String, String[]> getResourceUsages(String k8sNamespace, String forUserID) throws Exception {
        Map<String, String[]> resourceUsages = new HashMap<String, String[]>(); 
        List<String> sessionResourceUsageCMD = getSessionResourceUsageCMD(k8sNamespace, forUserID);
        try {
            String sessionResourceUsageMap = execute(sessionResourceUsageCMD.toArray(new String[0]));
            log.debug("Resource used: " + sessionResourceUsageMap);
            if (StringUtil.hasLength(sessionResourceUsageMap)) {
                String[] lines = sessionResourceUsageMap.split("\n");
                for (String line : lines) {
                    String resourceUsage[] = line.trim().replaceAll("\\s+", " ").split(" ");
                    String fullName = resourceUsage[0];
                    String[] resources = {resourceUsage[1], resourceUsage[2]};
                    resourceUsages.put(fullName, resources);
                }
            }
        } catch (IOException ex) {
            // error or no session using any resources, return empty resourceUsages
            log.debug("failed to query for metrics", ex);
        }

        return resourceUsages;
    }
    
    private List<String> getGPUUsage(String usageData) {
        List<String> usage = new ArrayList<String>();
        if (StringUtil.hasLength(usageData)) {
            String[] lines = usageData.split("\n");
            for (String line : lines) {
                if (line.contains("%")) {
                    String[] segments = line.trim().split("\\|");
                    if (segments.length > 3) {
                        if (segments[3].contains("%")) {
                            String util = segments[3].trim().split(" ")[0];
                            if (util.contains("%")) {
                                String mem = formatGPUMemoryUsage(segments[2].trim());
                                usage.add(mem);
                                usage.add(util);
                                break;
                            }
                        }
                    }
                }
            }
        }
        
        // no GPU
        if (usage.isEmpty()) {
            usage.add(NONE);
            usage.add(NONE);
        }

        return usage;
    }
    
    private String formatGPUMemoryUsage(String memoryData) {
        String[] data = memoryData.split("/");
        String data0 = data[0].trim();
        if (data0.substring(data0.length() - 1).equalsIgnoreCase("B")) {
            data0 = toCommonUnit(data0.substring(0, data0.length() - 1));
        }

        String data1 = data[1].trim();
        if (data1.substring(data1.length() - 1).equalsIgnoreCase("B")) {
            data1 = toCommonUnit(data1.substring(0, data1.length() - 1));
        }
        return data0 + " / " + data1;
    }
    
    protected Map<String,String> getJobExpiryTimes(String k8sNamespace, String forUserID) throws Exception {
        Map<String,String> jobExpiryTimes = new HashMap<String,String>(); 
        List<String> jobExpiryTimeCMD = getJobExpiryTimeCMD(k8sNamespace, forUserID);
        String jobExpiryTimeMap = execute(jobExpiryTimeCMD.toArray(new String[0]));
        log.debug("Expiry times: " + jobExpiryTimeMap);
        if (StringUtil.hasLength(jobExpiryTimeMap)) {
            String[] lines = jobExpiryTimeMap.split("\n");
            for (String line : lines) {
                String expiryTime[] = line.trim().replaceAll("\\s+", " ").split(" ");
                jobExpiryTimes.put(expiryTime[0], expiryTime[1]);
            }
        }
        
        return jobExpiryTimes;
    }
    
    private String getFullName(String line) {
        String name = "";
        String[] parts = line.trim().replaceAll("\\s+", " ").split(" ");
        if (parts.length > 8) {
            name = parts[parts.length - 2];
        }
        
        return name;
    }
    
    private String getUID(String line) {
        String uid = "";
        String[] parts = line.trim().replaceAll("\\s+", " ").split(" ");
        if (parts.length > 8) {
            uid = parts[parts.length - 1];
        }
        
        return uid;
    }
    
    private List<String> getSessionsCMD(String k8sNamespace, String forUserID, String sessionID) {
        List<String> sessionsCMD = new ArrayList<String>();
        sessionsCMD.add("kubectl");
        sessionsCMD.add("get");
        sessionsCMD.add("--namespace");
        sessionsCMD.add(k8sNamespace);
        sessionsCMD.add("pod");
        if (forUserID != null) {
            sessionsCMD.add("-l");
            String labels = "canfar-net-userid=" + forUserID;
            if (sessionID != null) {
                labels = labels + ",canfar-net-sessionID=" + sessionID;
            }

            sessionsCMD.add(labels);
        }
        sessionsCMD.add("--no-headers=true");
        sessionsCMD.add("-o");
        
        String customColumns = "custom-columns=" +
            "SESSIONID:.metadata.labels.canfar-net-sessionID," + 
            "USERID:.metadata.labels.canfar-net-userid," +
            "IMAGE:.spec.containers[0].image," +
            "TYPE:.metadata.labels.canfar-net-sessionType," +
            "STATUS:.status.phase," +
            "NAME:.metadata.labels.canfar-net-sessionName," +
            "STARTED:.status.startTime," +
            "DELETION:.metadata.deletionTimestamp";
        if (forUserID != null) {
            customColumns = customColumns + 
            ",REQUESTEDRAM:.spec.containers[0].resources.requests.memory," +
            "REQUESTEDCPU:.spec.containers[0].resources.requests.cpu," +
            "REQUESTEDGPU:.spec.containers[0].resources.requests.nvidia\\.com/gpu," +
            "FULLNAME:.metadata.name," +
            "UID:.metadata.ownerReferences[].uid"; 
        }
        
        sessionsCMD.add(customColumns);
        return sessionsCMD;
    }
    
    private List<String> getJobExpiryTimeCMD(String k8sNamespace, String forUserID) {
        List<String> getSessionJobCMD = new ArrayList<String>();
        getSessionJobCMD.add("kubectl");
        getSessionJobCMD.add("get");
        getSessionJobCMD.add("--namespace");
        getSessionJobCMD.add(k8sNamespace);
        getSessionJobCMD.add("job");
        getSessionJobCMD.add("-l");
        getSessionJobCMD.add("canfar-net-userid=" + forUserID);
        getSessionJobCMD.add("--no-headers=true");
        getSessionJobCMD.add("-o");
        
        String customColumns = "custom-columns=" +
            "UID:.spec.selector.matchLabels.controller-uid," +
            "EXPIRY:.spec.activeDeadlineSeconds";
        
        getSessionJobCMD.add(customColumns);
        return getSessionJobCMD;
    }
    
    private List<String> getSessionResourceUsageCMD(String k8sNamespace, String forUserID) {
        List<String> getSessionJobCMD = new ArrayList<String>();
        getSessionJobCMD.add("kubectl");
        getSessionJobCMD.add("--namespace");
        getSessionJobCMD.add(k8sNamespace);
        getSessionJobCMD.add("top");
        getSessionJobCMD.add("pod");
        getSessionJobCMD.add("-l");
        getSessionJobCMD.add("canfar-net-userid=" + forUserID);
        getSessionJobCMD.add("--no-headers=true");
        getSessionJobCMD.add("--use-protocol-buffers=true");
        return getSessionJobCMD;
    }
    
    private List<String> getSessionGPUUsageCMD(String k8sNamespace, String podName) {
        List<String> getSessionGPUCMD = new ArrayList<String>();
        getSessionGPUCMD.add("kubectl");
        getSessionGPUCMD.add("--namespace");
        getSessionGPUCMD.add(k8sNamespace);
        getSessionGPUCMD.add("exec");   
        getSessionGPUCMD.add("-it");    
        getSessionGPUCMD.add(podName);  
        getSessionGPUCMD.add("--");     
        getSessionGPUCMD.add("nvidia-smi");
        return getSessionGPUCMD;        
    }
    
    protected Session constructSession(String k8sOutput) throws IOException {
        log.debug("line: " + k8sOutput);
        String[] parts = k8sOutput.trim().replaceAll("\\s+", " ").split(" ");
        String id = parts[0];
        String userid = parts[1];
        String image = parts[2];
        String type = parts[3];
        String status = parts[4];
        String name = parts[5];
        String startTime = parts[6];
        String deletionTimestamp = parts[7];
        if (deletionTimestamp != null && !NONE.equals(deletionTimestamp)) {
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
            connectURL = SessionAction.getNotebookURL(host, id, userid);
        }
        if (SessionAction.SESSION_TYPE_CONTRIB.equals(type)) {
            connectURL = SessionAction.getContributedURL(host, id);
        }

        Session session = new Session(id, userid, image, type, status, name, startTime, connectURL);
        if (parts.length > 8) {
            String requestedRAM = parts[8];
            String requestedCPUCores = parts[9];
            String requestedGPUCores = parts[10];
            session.setRequestedRAM(toCommonUnit(requestedRAM));
            session.setRequestedCPUCores(toCoreUnit(requestedCPUCores));
            session.setRequestedGPUCores(toCoreUnit(requestedGPUCores));
        }

        return session;
    }
    
}
