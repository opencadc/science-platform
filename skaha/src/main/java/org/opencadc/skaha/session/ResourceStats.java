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
package org.opencadc.skaha.session;

import org.apache.log4j.Logger;

/**
 * @author yeunga
 *
 */
public class ResourceStats {
    
    private static final Logger log = Logger.getLogger(ResourceStats.class);
    
    private JobInstances instances;
    private Core cores;
    private Ram ram;

    public ResourceStats(int desktopCount, int headlessCount, int totalCount, 
            Double requestedCPUCores, Double coresAvailable, Double mCores, String withRAM, String mRAM, Double withCores) {
        instances = new JobInstances(desktopCount, headlessCount, totalCount);

        MaxCoreResource maxCores = new MaxCoreResource();
        maxCores.cpuCores = mCores;
        maxCores.withRam = withRAM;
        cores = new Core();
        cores.maxCPUCores = maxCores;
        cores.cpuCoresAvailable = coresAvailable;
        cores.requestedCPUCores = requestedCPUCores;

        MaxRamResource maxRAM = new MaxRamResource();
        maxRAM.ram = mRAM;
        maxRAM.withCPUCores = withCores;
        ram = new Ram();
        ram.maxRAM = maxRAM;
    }
    
    class JobInstances {
        private int session;
        private int desktopApp;
        private int headless;
        private int total;
        
        public JobInstances(int desktopCount, int headlessCount, int totalCount) {
            desktopApp = desktopCount;
            headless = headlessCount;
            total = totalCount;
            session = totalCount - desktopCount - headlessCount;
        }
    }
    
    class Core {
        Double requestedCPUCores = 0.0;
        Double cpuCoresAvailable = 0.0;
        MaxCoreResource maxCPUCores;
    }
    
    class Ram {
        MaxRamResource maxRAM;
    }

    class MaxCoreResource {
        public Double cpuCores = 0.0;
        public String withRam = "0K";
    }

    class MaxRamResource {
        public String ram = "0K";
        public Double withCPUCores = 0.0;
    }
}
