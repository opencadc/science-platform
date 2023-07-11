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

package org.opencadc.skaha.session;

import ca.nrc.cadc.util.StringUtil;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.OutputStream;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.opencadc.skaha.K8SUtil;

/**
 * Process the GET request on the session(s) or app(s).
 *
 * @author majorb
 */
public class GetAction extends SessionAction {

    private static final Logger log = Logger.getLogger(GetAction.class);
    private static final Set<Character> VALID_RAM_UNITS =
            new HashSet<>(Arrays.asList('T', 't', 'G', 'g', 'M', 'm', 'K', 'k'));
    private static final DecimalFormat formatter =
            new DecimalFormat("#.###", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
    private static final String REQ_CPU_CORES_KEY = "reqCPUCoresKey";
    private static final String REQ_RAM_KEY = "reqRAMKey";

    public GetAction() {
        super();
    }

    @Override
    public void doAction() throws Exception {
        super.initRequest();
        String view = syncInput.getParameter("view");
        if (requestType.equals(REQUEST_TYPE_SESSION)) {
            if (sessionID == null) {
                if (SESSION_VIEW_STATS.equals(view)) {
                    ResourceStats resourceStats = getResourceStats();
                    Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
                    String json = gson.toJson(resourceStats);
                    syncOutput.setHeader("Content-Type", "application/json");
                    syncOutput.getOutputStream().write(json.getBytes());
                } else {
                    // List the sessions
                    String typeFilter = syncInput.getParameter("type");
                    String statusFilter = syncInput.getParameter("status");
                    boolean allUsers = SESSION_LIST_VIEW_ALL.equals(view);

                    String json = listSessions(typeFilter, statusFilter, allUsers);

                    syncOutput.setHeader("Content-Type", "application/json");
                    syncOutput.getOutputStream().write(json.getBytes());
                }
            } else {
                if (SESSION_VIEW_LOGS.equals(view)) {
                    // return the container log
                    syncOutput.setHeader("Content-Type", "text/plain");
                    syncOutput.setCode(200);
                    streamContainerLogs(sessionID, syncOutput.getOutputStream());
                } else if (SESSION_VIEW_EVENTS.equals(view)) {
                    // return the event logs
                    String logs = getEventLogs(sessionID);
                    syncOutput.setHeader("Content-Type", "text/plain");
                    syncOutput.getOutputStream().write(logs.getBytes());
                } else {
                    // return the session
                    String json = getSingleSession(sessionID);
                    syncOutput.setHeader("Content-Type", "application/json");
                    syncOutput.getOutputStream().write(json.getBytes());
                }
            }
            return;
        }

        if (requestType.equals(REQUEST_TYPE_APP)) {
            if (appID == null) {
                String statusFilter = syncInput.getParameter("status");
                boolean allUsers = SESSION_LIST_VIEW_ALL.equals(view);
                String json = listSessions(SessionAction.TYPE_DESKTOP_APP, statusFilter, allUsers);
                syncOutput.setHeader("Content-Type", "application/json");
                syncOutput.getOutputStream().write(json.getBytes());
            } else if (sessionID == null){
                throw new IllegalArgumentException("Missing session ID for desktop-app ID " + appID);
            } else {
                String json = getSingleDesktopApp(sessionID, appID);
                syncOutput.setHeader("Content-Type", "application/json");
                syncOutput.getOutputStream().write(json.getBytes());
            }
        }
    }

    private ResourceStats getResourceStats() throws Exception {
        // report stats on sessions and resources
        List<Session> sessions = getAllSessions(null);
        int desktopCount = filter(sessions, "desktop-app", "Running").size();
        int headlessCount = filter(sessions, "headless", "Running").size();
        int totalCount = filter(sessions, null, "Running").size();
        String k8sNamespace = K8SUtil.getWorkloadNamespace();
        try {
            double requestedCPUCores = 0.0;
            double coresAvailable = 0.0;
            double requestedRAM = 0.0;
            double ramAvailable = 0.0;
            double maxCores = 0.0;
            long maxRAM = 0;
            double withCores = 0.0;
            String withRAM = "0G";
            Map<String, Map<String, Double>> nodeResourcesMap = getNodeResources(k8sNamespace);
            Map<String, String[]> aResourceMap = getAvailableResources(k8sNamespace);
            List<String> nodeNames = new ArrayList<>(nodeResourcesMap.keySet());
            for (String nodeName : nodeNames) {
                String[] aResources = aResourceMap.get(nodeName);
                if (aResources != null) {
                    final double aCPUCores = Double.parseDouble(aResources[0]);
                    if (aCPUCores > maxCores) {
                        maxCores = aCPUCores;
                        withRAM = toCommonUnit(aResources[1]);
                    }

                    long aRAM = normalizeToLong(toCommonUnit(aResources[1]));
                    if (aRAM > maxRAM) {
                        maxRAM = aRAM;
                        withCores = aCPUCores;
                    }

                    Map<String, Double> resourcesMap = nodeResourcesMap.get(nodeName);
                    requestedCPUCores = requestedCPUCores + resourcesMap.get(REQ_CPU_CORES_KEY);
                    requestedRAM = requestedRAM + resourcesMap.get(REQ_RAM_KEY);
                    coresAvailable = coresAvailable + aCPUCores;
                    ramAvailable = ramAvailable + aRAM;
                }
            }

            // amount of RAM is in 'Ki' unit, use the commonly accepted 'K' unit
            /*
            String withRAMStr = String.valueOf(formatter.format(Double.valueOf(normalizeToLong(withRAM))/(1024 * 1024 * 1024))) + "G";
            String maxRAMStr = String.valueOf(formatter.format(Double.valueOf(maxRAM)/(1024 * 1024 * 1024))) + "G";
            */
            String withRAMStr = formatter.format(Double.valueOf(
                    (double) (normalizeToLong(withRAM)) / (1024 * 1024 * 1024))) + "G";
            String maxRAMStr = formatter.format(Double.valueOf((double) (maxRAM) / (1024 * 1024 * 1024))) + "G";
            String requestedRAMStr = formatter.format(requestedRAM) + "G";
            String ramAvailableStr = formatter.format(Double.valueOf((double) (ramAvailable) / (1024 * 1024 * 1024))) + "G";
            return new ResourceStats(desktopCount, headlessCount, totalCount, requestedCPUCores, requestedRAMStr, 
                                     coresAvailable, ramAvailableStr, maxCores, withRAMStr, maxRAMStr, withCores);
        } catch (Exception e) {
            log.error(e);
            throw new IllegalStateException("failed to gather resource statistics", e);
        }
    }

    protected long normalizeToLong(String ramString) {
        long value = 0;
        char unit = ramString.charAt(ramString.length() - 1);
        if (VALID_RAM_UNITS.contains(unit)) {
            value = Integer.parseInt(ramString.substring(0, ramString.length() - 1));
            unit = Character.toUpperCase(unit);
            if ('K' == unit) {
                value = value * 1024;
            } else if ('M' == unit) {
                value = value * 1024 * 1024;
            } else if ('G' == unit) {
                value = value * 1024 * 1024 * 1024;
            } else if ('T' == unit) {
                value = value * 1024 * 1024 * 1024 * 1024;
            }
        } else if (Character.isDigit(unit)) {
            value = Integer.parseInt(ramString);
        } else {
            throw new IllegalStateException("unknown RAM unit: " + unit);
        }

        return value;
    }

    private Map<String, Map<String, Double>> getNodeResources(String k8sNamespace) throws Exception {
        String getCPUCoresCmd = "kubectl -n " + k8sNamespace + " get pods --no-headers=true -o custom-columns=" +
                                "NODENAME:.spec.nodeName,PODNAME:.metadata.name," + 
                                "REQCPUCORES:.spec.containers[].resources.requests.cpu," +
                                "REQRAM:.spec.containers[].resources.requests.memory " +
                                "--field-selector status.phase=Running --sort-by=.spec.nodeName";
        String cpuCores = execute(getCPUCoresCmd.split(" "));
        Map<String, Map<String, Double>> nodeToResourcesMap = new HashMap<>();
        if (StringUtil.hasLength(cpuCores)) {
            String[] lines = cpuCores.split("\n");
            if (lines.length > 0) {
                Map<String, Double> resourcesMap = initResourcesMap();
                String nodeName = "";
                for (String line : lines) {
                    String[] parts = line.split("\\s+");
                    if (nodeName.equals(parts[0])) {
                        resourcesMap = getResourcesMap(resourcesMap, parts);
                    } else {
                        if (nodeName.length() > 0) {
                            // processing first line of a subsequent nodeName
                            nodeToResourcesMap.put(nodeName, resourcesMap);
                            resourcesMap = initResourcesMap();
                            nodeName = parts[0];
                            resourcesMap = getResourcesMap(resourcesMap, parts);
                        } else {
                            // processing first line of first nodeName
                            nodeName = parts[0];
                            resourcesMap = getResourcesMap(resourcesMap, parts);
                        }
                    }
                }

                // processing last line of the last nodeName
                nodeToResourcesMap.put(nodeName, resourcesMap);
            }
        }

        return nodeToResourcesMap;
    }

    private Map<String, Double> initResourcesMap() {
        Map<String, Double> rMap = new HashMap<>();
        rMap.put(REQ_CPU_CORES_KEY, Double.valueOf(0L));
        rMap.put(REQ_RAM_KEY, Double.valueOf(0L));
        return rMap;
    }
    
    private Map<String, Double> getResourcesMap(Map<String, Double> resourcesMap, String[] resources) {
        if (!NONE.equalsIgnoreCase(resources[2])) {
            resourcesMap.put(REQ_CPU_CORES_KEY, resourcesMap.get(REQ_CPU_CORES_KEY) + Double.parseDouble(toCoreUnit(resources[2])));
            log.debug("Node: " + resources[0] + " " + REQ_CPU_CORES_KEY + ": "+ resourcesMap.get(REQ_CPU_CORES_KEY));
        }

        if (!NONE.equalsIgnoreCase(resources[3])) {
            resourcesMap.put(REQ_RAM_KEY, resourcesMap.get(REQ_RAM_KEY) + Double.valueOf((double) (normalizeToLong(toCommonUnit(resources[3]))) / (1024 * 1024 * 1024)));
            log.debug("Node: " + resources[0] + " " + REQ_RAM_KEY + ": "+ resourcesMap.get(REQ_RAM_KEY));
        }
        
        return resourcesMap;
    }
    
    private Map<String, String[]> getAvailableResources(String k8sNamespace) throws Exception {
        String getAvailableResourcesCmd = "kubectl -n " + k8sNamespace + " describe nodes ";
        String rawResources = execute(getAvailableResourcesCmd.split(" "));
        Map<String, String[]> nodeToResourcesMap = new HashMap<>();
        if (StringUtil.hasLength(rawResources)) {
            String[] lines = rawResources.split("\n");
            if (lines.length > 0) {
                List<String> keywords = Arrays.asList("Name:", "Capacity:", "cpu:", "memory:", "nvidia.com/gpu:", "Allocatable:");
                String nodeName = "";
                boolean hasName = false;
                boolean hasCapacity = false;
                boolean hasAllocatable = false;
                boolean hasCores = false;
                boolean hasRAM = false;
                boolean hasGPU = false;
                String[] resources = null;
                for (String line : lines) {
                    String[] parts = line.replaceAll("\\s+", " ").trim().split(" ");
                    if (keywords.stream().anyMatch(parts[0]::equals)) {
                        if ("Name:".equals(parts[0])) {
                            // start processing a new node
                            hasName = true;
                            hasCapacity = false;
                            hasAllocatable = false;
                            hasCores = false;
                            hasRAM = false;
                            hasGPU = false;
                            resources = new String[2];
                            nodeName = parts[1];
                        } else if (!hasAllocatable && !hasGPU && hasName) {
                            if ("Capacity:".equals(parts[0])) {
                                if (!hasCapacity && !hasCores && !hasRAM) {
                                    hasCapacity = true;
                                } else {
                                    throw new IllegalStateException("Unexpected 'Capabity:' line");
                                }
                            } else if (hasCapacity) {
                                if ("cpu:".equals(parts[0])) {
                                    if (!hasCores) {
                                        // number of cores from "Capability.cpu",
                                        String cores = toCoreUnit(parts[1]);
                                        resources[0] = cores;
                                        hasCores = true;
                                    } else {
                                        throw new IllegalStateException("Unexpected 'cpu:' line");
                                    }
                                } else if ("memory:".equals(parts[0])) {
                                    if (!hasRAM) {
                                        // amount of RAM from "Capability.memory" is in Ki
                                        String ram = parts[1];
                                        resources[1] = ram;

                                        hasRAM = true;
                                    } else {
                                        throw new IllegalStateException("Unexpected 'memory:' line");
                                    }
                                } else if ("nvidia.com/gpu:".equals(parts[0])) {
                                    if (Integer.parseInt(parts[1]) > 0) {
                                        // we do not count resources on a node with nvidia gpu
                                        hasGPU = true;
                                    }
                                } else if ("Allocatable:".equals(parts[0]) && hasCores && hasRAM) {
                                    // finish processing the resources of a node
                                    nodeToResourcesMap.put(nodeName, resources);
                                    hasAllocatable = true;
                                    // TODO: add RAM unit to debug message
                                    log.debug("node: " + nodeName + ", cores=" + resources[0]
                                              + ", RAM=" + resources[1]);

                                }
                            }
                        }
                    }
                }
            }
        }

        return nodeToResourcesMap;
    }
    
    public String getSingleDesktopApp(String sessionID, String appID) throws Exception {
        Session session = this.getDesktopApp(userID, sessionID, appID);
        Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
        return gson.toJson(session);
    }
    
    public String getSingleSession(String sessionID) throws Exception {
        Session session = this.getSession(userID, sessionID);
        Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
        return gson.toJson(session);
    }

    public String listSessions(String typeFilter, String statusFilter, boolean allUsers) throws Exception {

        final String forUser = allUsers ? null : userID;
        final List<Session> sessions = getAllSessions(forUser);

        log.debug("typeFilter=" + typeFilter);
        log.debug("statusFilter=" + statusFilter);

        final List<Session> filteredSessions = filter(sessions, typeFilter, statusFilter);

        // if for all users, only show public information
        String json;
        final Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
        if (allUsers) {
            final List<PublicSession> publicSessions = new ArrayList<>(filteredSessions.size());
            for (final Session s : filteredSessions) {
                publicSessions.add(new PublicSession(s.getUserid(), s.getType(), s.getStatus(), s.getStartTime()));
            }
            json = gson.toJson(publicSessions);
        } else {
            json = gson.toJson(filteredSessions);
        }

        return json;
    }

    public List<Session> filter(List<Session> sessions, String typeFilter, String statusFilter) {
        List<Session> ret = new ArrayList<>();
        for (Session session : sessions) {
            if ((typeFilter == null || session.getType().equalsIgnoreCase(typeFilter)) &&
                (statusFilter == null || session.getStatus().equalsIgnoreCase(statusFilter))) {
                ret.add(session);
            }
        }
        return ret;
    }

    public String getEventLogs(String sessionID) throws Exception {
        String events = getEvents(userID, sessionID);
        if (!StringUtil.hasLength(events)) {
            events = NONE;
        }
        return events + "\n";
    }

    public void streamContainerLogs(String sessionID, OutputStream out) throws Exception {
        streamPodLogs(userID, sessionID, out);
    }
}
