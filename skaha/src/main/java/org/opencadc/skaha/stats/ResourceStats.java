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
package org.opencadc.skaha.stats;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.log4j.Logger;
import org.opencadc.skaha.K8SUtil;

/**
 * @author yeunga
 *
 */
public class ResourceStats {
    
    private static final Logger log = Logger.getLogger(ResourceStats.class);
    
    private Integer defaultRAM;
    private Integer defaultCores;
    private Integer availableRAM;
    private Integer availableCores;

    public ResourceStats() {
        String k8sNamespace;
        try {
            k8sNamespace = K8SUtil.getWorkloadNamespace();
        } catch (Exception e) {
            log.error(e);
            throw new IllegalStateException("failed to get workload namespace", e);
        }

        try {
            int totalRequestedCPUCores = 0;
            int totalRequestedMemory = 0;
            int totalAvailableCPUCores = 0;
            int totalAvailableMemory = 0;
            List<String> nodeNames = getNodeNames(k8sNamespace);
            for (String nodeName : nodeNames) {
                int rMemory = getMemory(nodeName, k8sNamespace);
                int rCPUCores = getCPUCores(nodeName, k8sNamespace);
                int aMemory = getAvailableCPUCores(nodeName, k8sNamespace);
                int aCPUCores = getAvailableMemory(nodeName, k8sNamespace);
                totalRequestedMemory = totalRequestedMemory + rMemory;
                totalRequestedCPUCores = totalRequestedCPUCores + rCPUCores;
                totalAvailableMemory = totalAvailableMemory + aMemory;
                totalAvailableCPUCores = totalAvailableCPUCores + aCPUCores;
                log.debug("Node: " + nodeName + " Cores: " + rCPUCores + "/" + aCPUCores + " RAM: " + rMemory + "/" + aMemory + " GB");
            }
            
            defaultRAM = totalRequestedMemory;
            defaultCores = totalRequestedCPUCores;
            availableRAM = totalAvailableMemory;
            availableCores = totalAvailableCPUCores;
         }catch (Exception e) {
            log.error(e);
            throw new IllegalStateException("failed reading k8s-resources.properties", e);
        }
    }
    
    private static String readStream(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[1024];
        while ((nRead = in.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toString("UTF-8");
    }
    
    private static String execute(String[] command) throws IOException, InterruptedException {
        return execute(command, false);
    }

    private static String execute(String[] command, boolean allowError) throws IOException, InterruptedException {
        Process p = Runtime.getRuntime().exec(command);
        int status = p.waitFor();
        log.debug("Status=" + status + " for command: " + Arrays.toString(command));
        String stdout = readStream(p.getInputStream());
        String stderr = readStream(p.getErrorStream());
        log.debug("stdout: " + stdout);
        log.debug("stderr: " + stderr);
        if (status != 0) {
            if (allowError) {
                return stderr;
            } else {
                String message = "Error executing command: " + Arrays.toString(command) + " Error: " + stderr;
                throw new IOException(message);
            }
        } 
        return stdout.trim();
    }

    private List<String> getNodeNames(String k8sNamespace) throws Exception {
        String getNodeNamesCmd = "kubectl -n " + k8sNamespace + " get nodes -o custom-columns=:metadata.name";
        String nodeNames = execute(getNodeNamesCmd.split(" "));
        log.debug("nodes: " + nodeNames);
        if (nodeNames != null) {
            String[] lines = nodeNames.split("\n");
            if (lines.length > 0) {
                return Arrays.asList(lines);
            }
        }
        
        return new ArrayList<String>();
    }

    private int getMemory(String nodeName, String k8sNamespace) throws Exception {
        String getMemoryCmd = "kubectl -n " + k8sNamespace + " get pods -o custom-columns=0:.spec.containers[].resources.requests.memory --field-selector spec.nodeName=" + nodeName + " | sed 's/[^0-9]*//g'";
        String nodeMemory = execute(getMemoryCmd.split(" "));
        log.debug("memory in node " + nodeName + ": " + nodeMemory);
        if (nodeMemory != null) {
            String[] lines = nodeMemory.split("\n");
            if (lines.length > 0) {
                List<Integer> memories = Arrays.stream(lines).map(Integer::parseInt).collect(Collectors.toList());
                int totalNodeMemory = 0;
                for (Integer memory : memories) {
                    totalNodeMemory = totalNodeMemory + memory;
                }
                
                return totalNodeMemory;
            }
        }
        
        return 0;
    }

    private int getCPUCores(String nodeName, String k8sNamespace) throws Exception {
        String getCPUCoresCmd = "kubectl -n " + k8sNamespace + " get pods -o custom-columns=0:.spec.containers[].resources.requests.cpu --field-selector spec.nodeName=" + nodeName;
        String nodeCPUCores = execute(getCPUCoresCmd.split(" "));
        log.debug("CPU cores in node " + nodeName + ": " + nodeCPUCores);
        if (nodeCPUCores != null) {
            String[] lines = nodeCPUCores.split("\n");
            if (lines.length > 0) {
                List<Integer> cpuCores = Arrays.stream(lines).map(Integer::parseInt).collect(Collectors.toList());
                int totalNodeCPUCores = 0;
                for (Integer cpuCore : cpuCores) {
                    totalNodeCPUCores = totalNodeCPUCores + cpuCore;
                }
                
                return totalNodeCPUCores;
            }
        }
        
        return 0;
    }

    private int getAvailableCPUCores(String nodeName, String k8sNamespace) throws Exception {
        String getCPUCoresCmd = "kubectl -n " + k8sNamespace + " describe node " + nodeName + " | grep cpu: | sed 's/[^0-9]*//g'";
        String nodeCPUCores = execute(getCPUCoresCmd.split(" "));
        log.debug("Available CPU cores in node " + nodeName + ": " + nodeCPUCores);
        if (nodeCPUCores != null) {
            String[] lines = nodeCPUCores.split("\n");
            if (lines.length > 0) {
                return Integer.parseInt(lines[0]);
            }
        }
        
        return 0;
    }

    private int getAvailableMemory(String nodeName, String k8sNamespace) throws Exception {
        String getAvailableMemoryCmd = "kubectl -n " + k8sNamespace + " describe node " + nodeName + " | grep memory: | sed 's/[^0-9]*//g'";
        String nodeAvailableMemory = execute(getAvailableMemoryCmd.split(" "));
        log.debug("Available memory in node " + nodeName + ": " + nodeAvailableMemory);
        if (nodeAvailableMemory != null) {
            String[] lines = nodeAvailableMemory.split("\n");
            if (lines.length > 1) {
                return Integer.parseInt(lines[1])/1000000;
            }
        }
        
        return 0;
    }
}
