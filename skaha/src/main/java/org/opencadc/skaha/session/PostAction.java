/*
 ************************************************************************
 *******************  CANADIAN ASTRONOMY DATA CENTRE  *******************
 **************  CENTRE CANADIEN DE DONNÉES ASTRONOMIQUES  **************
 *
 *  (c) 2024.                            (c) 2024.
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
import ca.nrc.cadc.net.ResourceNotFoundException;
import ca.nrc.cadc.rest.SyncInput;
import ca.nrc.cadc.util.StringUtil;
import ca.nrc.cadc.uws.server.RandomStringGenerator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessControlException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import javax.security.auth.Subject;
import org.apache.log4j.Logger;
import org.opencadc.auth.PosixGroup;
import org.opencadc.gms.GroupURI;
import org.opencadc.permissions.WriteGrant;
import org.opencadc.skaha.K8SUtil;
import org.opencadc.skaha.KubernetesJob;
import org.opencadc.skaha.SkahaAction;
import org.opencadc.skaha.context.ResourceContexts;
import org.opencadc.skaha.repository.ImageRepositoryAuth;
import org.opencadc.skaha.utils.CommandExecutioner;
import org.opencadc.skaha.utils.KubectlCommandBuilder;
import org.opencadc.skaha.utils.PosixCache;

/**
 * POST submission for creating a new session or app, or updating (renewing) an existing session. Configuration is
 * pulled from the environment.
 *
 * @author majorb
 */
public class PostAction extends SessionAction {

    // variables replaced in kubernetes yaml config files for
    // launching desktop sessions and launching software
    // use in the form: ${var.name}
    public static final String SKAHA_HOSTNAME = "skaha.hostname";
    public static final String SKAHA_SESSIONS_HOSTNAME = "skaha.sessions.hostname";
    public static final String SKAHA_USERID = "skaha.userid";
    public static final String SKAHA_POSIXID = "skaha.posixid";
    public static final String SKAHA_SUPPLEMENTALGROUPS = "skaha.supgroups";
    public static final String SKAHA_SESSIONID = "skaha.sessionid";

    // Used only with CARTA-5 images.
    public static final String SKAHA_SESSIONURLPATH = "skaha.sessionurlpath";

    public static final String SKAHA_SESSIONNAME = "skaha.sessionname";
    public static final String SKAHA_SESSIONTYPE = "skaha.sessiontype";
    public static final String SKAHA_SESSIONEXPIRY = "skaha.sessionexpiry";
    public static final String SKAHA_JOBNAME = "skaha.jobname";
    public static final String SKAHA_JOBUID = "skaha.jobuid";
    public static final String SOFTWARE_JOBNAME = "software.jobname";
    public static final String SOFTWARE_HOSTNAME = "software.hostname";
    public static final String SOFTWARE_APPID = "software.appid";
    public static final String SOFTWARE_CONTAINERNAME = "software.containername";
    public static final String SOFTWARE_CONTAINERPARAM = "software.containerparam";
    public static final String SOFTWARE_TARGETIP = "software.targetip";
    public static final String SOFTWARE_IMAGEID = "software.imageid";
    public static final String SOFTWARE_REQUESTS_CORES = "software.requests.cores";
    public static final String SOFTWARE_REQUESTS_RAM = "software.requests.ram";
    public static final String SOFTWARE_LIMITS_CORES = "software.limits.cores";
    public static final String SOFTWARE_LIMITS_RAM = "software.limits.ram";
    public static final String HEADLESS_PRIORITY = "headless.priority";
    public static final String HEADLESS_IMAGE_BUNDLE = "headless.image.bundle";

    // k8s rejects label size > 63. Since k8s appends a maximum of six characters
    // to a job name to form a pod name, we limit the job name length to 57 characters.
    private static final int MAX_JOB_NAME_LENGTH = 57;
    private static final String CREATE_USER_BASE_COMMAND = "/usr/local/bin/add-user";
    private static final String DESKTOP_SESSION_APP_TOKEN = "software.desktop.app.token";
    private static final String SKAHA_TLD = "SKAHA_TLD";

    private static final Logger log = Logger.getLogger(PostAction.class);

    public PostAction() {
        super();
    }

