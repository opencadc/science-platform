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
import ca.nrc.cadc.auth.NotAuthenticatedException;
import ca.nrc.cadc.auth.PosixPrincipal;
import ca.nrc.cadc.cred.client.CredUtil;
import ca.nrc.cadc.net.HttpGet;
import ca.nrc.cadc.reg.Standards;
import ca.nrc.cadc.reg.client.LocalAuthority;
import ca.nrc.cadc.reg.client.RegistryClient;
import ca.nrc.cadc.rest.InlineContentHandler;
import ca.nrc.cadc.rest.RestAction;
import ca.nrc.cadc.util.StringUtil;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.opencadc.auth.PosixMapperClient;
import org.opencadc.gms.GroupURI;
import org.opencadc.gms.IvoaGroupClient;
import org.opencadc.skaha.image.Image;
import org.opencadc.skaha.utils.CollectionUtils;

import javax.security.auth.Subject;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.security.AccessControlException;
import java.security.PrivilegedExceptionAction;
import java.util.*;

import static java.util.stream.Collectors.toList;


public abstract class SkahaAction extends RestAction {

    private static final Logger log = Logger.getLogger(SkahaAction.class);
    private static final String POSIX_MAPPER_RESOURCE_ID_KEY = "skaha.posixmapper.resourceid";

    public static final String SESSION_TYPE_CARTA = "carta";
    public static final String SESSION_TYPE_NOTEBOOK = "notebook";
    public static final String SESSION_TYPE_DESKTOP = "desktop";
    public static final String SESSION_TYPE_CONTRIB = "contributed";
    public static final String SESSION_TYPE_HEADLESS = "headless";
    public static final String TYPE_DESKTOP_APP = "desktop-app";
    public static List<String> SESSION_TYPES = Arrays.asList(
            SESSION_TYPE_CARTA, SESSION_TYPE_NOTEBOOK, SESSION_TYPE_DESKTOP,
            SESSION_TYPE_CONTRIB, SESSION_TYPE_HEADLESS, TYPE_DESKTOP_APP);

    protected PosixPrincipal posixPrincipal;
    protected boolean adminUser = false;
    protected String server;
    protected String homedir;
    protected String scratchdir;
    public List<String> harborHosts = new ArrayList<>();
    protected String skahaUsersGroup;
    protected int maxUserSessions;
    protected final URI posixMapperResourceID;

    public SkahaAction() {
        server = System.getenv("skaha.hostname");
        homedir = System.getenv("skaha.homedir");
        scratchdir = System.getenv("skaha.scratchdir");
        String harborHostList = System.getenv("skaha.harborhosts");
        if (harborHostList == null) {
            log.warn("no harbor host list configured!");
        } else {
            harborHosts = Arrays.asList(harborHostList.split(" "));
        }
        skahaUsersGroup = System.getenv("skaha.usersgroup");
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
        log.debug("skaha.scratchdir=" + scratchdir);
        log.debug("skaha.harborHosts=" + harborHostList);
        log.debug("skaha.usersgroup=" + skahaUsersGroup);
        log.debug("skaha.maxusersessions=" + maxUserSessions);
        log.debug(SkahaAction.POSIX_MAPPER_RESOURCE_ID_KEY + "=" + configuredPosixMapperResourceID);

        if (StringUtil.hasText(configuredPosixMapperResourceID)) {
            posixMapperResourceID = URI.create(configuredPosixMapperResourceID);
        } else {
            posixMapperResourceID = null;
        }
    }

    protected PosixMapperClient getPosixMapperClient() {
        return new PosixMapperClient(posixMapperResourceID);
    }

    protected URL lookupGroupMapperURL() {
        return new RegistryClient().getServiceURL(posixMapperResourceID, Standards.POSIX_GROUPMAP, AuthMethod.TOKEN);
    }

    protected URL lookupUserMapperURL() {
        return new RegistryClient().getServiceURL(posixMapperResourceID, Standards.POSIX_USERMAP, AuthMethod.TOKEN);
    }

    @Override
    protected InlineContentHandler getInlineContentHandler() {
        return null;
    }

    protected void initRequest() throws Exception {

        final Subject currentSubject = AuthenticationUtil.getCurrentSubject();
        log.debug("Subject: " + currentSubject);

        if (currentSubject == null || currentSubject.getPrincipals().isEmpty()) {
            throw new NotAuthenticatedException("Unauthorized");
        }
        Set<PosixPrincipal> posixPrincipals = currentSubject.getPrincipals(PosixPrincipal.class);
        if (posixPrincipals.isEmpty()) {
            throw new AccessControlException("No POSIX Principal");
        }
        posixPrincipal = posixPrincipals.iterator().next();
        log.debug("userID: " + posixPrincipal);

        // ensure user is a part of the skaha group
        if (skahaUsersGroup == null) {
            throw new IllegalStateException("skaha.usersgroup not defined in system properties");
        }
        LocalAuthority localAuthority = new LocalAuthority();
        URI gmsSearchURI = localAuthority.getServiceURI(Standards.GMS_SEARCH_10.toString());

        IvoaGroupClient ivoaGroupClient = new IvoaGroupClient();
        GroupURI skahaUsersGroupUri = new GroupURI(URI.create(skahaUsersGroup));
        Set<GroupURI> skahaUsersGroupUriSet = ivoaGroupClient.getMemberships(gmsSearchURI);
        if (!skahaUsersGroupUriSet.contains(skahaUsersGroupUri)) {
            log.debug("user is not a member of skaha user group ");
            throw new AccessControlException("Not authorized to use the skaha system");
        }
        log.debug("user is a member of skaha user group ");
        List<Group> groups = CollectionUtils.isNotEmpty(skahaUsersGroupUriSet) ?
                skahaUsersGroupUriSet.stream().map(Group::new).collect(toList())
                : new ArrayList<>();

        // adding all groups to the Subject
        currentSubject.getPublicCredentials().add(groups);
    }

    protected String getUsername() {
        return posixPrincipal.username;
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

        if (oauthURL == null) {
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
}
