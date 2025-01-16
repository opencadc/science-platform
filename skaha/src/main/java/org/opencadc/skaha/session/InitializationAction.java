/*
 ************************************************************************
 *******************  CANADIAN ASTRONOMY DATA CENTRE  *******************
 **************  CENTRE CANADIEN DE DONNÉES ASTRONOMIQUES  **************
 *
 *  (c) 2025.                            (c) 2025.
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
 *
 ************************************************************************
 */

package org.opencadc.skaha.session;

import ca.nrc.cadc.rest.InitAction;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1CustomResourceDefinitionList;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opencadc.skaha.K8SUtil;

/** One-time initialization action for Skaha. This will verify the existence of the local queues, if specified. */
public class InitializationAction extends InitAction {
    private static final Logger LOGGER = LogManager.getLogger(InitializationAction.class);
    private static final String FATAL_LOG_MESSAGE =
            "Queue %s specified in configuration but no local queues exist in namespace %s";

    @Override
    public void doInit() {
        LOGGER.info("Verifying QueueConfigurations, if any...");
        final QueueConfiguration interactiveQueueConfig = getInteractiveQueueConfiguration();
        int queuesChecked = 0;
        if (interactiveQueueConfig == null) {
            LOGGER.info("Skipping missing InteractiveQueueConfiguration");
        } else {
            ensureLocalQueueValid(interactiveQueueConfig);
            queuesChecked++;
        }

        final QueueConfiguration headlessQueueConfig = getHeadlessQueueConfiguration();
        if (headlessQueueConfig == null) {
            LOGGER.info("Skipping missing HeadlessQueueConfiguration");
        } else {
            ensureLocalQueueValid(headlessQueueConfig);
            queuesChecked++;
        }
        LOGGER.info("Verifying QueueConfigurations: OK ({} queues checked)", queuesChecked);
    }

    private void ensureLocalQueueValid(final QueueConfiguration queueConfiguration) {
        final V1CustomResourceDefinitionList listResults = queryLocalQueues();
        if (listResults == null
                || listResults.getItems().stream()
                        .noneMatch(localQueueCRD ->
                                queueConfiguration.queueName.equals(Objects.requireNonNull(localQueueCRD.getMetadata())
                                        .getName()))) {
            final String message = String.format(
                    InitializationAction.FATAL_LOG_MESSAGE, queueConfiguration.queueName, getWorkloadNamespace());
            LOGGER.fatal(message);
            throw new IllegalStateException(message);
        }
    }

    /**
     * Query the local queues in the workload namespace. Tests can override this method to return a mock response.
     *
     * @return V1CustomResourceDefinitionList instance, or null if none exist.
     */
    V1CustomResourceDefinitionList queryLocalQueues() {
        final CoreV1Api api = new CoreV1Api();
        final CustomObjectsApi customObjectsApi = new CustomObjectsApi(api.getApiClient());

        try {
            final Object listResults = customObjectsApi
                    .listNamespacedCustomObject("kueue.x-k8s.io", "v1", getWorkloadNamespace(), "localqueues")
                    .execute();
            if (listResults == null) {
                LOGGER.warn("No local queues exist in namespace {}", getWorkloadNamespace());
                return null;
            } else {
                return (V1CustomResourceDefinitionList) listResults;
            }
        } catch (ApiException apiException) {
            throw new IllegalStateException(apiException.getMessage(), apiException);
        }
    }

    QueueConfiguration getInteractiveQueueConfiguration() {
        return K8SUtil.getInteractiveQueueConfiguration();
    }

    QueueConfiguration getHeadlessQueueConfiguration() {
        return K8SUtil.getHeadlessQueueConfiguration();
    }

    String getWorkloadNamespace() {
        return K8SUtil.getWorkloadNamespace();
    }
}
