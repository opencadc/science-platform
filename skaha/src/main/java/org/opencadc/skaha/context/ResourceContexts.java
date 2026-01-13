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

import ca.nrc.cadc.util.PropertiesReader;
import com.google.gson.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.opencadc.skaha.K8SUtil;

/**
 * Describes the JSON file that contains the default and available resources for the Kubernetes cluster.
 *
 * @author majorb
 */
public class ResourceContexts {

    private static final Logger log = Logger.getLogger(ResourceContexts.class);
    static final String SESSION_LIMIT_RANGE_FEATURE_GATE = "sessionLimitRange";
    private static final String SESSION_LIMIT_FILE_NAME = "k8s-resources.json";

    private final Integer defaultRequestCores;
    private final Integer defaultLimitCores;
    private final List<Integer> availableCores = new ArrayList<>();

    // units in GB
    private final Integer defaultRequestRAM;
    private final Integer defaultLimitRAM;
    private final List<Integer> availableRAM = new ArrayList<>();

    private final List<Integer> availableGPUs = new ArrayList<>();

    /** Default constructor reads the resources from the k8s-resources.json file and objectifies them locally. */
    public ResourceContexts() {
        try (final Reader reader = ResourceContexts.getJSONReader()) {
            JsonElement jsonElement = JsonParser.parseReader(reader);
            JsonObject jsonObject = jsonElement.getAsJsonObject();

            // Extract fields into variables
            JsonObject cores = jsonObject.getAsJsonObject("cores");
            defaultRequestCores = cores.get("defaultRequest").getAsInt();
            defaultLimitCores = cores.get("defaultLimit").getAsInt();
            JsonArray coresOptions = cores.getAsJsonArray("options");
            coresOptions.asList().forEach(coreOption -> availableCores.add(coreOption.getAsInt()));

            JsonObject memory = jsonObject.getAsJsonObject("memoryGB");
            defaultRequestRAM = memory.get("defaultRequest").getAsInt();
            defaultLimitRAM = memory.get("defaultLimit").getAsInt();
            JsonArray ramOptions = memory.getAsJsonArray("options");
            ramOptions.asList().forEach(ramOption -> availableRAM.add(ramOption.getAsInt()));

            JsonObject gpus = jsonObject.getAsJsonObject("gpus");
            JsonArray gpuOptions = gpus.getAsJsonArray("options");
            gpuOptions.asList().forEach(gpuOption -> availableGPUs.add(gpuOption.getAsInt()));
        } catch (Exception e) {
            log.error(e);
            throw new IllegalStateException("Failed reading Resource Context data.", e);
        }
    }

    /**
     * Obtain the current JSON reader for the resources output. If the sessionLimitRange feature gate is enabled, the
     * resources will be read from the Kubernetes LimitRange. Otherwise, the resources will be read from the
     * k8s-resources.json file.
     *
     * @return Reader for the resources in JSON format. Never null.
     */
    @NotNull static Reader getJSONReader() {
        try {
            final K8SUtil.ExperimentalFeatures experimentalFeatures = K8SUtil.getExperimentalFeatures();
            final boolean sessionLimitRangeEnabled =
                    experimentalFeatures.isEnabled(ResourceContexts.SESSION_LIMIT_RANGE_FEATURE_GATE);
            if (sessionLimitRangeEnabled) {
                final LimitRangeResourceContext limitRangeResourceContext = new LimitRangeResourceContext();
                try (final OutputStream outputStream = new ByteArrayOutputStream()) {
                    limitRangeResourceContext.write(outputStream);
                    outputStream.flush();
                    return new StringReader(outputStream.toString());
                }
            } else {
                return new FileReader(ResourceContexts.getResourcesFile());
            }
        } catch (Exception e) {
            log.error(e);
            throw new IllegalStateException("Failed reading Resources as JSON", e);
        }
    }

    @NotNull private static File getResourcesFile() {
        String configDir = System.getProperty("user.home") + "/config";
        String configDirSystemProperty = PropertiesReader.class.getName() + ".dir";
        if (System.getProperty(configDirSystemProperty) != null) {
            configDir = System.getProperty(configDirSystemProperty);
        }
        return new File(new File(configDir), ResourceContexts.SESSION_LIMIT_FILE_NAME);
    }

    public Integer getDefaultRequestCores() {
        return defaultRequestCores;
    }

    public Integer getDefaultLimitCores() {
        return defaultLimitCores;
    }

    public boolean isCoreCountAvailable(final Integer coreCount) {
        return this.availableCores.contains(coreCount);
    }

    public boolean isRAMAmountAvailable(final Integer ramAmount) {
        return this.availableRAM.contains(ramAmount);
    }

    public Integer getDefaultRequestRAM() {
        return defaultRequestRAM;
    }

    public Integer getDefaultLimitRAM() {
        return defaultLimitRAM;
    }

    @NotNull public List<Integer> getAvailableRAM() {
        return availableRAM;
    }

    @NotNull public List<Integer> getAvailableGPUs() {
        return availableGPUs;
    }
}
