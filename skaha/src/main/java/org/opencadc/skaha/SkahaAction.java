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

package org.opencadc.skaha;

import ca.nrc.cadc.ac.Group;
import ca.nrc.cadc.auth.AuthMethod;
import ca.nrc.cadc.auth.AuthenticationUtil;
import ca.nrc.cadc.auth.HttpPrincipal;
import ca.nrc.cadc.auth.NotAuthenticatedException;
import ca.nrc.cadc.auth.PosixPrincipal;
import ca.nrc.cadc.cred.client.CredUtil;
import ca.nrc.cadc.io.ResourceIterator;
import ca.nrc.cadc.net.HttpGet;
import ca.nrc.cadc.net.ResourceNotFoundException;
import ca.nrc.cadc.reg.Standards;
import ca.nrc.cadc.reg.client.LocalAuthority;
import ca.nrc.cadc.reg.client.RegistryClient;
import ca.nrc.cadc.rest.InlineContentHandler;
import ca.nrc.cadc.rest.RestAction;
import ca.nrc.cadc.util.RsaSignatureGenerator;
import ca.nrc.cadc.util.StringUtil;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.opencadc.auth.PosixGroup;
import org.opencadc.auth.PosixMapperClient;
import org.opencadc.gms.GroupURI;
import org.opencadc.gms.IvoaGroupClient;
import org.opencadc.permissions.TokenTool;
import org.opencadc.permissions.WriteGrant;
import org.opencadc.skaha.image.Image;
import org.opencadc.skaha.session.Session;
import org.opencadc.skaha.session.SessionDAO;
import org.opencadc.skaha.utils.CommandExecutioner;
import org.opencadc.skaha.utils.CommonUtils;

import javax.security.auth.Subject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessControlException;
import java.security.KeyPair;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;
import static org.opencadc.skaha.utils.CommonUtils.isNotEmpty;


public abstract class SkahaAction extends RestAction {

    private static final Logger log = Logger.getLogger(SkahaAction.class);

    protected static final String POSIX_DELIMITER = ";";

    private static final String POSIX_MAPPER_RESOURCE_ID_KEY = "skaha.posixmapper.resourceid";

    public static final String SESSION_TYPE_CARTA = "carta";
    public static final String SESSION_TYPE_NOTEBOOK = "notebook";
    public static final String SESSION_TYPE_DESKTOP = "desktop";
    public static final String SESSION_TYPE_CONTRIB = "contributed";
    public static final String SESSION_TYPE_HEADLESS = "headless";
    public static final String TYPE_DESKTOP_APP = "desktop-app";
    public static final String X_AUTH_TOKEN_SKAHA = "x-auth-token-skaha";
    public static List<String> SESSION_TYPES = Arrays.asList(
            SESSION_TYPE_CARTA, SESSION_TYPE_NOTEBOOK, SESSION_TYPE_DESKTOP,
            SESSION_TYPE_CONTRIB, SESSION_TYPE_HEADLESS, TYPE_DESKTOP_APP);

    protected PosixPrincipal posixPrincipal;
    protected boolean adminUser = false;
    protected boolean headlessUser = false;
    protected boolean priorityHeadlessUser = false;
    protected String server;
    protected String homedir;
    protected String scratchdir;
    protected String skahaTld;
    public List<String> harborHosts = new ArrayList<>();
    protected String skahaUsersGroup;
    protected String skahaHeadlessGroup;
    protected String skahaPriorityHeadlessGroup;
    protected String skahaAdminsGroup;
    protected String skahaHeadlessPriortyClass;
    protected int maxUserSessions;
    protected final PosixMapperConfiguration posixMapperConfiguration;


    protected boolean skahaCallbackFlow = false;
    protected String callbackSessionId = null;
    protected String callbackSupplementalGroups = null;
    protected String xAuthTokenSkaha = null;


