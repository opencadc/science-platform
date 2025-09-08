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
import ca.nrc.cadc.auth.X509CertificateChain;
import ca.nrc.cadc.cred.CertUtil;
import ca.nrc.cadc.cred.client.CredClient;
import ca.nrc.cadc.cred.client.CredUtil;
import ca.nrc.cadc.net.ResourceNotFoundException;
import ca.nrc.cadc.reg.Standards;
import ca.nrc.cadc.reg.client.RegistryClient;
import ca.nrc.cadc.util.StringUtil;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.security.PrivilegedExceptionAction;
import java.util.*;
import javax.security.auth.Subject;
import org.apache.log4j.Logger;
import org.opencadc.skaha.K8SUtil;
import org.opencadc.skaha.SkahaAction;
import org.opencadc.skaha.utils.CommandExecutioner;
import org.opencadc.skaha.utils.CommonUtils;
import org.opencadc.skaha.utils.KubectlCommandBuilder;

public abstract class SessionAction extends SkahaAction {

    protected static final String REQUEST_TYPE_SESSION = "session";
    protected static final String REQUEST_TYPE_APP = "app";
    protected static final String SESSION_LIST_VIEW_ALL = "all";
    protected static final String SESSION_VIEW_EVENTS = "events";
    protected static final String SESSION_VIEW_LOGS = "logs";
    protected static final String SESSION_VIEW_STATS = "stats";
    protected static final String NONE = "<none>";

    private static final Logger log = Logger.getLogger(SessionAction.class);
    private static final double ONE_WEEK_DAYS = 7.0D;

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

    protected void injectProxyCertificate() {
        log.debug("injectProxyCertificate()");

        // inject a delegated proxy certificate if available
        try {
            final URI credServiceID = CommonUtils.firstLocalServiceURI(Standards.CRED_PROXY_10);

            // Should throw a NoSuchElementException if it's missing, but check here anyway.
            if (credServiceID != null) {
                final RegistryClient registryClient = new RegistryClient();
                final URL credServiceURL =
                        registryClient.getServiceURL(credServiceID, Standards.CRED_PROXY_10, AuthMethod.CERT);

                if (credServiceURL != null) {
                    final CredClient credClient = new CredClient(credServiceID);
                    final Subject currentSubject = AuthenticationUtil.getCurrentSubject();
                    final X509CertificateChain proxyCert =
                            Subject.doAs(CredUtil.createOpsSubject(), (PrivilegedExceptionAction<X509CertificateChain>)
                                    () -> credClient.getProxyCertificate(currentSubject, SessionAction.ONE_WEEK_DAYS));

                    log.debug("Proxy cert: " + proxyCert);
                    // inject the proxy cert
                    log.debug("Running docker exec to insert cert");

                    writeClientCertificate(
                            proxyCert,
                            Path.of(homedir, this.posixPrincipal.username, ".ssl", "cadcproxy.pem")
                                    .toString());
                    log.debug("injectProxyCertificate(): OK");
                }
            }
        } catch (NoSuchElementException noSuchElementException) {
            log.debug("Not using proxy certificates");
            log.debug("injectProxyCertificate(): UNSUCCESSFUL");
        } catch (Exception e) {
            log.warn("failed to inject cert: " + e.getMessage(), e);
            log.debug("injectProxyCertificate(): UNSUCCESSFUL");
        }
    }

