/*
 ************************************************************************
 *******************  CANADIAN ASTRONOMY DATA CENTRE  *******************
 **************  CENTRE CANADIEN DE DONNÉES ASTRONOMIQUES  **************
 *
 *  (c) 2021.                            (c) 2021.
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
package org.opencadc.skaha.context;

import ca.nrc.cadc.util.StringUtil;
import java.io.IOException;
import java.io.OutputStream;
import org.apache.log4j.Logger;
import org.opencadc.skaha.K8SUtil;

/**
 * Describes the JSON file that contains the default and available resources for the Kubernetes cluster.
 *
 * @author majorb
 */
public class ResourceContexts {
    private static final Logger LOGGER = Logger.getLogger(ResourceContexts.class);

    // Identify the GPU resources.
    public static final String NVIDIA_GPU_LABEL = "nvidia.com/gpu";

    private final ResourceContext resourceContext;

    public ResourceContexts() {
        final K8SUtil.ExperimentalFeatures experimentalFeatures = K8SUtil.getExperimentalFeatures();
        if (StringUtil.hasText(experimentalFeatures.sessionLimitRangeName)) {
            try {
                LOGGER.info("Initializing LimitRangeResourceContext for " + experimentalFeatures.sessionLimitRangeName);
                this.resourceContext = new LimitRangeResourceContext(experimentalFeatures.sessionLimitRangeName);
            } catch (final Exception ioException) {
                throw new IllegalStateException(
                        "Error reading LimitRange " + experimentalFeatures.sessionLimitRangeName, ioException);
            }
        } else {
            LOGGER.info("Initializing StaticResourceContext (default)");
            try {
                this.resourceContext = new StaticResourceContext();
            } catch (IOException ioException) {
                throw new IllegalStateException("Error reading static k8s-resources.json", ioException);
            }
        }
    }

    public void validateCores(final int coreCount) {
        if (this.resourceContext.coreOutOfRange(coreCount)) {
            throw new IllegalArgumentException("Unavailable option for 'cores': " + coreCount + ".  Not in range "
                    + this.resourceContext.getCoreCounts());
        }
    }

    public void validateMemoryGB(final int memoryGB) {
        if (this.resourceContext.memoryOutOfRange(memoryGB)) {
            throw new IllegalArgumentException("Unavailable option for 'ram': " + memoryGB + ".  Not in range "
                    + this.resourceContext.getMemoryCounts());
        }
    }

    public void validateGPUs(final int gpuCount) {
        if (this.resourceContext.gpusOutOfRange(gpuCount)) {
            throw new IllegalArgumentException("Unavailable option for 'gpus': " + gpuCount + ".  Not in range "
                    + this.resourceContext.getGPUCounts());
        }
    }

    public int getDefaultRequestCores() {
        return this.resourceContext.getDefaultCoreCounts().minimum;
    }

    public int getDefaultLimitCores() {
        return this.resourceContext.getDefaultCoreCounts().maximum;
    }

    public int getDefaultRequestMemoryGB() {
        return this.resourceContext.getDefaultMemoryCounts().minimum;
    }

    public int getDefaultLimitMemoryGB() {
        return this.resourceContext.getDefaultMemoryCounts().maximum;
    }

    public void writeOut(final OutputStream outputStream) throws IOException {
        resourceContext.write(outputStream);
    }
}
