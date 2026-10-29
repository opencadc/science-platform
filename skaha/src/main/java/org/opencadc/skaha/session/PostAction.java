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
import ca.nrc.cadc.rest.SyncInput;
import ca.nrc.cadc.util.StringUtil;
import ca.nrc.cadc.uws.server.RandomStringGenerator;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.log4j.Logger;
import org.opencadc.auth.PosixGroup;
import org.opencadc.permissions.WriteGrant;
import org.opencadc.skaha.K8SUtil;
import org.opencadc.skaha.SkahaAction;
import org.opencadc.skaha.SkahaKubernetesClient;
import org.opencadc.skaha.context.ResourceContexts;
import org.opencadc.skaha.metrics.PodResourceUsage;
import org.opencadc.skaha.repository.ImageRepositoryAuth;
import org.opencadc.skaha.session.userStorage.UserStorageConfiguration;
import org.opencadc.skaha.utils.CommonUtils;
import org.opencadc.skaha.utils.PosixCache;
import org.opencadc.skaha.utils.PosixGroupCache;

/**
 * POST submission for creating a new session or app, or updating (renewing) an existing session. Configuration is
 * pulled from the environment.
 *
 * @author majorb
 */
public class PostAction extends SessionAction {
    // variables replaced in kubernetes YAML config files for
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
    public static final String SKAHA_TOP_LEVEL_DIR = "SKAHA_TOP_LEVEL_DIR";
    public static final String SKAHA_USER_HOME_DIR = "SKAHA_USER_HOME_DIR";
    public static final String SKAHA_PROJECTS_DIR = "SKAHA_PROJECTS_DIR";
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
    public static final String SOFTWARE_THREAD_COUNT = "software.thread.count";

    // k8s rejects label size > 63. Since k8s appends a maximum of six characters
    // to a job name to form a pod name, we limit the job name length to 57 characters.
    private static final int MAX_JOB_NAME_LENGTH = 57;
    private static final String DESKTOP_SESSION_APP_TOKEN = "software.desktop.app.token";

    private static final Logger log = Logger.getLogger(PostAction.class);
    private final UserStorageClient userStorageClient;

    /** Default constructor. */
    public PostAction() {
        this(new UserStorageClient());
    }

    /**
     * Constructor used for testing.
     *
     * @param userStorageClient A UserStorageClient instance.
     */
    PostAction(UserStorageClient userStorageClient) {
        super();
        this.userStorageClient = userStorageClient;
    }