    public SkahaAction() {
        server = System.getenv("skaha.hostname");
        homedir = System.getenv("skaha.homedir");
        skahaTld = System.getenv("SKAHA_TLD");
        scratchdir = System.getenv("skaha.scratchdir");
        String harborHostList = System.getenv("skaha.harborhosts");
        if (harborHostList == null) {
            log.warn("no harbor host list configured!");
        } else {
            harborHosts = Arrays.asList(harborHostList.split(" "));
        }
        skahaUsersGroup = System.getenv("skaha.usersgroup");
        skahaHeadlessGroup = System.getenv("skaha.headlessgroup");
        skahaPriorityHeadlessGroup = System.getenv("skaha.headlessprioritygroup");
        skahaAdminsGroup = System.getenv("skaha.adminsgroup");
        skahaHeadlessPriortyClass = System.getenv("skaha.headlesspriortyclass");
        String maxUsersSessionsString = System.getenv("skaha.maxusersessions");
        if (maxUsersSessionsString == null) {
            log.warn("no max user sessions value configured.");
            maxUserSessions = 1;
        } else {
            maxUserSessions = Integer.parseInt(maxUsersSessionsString);
        }

        final String configuredPosixMapperResourceID = System.getenv(SkahaAction.POSIX_MAPPER_RESOURCE_ID_KEY);

        log.debug("skaha.hostname=" + server);
        log.debug("skaha.homedir=" + homedir);
        log.debug("SKAHA_TLD=" + skahaTld);
        log.debug("skaha.scratchdir=" + scratchdir);
        log.debug("skaha.harborHosts=" + harborHostList);
        log.debug("skaha.usersgroup=" + skahaUsersGroup);
        log.debug("skaha.headlessgroup=" + skahaHeadlessGroup);
        log.debug("skaha.priorityheadlessgroup=" + skahaPriorityHeadlessGroup);
        log.debug("skaha.adminsgroup=" + skahaAdminsGroup);
        log.debug("skaha.skahaheadlesspriorityclass=" + skahaHeadlessPriortyClass);
        log.debug("skaha.maxusersessions=" + maxUserSessions);
        log.debug(SkahaAction.POSIX_MAPPER_RESOURCE_ID_KEY + "=" + configuredPosixMapperResourceID);

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

    protected String getGroupEntries() throws Exception {
        final StringBuilder groupEntryBuilder = new StringBuilder();
        try (final ResourceIterator<PosixGroup> posixGroupIterator =
                     posixMapperConfiguration.getPosixMapperClient().getGroupMap()) {
            posixGroupIterator.forEachRemaining(pg -> groupEntryBuilder.append(
                    String.format("%s:x:%d:\n",
                                  pg.getGroupURI().getURI().getQuery(),
                                  pg.getGID())));
        }

        final String userEntriesString = groupEntryBuilder.toString();
        if (userEntriesString.lastIndexOf(SkahaAction.POSIX_DELIMITER) > 0) {
            return groupEntryBuilder.substring(0, userEntriesString.lastIndexOf(SkahaAction.POSIX_DELIMITER));
        } else {
            return groupEntryBuilder.toString();
        }
    }

    private boolean isSkahaCallBackFlow(Subject currentSubject) {
        AuthMethod authMethod = AuthenticationUtil.getAuthMethodFromCredentials(currentSubject);
        log.debug("authMethod is " + authMethod);
        log.debug("x-auth-token-skaha is " + syncInput.getHeader(X_AUTH_TOKEN_SKAHA));
        return authMethod == AuthMethod.ANON && isNotEmpty(syncInput.getHeader(X_AUTH_TOKEN_SKAHA));
    }

    protected static TokenTool getTokenTool() throws Exception {
        final EncodedKeyPair encodedKeyPair = getPreAuthorizedTokenSecret();
        return new TokenTool(encodedKeyPair.encodedPublicKey, encodedKeyPair.encodedPrivateKey);
    }

    private static EncodedKeyPair getPreAuthorizedTokenSecret() throws Exception {
        // Check the current secret
        final JSONObject secretData = CommandExecutioner.getSecretData(K8SUtil.getPreAuthorizedTokenSecretName(),
                                                                       K8SUtil.getWorkloadNamespace());
        final String publicKeyPropertyName = "public";
        final String privateKeyPropertyName = "private";

        if (secretData.isEmpty()) {
            final KeyPair keyPair = RsaSignatureGenerator.getKeyPair(2048);
            final byte[] encodedPublicKey = keyPair.getPublic().getEncoded();
            final byte[] encodedPrivateKey = keyPair.getPrivate().getEncoded();

            // create new secret
            final String[] createCmd = new String[] {
                    "kubectl", "--namespace", K8SUtil.getWorkloadNamespace(), "create", "secret", "generic",
                    K8SUtil.getPreAuthorizedTokenSecretName(),
                    String.format("--from-literal=%s=", publicKeyPropertyName)
                    + CommonUtils.encodeBase64(encodedPublicKey),
                    String.format("--from-literal=%s=", privateKeyPropertyName)
                    + CommonUtils.encodeBase64(encodedPrivateKey)
            };

            final String createResult = CommandExecutioner.execute(createCmd);
            log.debug("create secret result: " + createResult);

            return new EncodedKeyPair(encodedPublicKey, encodedPrivateKey);
        } else {
            final Base64.Decoder base64Decoder = Base64.getDecoder();
            // Decode twice since Kubernetes does a separate Base64 encoding.
            return new EncodedKeyPair(base64Decoder.decode(base64Decoder.decode(
                    secretData.getString(publicKeyPropertyName))),
                                      base64Decoder.decode(base64Decoder.decode(
                                              secretData.getString(privateKeyPropertyName))));
        }
    }

    private void initiateSkahaCallbackFlow(Subject currentSubject, URI skahaUsersUri) {
        skahaCallbackFlow = true;
        xAuthTokenSkaha = syncInput.getHeader(X_AUTH_TOKEN_SKAHA);
        log.debug("x-auth-token-skaha header is " + xAuthTokenSkaha);
        try {
            callbackSessionId = SkahaAction.getTokenTool().validateToken(xAuthTokenSkaha, skahaUsersUri, WriteGrant.class);

            final Session session = SessionDAO.getSession(null, callbackSessionId, skahaTld);
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
        GroupURI skahaUsersGroupUri = new GroupURI(skahaUsersUri);
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
        LocalAuthority localAuthority = new LocalAuthority();
        URI gmsSearchURI = localAuthority.getServiceURI(Standards.GMS_SEARCH_10.toString());

        IvoaGroupClient ivoaGroupClient = new IvoaGroupClient();
        Set<GroupURI> skahaUsersGroupUriSet = ivoaGroupClient.getMemberships(gmsSearchURI);

        final GroupURI skahaHeadlessGroupURI = StringUtil.hasText(this.skahaHeadlessGroup)
                                               ? new GroupURI(URI.create(this.skahaHeadlessGroup))
                                               : null;

        if (skahaHeadlessGroupURI != null && skahaUsersGroupUriSet.contains(skahaHeadlessGroupURI)) {
            headlessUser = true;
        }

        final GroupURI skahaPriorityHeadlessGroupURI = StringUtil.hasText(this.skahaPriorityHeadlessGroup)
                                                       ? new GroupURI(URI.create(this.skahaPriorityHeadlessGroup))
                                                       : null;

        if (skahaPriorityHeadlessGroupURI != null && skahaUsersGroupUriSet.contains(skahaPriorityHeadlessGroupURI)) {
            priorityHeadlessUser = true;
        }

        if (!skahaUsersGroupUriSet.contains(skahaUsersGroupUri)) {
            log.debug("user is not a member of skaha user group ");
            throw new AccessControlException("Not authorized to use the skaha system");
        }

        log.debug("user is a member of skaha user group ");
        if (skahaAdminsGroup == null) {
            log.warn("skaha.adminsgroup not defined in system properties");
        } else {
            try {
                final GroupURI adminGroupURI = new GroupURI(URI.create(skahaAdminsGroup));
                if (skahaUsersGroupUriSet.contains(adminGroupURI)) {
                    adminUser = true;
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

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

    /**
     * Obtain an ID Token.  This is only available with a subset of Identity Managers, and so will return null if
     * not supported.
     * @return  String ID Token, or null if none.
     * @throws Exception    Access Control and/or Malformed URL Exceptions
     */
    protected String getIdToken() throws Exception {
        LocalAuthority localAuthority = new LocalAuthority();
        URI serviceURI = localAuthority.getServiceURI(Standards.SECURITY_METHOD_OAUTH.toString());
        RegistryClient regClient = new RegistryClient();
        URL oauthURL = regClient.getServiceURL(serviceURI, Standards.SECURITY_METHOD_OAUTH, AuthMethod.TOKEN);
        log.debug("using ac oauth endpoint: " + oauthURL);

        // There is no ID Token for the special Skaha BackFlow (API Token).
        if (oauthURL == null || isSkahaCallBackFlow(AuthenticationUtil.getCurrentSubject())) {
            return null;
        }

        log.debug("checking public credentials for idToken");
        Subject subject = AuthenticationUtil.getCurrentSubject();
        if (subject != null) {
            Set<IDToken> idTokens = subject.getPublicCredentials(IDToken.class);
            if (!idTokens.isEmpty()) {
                log.debug("returning idToken from public credentials");
                return idTokens.iterator().next().idToken;
            }
        }
        log.debug("verifying delegated credentials");
        if (!CredUtil.checkCredentials()) {
            throw new IllegalStateException("cannot access delegated credentials");
        }

        log.debug("getting idToken from ac");
        URL acURL = new URL(oauthURL + "?response_type=id_token&client_id=arbutus-harbor&scope=cli");
        OutputStream out = new ByteArrayOutputStream();
        HttpGet get = new HttpGet(acURL, out);
        get.run();
        if (get.getThrowable() != null) {
            log.warn("error obtaining idToken", get.getThrowable());
            return null;
        }
        String idToken = out.toString();
        log.debug("idToken: " + idToken);
        if (idToken == null || idToken.trim().isEmpty()) {
            log.warn("null id token returned");
            return null;
        }
        // adding to public credentials
        IDToken tokenClass = new IDToken();
        tokenClass.idToken = idToken;
        if (subject != null) {
            subject.getPublicCredentials().add(tokenClass);
        }

        return idToken;
    }

    public Image getImage(String imageID) throws Exception {
        String idToken = getIdToken();

        log.debug("get image: " + imageID);
        int firstSlash = imageID.indexOf("/");
        int secondSlash = imageID.indexOf("/", firstSlash + 1);
        int colon = imageID.lastIndexOf(":");
        String harborHost = imageID.substring(0, firstSlash);
        String project = imageID.substring(firstSlash + 1, secondSlash);
        String repo = imageID.substring(secondSlash + 1, colon);
        String version = imageID.substring(colon + 1);
        log.debug("host: " + harborHost);
        log.debug("project: " + project);
        log.debug("repo: " + repo);
        log.debug("version: " + version);

        String artifacts = callHarbor(idToken, harborHost, project, repo);

        JSONArray jArtifacts = new JSONArray(artifacts);

        for (int a = 0; a < jArtifacts.length(); a++) {
            JSONObject jArtifact = jArtifacts.getJSONObject(a);

            if (!jArtifact.isNull("tags")) {
                JSONArray tags = jArtifact.getJSONArray("tags");
                for (int j = 0; j < tags.length(); j++) {
                    JSONObject jTag = tags.getJSONObject(j);
                    String tag = jTag.getString("name");
                    if (version.equals(tag)) {
                        if (!jArtifact.isNull("labels")) {
                            String digest = jArtifact.getString("digest");
                            JSONArray labels = jArtifact.getJSONArray("labels");
                            Set<String> types = getTypesFromLabels(labels);
                            if (!types.isEmpty()) {
                                return new Image(imageID, types, digest);
                            }
                        }
                    }
                }
            }

        }


        return null;
    }

    protected String callHarbor(String idToken, String harborHost, String project, String repo) throws Exception {

        final URL harborURL;
        final String message;
        if (project == null) {
            harborURL = new URL("https://" + harborHost + "/api/v2.0/projects?page_size=100");
            message = "projects";
        } else if (repo == null) {
            harborURL = new URL(
                    "https://" + harborHost + "/api/v2.0/projects/" + project + "/repositories?page_size=-1");
            message = "repositories";
        } else {
            harborURL = new URL("https://" + harborHost + "/api/v2.0/projects/" + project + "/repositories/"
                                + repo + "/artifacts?detail=true&with_label=true&page_size=-1");
            message = "artifacts";
        }

        OutputStream out = new ByteArrayOutputStream();
        HttpGet get = new HttpGet(harborURL, out);
        if (StringUtil.hasText(idToken)) {
            get.setRequestProperty("Authorization", "Bearer " + idToken);
        }
        log.debug("calling " + harborURL + " for " + message);
        try {
            get.run();
        } catch (Exception e) {
            log.debug("error listing harbor " + message + ": " + e.getMessage(), e);
            log.debug("response code: " + get.getResponseCode());
            throw e;
        }

        if (get.getThrowable() != null) {
            log.warn("error listing harbor " + message, get.getThrowable());
            throw new RuntimeException(get.getThrowable());
        }

        String output = out.toString();
        log.debug(message + " output: " + output);
        return output;

    }

    protected Set<String> getTypesFromLabels(JSONArray labels) {
        Set<String> types = new HashSet<>();
        for (int i = 0; i < labels.length(); i++) {
            JSONObject label = labels.getJSONObject(i);
            String name = label.getString("name");
            log.debug("label: " + name);
            if (name != null && SESSION_TYPES.contains(name)) {
                types.add(name);
            }
        }
        return types;
    }

    /**
     * Temporary holder of tokens until cadc-util auth package with Token
     * support released.
     */
    static class IDToken {
        public String idToken;
    }

    /**
     * It's important to use the correct constructor for the PosixMapperClient, this class will wrap the logic
     * based on how the Resource ID of the POSIX mapper was set (URI or URL).
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