    private static Set<List<Group>> getCachedGroupsFromSubject() {
        Subject subject = AuthenticationUtil.getCurrentSubject();
        Class<List<Group>> c = (Class<List<Group>>) (Class<?>) List.class;
        return subject.getPublicCredentials(c);
    }

    @Override
    public void doAction() throws Exception {

        super.initRequest();

        ResourceContexts rc = new ResourceContexts();
        String image = syncInput.getParameter("image");
        if (image == null) {
            if (requestType.equals(REQUEST_TYPE_APP)
                    || (requestType.equals(REQUEST_TYPE_SESSION) && sessionID == null)) {
                throw new IllegalArgumentException("Missing parameter 'image'");
            }
        }

        if (requestType.equals(REQUEST_TYPE_SESSION)) {
            if (sessionID == null) {
                final String requestedType = syncInput.getParameter("type");

                // Absence of type is assumed to be headless
                final String type =
                        StringUtil.hasText(requestedType) ? requestedType : PostAction.SESSION_TYPE_HEADLESS;

                final SessionType validatedType = validateImage(image, type);

                final ResourceSpecification resourceSpecification = ResourceSpecification.fromSyncInput(this.syncInput);

                String name = syncInput.getParameter("name");
                if (!StringUtil.hasText(name)) {
                    throw new IllegalArgumentException("Missing parameter 'name'");
                }

                validateName(name);

                // check for no existing session for this user
                // (rule: only 1 session of same type per user allowed)
                checkExistingSessions(validatedType);

                // create a new session id
                // (VNC passwords are only good up to 8 characters)
                this.sessionID = new RandomStringGenerator(8).getID();

                int gpus = 0;
                final String gpusParam = syncInput.getParameter("gpus");
                if (StringUtil.hasText(gpusParam)) {
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

                final String cmd = syncInput.getParameter("cmd");
                final String args = syncInput.getParameter("args");
                final List<String> envs = syncInput.getParameters("env");
                createSession(validatedType, image, name, resourceSpecification, gpus, cmd, args, envs);
                // return the session id
                syncOutput.setHeader("Content-Type", "text/plain");
                syncOutput.getOutputStream().write((sessionID + "\n").getBytes());
            } else {
                String action = syncInput.getParameter("action");
                if (StringUtil.hasLength(action)) {
                    if (action.equalsIgnoreCase("renew")) {
                        Map<String, List<String>> jobNameToAttributesMap =
                                getJobsToRenew(posixPrincipal.username, sessionID);
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
        } else if (requestType.equals(REQUEST_TYPE_APP)) {
            if (appID == null) {
                final ResourceSpecification resourceSpecification = ResourceSpecification.fromSyncInput(this.syncInput);

                attachDesktopApp(image, resourceSpecification);
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
            PostAction.CREATE_USER_BASE_COMMAND,
            getUsername(),
            Integer.toString(getUID()),
            getDefaultQuota(),
            userHomePath.toAbsolutePath().toString()
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
        CommandExecutioner.execute(command, standardOut, standardErr);
    }

    /**
     * Override to test injected quota value without processing an entire Request.
     *
     * @return String quota number in GB, or null if not configured.
     */
    String getDefaultQuota() {
        return K8SUtil.getDefaultQuota();
    }

    private void renew(Map.Entry<String, List<String>> entry) throws Exception {
        Long newExpiryTime = calculateExpiryTime(entry.getValue());
        if (newExpiryTime > 0) {
            KubectlCommandBuilder.KubectlCommand renewExpiryTimeCmd = KubectlCommandBuilder.command("patch")
                    .namespace(K8SUtil.getWorkloadNamespace())
                    .job()
                    .argument(entry.getKey())
                    .argument("--type=json")
                    .option(
                            "-p",
                            "[{\"op\":\"add\",\"path\":\"/spec/activeDeadlineSeconds\", \"value\":" + newExpiryTime
                                    + "}]");

            CommandExecutioner.execute(renewExpiryTimeCmd.build());
        }

        injectProxyCertificate();
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
        KubectlCommandBuilder.KubectlCommand getRenewJobNamesCmd = KubectlCommandBuilder.command("get")
                .namespace(K8SUtil.getWorkloadNamespace())
                .job()
                .label("canfar-net-sessionID=" + sessionID + ",canfar-net-userid=" + forUserID)
                .noHeaders()
                .outputFormat(
                        "custom-columns=NAME:.metadata.name,UID:.metadata.uid,STATUS:.status.active,START:.status.startTime");

        String renewJobNamesStr = CommandExecutioner.execute(getRenewJobNamesCmd.build());

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

    private void validateName(String name) {
        if (!StringUtil.hasText(name)) {
            throw new IllegalArgumentException("name must have a value");
        }
        if (!name.matches("[A-Za-z0-9\\-]+")) {
            throw new IllegalArgumentException("name can only contain alpha-numeric chars and '-'");
        }
    }

    /**
     * Validate and return the session type.
     *
     * @param imageID The image to validate
     * @param type Session type
     * @return The system recognized session type
     */
    private SessionType validateImage(String imageID, String type) {
        if (!StringUtil.hasText(imageID)) {
            throw new IllegalArgumentException("image is required");
        }

        // make sure the host of the image matches one of the allowed image hosts
        // in the configuration
        log.debug("validating image: " + imageID);
        final String imageRegistryHost = getRegistryHost(imageID);
        log.debug("imageRegistryHost " + imageRegistryHost);

        for (String authorizedHost : harborHosts) {
            if (authorizedHost.equals(imageRegistryHost)) {
                final SessionType sessionType = SessionType.fromApplicationStringType(type);
                if (SessionType.HEADLESS == sessionType) {
                    // assert headless group membership
                    validateHeadlessMembership();
                }
                return sessionType;
            }
        }
        throw new IllegalArgumentException("image not in a trusted repository");
    }

    public void checkExistingSessions(SessionType type) throws Exception {
        // multiple
        if (!type.isHeadless()) {
            final List<Session> sessions = SessionDAO.getUserSessions(this.posixPrincipal.username, null, true);
            int count = 0;
            for (Session session : sessions) {
                log.debug("checking session: " + session);
                if (!TYPE_DESKTOP_APP.equals(session.getType())) {
                    final String status = session.getStatus();
                    if (!(status.equalsIgnoreCase(Session.STATUS_TERMINATING)
                            || status.equalsIgnoreCase(Session.STATUS_SUCCEEDED))) {
                        count++;
                    }
                }
            }
            log.debug("active interactive sessions: " + count);
            if (count >= maxUserSessions) {
                throw new IllegalArgumentException("User " + posixPrincipal.username + " has reached the maximum of "
                        + maxUserSessions + " active sessions.");
            }
        }
    }

    public void createSession(
            SessionType type,
            String image,
            String name,
            ResourceSpecification resourceSpecification,
            Integer gpus,
            String cmd,
            String args,
            List<String> envs)
            throws Exception {

        String supplementalGroups = getSupplementalGroupsList();
        log.debug("supplementalGroups are " + supplementalGroups);

        final String headlessPriority = getHeadlessPriority();
        final String headlessImageBundle = getHeadlessImageBundle(image, cmd, args, envs);
        final String jobName = K8SUtil.getJobName(sessionID, type, posixPrincipal.username);

        final Integer majorVersion = K8SUtil.getMajorImageVersion(image);
        final boolean isLegacyCARTA = (type == SessionType.CARTA && (majorVersion == null || majorVersion < 5));

        SessionJobBuilder sessionJobBuilder = SessionJobBuilder.fromPath(type.getJobConfigPath(isLegacyCARTA))
                .withGPUEnabled(this.gpuEnabled)
                .withGPUCount(gpus)
                .withQueue(QueueConfiguration.fromType(type.name())) // Can be null.
                .withParameter(PostAction.SKAHA_SESSIONID, this.sessionID)
                .withParameter(PostAction.SKAHA_SESSIONNAME, name.toLowerCase())
                .withParameter(PostAction.SKAHA_SESSIONEXPIRY, K8SUtil.getSessionExpiry())
                .withParameter(PostAction.SKAHA_JOBNAME, jobName)
                .withParameter(PostAction.SKAHA_HOSTNAME, K8SUtil.getSkahaHostName())
                .withParameter(PostAction.SKAHA_SESSIONS_HOSTNAME, K8SUtil.getSessionsHostName())
                .withParameter(PostAction.SKAHA_USERID, getUsername())
                .withParameter(PostAction.SKAHA_POSIXID, Integer.toString(this.posixPrincipal.getUidNumber()))
                .withParameter(PostAction.SKAHA_SESSIONTYPE, type.name().toLowerCase())
                .withParameter(PostAction.SOFTWARE_IMAGEID, image)
                .withParameter(PostAction.SOFTWARE_HOSTNAME, name.toLowerCase())
                .withParameter(PostAction.HEADLESS_IMAGE_BUNDLE, headlessImageBundle)
                .withParameter(PostAction.HEADLESS_PRIORITY, headlessPriority)
                .withParameter(PostAction.SOFTWARE_REQUESTS_CORES, resourceSpecification.requestCores.toString())
                .withParameter(PostAction.SOFTWARE_REQUESTS_RAM, resourceSpecification.requestRAM.toString() + "Gi")
                .withParameter(PostAction.SOFTWARE_LIMITS_CORES, resourceSpecification.limitCores.toString())
                .withParameter(PostAction.SOFTWARE_LIMITS_RAM, resourceSpecification.limitRAM + "Gi")
                .withParameter(PostAction.SKAHA_TLD, this.skahaTld)
                .withParameter(
                        PostAction.SKAHA_SUPPLEMENTALGROUPS,
                        StringUtil.hasText(supplementalGroups) ? supplementalGroups : "");

        if (type == SessionType.DESKTOP) {
            sessionJobBuilder = sessionJobBuilder.withParameter(PostAction.DESKTOP_SESSION_APP_TOKEN, generateToken());
        } else if (type == SessionType.CARTA) {
            final String imageVersion = image.substring(image.lastIndexOf(":") + 1);
            if (imageVersion.startsWith("5")) {
                final String connectURLPrefix = SessionURLBuilder.cartaSession(
                                K8SUtil.getSessionsHostName(), this.sessionID)
                        .withVersion5Path(true)
                        .withAlternateSocket(false)
                        .build();
                final String connectURLPath = URI.create(connectURLPrefix).getPath();
                sessionJobBuilder = sessionJobBuilder.withParameter(
                        PostAction.SKAHA_SESSIONURLPATH, connectURLPath.replaceAll("/$", ""));
            }
        }

        // In the absence of the existence of a public image, assume Private.  The validateImage() step above will have
        // caught a non-existent Image already.
        if (getPublicImage(image) == null) {
            final ImageRepositoryAuth userRegistryAuth = getRegistryAuth(getRegistryHost(image));
            sessionJobBuilder = sessionJobBuilder.withImageSecret(createRegistryImageSecret(userRegistryAuth));
        }

        String jobLaunchString = sessionJobBuilder.build();
        String jsonLaunchFile = super.stageFile(jobLaunchString);

        // insert the user's proxy cert in the home dir.  Do this first, so they're available to initContainer
        // configurations.
        injectProxyCertificate();

        // inject the entries from the POSIX Mapper
        injectPOSIXDetails();

        final String k8sNamespace = K8SUtil.getWorkloadNamespace();

        final KubectlCommandBuilder.KubectlCommand launchCmd =
                KubectlCommandBuilder.command("create").namespace(k8sNamespace).option("-f", jsonLaunchFile);
        String createResult = CommandExecutioner.execute(launchCmd.build());

        log.debug("Create job result: " + createResult);

        final KubernetesJob kubernetesJob = SessionDAO.getJob(jobName);

        // Ingress construction is still done using plain String interpolation for now.  When the Kubernetes Gateway
        // API is in place, we can swap this out with a proper Java client API.
        // TODO: Use the Kubernetes Java client to create Service objects.
        if (type.supportsService()) {
            byte[] serviceBytes = Files.readAllBytes(type.getServiceConfigPath(isLegacyCARTA));
            String serviceString = new String(serviceBytes, StandardCharsets.UTF_8);
            serviceString = SessionJobBuilder.setConfigValue(serviceString, PostAction.SKAHA_SESSIONID, this.sessionID);
            serviceString =
                    SessionJobBuilder.setConfigValue(serviceString, PostAction.SKAHA_JOBUID, kubernetesJob.getUID());
            serviceString =
                    SessionJobBuilder.setConfigValue(serviceString, PostAction.SKAHA_JOBNAME, kubernetesJob.getName());
            jsonLaunchFile = super.stageFile(serviceString);
            final KubectlCommandBuilder.KubectlCommand serviceLaunchCommand = KubectlCommandBuilder.command("create")
                    .namespace(k8sNamespace)
                    .option("-f", jsonLaunchFile);
            createResult = CommandExecutioner.execute(serviceLaunchCommand.build());

            log.debug("Create service result: " + createResult);
        }

        // Ingress construction is still done using plain String interpolation for now.  When the Kubernetes Gateway
        // API is in place, we can swap this out with a proper Java client API.
        // TODO: Use the Kubernetes Gateway API to create Ingresses.
        // TODO: Use the Kubernetes Java client to create Gateway API objects.
        if (type.supportsIngress()) {
            byte[] ingressBytes = Files.readAllBytes(type.getIngressConfigPath(isLegacyCARTA));
            String ingressString = new String(ingressBytes, StandardCharsets.UTF_8);
            ingressString = SessionJobBuilder.setConfigValue(ingressString, PostAction.SKAHA_SESSIONID, this.sessionID);
            ingressString = SessionJobBuilder.setConfigValue(
                    ingressString, PostAction.SKAHA_SESSIONS_HOSTNAME, K8SUtil.getSessionsHostName());
            ingressString =
                    SessionJobBuilder.setConfigValue(ingressString, PostAction.SKAHA_JOBUID, kubernetesJob.getUID());
            ingressString =
                    SessionJobBuilder.setConfigValue(ingressString, PostAction.SKAHA_JOBNAME, kubernetesJob.getName());
            jsonLaunchFile = super.stageFile(ingressString);
            final KubectlCommandBuilder.KubectlCommand ingressLaunchCommand = KubectlCommandBuilder.command("create")
                    .namespace(k8sNamespace)
                    .option("-f", jsonLaunchFile);
            createResult = CommandExecutioner.execute(ingressLaunchCommand.build());

            log.debug("Create ingress result: " + createResult);
        }
    }

    private void injectPOSIXDetails() throws Exception {
        final PosixCache posixCache = new PosixCache(
                this.skahaPosixCacheURL, this.homedir, this.posixMapperConfiguration.getPosixMapperClient());
        posixCache.writePOSIXEntries();
    }

    private String generateToken() throws Exception {
        return SkahaAction.getTokenTool()
                .generateToken(URI.create(this.skahaUsersGroup), WriteGrant.class, this.sessionID);
    }

    /**
     * Create a registry secret and return its name.
     *
     * @param registryAuth The credentials to use to authenticate to the Image Registry.
     * @return String secret name, never null.
     */
    private String createRegistryImageSecret(final ImageRepositoryAuth registryAuth) throws Exception {
        final String username = this.posixPrincipal.username;
        final String secretName = "registry-auth-" + username.toLowerCase();
        log.debug("Creating user secret " + secretName);
        CommandExecutioner.ensureRegistrySecret(registryAuth, secretName);

        return secretName;
    }

    private String getRegistryHost(final String imageID) {
        final String registryHost = this.harborHosts.stream()
                .filter(imageID::startsWith)
                .findFirst()
                .orElse(null);
        if (registryHost == null) {
            throw new IllegalArgumentException("session image '" + imageID + "' must come from one of "
                    + Arrays.toString(this.harborHosts.toArray()));
        }

        return registryHost;
    }

    /**
     * Attach a desktop application. TODO: This method requires rework. The Job Name does not use the same mechanism as
     * the K8SUtil.getJobName() TODO: and will suffer the same issue(s) with invalid characters in the Kubernetes object
     * names.
     *
     * @param image Container image name.
     * @param resourceSpecification The resource specification for the app.
     * @throws Exception For any unexpected errors.
     */
    public void attachDesktopApp(String image, ResourceSpecification resourceSpecification) throws Exception {
        // Get the IP address based on the session
        KubectlCommandBuilder.KubectlCommand getIPCommand = KubectlCommandBuilder.command("get")
                .pod()
                .namespace(K8SUtil.getWorkloadNamespace())
                .selector("canfar-net-sessionID=" + sessionID)
                .noHeaders()
                .outputFormat("custom-columns=IPADDR:.status.podIP,DT:.metadata.deletionTimestamp,"
                        + "TYPE:.metadata.labels.canfar-net-sessionType,NAME:.metadata.name");

        String ipResult = CommandExecutioner.execute(getIPCommand.build());

        log.debug("GET IP result: " + ipResult);

        String targetIP = null;
        String[] ipLines = ipResult.split("\n");
        for (final String ipLine : ipLines) {
            log.debug("ipLine: " + ipLine);
            String[] parts = ipLine.split("\\s+");
            if (log.isDebugEnabled()) {
                for (final String part : parts) {
                    log.debug("part: " + part);
                }
            }
            if (parts.length > 1 && parts[1].trim().equals(NONE) && SESSION_TYPE_DESKTOP.equals(parts[2])) {
                targetIP = parts[0].trim();
            }
        }

        if (targetIP == null) {
            throw new ResourceNotFoundException("session " + sessionID + " not found");
        }

        log.debug("attaching desktop app: " + image + " to " + targetIP);

        String name = getImageName(image);
        log.debug("name: " + name);

        // incoming params ignored for the time being.  set to the 'name' so
        // that it becomes the xterm title
        String param = name;
        log.debug("Using parameter: " + param);
        log.debug("Using requests.cores: " + resourceSpecification.requestCores.toString());
        log.debug("Using limits.cores: " + resourceSpecification.limitCores.toString());
        log.debug("Using requests.ram: " + resourceSpecification.requestRAM.toString() + "Gi");
        log.debug("Using limits.ram: " + resourceSpecification.limitRAM.toString() + "Gi");

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

        final String ownerJobName = K8SUtil.getJobName(this.sessionID, SessionType.DESKTOP, posixPrincipal.username);
        final KubernetesJob ownerKubernetesJob = SessionDAO.getJob(ownerJobName);
        final String supplementalGroups = getSupplementalGroupsList();
        final String launchSoftwarePath = K8SUtil.getWorkingDirectory() + "/config/launch-desktop-app.yaml";
        SessionJobBuilder sessionJobBuilder = SessionJobBuilder.fromPath(Paths.get(launchSoftwarePath))
                .withGPUEnabled(this.gpuEnabled)
                .withQueue(QueueConfiguration.fromType(SessionAction.TYPE_DESKTOP_APP)) // Can be null.
                .withParameter(PostAction.SKAHA_SESSIONID, this.sessionID)
                .withParameter(PostAction.SKAHA_SESSIONEXPIRY, K8SUtil.getSessionExpiry())
                .withParameter(PostAction.SKAHA_SESSIONTYPE, SessionAction.TYPE_DESKTOP_APP)
                .withParameter(PostAction.SKAHA_HOSTNAME, K8SUtil.getSkahaHostName())
                .withParameter(PostAction.SKAHA_SESSIONS_HOSTNAME, K8SUtil.getSessionsHostName())
                .withParameter(PostAction.SKAHA_USERID, getUsername())
                .withParameter(PostAction.SKAHA_POSIXID, Integer.toString(this.posixPrincipal.getUidNumber()))
                .withParameter(PostAction.SOFTWARE_IMAGEID, image)
                .withParameter(PostAction.SOFTWARE_APPID, this.appID)
                .withParameter(PostAction.SOFTWARE_JOBNAME, jobName)
                .withParameter(PostAction.SOFTWARE_HOSTNAME, name.toLowerCase())
                .withParameter(PostAction.SOFTWARE_REQUESTS_CORES, resourceSpecification.requestCores.toString())
                .withParameter(PostAction.SOFTWARE_LIMITS_CORES, resourceSpecification.limitCores.toString())
                .withParameter(PostAction.SOFTWARE_REQUESTS_RAM, resourceSpecification.requestRAM + "Gi")
                .withParameter(PostAction.SOFTWARE_LIMITS_RAM, resourceSpecification.limitRAM + "Gi")
                .withParameter(PostAction.SOFTWARE_TARGETIP, targetIP + ":1")
                .withParameter(PostAction.SOFTWARE_CONTAINERNAME, containerName)
                .withParameter(PostAction.SOFTWARE_CONTAINERPARAM, param)
                .withParameter(PostAction.SKAHA_TLD, this.skahaTld)
                .withParameter(
                        PostAction.SKAHA_SUPPLEMENTALGROUPS,
                        StringUtil.hasText(supplementalGroups) ? supplementalGroups : "")
                .withParameter(PostAction.SKAHA_JOBUID, ownerKubernetesJob.getUID())
                .withParameter(PostAction.SKAHA_JOBNAME, ownerKubernetesJob.getName());

        String launchFile = super.stageFile(sessionJobBuilder.build());
        KubectlCommandBuilder.KubectlCommand launchCmd = KubectlCommandBuilder.command("create")
                .namespace(K8SUtil.getWorkloadNamespace())
                .option("-f", launchFile);

        String createResult = CommandExecutioner.execute(launchCmd.build());

        log.debug("Create result: " + createResult);

        // refresh the user's proxy cert
        injectProxyCertificate();
    }

    private void validateHeadlessMembership() {
        if (skahaHeadlessGroup == null) {
            log.warn("skaha.headlessgroup not defined in system properties");
        } else if (!headlessUser) {
            throw new AccessControlException("Not authorized to create a headless session");
        }
    }

    private String getSupplementalGroupsList() throws Exception {
        if (skahaCallbackFlow) {
            return callbackSupplementalGroups;
        }
        Set<List<Group>> groupCredentials = getCachedGroupsFromSubject();
        if (groupCredentials.size() == 1) {
            return buildGroupUriList(groupCredentials).stream()
                    .map(posixGroup -> Integer.toString(posixGroup.getGID()))
                    .collect(Collectors.joining(","));
        } else {
            return "";
        }
    }

    private List<PosixGroup> buildGroupUriList(Set<List<Group>> groupCredentials) throws Exception {
        return toGIDs(
                groupCredentials.iterator().next().stream().map(Group::getID).collect(Collectors.toList()));
    }

    List<PosixGroup> toGIDs(final List<GroupURI> groupURIS) throws Exception {
        return posixMapperConfiguration.getPosixMapperClient().getGID(groupURIS);
    }

    /**
     * Create the image, command, args, and env sections of the job launch yaml. Example:
     *
     * <p>image: "${software.imageid}" command: ["/skaha-system/start-desktop-software.sh"] args: [arg1, arg2] env: -
     * name: HOME value: "/cavern/home/${skaha.userid}" - name: SHELL value: "/bin/bash"
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

    /** DTO for resource specification parameters. */
    public static class ResourceSpecification {
        private static final ResourceContexts RESOURCE_CONTEXTS = new ResourceContexts();
        private final SyncInput syncInput;

        Integer requestCores;
        Integer limitCores;
        Integer requestRAM;
        Integer limitRAM;

        static ResourceSpecification fromSyncInput(final SyncInput input) {
            return new ResourceSpecification(input);
        }

        private ResourceSpecification(SyncInput syncInput) {
            this.syncInput = syncInput;

            this.requestCores = getCoresParam();
            this.limitCores = this.requestCores;
            if (this.requestCores == null) {
                this.requestCores = ResourceSpecification.RESOURCE_CONTEXTS.getDefaultRequestCores();
                this.limitCores = ResourceSpecification.RESOURCE_CONTEXTS.getDefaultLimitCores();
            }

            this.requestRAM = getRamParam();
            this.limitRAM = this.requestRAM;
            if (this.requestRAM == null) {
                this.requestRAM = ResourceSpecification.RESOURCE_CONTEXTS.getDefaultRequestRAM();
                this.limitRAM = ResourceSpecification.RESOURCE_CONTEXTS.getDefaultLimitRAM();
            }
        }

        private Integer getCoresParam() {
            Integer cores = null;
            String coresParam = syncInput.getParameter("cores");
            if (StringUtil.hasText(coresParam)) {
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
            if (StringUtil.hasText(ramParam)) {
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
    }
}