    @Override
    public void doAction() throws Exception {

        initRequest();

        String image = syncInput.getParameter("image");
        if (image == null) {
            if (requestType.equals(REQUEST_TYPE_APP)
                    || (requestType.equals(REQUEST_TYPE_SESSION) && sessionID == null)) {
                throw new IllegalArgumentException("Missing parameter 'image'");
            }
        }

        if (requestType.equals(REQUEST_TYPE_SESSION)) {
            if (sessionID == null) {
                final String type = SessionAction.getRequestedSessionType(this.syncInput);
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
                        final ResourceContexts resourceContexts = new ResourceContexts();
                        if (!resourceContexts.getAvailableGPUs().contains(gpus)) {
                            throw new IllegalArgumentException("Unavailable option for 'gpus': " + gpusParam);
                        }
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Invalid value for 'gpus': " + gpusParam);
                    }
                }

                this.userStorageClient.ensureUserBase(getUsername());

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
                        renew();
                    } else {
                        throw new UnsupportedOperationException("unrecognized action");
                    }
                } else {
                    throw new UnsupportedOperationException("Cannot modify an existing session");
                }
            }
        } else if (requestType.equals(REQUEST_TYPE_APP)) {
            if (appID == null) {
                final ResourceSpecification resourceSpecification =
                        ResourceSpecification.fromSyncInput(this.syncInput, SessionType.DESKTOP_APP.applicationName);

                attachDesktopApp(image, resourceSpecification);
                syncOutput.setHeader("Content-Type", "text/plain");
                syncOutput.getOutputStream().write((appID + "\n").getBytes());
            } else {
                throw new UnsupportedOperationException("Cannot modify an existing app.");
            }
        }
    }

    private void renew() throws Exception {
        final String configuredExpiry = K8SUtil.getSessionExpiry();
        if (!StringUtil.hasLength(configuredExpiry)) {
            throw new IllegalStateException("missing configuration item " + SKAHA_SESSIONEXPIRY);
        }
        SessionDAO.renew(
                posixPrincipal.username,
                sessionID,
                Duration.ofSeconds(Long.parseLong(configuredExpiry)),
                Instant.now());

        this.userStorageClient.injectProxyCertificate(getUsername());
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
                return SessionType.fromApplicationStringType(type);
            }
        }
        throw new IllegalArgumentException("image not in a trusted repository");
    }

    private void checkExistingSessions(final SessionType type) throws Exception {
        // Live, not cached: concurrent launches by the same user must see each other. Only status counts, so no
        // pod usage is loaded.
        checkExistingSessions(
                type,
                SessionDAO.liveJobs(this.posixPrincipal.username, null, true).stream()
                        .map(job -> SessionBuilder.fromJob(job, PodResourceUsage.empty()))
                        .collect(Collectors.toList()));
    }

    /**
     * Check the count of existing non-headless and non desktop-app sessions for the user.
     *
     * @param type The type of desired session to ensure not headless.
     * @param sessions The list of existing Session objects for the user.
     */
    void checkExistingSessions(final SessionType type, final List<Session> sessions) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(sessions, "sessions must not be null");

        if (!type.isHeadless()) {
            final long count = sessions.stream()
                    .filter(session -> !TYPE_DESKTOP_APP.equals(session.getType()))
                    .filter(session -> session.getStatus().equalsIgnoreCase(Session.STATUS_RUNNING)
                            || session.getStatus().equalsIgnoreCase(Session.STATUS_PENDING))
                    .count();

            log.debug("active interactive sessions: " + count);
            if (count >= maxUserSessions) {
                throw new IllegalArgumentException("User " + getUsername() + " has reached the maximum of "
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

        final String jobName = K8SUtil.getJobName(sessionID, type, posixPrincipal.username);
        final UserStorageConfiguration userStorageConfiguration = UserStorageConfiguration.fromEnv();
        final String owner = getUsername();

        if (type.isDesktop()) {
            this.userStorageClient.ensureDesktopUserHomePreparation(owner);
        }

        SessionJobBuilder sessionJobBuilder = SessionJobBuilder.fromPath(type.getJobConfigPath())
                .withGPUEnabled(this.gpuEnabled)
                .withGPUCount(gpus)
                .withQueue(QueueConfiguration.fromType(type.name())) // Can be null.
                .withParameter(PostAction.SKAHA_SESSIONID, this.sessionID)
                .withParameter(PostAction.SKAHA_SESSIONNAME, name.toLowerCase())
                .withParameter(PostAction.SKAHA_SESSIONEXPIRY, K8SUtil.getSessionExpiry())
                .withParameter(PostAction.SKAHA_JOBNAME, jobName)
                .withParameter(PostAction.SKAHA_HOSTNAME, K8SUtil.getSkahaHostName())
                .withParameter(PostAction.SKAHA_SESSIONS_HOSTNAME, K8SUtil.getSessionsHostName())
                .withParameter(PostAction.SKAHA_USERID, owner)
                .withParameter(PostAction.SKAHA_POSIXID, Integer.toString(this.posixPrincipal.getUidNumber()))
                .withParameter(PostAction.SKAHA_SESSIONTYPE, type.name().toLowerCase())
                .withImage(image)
                .withParameter(PostAction.SOFTWARE_HOSTNAME, name.toLowerCase())
                .withParameter(
                        PostAction.SKAHA_USER_HOME_DIR,
                        userStorageConfiguration.homeBaseDirectory.toString() + "/" + owner)
                .withParameter(PostAction.SKAHA_TOP_LEVEL_DIR, userStorageConfiguration.topLevelDirectory.toString())
                .withParameter(PostAction.SKAHA_PROJECTS_DIR, userStorageConfiguration.projectsBaseDirectory.toString())
                .withParameter(PostAction.SOFTWARE_REQUESTS_CORES, resourceSpecification.requestCores.toString())
                .withParameter(PostAction.SOFTWARE_REQUESTS_RAM, resourceSpecification.requestRAMGiB.toString() + "Gi")
                .withParameter(PostAction.SOFTWARE_LIMITS_CORES, resourceSpecification.limitCores.toString())
                .withParameter(PostAction.SOFTWARE_LIMITS_RAM, resourceSpecification.limitRAMGiB + "Gi")
                .withParameter(
                        PostAction.SOFTWARE_THREAD_COUNT,
                        Integer.toString(Math.max(1, resourceSpecification.requestCores.intValue())))
                .withParameter(
                        PostAction.SKAHA_SUPPLEMENTALGROUPS,
                        StringUtil.hasText(supplementalGroups) ? supplementalGroups : "");

        if (type.isHeadless()) {
            sessionJobBuilder = sessionJobBuilder
                    .withHeadlessCommand(SessionJobBuilder.HeadlessCommand.fromRequest(cmd, args, envs))
                    .withPriorityClass(getHeadlessPriorityClass());
        } else if (type == SessionType.DESKTOP) {
            sessionJobBuilder = sessionJobBuilder.withParameter(PostAction.DESKTOP_SESSION_APP_TOKEN, generateToken());
        } else if (type == SessionType.CARTA) {
            final String connectURLPrefix = SessionURLBuilder.cartaSession(
                            K8SUtil.getSessionsHostName(), this.sessionID)
                    .build();
            final String connectURLPath = URI.create(connectURLPrefix).getPath();
            sessionJobBuilder = sessionJobBuilder.withParameter(
                    PostAction.SKAHA_SESSIONURLPATH, connectURLPath.replaceAll("/$", ""));
        }

        // In the absence of the existence of a public image, assume Private.  The validateImage() step above will have
        // caught a non-existent Image already.
        if (getPublicImage(image) == null) {
            final ImageRepositoryAuth userRegistryAuth = getRegistryAuth(getRegistryHost(image));
            sessionJobBuilder = sessionJobBuilder.withImageSecret(createRegistryImageSecret(userRegistryAuth));
        }

        final SessionJobBuilder.LaunchArtifacts launch = sessionJobBuilder.buildLaunch();

        // insert the user's proxy cert in the home dir.  Do this first, so they're available to initContainer
        // configurations.
        this.userStorageClient.injectProxyCertificate(owner);

        // inject the entries from the POSIX Mapper
        injectPOSIXDetails();

        final List<String> ownedResourceYamls = new ArrayList<>();
        if (type.supportsService()) {
            byte[] serviceBytes = Files.readAllBytes(type.getServiceConfigPath());
            String serviceString = new String(serviceBytes, StandardCharsets.UTF_8);
            serviceString = SessionJobBuilder.setConfigValue(serviceString, PostAction.SKAHA_SESSIONID, this.sessionID);
            ownedResourceYamls.add(SessionJobBuilder.labelService(serviceString, launch.labels()));
        }

        // Ingress construction is still done using plain String interpolation for now.  When the Kubernetes Gateway
        // API is in place, we can swap this out with a proper Java client API.
        if (type.supportsIngress()) {
            byte[] ingressBytes = Files.readAllBytes(type.getIngressConfigPath());
            String ingressString = new String(ingressBytes, StandardCharsets.UTF_8);
            ingressString = SessionJobBuilder.setConfigValue(ingressString, PostAction.SKAHA_SESSIONID, this.sessionID);
            ingressString = SessionJobBuilder.setConfigValue(
                    ingressString, PostAction.SKAHA_SESSIONS_HOSTNAME, K8SUtil.getSessionsHostName());
            ownedResourceYamls.add(SessionJobBuilder.labelIngress(ingressString, launch.labels()));
        }

        SessionDAO.created(new SessionLauncher(SkahaKubernetesClient.get()).launch(launch.job(), ownedResourceYamls));
    }

    protected void injectPOSIXDetails() throws Exception {
        final UserStorageConfiguration userStorageConfiguration = UserStorageConfiguration.fromEnv();
        final PosixCache posixCache = new PosixCache(
                this.skahaPosixCacheURL,
                userStorageConfiguration.homeBaseDirectory.toString(),
                this.posixMapperConfiguration.getPosixMapperClient());
        posixCache.writePOSIXEntries(this.posixPrincipal, this.skahaCallbackFlow ? Set.of() : getUserPosixGroups());
    }

    private String generateToken() throws Exception {
        return SkahaAction.getSkahaCallbackTokenTool().generateToken(WriteGrant.class, this.sessionID);
    }

    /**
     * Write the user's registry Secret and return its name.
     *
     * @param registryAuth The credentials to use to authenticate to the Image Registry.
     * @return String secret name, never null.
     */
    private String createRegistryImageSecret(final ImageRepositoryAuth registryAuth) throws Exception {
        final String username = this.posixPrincipal.username;
        log.debug("Writing image pull secret for " + username);
        RegistryPullSecret.apply(username, registryAuth);
        return RegistryPullSecret.name(username);
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
        final String targetIP = SessionPods.desktopIP(posixPrincipal.username, sessionID);

        log.debug("attaching desktop app: " + image + " to " + targetIP);

        String name = getImageName(image);
        log.debug("name: " + name);

        // incoming params ignored for the time being.  set to the 'name' so
        // that it becomes the xterm title
        String param = name;
        log.debug("Using parameter: " + param);
        log.debug("Using requests.cores: " + resourceSpecification.requestCores.toString());
        log.debug("Using limits.cores: " + resourceSpecification.limitCores.toString());
        log.debug("Using requests.ram: " + resourceSpecification.requestRAMGiB.toString() + "Gi");
        log.debug("Using limits.ram: " + resourceSpecification.limitRAMGiB.toString() + "Gi");

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

        final Job ownerJob = SessionDAO.desktopJob(this.sessionID, posixPrincipal.username);
        final UserStorageConfiguration userStorageConfiguration = UserStorageConfiguration.fromEnv();
        final String supplementalGroups = getSupplementalGroupsList();
        final String owner = getUsername();
        final String launchSoftwarePath = K8SUtil.getWorkingDirectory() + "/config/launch-desktop-app.yaml";
        SessionJobBuilder sessionJobBuilder = SessionJobBuilder.fromPath(Paths.get(launchSoftwarePath))
                .withGPUEnabled(this.gpuEnabled)
                .withQueue(QueueConfiguration.fromType(SessionAction.TYPE_DESKTOP_APP)) // Can be null.
                .withImage(image)
                .withParameter(PostAction.SKAHA_SESSIONID, this.sessionID)
                .withParameter(PostAction.SKAHA_SESSIONNAME, name.toLowerCase())
                .withParameter(PostAction.SKAHA_SESSIONEXPIRY, K8SUtil.getSessionExpiry())
                .withParameter(PostAction.SKAHA_SESSIONTYPE, SessionAction.TYPE_DESKTOP_APP)
                .withParameter(PostAction.SKAHA_HOSTNAME, K8SUtil.getSkahaHostName())
                .withParameter(PostAction.SKAHA_SESSIONS_HOSTNAME, K8SUtil.getSessionsHostName())
                .withParameter(PostAction.SKAHA_USERID, owner)
                .withParameter(PostAction.SKAHA_POSIXID, Integer.toString(this.posixPrincipal.getUidNumber()))
                .withParameter(PostAction.SOFTWARE_APPID, this.appID)
                .withParameter(PostAction.SOFTWARE_JOBNAME, jobName)
                .withParameter(PostAction.SOFTWARE_HOSTNAME, name.toLowerCase())
                .withParameter(PostAction.SOFTWARE_REQUESTS_CORES, resourceSpecification.requestCores.toString())
                .withParameter(PostAction.SOFTWARE_LIMITS_CORES, resourceSpecification.limitCores.toString())
                .withParameter(PostAction.SOFTWARE_REQUESTS_RAM, resourceSpecification.requestRAMGiB + "Gi")
                .withParameter(PostAction.SOFTWARE_LIMITS_RAM, resourceSpecification.limitRAMGiB + "Gi")
                .withParameter(
                        PostAction.SOFTWARE_THREAD_COUNT,
                        Integer.toString(Math.max(1, resourceSpecification.requestCores.intValue())))
                .withParameter(PostAction.SOFTWARE_TARGETIP, targetIP + ":1")
                .withParameter(PostAction.SOFTWARE_CONTAINERNAME, containerName)
                .withParameter(PostAction.SOFTWARE_CONTAINERPARAM, param)
                .withParameter(
                        PostAction.SKAHA_USER_HOME_DIR,
                        userStorageConfiguration.homeBaseDirectory.toString() + "/" + owner)
                .withParameter(PostAction.SKAHA_TOP_LEVEL_DIR, userStorageConfiguration.topLevelDirectory.toString())
                .withParameter(PostAction.SKAHA_PROJECTS_DIR, userStorageConfiguration.projectsBaseDirectory.toString())
                .withParameter(
                        PostAction.SKAHA_SUPPLEMENTALGROUPS,
                        StringUtil.hasText(supplementalGroups) ? supplementalGroups : "")
                .withParameter(PostAction.SKAHA_JOBUID, ownerJob.getMetadata().getUid())
                .withParameter(PostAction.SKAHA_JOBNAME, ownerJob.getMetadata().getName());

        SessionDAO.created(new SessionLauncher(SkahaKubernetesClient.get())
                .launch(sessionJobBuilder.buildLaunch().job(), List.of()));

        // refresh the user's proxy cert
        this.userStorageClient.injectProxyCertificate(owner);
    }

    @Override
    protected String getRequestMethod() {
        return "POST";
    }

    private String getSupplementalGroupsList() throws Exception {
        if (skahaCallbackFlow) {
            return callbackSupplementalGroups;
        }
        return getUserPosixGroups().stream()
                .map(posixGroup -> Integer.toString(posixGroup.getGID()))
                .collect(Collectors.joining(","));
    }

    protected Set<PosixGroup> getUserPosixGroups() throws Exception {
        final List<Group> groupCredentials =
                CommonUtils.getCachedGroupsFromSubject(AuthenticationUtil.getCurrentSubject());
        return new PosixGroupCache(this.posixMapperConfiguration.getPosixMapperClient())
                .toGIDs(groupCredentials.stream().map(Group::getID).collect(Collectors.toList()));
    }

    /** The PriorityClass for this user's headless sessions, or null for the default. */
    private String getHeadlessPriorityClass() {
        if (skahaPriorityHeadlessGroup == null) {
            return null;
        }
        if (skahaHeadlessPriorityClass == null) {
            log.warn("headlessPriorityGroup set but headlessPriorityClass not set");
            return null;
        }
        return priorityHeadlessUser ? skahaHeadlessPriorityClass : null;
    }

    /** DTO for resource specification parameters. */
    public static class ResourceSpecification {
        private static final ResourceContexts RESOURCE_CONTEXTS = new ResourceContexts();
        private final SyncInput syncInput;

        Double requestCores;
        Double limitCores;
        Double requestRAMGiB;
        Double limitRAMGiB;

        /**
         * Create a ResourceSpecification from the SyncInput, applying defaults from the
         * FlexResourceRequestConfiguration for the session type as needed.
         *
         * @param input The SyncInput containing the resource parameters and session type information to determine which
         *     defaults to apply for any missing resource parameters. The Session Type is drawn from the request
         *     parameters.
         * @return ResourceSpecification with all resource parameters populated, either from the SyncInput or from
         *     defaults based on the session type.
         */
        static ResourceSpecification fromSyncInput(final SyncInput input) {
            return new ResourceSpecification(
                    input,
                    FlexResourceRequestConfiguration.fromSessionType(SessionAction.getRequestedSessionType(input)));
        }

        /**
         * Create a ResourceSpecification from the SyncInput and session type, applying defaults from the
         * FlexResourceRequestConfiguration for the session type as needed.
         *
         * @param input The SyncInput containing the resource parameters.
         * @param sessionType The session type to determine which defaults to apply for any missing resource parameters.
         * @return ResourceSpecification with all resource parameters populated, either from the SyncInput or from
         *     defaults based on the session type.
         */
        static ResourceSpecification fromSyncInput(final SyncInput input, final String sessionType) {
            return new ResourceSpecification(input, FlexResourceRequestConfiguration.fromSessionType(sessionType));
        }

        private ResourceSpecification(
                SyncInput syncInput, FlexResourceRequestConfiguration flexResourceRequestConfiguration) {
            this.syncInput = syncInput;

            this.requestCores = getCoresParam();
            this.limitCores = this.requestCores;
            if (this.requestCores == null) {
                final int defaultRequestCores = ResourceSpecification.RESOURCE_CONTEXTS.getDefaultRequestCores();

                this.requestCores = flexResourceRequestConfiguration.getCPU(defaultRequestCores);
                this.limitCores = (double) ResourceSpecification.RESOURCE_CONTEXTS.getDefaultLimitCores();
            }

            this.requestRAMGiB = getRamParam();
            this.limitRAMGiB = this.requestRAMGiB;
            if (this.requestRAMGiB == null) {
                final int defaultRequestRAM = ResourceSpecification.RESOURCE_CONTEXTS.getDefaultRequestRAM();
                this.requestRAMGiB = flexResourceRequestConfiguration.getMemory(defaultRequestRAM);
                this.limitRAMGiB = (double) ResourceSpecification.RESOURCE_CONTEXTS.getDefaultLimitRAM();
            }
        }

        private Double getCoresParam() {
            String coresParam = syncInput.getParameter("cores");
            if (StringUtil.hasText(coresParam)) {
                try {
                    final Double cores = Double.valueOf(coresParam);
                    final ResourceContexts rc = new ResourceContexts();
                    // Convert to integer cores for validation against available core options
                    if (!rc.isCoreCountAvailable(cores.intValue())) {
                        throw new IllegalArgumentException("Unavailable option for 'cores': " + coresParam);
                    }
                    return cores;
                } catch (Exception e) {
                    throw new IllegalArgumentException("Invalid value for 'cores': " + coresParam);
                }
            }

            return null;
        }

        private Double getRamParam() {
            String ramParam = syncInput.getParameter("ram");
            if (StringUtil.hasText(ramParam)) {
                try {
                    final Double ram = Double.valueOf(ramParam);
                    ResourceContexts rc = new ResourceContexts();
                    // Convert to integer GiB for validation against available RAM options
                    if (!rc.isRAMAmountAvailable(ram.intValue())) {
                        throw new IllegalArgumentException("Unavailable option for 'ram': " + ramParam);
                    }
                    return ram;
                } catch (Exception e) {
                    throw new IllegalArgumentException("Invalid value for 'ram': " + ramParam);
                }
            }

            return null;
        }
    }
}
