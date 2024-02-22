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

import ca.nrc.cadc.auth.AuthMethod;
import ca.nrc.cadc.auth.AuthenticationUtil;
import ca.nrc.cadc.auth.AuthorizationToken;
import ca.nrc.cadc.auth.HttpPrincipal;
import ca.nrc.cadc.net.HttpGet;
import ca.nrc.cadc.net.ResourceNotFoundException;
import ca.nrc.cadc.reg.Standards;
import ca.nrc.cadc.reg.client.LocalAuthority;
import ca.nrc.cadc.reg.client.RegistryClient;
import ca.nrc.cadc.util.StringUtil;
import org.apache.log4j.Logger;
import org.opencadc.skaha.K8SUtil;
import org.opencadc.skaha.SkahaAction;
import org.opencadc.skaha.utils.CommandExecutioner;

import javax.security.auth.Subject;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.security.PrivilegedExceptionAction;
import java.util.*;

import static org.opencadc.skaha.utils.CommandExecutioner.execute;

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
        log.debug("userID: " + posixPrincipal);
    }


    public static String getVNCURL(String host, String sessionID) throws MalformedURLException {
        // vnc_light.html accepts title and resize
        //return "https://" + host + "/desktop/" + ipAddress + "/" + sessionID + "/connect?" +
        //    "title=skaha&resize=true&path=desktop/" + ipAddress + "/" + sessionID + "/websockify&password=" + sessionID;

        // vnc.html does not...
        return "https://" + host + "/session/desktop/" + sessionID + "/?password=" + sessionID
               + "&path=session/desktop/" + sessionID + "/";
    }

    public static String getCartaURL(String host, String sessionID, boolean altSocketUrl) throws MalformedURLException {
        String url = "https://" + host + "/session/carta/http/" + sessionID + "/";
        if (altSocketUrl) {
            url = url + "?socketUrl=wss://" + host + "/session/carta/ws/" + sessionID + "/";
        }
        return url;
    }

    public static String getNotebookURL(String host, String sessionID, String userid, String skahaTLD) {
        return String.format("https://%s/session/notebook/%s/lab/tree/%s/home/%s?token=%s", host, sessionID,
                             skahaTLD.replaceAll("/", ""), userid, sessionID);
    }

    public static String getContributedURL(String host, String sessionID) throws MalformedURLException {
        return "https://" + host + "/session/contrib/" + sessionID + "/";
    }

    protected AuthorizationToken token(final Subject subject) {
        return subject
                .getPublicCredentials(AuthorizationToken.class)
                .iterator()
                .next();
    }


    protected void injectCredentials() {
        final String username = posixPrincipal.username;
        int posixId = getUID();
        injectToken(username, posixId, xAuthTokenSkaha);
        injectProxyCertificate(username);
    }

    private void injectToken(String username, int posixId, String token) {
        // inject a token if available
        try {
            String userHomeDirectory = CommandExecutioner.createDirectoryIfNotExist(homedir, username);
            CommandExecutioner.changeOwnership(userHomeDirectory, posixId, posixId);
            String tokenDirectory = CommandExecutioner.createDirectoryIfNotExist(userHomeDirectory, ".token");
            CommandExecutioner.changeOwnership(tokenDirectory, posixId, posixId);
            String tokenFilePath = CommandExecutioner.createOrOverrideFile(tokenDirectory, ".skaha", token);
            CommandExecutioner.changeOwnership(tokenFilePath, posixId, posixId);
        } catch (Exception exception) {
            log.debug("failed to inject token: " + exception.getMessage(), exception);
        }
    }

    private void injectProxyCertificate(String username) {
        // inject a delegated proxy certificate if available
        try {
            final LocalAuthority localAuthority = new LocalAuthority();
            final URI credServiceID = localAuthority.getServiceURI(Standards.CRED_PROXY_10.toString());

            // Should throw a NoSuchElementException if it's missing, but check here anyway.
            if (credServiceID != null) {
                final RegistryClient registryClient = new RegistryClient();
                final URL credServiceURL = registryClient.getServiceURL(credServiceID, Standards.CRED_PROXY_10,
                                                                        AuthMethod.CERT);

                if (credServiceURL != null) {
                    final Subject currentSubject = AuthenticationUtil.getCurrentSubject();
                    final String proxyCert = Subject.doAs(currentSubject, (PrivilegedExceptionAction<String>) () -> {
                        final ByteArrayOutputStream out = new ByteArrayOutputStream();
                        final String userName =
                                currentSubject.getPrincipals(HttpPrincipal.class).iterator().next().getName();
                        final URL certificateURL = new URL(credServiceURL.toExternalForm() + "/userid/" + userName);
                        final HttpGet download = new HttpGet(certificateURL, out);
                        download.setFollowRedirects(true);
                        download.run();

                        return out.toString();
                    });
                    log.debug("Proxy cert: " + proxyCert);
                    // inject the proxy cert
                    log.debug("Running docker exec to insert cert");

                    injectFile(proxyCert, Path.of(homedir, username, ".ssl", "cadcproxy.pem").toString());
                }
            }
        } catch (NoSuchElementException noSuchElementException) {
            log.debug("Not using proxy certificates.  Skipping certificate injection...");
        } catch (Exception e) {
            log.warn("failed to inject cert: " + e.getMessage(), e);
        }
    }

    protected String getImageName(String image) {
        try {
            // return the last segment of the path
            int lastSlash = image.lastIndexOf("/");
            String name = image.substring(lastSlash + 1);
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

    protected void injectFile(String data, String path) throws IOException, InterruptedException {
        final int uid = posixPrincipal.getUidNumber();
        // stage file
        String tmpFileName = "/tmp/" + UUID.randomUUID();
        File file = new File(tmpFileName);
        if (!file.setExecutable(true, true)) {
            log.debug("Failed to set execution permission on file " + tmpFileName);
        }
        BufferedWriter writer = new BufferedWriter(new FileWriter(file));
        writer.write(data + "\n");
        writer.flush();
        writer.close();

        // update file permissions
        String[] chown = new String[] {"chown", uid + ":" + uid, tmpFileName};
        execute(chown);

        // inject file
        String[] inject = new String[] {"mv", "-f", tmpFileName, path};
        execute(inject);
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
        List<String> getPodCMD = new ArrayList<>();
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
        List<String> getEventsCmd = new ArrayList<>();
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
    }

    public void streamPodLogs(String forUserID, String sessionID, OutputStream out) throws Exception {
        String k8sNamespace = K8SUtil.getWorkloadNamespace();
        List<String> getLogsCmd = new ArrayList<>();
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

    public Session getDesktopApp(String sessionID, String appID) throws Exception {
        List<Session> sessions = SessionDAO.getSessions(posixPrincipal.username, sessionID, skahaTld);
        if (!sessions.isEmpty()) {
            for (Session session : sessions) {
                // only include 'desktop-app'
                if (SkahaAction.TYPE_DESKTOP_APP.equalsIgnoreCase(session.getType()) &&
                    (sessionID.equals(session.getId())) && (appID.equals(session.getAppId()))) {
                    return session;
                }
            }
        }

        throw new ResourceNotFoundException(
                "desktop app with session " + sessionID + " and app ID " + appID + " was not found");
    }

    public Session getSession(String forUserID, String sessionID) throws Exception {
        for (final Session session : SessionDAO.getSessions(forUserID, sessionID, skahaTld)) {
            // exclude 'desktop-app'
            if (!SkahaAction.TYPE_DESKTOP_APP.equalsIgnoreCase(session.getType())) {
                return session;
            }
        }

        throw new ResourceNotFoundException("session " + sessionID + " not found");
    }

    public List<Session> getAllSessions(String forUserID) throws Exception {
        return SessionDAO.getSessions(forUserID, null, skahaTld);
    }

    protected String toCoreUnit(String cores) {
        String ret = NONE;
        if (StringUtil.hasLength(cores)) {
            if ("m".equals(cores.substring(cores.length() - 1))) {
                // in "m" (millicore) unit, covert to cores
                int milliCores = Integer.parseInt(cores.substring(0, cores.length() - 1));
                ret = ((Double) (milliCores / Math.pow(10, 3))).toString();
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
            if ("i".equals(inK8sUnit.substring(inK8sUnit.length() - 1))) {
                // unit is in Ki, Mi, Gi, etc., remove the i
                ret = inK8sUnit.substring(0, inK8sUnit.length() - 1);
            } else {
                // use value as is, can be '<none>' or some value
                ret = inK8sUnit;
            }
        }

        return ret;
    }

    protected Map<String, String> getJobExpiryTimes(String k8sNamespace, String forUserID) throws Exception {
        Map<String, String> jobExpiryTimes = new HashMap<>();
        List<String> jobExpiryTimeCMD = getJobExpiryTimeCMD(k8sNamespace, forUserID);
        String jobExpiryTimeMap = execute(jobExpiryTimeCMD.toArray(new String[0]));
        log.debug("Expiry times: " + jobExpiryTimeMap);
        if (StringUtil.hasLength(jobExpiryTimeMap)) {
            String[] lines = jobExpiryTimeMap.split("\n");
            for (String line : lines) {
                String[] expiryTime = line.trim().replaceAll("\\s+", " ").split(" ");
                jobExpiryTimes.put(expiryTime[0], expiryTime[1]);
            }
        }

        return jobExpiryTimes;
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
                               "UID:.metadata.uid," +
                               "EXPIRY:.spec.activeDeadlineSeconds";

        getSessionJobCMD.add(customColumns);
        return getSessionJobCMD;
    }

    protected String getAppJobName(String sessionID, String userID, String appID) throws
                                                                                  IOException, InterruptedException {
        String k8sNamespace = K8SUtil.getWorkloadNamespace();
        List<String> getAppJobNameCMD = getAppJobNameCMD(k8sNamespace, userID, sessionID, appID);
        return execute(getAppJobNameCMD.toArray(new String[0]));
    }

    private List<String> getAppJobNameCMD(String k8sNamespace, String userID, String sessionID, String appID) {
        String labels = "canfar-net-sessionType=" + TYPE_DESKTOP_APP;
        labels = labels + ",canfar-net-userid=" + userID;
        if (sessionID != null) {
            labels = labels + ",canfar-net-sessionID=" + sessionID;
        }
        if (appID != null) {
            labels = labels + ",canfar-net-appID=" + appID;
        }

        List<String> getAppJobNameCMD = new ArrayList<String>();
        getAppJobNameCMD.add("kubectl");
        getAppJobNameCMD.add("get");
        getAppJobNameCMD.add("--namespace");
        getAppJobNameCMD.add(k8sNamespace);
        getAppJobNameCMD.add("job");
        getAppJobNameCMD.add("-l");
        getAppJobNameCMD.add(labels);
        getAppJobNameCMD.add("--no-headers=true");
        getAppJobNameCMD.add("-o");

        String customColumns = "custom-columns=" +
                               "NAME:.metadata.name";

        getAppJobNameCMD.add(customColumns);
        return getAppJobNameCMD;
    }

}
