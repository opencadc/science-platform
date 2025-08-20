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
 *  the GNU Affero General Public        la "GNU Affero General Public
 *  License as published by the          License" telle que publiée
 *  Free Software Foundation,            par la Free Software Foundation
 *  either version 3 of the              : soit la version 3 de cette
 *  License, or (at your option)         licence, soit (à votre gré)
 *  any later version.                   toute version ultérieure.
 *
 *  OpenCADC is distributed in the       OpenCADC est distribué
 *  hope that it will be useful,         dans l'espoir qu'il vous
 *  but WITHOUT ANY WARRANTY;            sera utile, mais SANS AUCUNE
 *  without even the implied             GARANTIE : sans même la garantie
 *  warranty of MERCHANTABILITY          implicite de COMMERCIALISABILITÉ
 *  or FITNESS FOR A PARTICULAR          ni d'ADÉQUATION À UN OBJECTIF
 *  PURPOSE.  See the GNU Affero         PARTICULIER. Consultez la Licence
 *  General Public License for           Générale Publique GNU Affero
 *  more details.                        pour plus de détails.
 *
 *  You should have received             Vous devriez avoir reçu une
 *  a copy of the GNU Affero             copie de la Licence Générale
 *  General Public License along         Publique GNU Affero avec
 *  with OpenCADC.  If not, see          OpenCADC ; si ce n'est
 *  <http://www.gnu.org/licenses/>.      pas le cas, consultez :
 *                                       <http://www.gnu.org/licenses/>.
 *
 ************************************************************************
 */

package org.opencadc.skaha;

import static java.util.stream.Collectors.toList;
import static org.opencadc.skaha.utils.CommonUtils.isNotEmpty;

import ca.nrc.cadc.ac.Group;
import ca.nrc.cadc.auth.*;
import ca.nrc.cadc.net.ResourceNotFoundException;
import ca.nrc.cadc.reg.Standards;
import ca.nrc.cadc.rest.InlineContentHandler;
import ca.nrc.cadc.rest.RestAction;
import ca.nrc.cadc.util.InvalidConfigException;
import ca.nrc.cadc.util.RsaSignatureGenerator;
import ca.nrc.cadc.util.StringUtil;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessControlException;
import java.security.KeyPair;
import java.util.*;
import java.util.stream.Collectors;
import javax.security.auth.Subject;
import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.opencadc.auth.PosixMapperClient;
import org.opencadc.gms.GroupURI;
import org.opencadc.gms.IvoaGroupClient;
import org.opencadc.permissions.TokenTool;
import org.opencadc.permissions.WriteGrant;
import org.opencadc.skaha.image.Image;
import org.opencadc.skaha.repository.ImageRepositoryAuth;
import org.opencadc.skaha.session.Session;
import org.opencadc.skaha.session.SessionDAO;
import org.opencadc.skaha.utils.CommandExecutioner;
import org.opencadc.skaha.utils.CommonUtils;
import org.opencadc.skaha.utils.KubectlCommandBuilder;
import org.opencadc.skaha.utils.RedisCache;

public abstract class SkahaAction extends RestAction {

    public static final String SESSION_TYPE_CARTA = "carta";
    public static final String SESSION_TYPE_NOTEBOOK = "notebook";
    public static final String SESSION_TYPE_DESKTOP = "desktop";
    public static final String SESSION_TYPE_CONTRIB = "contributed";
    public static final String SESSION_TYPE_HEADLESS = "headless";
    public static final String SESSION_TYPE_FIREFLY = "firefly";
    public static final String TYPE_DESKTOP_APP = "desktop-app";
    public static final String X_AUTH_TOKEN_SKAHA = "x-auth-token-skaha";
    private static final String X_REGISTRY_AUTH_HEADER = "x-skaha-registry-auth";
    private static final Logger log = Logger.getLogger(SkahaAction.class);
    public static List<String> SESSION_TYPES = Arrays.asList(
            SESSION_TYPE_CARTA,
            SESSION_TYPE_NOTEBOOK,
            SESSION_TYPE_DESKTOP,
            SESSION_TYPE_CONTRIB,
            SESSION_TYPE_HEADLESS,
            SESSION_TYPE_FIREFLY,
            TYPE_DESKTOP_APP);
    protected final PosixMapperConfiguration posixMapperConfiguration;
    public List<String> harborHosts;
    protected PosixPrincipal posixPrincipal;
    protected boolean headlessUser = false;
    protected boolean priorityHeadlessUser = false;
    protected String homedir;
    protected String scratchdir;
    protected String skahaTld;
    protected boolean gpuEnabled;
    protected String skahaUsersGroup;
    protected String skahaHeadlessGroup;
    protected String skahaPriorityHeadlessGroup;
    protected String skahaAdminsGroup;
    protected String skahaHeadlessPriortyClass;
    protected int maxUserSessions;
    protected String skahaPosixCacheURL;
    protected boolean skahaCallbackFlow = false;
    protected String callbackSupplementalGroups = null;

