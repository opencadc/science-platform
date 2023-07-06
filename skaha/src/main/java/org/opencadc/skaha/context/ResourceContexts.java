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

import ca.nrc.cadc.util.MultiValuedProperties;
import ca.nrc.cadc.util.PropertiesReader;

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
    
    private Integer defaultRequestCores;
    private Integer defaultLimitCores;
    private Integer defaultCores;
    private Integer defaultCoresHeadless;
    private List<Integer> availableCores = new ArrayList<Integer>();
    
    // units in GB
    private Integer defaultRequestRAM;
    private Integer defaultLimitRAM;
    private Integer defaultRAM;
    private Integer defaultRAMHeadless;
    private List<Integer> availableRAM = new ArrayList<Integer>();
    
    private List<Integer> availableGPUs = new ArrayList<Integer>();

    public ResourceContexts() {
        try {
            PropertiesReader reader = new PropertiesReader("k8s-resources.properties");
            MultiValuedProperties mvp = reader.getAllProperties();
            defaultRequestCores = Integer.valueOf(mvp.getFirstPropertyValue("cores-default-request"));
            defaultLimitCores = Integer.valueOf(mvp.getFirstPropertyValue("cores-default-limit"));
            defaultCores = Integer.valueOf(mvp.getFirstPropertyValue("cores-default"));
            defaultCoresHeadless = Integer.valueOf(mvp.getFirstPropertyValue("cores-default-headless"));
            defaultRequestRAM = Integer.valueOf(mvp.getFirstPropertyValue("mem-gb-default-request"));
            defaultLimitRAM = Integer.valueOf(mvp.getFirstPropertyValue("mem-gb-default-limit"));
            defaultRAM = Integer.valueOf(mvp.getFirstPropertyValue("mem-gb-default"));
            defaultRAMHeadless = Integer.valueOf(mvp.getFirstPropertyValue("mem-gb-default-headless"));
            String cOptions = mvp.getFirstPropertyValue("cores-options");
            String rOptions = mvp.getFirstPropertyValue("mem-gb-options");
            String gOptions = mvp.getFirstPropertyValue("gpus-options");
            
            for (String c : cOptions.split(" ")) {
                availableCores.add(Integer.valueOf(c));
            }
            for (String r : rOptions.split(" ")) {
                availableRAM.add(Integer.valueOf(r));
            }
            for (String g : gOptions.split(" ")) {
                availableGPUs.add(Integer.valueOf(g));
            }
        } catch (Exception e) {
            log.error(e);
            throw new IllegalStateException("failed reading k8s-resources.properties", e);
        }
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

    public List<Integer> getAvailableCores() {
        return availableCores;
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
