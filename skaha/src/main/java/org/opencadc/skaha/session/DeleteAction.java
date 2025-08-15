/*
 ************************************************************************
 *******************  CANADIAN ASTRONOMY DATA CENTRE  *******************
 **************  CENTRE CANADIEN DE DONNÉES ASTRONOMIQUES  **************
 *
 *  (c) 2018.                            (c) 2018.
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

import ca.nrc.cadc.net.ResourceNotFoundException;
import ca.nrc.cadc.util.StringUtil;
import java.io.IOException;
import java.security.AccessControlException;
import org.apache.log4j.Logger;
import org.opencadc.skaha.K8SUtil;
import org.opencadc.skaha.utils.CommandExecutioner;
import org.opencadc.skaha.utils.KubectlCommandBuilder;

/**
 * Handle a delete request for a session or an app.
 *
 * @author majorb
 */
public class DeleteAction extends SessionAction {

    private static final Logger log = Logger.getLogger(DeleteAction.class);

    public DeleteAction() {
        super();
    }

    @Override
    public void doAction() throws Exception {
        super.initRequest();
        if (requestType.equals(REQUEST_TYPE_SESSION)) {
            if (sessionID == null) {
                throw new UnsupportedOperationException("Cannot kill all sessions.");
            } else {
                final String[] getSessionsCmd = KubectlCommandBuilder.command("get")
                        .namespace(K8SUtil.getWorkloadNamespace())
                        .job()
                        .selector(String.format(
                                "canfar-net-sessionID=%s,canfar-net-sessionType!=%s",
                                sessionID, SessionType.DESKTOP_APP.applicationName))
                        .noHeaders()
                        .outputFormat("custom-columns=USERID:.metadata.labels.canfar-net-userid,JOBNAME:.metadata.name")
                        .build();

                final String session = CommandExecutioner.execute(getSessionsCmd);

                if (StringUtil.hasText(session)) {
                    final String[] lines = session.split("\n");
                    // sessionID was added to desktop-app. This resulted in the
                    // above kubectl command returning desktop-app as well. We
                    // want to ignore them as we pick the session to be deleted.
                    for (String line : lines) {
                        String[] parts = line.split("\\s+");
                        String sessionUserId = parts[0];
                        if (!posixPrincipal.username.equals(sessionUserId)) {
                            throw new AccessControlException("forbidden");
                        }

                        final String jobName = parts[1];
                        delete(K8SUtil.getWorkloadNamespace(), jobName);
                        return;
                    }
                }

                // no session to delete
                throw new ResourceNotFoundException(sessionID);
            }
        } else if (requestType.equals(REQUEST_TYPE_APP)) {
            deleteDesktopApp();
        } else {
            throw new IllegalArgumentException("Invalid request type for deletion: " + requestType);
        }
    }

    private void deleteDesktopApp() throws Exception {
        // kill the session specified by sessionID
        log.debug("Stopping Desktop App for Session " + sessionID);
        String k8sNamespace = K8SUtil.getWorkloadNamespace();
        // deleting a desktop-app
        if (StringUtil.hasText(appID)) {
            log.debug("appID " + appID);
            String jobName = this.getAppJobName(sessionID, posixPrincipal.username, appID);
            if (StringUtil.hasText(jobName)) {
                delete(k8sNamespace, jobName);
            } else {
                log.warn("no job deleted, desktop-app job name not found for userID " + posixPrincipal.username
                        + ", sessionID " + sessionID + ", appID " + appID);
            }
        } else {
            throw new IllegalArgumentException("Missing app ID");
        }
    }

    private void delete(String k8sNamespace, String name) {
        try {
            String[] delete = KubectlCommandBuilder.command("delete")
                    .namespace(k8sNamespace)
                    .argument("job")
                    .argument(name)
                    .build();
            CommandExecutioner.execute(delete);
        } catch (Exception ex) {
            // fail to delete the object, just log a warning and continue
            log.warn(ex.getMessage());
        }
    }

    private String getAppJobName(String sessionID, String userID, String appID)
            throws IOException, InterruptedException {
        String k8sNamespace = K8SUtil.getWorkloadNamespace();
        String[] getAppJobNameCMD = getAppJobNameCMD(k8sNamespace, userID, sessionID, appID);
        return CommandExecutioner.execute(getAppJobNameCMD);
    }

    private String[] getAppJobNameCMD(String k8sNamespace, String userID, String sessionID, String appID) {
        String labels = "canfar-net-sessionType=" + TYPE_DESKTOP_APP;
        labels = labels + ",canfar-net-userid=" + userID;
        if (sessionID != null) {
            labels = labels + ",canfar-net-sessionID=" + sessionID;
        }
        if (appID != null) {
            labels = labels + ",canfar-net-appID=" + appID;
        }

        KubectlCommandBuilder.KubectlCommand getAppJobNameCmd = KubectlCommandBuilder.command("get")
                .namespace(k8sNamespace)
                .job()
                .label(labels)
                .noHeaders()
                .outputFormat("custom-columns=NAME:.metadata.name");

        return getAppJobNameCmd.build();
    }
}