    public SkahaAction() {
        homedir = K8SUtil.getHomeDir();
        skahaTld = K8SUtil.getSkahaTld();
        gpuEnabled = K8SUtil.isGpuEnabled();
        scratchdir = K8SUtil.getScratchDir();
        harborHosts = K8SUtil.getHarborHosts();
        skahaUsersGroup = K8SUtil.getSkahaUsersGroup();
        skahaHeadlessGroup = K8SUtil.getSkahaHeadlessGroup();
        skahaPriorityHeadlessGroup = K8SUtil.getSkahaHeadlessPriorityGroup();
        skahaAdminsGroup = K8SUtil.getSkahaAdminsGroup();
        skahaHeadlessPriortyClass = K8SUtil.getSkahaHeadlessPriorityClass();
        maxUserSessions = K8SUtil.getMaxUserSessions();

        // Check the catalina.properties for this setting.
        skahaPosixCacheURL = K8SUtil.getPosixCacheUrl(SkahaAction.class.getPackageName());

        final String configuredPosixMapperResourceID = K8SUtil.getPosixMapperResourceId();

        log.debug("skaha.hostname=" + K8SUtil.getSkahaHostName());
        log.debug("skaha.sessions.hostname=" + K8SUtil.getSessionsHostName());
        log.debug("skaha.homedir=" + homedir);
        log.debug("SKAHA_TLD=" + skahaTld);
        log.debug("skaha.scratchdir=" + scratchdir);
        log.debug("skaha.harborHosts=" + harborHosts.toString());
        log.debug("skaha.usersgroup=" + skahaUsersGroup);
        log.debug("skaha.headlessgroup=" + skahaHeadlessGroup);
        log.debug("skaha.priorityheadlessgroup=" + skahaPriorityHeadlessGroup);
        log.debug("skaha.adminsgroup=" + skahaAdminsGroup);
        log.debug("skaha.skahaheadlesspriorityclass=" + skahaHeadlessPriortyClass);
        log.debug("skaha.maxusersessions=" + maxUserSessions);
        log.debug("skaha.posixmapper.resourceid" + "=" + configuredPosixMapperResourceID);

        try {
            if (StringUtil.hasText(configuredPosixMapperResourceID)) {
                final URI configuredPosixMapperResourceURI = URI.create(configuredPosixMapperResourceID);
                posixMapperConfiguration = new PosixMapperConfiguration(configuredPosixMapperResourceURI);
            } else {
                posixMapperConfiguration = null;
            }
        } catch (IOException ioException) {
            throw new IllegalArgumentException(ioException.getMessage(), ioException);
        }
    }

    protected static TokenTool getTokenTool() throws Exception {
        final EncodedKeyPair encodedKeyPair = getPreAuthorizedTokenSecret();
        return new TokenTool(encodedKeyPair.encodedPublicKey, encodedKeyPair.encodedPrivateKey);
    }

    private static EncodedKeyPair getPreAuthorizedTokenSecret() throws Exception {
        // Check the current secret
        final JSONObject secretData = CommandExecutioner.getSecretData(
                K8SUtil.getPreAuthorizedTokenSecretName(), K8SUtil.getWorkloadNamespace());
        final String publicKeyPropertyName = "public";
        final String privateKeyPropertyName = "private";

        if (secretData.isEmpty()) {
            final KeyPair keyPair = RsaSignatureGenerator.getKeyPair(2048);
            final byte[] encodedPublicKey = keyPair.getPublic().getEncoded();
            final byte[] encodedPrivateKey = keyPair.getPrivate().getEncoded();

            String[] createCmd = KubectlCommandBuilder.command("create")
                    .argument("secret")
                    .argument("generic")
                    .argument(K8SUtil.getPreAuthorizedTokenSecretName())
                    .namespace(K8SUtil.getWorkloadNamespace())
                    .argument(String.format(
                            "--from-literal=%s=%s", publicKeyPropertyName, CommonUtils.encodeBase64(encodedPublicKey)))
                    .argument(String.format(
                            "--from-literal=%s=%s",
                            privateKeyPropertyName, CommonUtils.encodeBase64(encodedPrivateKey)))
                    .build();

            final String createResult = CommandExecutioner.execute(createCmd);
            log.debug("create secret result: " + createResult);

            return new EncodedKeyPair(encodedPublicKey, encodedPrivateKey);
        } else {
            final Base64.Decoder base64Decoder = Base64.getDecoder();
            // Decode twice since Kubernetes does a separate Base64 encoding.
            return new EncodedKeyPair(
                    base64Decoder.decode(base64Decoder.decode(secretData.getString(publicKeyPropertyName))),
                    base64Decoder.decode(base64Decoder.decode(secretData.getString(privateKeyPropertyName))));
        }
    }