    private void writeClientCertificate(X509CertificateChain clientCertificateChain, String path)
            throws IOException, InterruptedException {
        final int uid = posixPrincipal.getUidNumber();
        // stage file

        final String tmpFileName = "/tmp/" + UUID.randomUUID();
        File file = new File(tmpFileName);
        if (!file.setExecutable(true, true)) {
            log.debug("Failed to set execution permission on file " + tmpFileName);
        }
        final Writer writer = new BufferedWriter(new FileWriter(file));
        CertUtil.writePEMCertificateAndKey(clientCertificateChain, writer);

        // update file permissions
        CommandExecutioner.changeOwnership(tmpFileName, uid, uid);

        // inject file
        String[] inject = new String[] {"mv", "-f", tmpFileName, path};
        CommandExecutioner.execute(inject);
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
        KubectlCommandBuilder.KubectlCommand getPodCmd = KubectlCommandBuilder.command("get")
                .pod()
                .namespace(K8SUtil.getWorkloadNamespace())
                .label("canfar-net-sessionID=" + sessionID + ",canfar-net-userid=" + forUserID)
                .noHeaders()
                .outputFormat("custom-columns=NAME:.metadata.name");

        final String podID = CommandExecutioner.execute(getPodCmd.build());

        log.debug("podID: " + podID);
        if (!StringUtil.hasLength(podID)) {
            throw new ResourceNotFoundException("session " + sessionID + " not found.");
        }
        return podID;
    }

    public String getEvents(String forUserID, String sessionID) throws Exception {

        String podID = getPodID(forUserID, sessionID);

        KubectlCommandBuilder.KubectlCommand getEventsCmd = KubectlCommandBuilder.command("get")
                .argument("event")
                .namespace(K8SUtil.getWorkloadNamespace())
                .option("--field-selector", "involvedObject.name=" + podID)
                .outputFormat(
                        "custom-columns=TYPE:.type,REASON:.reason,MESSAGE:.message,FIRST-TIME:.firstTimestamp,LAST-TIME:.lastTimestamp");

        String events = CommandExecutioner.execute(getEventsCmd.build());

        log.debug("events: " + events);
        if (events != null) {
            String[] lines = events.split("\n");
            if (lines.length > 1) { // header row returned
                return events;
            }
        }
        return "";
    }

    public void streamPodLogs(String forUserID, String sessionID, OutputStream out) throws Exception {

        KubectlCommandBuilder.KubectlCommand getLogsCmd = KubectlCommandBuilder.command("logs")
                .namespace(K8SUtil.getWorkloadNamespace())
                .label("canfar-net-sessionID=" + sessionID + ",canfar-net-userid=" + forUserID)
                .option("--tail", "-1");

        CommandExecutioner.execute(getLogsCmd.build(), out);
    }

    public Session getDesktopApp(String sessionID, String appID) throws Exception {
        List<Session> sessions = SessionDAO.getUserSessions(posixPrincipal.username, sessionID, true);
        if (!sessions.isEmpty()) {
            for (Session session : sessions) {
                // only include 'desktop-app'
                if (SkahaAction.TYPE_DESKTOP_APP.equalsIgnoreCase(session.getType())
                        && (sessionID.equals(session.getId()))
                        && (appID.equals(session.getAppId()))) {
                    return session;
                }
            }
        }

        throw new ResourceNotFoundException(
                "desktop app with session " + sessionID + " and app ID " + appID + " was not found");
    }

    public Session getSession(String forUserID, String sessionID) throws Exception {
        for (final Session session : SessionDAO.getUserSessions(forUserID, sessionID, false)) {
            // exclude 'desktop-app'
            if (!SkahaAction.TYPE_DESKTOP_APP.equalsIgnoreCase(session.getType())) {
                return session;
            }
        }

        throw new ResourceNotFoundException("session " + sessionID + " not found");
    }

    List<Session> getAllSessions(final String forUserID) throws Exception {
        return SessionDAO.getUserSessions(forUserID, null, false);
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
        String[] jobExpiryTimeCMD = getJobExpiryTimeCMD(k8sNamespace, forUserID);
        String jobExpiryTimeMap = CommandExecutioner.execute(jobExpiryTimeCMD);
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

    private String[] getJobExpiryTimeCMD(String k8sNamespace, String forUserID) {
        KubectlCommandBuilder.KubectlCommand getSessionJobCmd = KubectlCommandBuilder.command("get")
                .namespace(k8sNamespace)
                .job()
                .label("canfar-net-userid=" + forUserID)
                .noHeaders()
                .outputFormat("custom-columns=UID:.metadata.uid,EXPIRY:.spec.activeDeadlineSeconds");

        return getSessionJobCmd.build();
    }
}
