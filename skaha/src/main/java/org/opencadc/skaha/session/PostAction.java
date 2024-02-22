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
import ca.nrc.cadc.io.ResourceIterator;
import ca.nrc.cadc.net.HttpGet;
import ca.nrc.cadc.net.ResourceNotFoundException;
import ca.nrc.cadc.util.StringUtil;
import ca.nrc.cadc.uws.server.RandomStringGenerator;
import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.opencadc.auth.PosixGroup;
import org.opencadc.gms.GroupURI;
import org.opencadc.permissions.WriteGrant;
import org.opencadc.skaha.K8SUtil;
import org.opencadc.skaha.SkahaAction;
import org.opencadc.skaha.context.ResourceContexts;
import org.opencadc.skaha.image.Image;
import org.opencadc.skaha.utils.PosixHelper;

import javax.security.auth.Subject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessControlException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static org.opencadc.skaha.utils.CommandExecutioner.execute;

/**
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
    public static final String SKAHA_USER_QUOTA_GB = "skaha.userquotagb";
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
    public static final String SOFTWARE_APPID = "software.appid";
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
    public static final String HEADLESS_PRIORITY = "headless.priority";
    public static final String HEADLESS_IMAGE_BUNDLE = "headless.image.bundle";
    private static final String CREATE_USER_BASE_COMMAND = "/usr/local/bin/add-user";
    private static final String DEFAULT_HARBOR_SECRET = "notused";
    private static final String POSIX_MAPPING_SECRET = "POSIX_MAPPING_SECRET";
    private static final String SKAHA_TLD = "SKAHA_TLD";

    public PostAction() {
        super();
    }

    @Override
    public void doAction() throws Exception {

        super.initRequest();

        final String validatedType;
        ResourceContexts rc = new ResourceContexts();
        String image = syncInput.getParameter("image");
        if (image == null) {
            if (requestType.equals(REQUEST_TYPE_APP) || (requestType.equals(REQUEST_TYPE_SESSION)
                                                         && sessionID == null)) {
                throw new IllegalArgumentException("Missing parameter 'image'");
            }
        }

        if (requestType.equals(REQUEST_TYPE_SESSION)) {
            if (sessionID == null) {
                String type = syncInput.getParameter("type");
                validatedType = validateImage(image, type);
                Integer cores = getCoresParam();
                if (cores == null) {
                    cores = rc.getDefaultCores(validatedType);
                }

                Integer ram = getRamParam();
                if (ram == null) {
                    ram = rc.getDefaultRAM(validatedType);
                }

                String name = syncInput.getParameter("name");
                String gpusParam = syncInput.getParameter("gpus");
                String cmd = syncInput.getParameter("cmd");
                String args = syncInput.getParameter("args");
                List<String> envs = syncInput.getParameters("env");
                if (name == null) {
                    throw new IllegalArgumentException("Missing parameter 'name'");
                }

                validateName(name);

                // check for no existing session for this user
                // (rule: only 1 session of same type per user allowed)
                checkExistingSessions(posixPrincipal.username, validatedType);

                // create a new session id
                // (VNC passwords are only good up to 8 characters)
                this.sessionID = new RandomStringGenerator(8).getID();

                int gpus = 0;
                if (gpusParam != null) {
                    try {
                        gpus = Integer.parseInt(gpusParam);
                        if (!rc.getAvailableGPUs().contains(gpus)) {
                            throw new IllegalArgumentException("Unavailable option for 'gpus': " + gpusParam);
                        }
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Invalid value for 'gpus': " + gpusParam);
                    }
                }

                ensureUserBase();
                createSession(validatedType, image, name, cores, ram, gpus, cmd, args, envs);
                // return the session id
                syncOutput.setHeader("Content-Type", "text/plain");
                syncOutput.getOutputStream().write((sessionID + "\n").getBytes());
            } else {
                String action = syncInput.getParameter("action");
                if (StringUtil.hasLength(action)) {
                    if (action.equalsIgnoreCase("renew")) {
                        Map<String, List<String>> jobNameToAttributesMap = getJobsToRenew(posixPrincipal.username,
                                                                                          sessionID);
                        if (!jobNameToAttributesMap.isEmpty()) {
                            for (Map.Entry<String, List<String>> entry : jobNameToAttributesMap.entrySet()) {
                                renew(entry);
                            }
                        } else {
                            throw new IllegalArgumentException(
                                    "No active job for user " + posixPrincipal + " with session " + sessionID);
                        }
                    } else {
                        throw new UnsupportedOperationException("unrecognized action");
                    }
                } else {
                    throw new UnsupportedOperationException("Cannot modify an existing session");
                }
            }
            return;
        } else if (requestType.equals(REQUEST_TYPE_APP)) {
            if (appID == null) {
                // create an app
                Integer requestCores = getCoresParam();
                Integer limitCores = requestCores;
                if (requestCores == null) {
                    requestCores = rc.getDefaultRequestCores();
                    limitCores = rc.getDefaultLimitCores();
                }

                Integer requestRAM = getRamParam();
                Integer limitRAM = requestRAM;
                if (requestRAM == null) {
                    requestRAM = rc.getDefaultRequestRAM();
                    limitRAM = rc.getDefaultLimitRAM();
                }

                attachDesktopApp(image, requestCores, limitCores, requestRAM, limitRAM);
                syncOutput.setHeader("Content-Type", "text/plain");
                syncOutput.getOutputStream().write((appID + "\n").getBytes());
            } else {
                throw new UnsupportedOperationException("Cannot modify an existing app.");
            }
        }
    }

    void ensureUserBase() throws Exception {
        final Path homeDir = getUserHomeDirectory();

        if (Files.notExists(homeDir)) {
            log.debug("Allocating new user home to " + homeDir);
            allocateUser();
            log.debug("Allocating new user home to " + homeDir + ": OK");
        }
    }

    void allocateUser() throws Exception {
        log.debug("PostAction.makeUserBase()");
        final Path userHomePath = getUserHomeDirectory();
        final String[] allocateUserCommand = new String[] {
                PostAction.CREATE_USER_BASE_COMMAND, getUsername(), Integer.toString(getUID()),
                getDefaultQuota(), userHomePath.toAbsolutePath().toString()
        };

        log.debug("Executing " + Arrays.toString(allocateUserCommand));
        try (final ByteArrayOutputStream standardOutput = new ByteArrayOutputStream();
             final ByteArrayOutputStream standardError = new ByteArrayOutputStream()) {
            executeCommand(allocateUserCommand, standardOutput, standardError);

            final String errorOutput = standardError.toString();
            final String commandOutput = standardOutput.toString();

            if (StringUtil.hasText(errorOutput)) {
                throw new IOException("Unable to create user home."
                                      + "\nError message from server: " + errorOutput
                                      + "\nOutput from command: " + commandOutput);
            } else {
                log.debug("PostAction.makeUserBase() success creating: " + commandOutput);
            }
        }

        log.debug("PostAction.makeUserBase(): OK");
    }

    void executeCommand(final String[] command, final OutputStream standardOut, final OutputStream standardErr)
            throws IOException, InterruptedException {
        execute(command, standardOut, standardErr);
    }

    /**
     * Override to test injected quota value without processing an entire Request.
     *
     * @return String quota number in GB, or null if not configured.
     */
    String getDefaultQuota() {
        return K8SUtil.getDefaultQuota();
    }

    private Integer getCoresParam() {
        Integer cores = null;
        String coresParam = syncInput.getParameter("cores");
        if (coresParam != null) {
            try {
                cores = Integer.valueOf(coresParam);
                final ResourceContexts rc = new ResourceContexts();
                if (!rc.isCoreCountAvailable(cores)) {
                    throw new IllegalArgumentException("Unavailable option for 'cores': " + coresParam);
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid value for 'cores': " + coresParam);
            }
        }

        return cores;
    }

    private Integer getRamParam() {
        Integer ram = null;
        String ramParam = syncInput.getParameter("ram");
        if (ramParam != null) {
            try {
                ram = Integer.valueOf(ramParam);
                ResourceContexts rc = new ResourceContexts();
                if (!rc.getAvailableRAM().contains(ram)) {
                    throw new IllegalArgumentException("Unavailable option for 'ram': " + ramParam);
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid value for 'ram': " + ramParam);
            }
        }

        return ram;
    }


    private void renew(Map.Entry<String, List<String>> entry) throws Exception {
        Long newExpiryTime = calculateExpiryTime(entry.getValue());
        if (newExpiryTime > 0) {
            String k8sNamespace = K8SUtil.getWorkloadNamespace();
            List<String> renewExpiryTimeCmd = new ArrayList<>();
            renewExpiryTimeCmd.add("kubectl");
            renewExpiryTimeCmd.add("--namespace");
            renewExpiryTimeCmd.add(k8sNamespace);
            renewExpiryTimeCmd.add("patch");
            renewExpiryTimeCmd.add("job");
            renewExpiryTimeCmd.add(entry.getKey());
            renewExpiryTimeCmd.add("--type=json");
            renewExpiryTimeCmd.add("-p");
            renewExpiryTimeCmd.add(
                    "[{\"op\":\"add\",\"path\":\"/spec/activeDeadlineSeconds\", \"value\":" + newExpiryTime + "}]");
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
        long configuredExpiryTime = Long.parseLong(configuredExpiryStr);

        String k8sNamespace = K8SUtil.getWorkloadNamespace();
        Map<String, String> jobExpiryTimeMap = getJobExpiryTimes(k8sNamespace, posixPrincipal.username);
        String activeDeadlineSecondsStr = jobExpiryTimeMap.get(uid);
        if (StringUtil.hasLength(activeDeadlineSecondsStr)) {
            long activeDeadlineSeconds = Long.parseLong(activeDeadlineSecondsStr);
            Instant startTime = Instant.parse(startTimeStr);

            long timeUsed = startTime.until(Instant.now(), ChronoUnit.SECONDS);
            long timeRemaining = activeDeadlineSeconds - timeUsed;
            // default to no need to extend the expiry time
            long calculatedTime = 0L;
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
        final List<String> getRenewJobNamesCmd = PostAction.getRenewJobNamesCmd(forUserID, sessionID);

        String renewJobNamesStr = execute(getRenewJobNamesCmd.toArray(new String[0]));
        log.debug("jobs for user " + forUserID + " with session ID=" + sessionID + ":\n" + renewJobNamesStr);

        Map<String, List<String>> renewJobMap = new HashMap<>();
        if (StringUtil.hasLength(renewJobNamesStr)) {
            String[] lines = renewJobNamesStr.split("\n");
            for (String line : lines) {
                List<String> renewJobAttributes = new ArrayList<>();
                String[] parts = line.replaceAll("\\s+", " ").trim().split(" ");
                String jobName = parts[0];
                String uid = parts[1];
                String isActive = parts[2];
                String startTime = parts[3];
                // look for the job ID of an active session
                if (!NONE.equalsIgnoreCase(isActive) && (Integer.parseInt(isActive) == 1)) {
                    renewJobAttributes.add(uid);
                    renewJobAttributes.add(startTime);
                    renewJobMap.put(jobName, renewJobAttributes);
                }
            }
        }

        return renewJobMap;
    }

    private static List<String> getRenewJobNamesCmd(String forUserID, String sessionID) {
        final String k8sNamespace = K8SUtil.getWorkloadNamespace();
        List<String> getRenewJobNamesCmd = new ArrayList<>();
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
        return getRenewJobNamesCmd;
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
     * @param type    User-provided session type (optional)
     * @return The system recognized session type
     * @throws ResourceNotFoundException If an image with the supplied ID cannot be found
     * @throws Exception                 If Harbor calls fail
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
                if (type == null) {
                    return image.getTypes().iterator().next();
                } else {
                    if (image.getTypes().contains(type)) {
                        return type;
                    } else {
                        throw new IllegalArgumentException("image/type mismatch: " + imageID + "/" + type);
                    }
                }
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
            throw new IllegalArgumentException("User " + posixPrincipal.username + " has reached the maximum of " +
                                               maxUserSessions + " active sessions.");
        }
    }

    public void createSession(String type, String image, String name, Integer cores, Integer ram, Integer gpus,
                              String cmd, String args, List<String> envs)
            throws Exception {

        String jobName = K8SUtil.getJobName(sessionID, type, posixPrincipal.username);

        final String imageSecret = getHarborSecret(image);
        log.debug("image secret: " + imageSecret);

        String supplementalGroups = getSupplementalGroupsList();
        log.debug("supplementalGroups are " + supplementalGroups);
        String secretName = createPosixMappingSecret(sessionID);
        xAuthTokenSkaha = skahaCallbackFlow ? xAuthTokenSkaha : generateToken(sessionID);

        String k8sNamespace = K8SUtil.getWorkloadNamespace();

        final String jobLaunchPath;
        final String servicePath;
        final String ingressPath;
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
                validateHeadlessMembership();
                jobLaunchPath = System.getProperty("user.home") + "/config/launch-headless.yaml";
                servicePath = null;
                ingressPath = null;
                break;
            default:
                throw new IllegalStateException("Bug: unknown session type: " + type);
        }

        byte[] jobLaunchBytes = Files.readAllBytes(Paths.get(jobLaunchPath));
        String jobLaunchString = new String(jobLaunchBytes, StandardCharsets.UTF_8);
        String headlessPriority = getHeadlessPriority();
        String headlessImageBundle = getHeadlessImageBundle(image, cmd, args, envs);
        String gpuScheduling = getGPUScheduling(gpus);

        jobLaunchString = setConfigValue(jobLaunchString, SKAHA_SESSIONID, sessionID);
        jobLaunchString = setConfigValue(jobLaunchString, SKAHA_SESSIONNAME, name.toLowerCase());
        jobLaunchString = setConfigValue(jobLaunchString, SKAHA_SESSIONEXPIRY, K8SUtil.getSessionExpiry());
        jobLaunchString = setConfigValue(jobLaunchString, SKAHA_JOBNAME, jobName);
        jobLaunchString = setConfigValue(jobLaunchString, SKAHA_HOSTNAME, K8SUtil.getHostName());
        jobLaunchString = setConfigValue(jobLaunchString, SKAHA_USERID, getUsername());
        jobLaunchString = setConfigValue(jobLaunchString, SKAHA_POSIXID,
                                         Integer.toString(posixPrincipal.getUidNumber()));
        if (StringUtil.hasText(supplementalGroups)) {
            jobLaunchString = setConfigValue(jobLaunchString, SKAHA_SUPPLEMENTALGROUPS, supplementalGroups);
        }
        jobLaunchString = setConfigValue(jobLaunchString, SKAHA_SESSIONTYPE, type);
        jobLaunchString = setConfigValue(jobLaunchString, SKAHA_SCHEDULEGPU, gpuScheduling);
        jobLaunchString = setConfigValue(jobLaunchString, SOFTWARE_IMAGEID, image);
        jobLaunchString = setConfigValue(jobLaunchString, SOFTWARE_HOSTNAME, name.toLowerCase());
        jobLaunchString = setConfigValue(jobLaunchString, SOFTWARE_IMAGESECRET, imageSecret);
        jobLaunchString = setConfigValue(jobLaunchString, HEADLESS_IMAGE_BUNDLE, headlessImageBundle);
        jobLaunchString = setConfigValue(jobLaunchString, HEADLESS_PRIORITY, headlessPriority);
        jobLaunchString = setConfigValue(jobLaunchString, SOFTWARE_REQUESTS_CORES, cores.toString());
        jobLaunchString = setConfigValue(jobLaunchString, SOFTWARE_REQUESTS_RAM, ram.toString() + "Gi");
        jobLaunchString = setConfigValue(jobLaunchString, SOFTWARE_LIMITS_CORES, cores.toString());
        jobLaunchString = setConfigValue(jobLaunchString, SOFTWARE_LIMITS_RAM, ram + "Gi");
        jobLaunchString = setConfigValue(jobLaunchString, SOFTWARE_LIMITS_GPUS, gpus.toString());
        jobLaunchString = setConfigValue(jobLaunchString, POSIX_MAPPING_SECRET, secretName);
        jobLaunchString = setConfigValue(jobLaunchString, SKAHA_TLD, skahaTld);

        String jsonLaunchFile = super.stageFile(jobLaunchString);
        String[] launchCmd = new String[] {"kubectl", "create", "--namespace", k8sNamespace, "-f", jsonLaunchFile};
        String createResult = execute(launchCmd);
        log.debug("Create job result: " + createResult);

        // insert the user's proxy cert in the home dir
        injectCredentials();

        if (servicePath != null) {
            byte[] serviceBytes = Files.readAllBytes(Paths.get(servicePath));
            String serviceString = new String(serviceBytes, StandardCharsets.UTF_8);
            serviceString = setConfigValue(serviceString, SKAHA_SESSIONID, sessionID);
            jsonLaunchFile = super.stageFile(serviceString);
            launchCmd = new String[] {"kubectl", "create", "--namespace", k8sNamespace, "-f", jsonLaunchFile};
            createResult = execute(launchCmd);
            log.debug("Create service result: " + createResult);
        }

        if (ingressPath != null) {
            byte[] ingressBytes = Files.readAllBytes(Paths.get(ingressPath));
            String ingressString = new String(ingressBytes, StandardCharsets.UTF_8);
            ingressString = setConfigValue(ingressString, SKAHA_SESSIONID, sessionID);
            ingressString = setConfigValue(ingressString, SKAHA_HOSTNAME, K8SUtil.getHostName());
            jsonLaunchFile = super.stageFile(ingressString);
            launchCmd = new String[] {"kubectl", "create", "--namespace", k8sNamespace, "-f", jsonLaunchFile};
            createResult = execute(launchCmd);
            log.debug("Create ingress result: " + createResult);
        }
    }

    private String generateToken(String sessionID) throws Exception {
        return SkahaAction.getTokenTool().generateToken(URI.create(skahaUsersGroup), WriteGrant.class, sessionID);
    }

    /**
     * Attach a desktop application.
     * TODO: This method requires rework.  The Job Name does not use the same mechanism as the K8SUtil.getJobName()
     * TODO: and will suffer the same issue(s) with invalid characters in the Kubernetes object names.
     *
     * @param image Container image name.
     * @throws Exception For any unexpected errors.
     */
    public void attachDesktopApp(String image, Integer requestCores, Integer limitCores, Integer requestRAM,
                                 Integer limitRAM) throws Exception {

        String k8sNamespace = K8SUtil.getWorkloadNamespace();

        // Get the IP address based on the session
        String[] getIPCommand = new String[] {
                "kubectl", "-n", k8sNamespace, "get", "pod", "--selector=canfar-net-sessionID=" + sessionID,
                "--no-headers=true",
                "-o", "custom-columns=" +
                      "IPADDR:.status.podIP," +
                      "DT:.metadata.deletionTimestamp," +
                      "TYPE:.metadata.labels.canfar-net-sessionType," +
                      "NAME:.metadata.name"};
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
        final String imageSecret = getHarborSecret(image);
        log.debug("image secret: " + imageSecret);

        String supplementalGroups = getSupplementalGroupsList();
        final String secretName = PosixHelper.getPosixMapperSecretName(sessionID);
        xAuthTokenSkaha = skahaCallbackFlow ? xAuthTokenSkaha : generateToken(sessionID);

        String launchSoftwarePath = System.getProperty("user.home") + "/config/launch-desktop-app.yaml";
        byte[] launchBytes = Files.readAllBytes(Paths.get(launchSoftwarePath));

        // incoming params ignored for the time being.  set to the 'name' so
        // that it becomes the xterm title
        String param = name;
        log.debug("Using parameter: " + param);
        log.debug("Using requests.cores: " + requestCores.toString());
        log.debug("Using limits.cores: " + limitCores.toString());
        log.debug("Using requests.ram: " + requestRAM.toString() + "Gi");
        log.debug("Using limits.ram: " + limitRAM.toString() + "Gi");

        appID = new RandomStringGenerator(3).getID();
        String userJobID = posixPrincipal.username.replaceAll("[^0-9a-zA-Z-]", "-");
        String jobName = sessionID + "-" + appID + "-" + userJobID.toLowerCase() + "-" + name.toLowerCase();
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

        String launchString = new String(launchBytes, StandardCharsets.UTF_8);
        launchString = setConfigValue(launchString, SKAHA_SESSIONID, sessionID);
        launchString = setConfigValue(launchString, SOFTWARE_JOBNAME, jobName);
        launchString = setConfigValue(launchString, SOFTWARE_HOSTNAME, containerName);
        launchString = setConfigValue(launchString, SOFTWARE_CONTAINERNAME, containerName);
        launchString = setConfigValue(launchString, SOFTWARE_APPID, appID);
        launchString = setConfigValue(launchString, SOFTWARE_CONTAINERPARAM, param);
        launchString = setConfigValue(launchString, SOFTWARE_REQUESTS_CORES, requestCores.toString());
        launchString = setConfigValue(launchString, SOFTWARE_LIMITS_CORES, limitCores.toString());
        launchString = setConfigValue(launchString, SOFTWARE_REQUESTS_RAM, requestRAM + "Gi");
        launchString = setConfigValue(launchString, SOFTWARE_LIMITS_RAM, limitRAM + "Gi");
        launchString = setConfigValue(launchString, SKAHA_USERID, posixPrincipal.username);
        launchString = setConfigValue(launchString, SKAHA_SESSIONTYPE, SessionAction.TYPE_DESKTOP_APP);
        launchString = setConfigValue(launchString, SKAHA_SESSIONEXPIRY, K8SUtil.getSessionExpiry());
        launchString = setConfigValue(launchString, SOFTWARE_TARGETIP, targetIP + ":1");
        launchString = setConfigValue(launchString, SKAHA_POSIXID, Integer.toString(posixPrincipal.getUidNumber()));
        if (StringUtil.hasText(supplementalGroups)) {
            launchString = setConfigValue(launchString, SKAHA_SUPPLEMENTALGROUPS, supplementalGroups);
        }
        launchString = setConfigValue(launchString, SKAHA_SCHEDULEGPU, gpuScheduling);
        launchString = setConfigValue(launchString, SOFTWARE_IMAGEID, image);
        launchString = setConfigValue(launchString, SOFTWARE_IMAGESECRET, imageSecret);
        launchString = setConfigValue(launchString, POSIX_MAPPING_SECRET, secretName);
        launchString = setConfigValue(launchString, SKAHA_TLD, skahaTld);

        String launchFile = super.stageFile(launchString);

        String[] launchCmd = new String[] {
                "kubectl", "create", "--namespace", k8sNamespace, "-f", launchFile
        };

        String createResult = execute(launchCmd);
        log.debug("Create result: " + createResult);

        // refresh the user's proxy cert
        injectCredentials();
    }

    private void validateHeadlessMembership() {
        if (skahaHeadlessGroup == null) {
            log.warn("skaha.headlessgroup not defined in system properties");
        } else if (!headlessUser) {
            throw new AccessControlException("Not authorized to create a headless session");
        }
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

        String idToken = getIdToken();

        // Default secret name if no ID Token is found.
        if (!StringUtil.hasText(idToken)) {
            return PostAction.DEFAULT_HARBOR_SECRET;
        }

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
            return PostAction.DEFAULT_HARBOR_SECRET;
        }
        if (get.getThrowable() != null) {
            log.warn("error obtaining harbor secret. response code: " + get.getResponseCode());
            return PostAction.DEFAULT_HARBOR_SECRET;
        }
        String userJson = out.toString();
        log.debug("harbor user info: " + userJson);
        JSONTokener tokener = new JSONTokener(userJson);
        JSONObject obj = new JSONObject(tokener);
        String cliSecret = obj.getJSONObject("oidc_user_meta").getString("secret");
        log.debug("cliSecret: " + cliSecret);
        String harborUsername = obj.getString("username");

        final String secretName = "harbor-secret-" + posixPrincipal.username.toLowerCase();

        // delete any old secret by this name
        String[] deleteCmd = new String[] {
                "kubectl", "--namespace", K8SUtil.getWorkloadNamespace(), "delete", "secret", secretName};
        try {
            String deleteResult = execute(deleteCmd);
            log.debug("delete secret result: " + deleteResult);
        } catch (IOException notFound) {
            log.debug("no secret to delete", notFound);
        }

        // harbor invalidates secrets with the unicode replacement characters 'fffd'.
        if (cliSecret != null && !cliSecret.startsWith("\ufffd")) {
            // create new secret
            String[] createCmd = new String[] {
                    "kubectl", "--namespace", K8SUtil.getWorkloadNamespace(), "create", "secret", "docker-registry",
                    secretName,
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
            return PostAction.DEFAULT_HARBOR_SECRET;
        }

        return secretName;
    }

    private String createPosixMappingSecret(final String sessionID) throws Exception {
        if (skahaCallbackFlow) {
            return PosixHelper.getPosixMapperSecretName(callbackSessionId);
        }

        final String posixMappingSecretName = PosixHelper.getPosixMapperSecretName(sessionID);
        final Path holdingDir =
                Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), posixMappingSecretName);
        final File uidMappingFile = new File(holdingDir.toString(), "uidmap.txt");
        final File gidMappingFile = new File(holdingDir.toString(), "gidmap.txt");

        try (final FileWriter uidMappingWriter = new FileWriter(uidMappingFile)) {
            uidMappingWriter.write(getUserEntries());
            uidMappingWriter.flush();
        }

        try (final FileWriter gidMappingWriter = new FileWriter(gidMappingFile)) {
            gidMappingWriter.write(getGroupEntries());
            gidMappingWriter.flush();
        }

        // create new secret
        final String[] createCmd = new String[] {
                "kubectl", "--namespace", K8SUtil.getWorkloadNamespace(), "create", "secret", "generic",
                posixMappingSecretName,
                "--from-file=" + uidMappingFile.getAbsolutePath(),
                "--from-file=" + gidMappingFile.getAbsolutePath()
        };

        final String createResult = execute(createCmd);
        log.debug("create secret result: " + createResult);

        return posixMappingSecretName;
    }

    private String getUserEntries() throws Exception {
        final StringBuilder userEntryBuilder = new StringBuilder();
        try (final ResourceIterator<PosixPrincipal> posixPrincipalIterator =
                     posixMapperConfiguration.getPosixMapperClient().getUserMap()) {
            posixPrincipalIterator.forEachRemaining(pp -> userEntryBuilder.append(PosixHelper.uidMapping(pp)));
        }

        final String userEntriesString = userEntryBuilder.toString();
        if (userEntriesString.lastIndexOf(SkahaAction.POSIX_DELIMITER) > 0) {
            return userEntryBuilder.substring(0, userEntriesString.lastIndexOf(SkahaAction.POSIX_DELIMITER));
        } else {
            return userEntryBuilder.toString();
        }
    }

    private String getSupplementalGroupsList() throws Exception {
        if (skahaCallbackFlow) {
            return callbackSupplementalGroups;
        }
        Set<List<Group>> groupCredentials = getCachedGroupsFromSubject();
        if (groupCredentials.size() == 1) {
            return buildGroupUriList(groupCredentials)
                    .stream()
                    .map(posixGroup -> Integer.toString(posixGroup.getGID()))
                    .collect(Collectors.joining(","));
        } else {
            return "";
        }
    }

    private List<PosixGroup> buildGroupUriList(Set<List<Group>> groupCredentials) throws Exception {
        return toGIDs(groupCredentials.iterator().next().stream()
                                      .map(Group::getID)
                                      .collect(Collectors.toList())
                     );
    }

    List<PosixGroup> toGIDs(final List<GroupURI> groupURIS) throws Exception {
        return posixMapperConfiguration.getPosixMapperClient().getGID(groupURIS);
    }

    private static Set<List<Group>> getCachedGroupsFromSubject() {
        Subject subject = AuthenticationUtil.getCurrentSubject();
        Class<List<Group>> c = (Class<List<Group>>) (Class<?>) List.class;
        return subject.getPublicCredentials(c);
    }

    /**
     * Create the image, command, args, and env sections of the job launch yaml.  Example:
     * <p>
     * image: "${software.imageid}"
     * command: ["/skaha-system/start-desktop-software.sh"]
     * args: [arg1, arg2]
     * env:
     * - name: HOME
     * value: "/cavern/home/${skaha.userid}"
     * - name: SHELL
     * value: "/bin/bash"
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

    private String getHeadlessPriority() {
        if (skahaPriorityHeadlessGroup == null) {
            return "";
        }
        if (skahaHeadlessPriortyClass == null) {
            log.warn("headlessPriorityGroup set but headlessPriorityClass not set");
            return "";
        }
        if (priorityHeadlessUser) {
            return "priorityClassName: " + skahaHeadlessPriortyClass;
        } else {
            return "";
        }
    }
}