    @Override
    protected InlineContentHandler getInlineContentHandler() {
        return null;
    }

    protected void initRequest() throws Exception {
        if (skahaUsersGroup == null) {
            throw new IllegalStateException("skaha.usersgroup not defined in system properties");
        }

        URI skahaUsersUri = URI.create(skahaUsersGroup);
        final Subject currentSubject = AuthenticationUtil.getCurrentSubject();
        log.debug("Subject: " + currentSubject);
        if (isSkahaCallBackFlow(currentSubject)) {
            initiateSkahaCallbackFlow(currentSubject, skahaUsersUri);
        } else {
            initiateGeneralFlow(currentSubject, skahaUsersUri);
        }
    }

    protected ImageRepositoryAuth getRegistryAuth(final String registryHost) {
        final String registryAuthValue = this.syncInput.getHeader(SkahaAction.X_REGISTRY_AUTH_HEADER);
        if (!StringUtil.hasText(registryAuthValue)) {
            throw new IllegalArgumentException("No authentication provided for unknown or private image.  Use "
                    + SkahaAction.X_REGISTRY_AUTH_HEADER + " request header with base64Encode(username:secret).");
        }
        return ImageRepositoryAuth.fromEncoded(registryAuthValue, registryHost);
    }

    private boolean isSkahaCallBackFlow(Subject currentSubject) {
        AuthMethod authMethod = AuthenticationUtil.getAuthMethodFromCredentials(currentSubject);
        log.debug("authMethod is " + authMethod);
        log.debug("x-auth-token-skaha is " + syncInput.getHeader(X_AUTH_TOKEN_SKAHA));
        return authMethod == AuthMethod.ANON && isNotEmpty(syncInput.getHeader(X_AUTH_TOKEN_SKAHA));
    }

    private void initiateSkahaCallbackFlow(Subject currentSubject, URI skahaUsersUri) {
        skahaCallbackFlow = true;
        final String xAuthTokenSkaha = syncInput.getHeader(X_AUTH_TOKEN_SKAHA);
        log.debug("x-auth-token-skaha header is " + xAuthTokenSkaha);
        try {
            final String callbackSessionId =
                    SkahaAction.getTokenTool().validateToken(xAuthTokenSkaha, skahaUsersUri, WriteGrant.class);

            final Session session = SessionDAO.getSession(null, callbackSessionId);
            this.posixPrincipal = session.getPosixPrincipal();
            currentSubject.getPrincipals().add(posixPrincipal);

            this.callbackSupplementalGroups = Arrays.stream(session.getSupplementalGroups())
                    .map(i -> Integer.toString(i))
                    .collect(Collectors.joining(","));
        } catch (Exception ex) {
            log.error("Unable to retrieve information for for callback flow", ex);
            if (ex instanceof IllegalStateException) {
                throw new RuntimeException(ex.getMessage());
            } else {
                throw new RuntimeException("Unable to retrieve information for callback flow");
            }
        }
    }

