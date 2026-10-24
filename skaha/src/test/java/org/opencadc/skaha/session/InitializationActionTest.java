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

import static org.opencadc.skaha.KubernetesApi.NAMESPACE;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.opencadc.skaha.KubernetesApi;
import org.opencadc.skaha.SkahaKubernetesClient;

public class InitializationActionTest {
    private static final String LOCAL_QUEUE_PATH =
            "/apis/kueue.x-k8s.io/v1beta2/namespaces/" + NAMESPACE + "/localqueues/test-queue";
    private static final QueueConfiguration[] QUEUES =
            new QueueConfiguration[] {new QueueConfiguration("notebook", "test-priority", "test-queue")};

    @Rule
    public final KubernetesApi api = new KubernetesApi();

    @Test
    public void testLocalQueuePresent() throws Exception {
        final GenericKubernetesResource localQueue = new GenericKubernetesResourceBuilder()
                .withApiVersion("kueue.x-k8s.io/v1beta2")
                .withKind("LocalQueue")
                .withNewMetadata()
                .withName("test-queue")
                .withNamespace(NAMESPACE)
                .endMetadata()
                .build();
        this.api
                .server()
                .expect()
                .get()
                .withPath(LOCAL_QUEUE_PATH)
                .andReturn(200, localQueue)
                .once();

        testSubject().ensureLocalQueuesValid(QUEUES);
        Assert.assertEquals(LOCAL_QUEUE_PATH, this.api.server().getLastRequest().getPath());
    }

    @Test
    public void testLocalQueueMissing() {
        this.api
                .server()
                .expect()
                .get()
                .withPath(LOCAL_QUEUE_PATH)
                .andReturn(404, "")
                .once();

        final IllegalStateException exception = Assert.assertThrows(
                IllegalStateException.class, () -> testSubject().ensureLocalQueuesValid(QUEUES));
        Assert.assertTrue(exception.getMessage(), exception.getMessage().contains("test-queue"));
    }

    @Test
    public void testLocalQueueForbidden() {
        this.api
                .server()
                .expect()
                .get()
                .withPath(LOCAL_QUEUE_PATH)
                .andReturn(403, "")
                .once();

        Assert.assertThrows(KubernetesClientException.class, () -> testSubject().ensureLocalQueuesValid(QUEUES));
    }

    @Test
    public void testNoQueuesConfigured() {
        testSubject().ensureLocalQueuesValid(new QueueConfiguration[0]);
        Assert.assertEquals(0, this.api.server().getRequestCount());
    }

    @Test
    public void testCacheStartFailureDoesNotStopStartup() {
        // Listing Jobs is forbidden, so the cache never syncs; startup carries on and sessions are read live.
        this.api
                .server()
                .expect()
                .get()
                .withPath("/apis/batch/v1/namespaces/" + NAMESPACE
                        + "/jobs?labelSelector=canfar.net%2Fid&resourceVersion=0")
                .andReturn(403, "")
                .always();
        this.api
                .server()
                .expect()
                .get()
                .withPath("/apis/batch/v1/namespaces/" + NAMESPACE + "/jobs?labelSelector=canfar.net%2Fid")
                .andReturn(403, "")
                .always();
        final InitializationAction testSubject = testSubject();

        testSubject.startSessionJobCache();

        Assert.assertFalse(SessionDAO.servedFromCache(testSubject.sessionJobCache(), "alice", null));
        testSubject.doShutdown();
        // doShutdown closed the client.
        Assert.assertThrows(IllegalStateException.class, SkahaKubernetesClient::get);
    }

    private static InitializationAction testSubject() {
        return new InitializationAction() {
            @Override
            String getWorkloadNamespace() {
                return NAMESPACE;
            }
        };
    }
}
