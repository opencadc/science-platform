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
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import org.apache.log4j.Logger;
import org.opencadc.skaha.SkahaAction;

/**
 * @author majorb
 *
 */
public class ResourceContexts {

    private static final Logger log = Logger.getLogger(ResourceContexts.class);

    private final Integer defaultRequestCores;
    private final Integer defaultLimitCores;
    private final Integer defaultCores;
    private final Integer defaultCoresHeadless;
    private final List<Integer> availableCores = new ArrayList<>();

    // units in GB
    private final Integer defaultRequestRAM;
    private final Integer defaultLimitRAM;
    private final Integer defaultRAM;
    private final Integer defaultRAMHeadless;
    private final List<Integer> availableRAM = new ArrayList<>();

    private final List<Integer> availableGPUs = new ArrayList<>();

    public ResourceContexts() {
        try (final FileReader reader = new FileReader(getResourcesFile("k8s-resources.json"))) {
            JsonElement jsonElement = JsonParser.parseReader(reader);
            JsonObject jsonObject = jsonElement.getAsJsonObject();

            // Extract fields into variables
            JsonObject cores = jsonObject.getAsJsonObject("cores");
            defaultRequestCores = cores.get("defaultRequest").getAsInt();
            defaultLimitCores = cores.get("defaultLimit").getAsInt();
            defaultCores = cores.get("default").getAsInt();
            defaultCoresHeadless = cores.get("defaultHeadless").getAsInt();
            JsonArray coresOptions = cores.getAsJsonArray("options");
            coresOptions.asList().forEach(coreOption -> availableCores.add(coreOption.getAsInt()));

            JsonObject memory = jsonObject.getAsJsonObject("memoryGB");
            defaultRequestRAM = memory.get("defaultRequest").getAsInt();
            defaultLimitRAM = memory.get("defaultLimit").getAsInt();
            defaultRAM = memory.get("default").getAsInt();
            defaultRAMHeadless = memory.get("defaultHeadless").getAsInt();
            JsonArray ramOptions = memory.getAsJsonArray("options");
            ramOptions.asList().forEach(ramOption -> availableRAM.add(ramOption.getAsInt()));

            JsonObject gpus = jsonObject.getAsJsonObject("gpus");
            JsonArray gpuOptions = gpus.getAsJsonArray("options");
            gpuOptions.asList().forEach(gpuOption -> availableGPUs.add(gpuOption.getAsInt()));
        } catch (Exception e) {
            log.error(e);
            throw new IllegalStateException("failed reading k8s-resources.json", e);
        }
    }

    public static File getResourcesFile(String fileName) {
        String configDir = System.getProperty("user.home") + "/config";
        String configDirSystemProperty = PropertiesReader.class.getName() + ".dir";
        if (System.getProperty(configDirSystemProperty) != null) {
            configDir = System.getProperty(configDirSystemProperty);
        }
        return new File(new File(configDir), fileName);
    }

    public Integer getDefaultRequestCores() {
        return defaultRequestCores;
    }

    public Integer getDefaultLimitCores() {
        return defaultLimitCores;
    }

    public Integer getDefaultCores(String sessionType) {
        if (SkahaAction.SESSION_TYPE_HEADLESS.equals(sessionType)) {
            return defaultCoresHeadless;
        }
        return defaultCores;
    }

    public boolean isCoreCountAvailable(final Integer coreCount) {
        return this.availableCores.contains(coreCount);
    }

    public Integer getDefaultRequestRAM() {
        return defaultRequestRAM;
    }

    public Integer getDefaultLimitRAM() {
        return defaultLimitRAM;
    }

    public Integer getDefaultRAM(String sessionType) {
        if (SkahaAction.SESSION_TYPE_HEADLESS.equals(sessionType)) {
            return defaultRAMHeadless;
        }
        return defaultRAM;
    }

    public List<Integer> getAvailableRAM() {
        return availableRAM;
    }

    public List<Integer> getAvailableGPUs() {
        return availableGPUs;
    }
}