    private void initiateGeneralFlow(Subject currentSubject, URI skahaUsersUri)
            throws IOException, InterruptedException, ResourceNotFoundException {
        if (currentSubject == null || currentSubject.getPrincipals().isEmpty()) {
            throw new NotAuthenticatedException("Unauthorized");
        }
        Set<PosixPrincipal> posixPrincipals = currentSubject.getPrincipals(PosixPrincipal.class);
        if (posixPrincipals.isEmpty()) {
            throw new AccessControlException("No POSIX Principal");
        }
        posixPrincipal = posixPrincipals.iterator().next();

        // If the PosixPrincipal's username is not populated, then do that here with an attempt at the HTTPPrincipal.
        if (posixPrincipal.username == null) {
            final Set<HttpPrincipal> httpPrincipals = currentSubject.getPrincipals(HttpPrincipal.class);
            if (!httpPrincipals.isEmpty()) {
                posixPrincipal.username = httpPrincipals.iterator().next().getName();
            }
        }

        // The username is necessary.
        if (posixPrincipal.username == null) {
            throw new AccessControlException("POSIX Principal is incomplete (no username).");
        }

        log.debug("userID: " + posixPrincipal + " (" + posixPrincipal.username + ")");

        // ensure user is a part of the skaha group
        final URI gmsSearchURI = CommonUtils.firstLocalServiceURI(Standards.GMS_SEARCH_10);
        if (gmsSearchURI == null) {
            throw new InvalidConfigException("GMS Search service not configured in the registry.  Ensure that the "
                    + Standards.GMS_SEARCH_10 + " standard is registered in the local cadc-registry.properties.");
        }

        IvoaGroupClient ivoaGroupClient = new IvoaGroupClient();
        Set<GroupURI> skahaUsersGroupUriSet = ivoaGroupClient.getMemberships(gmsSearchURI);

        final GroupURI skahaHeadlessGroupURI =
                StringUtil.hasText(this.skahaHeadlessGroup) ? new GroupURI(URI.create(this.skahaHeadlessGroup)) : null;

        if (skahaHeadlessGroupURI != null && skahaUsersGroupUriSet.contains(skahaHeadlessGroupURI)) {
            headlessUser = true;
        }

        final GroupURI skahaPriorityHeadlessGroupURI = StringUtil.hasText(this.skahaPriorityHeadlessGroup)
                ? new GroupURI(URI.create(this.skahaPriorityHeadlessGroup))
                : null;

        if (skahaPriorityHeadlessGroupURI != null && skahaUsersGroupUriSet.contains(skahaPriorityHeadlessGroupURI)) {
            priorityHeadlessUser = true;
        }

        final GroupURI skahaUsersGroupUri = new GroupURI(skahaUsersUri);
        if (!skahaUsersGroupUriSet.contains(skahaUsersGroupUri)) {
            log.debug("user is not a member of skaha user group ");
            throw new AccessControlException("Not authorized to use the skaha system");
        }

        log.debug("user is a member of skaha user group ");

        List<Group> groups = isNotEmpty(skahaUsersGroupUriSet)
                ? skahaUsersGroupUriSet.stream().map(Group::new).collect(toList())
                : Collections.emptyList();

        // adding all groups to the Subject
        currentSubject.getPublicCredentials().add(groups);
    }

    protected String getUsername() {
        return posixPrincipal.username;
    }

    protected Path getUserHomeDirectory() {
        return Paths.get(String.format("%s/%s", this.homedir, getUsername()));
    }

    protected int getUID() {
        return posixPrincipal.getUidNumber();
    }

    public Image getPublicImage(String imageID) {
        log.debug("get image: " + imageID);
        List<Image> images = RedisCache.getAll(K8SUtil.getRedisHost(), K8SUtil.getRedisPort(), "public", Image.class);
        if (images == null) {
            log.debug("no images in cache");
            return null;
        }
        return images.parallelStream()
                .filter(image -> image.getId().equals(imageID))
                .findFirst()
                .orElse(null);
    }

    /**
     * It's important to use the correct constructor for the PosixMapperClient, this class will wrap the logic based on
     * how the Resource ID of the POSIX mapper was set (URI or URL).
     */
    protected static class PosixMapperConfiguration {
        final URI resourceID;
        final URL baseURL;

        protected PosixMapperConfiguration(final URI configuredPosixMapperID) throws IOException {
            if ("ivo".equals(configuredPosixMapperID.getScheme())) {
                resourceID = configuredPosixMapperID;
                baseURL = null;
            } else if ("https".equals(configuredPosixMapperID.getScheme())) {
                resourceID = null;
                baseURL = configuredPosixMapperID.toURL();
            } else {
                throw new IllegalStateException("Incorrect configuration for specified posix mapper service ("
                        + configuredPosixMapperID + ").");
            }
        }

        public PosixMapperClient getPosixMapperClient() {
            if (resourceID == null) {
                return new PosixMapperClient(baseURL);
            } else {
                return new PosixMapperClient(resourceID);
            }
        }
    }

    protected static class EncodedKeyPair {
        final byte[] encodedPublicKey;
        final byte[] encodedPrivateKey;

        public EncodedKeyPair(byte[] encodedPublicKey, byte[] encodedPrivateKey) {
            this.encodedPublicKey = encodedPublicKey;
            this.encodedPrivateKey = encodedPrivateKey;
        }
    }
}
