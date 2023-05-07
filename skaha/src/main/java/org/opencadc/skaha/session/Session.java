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

import org.apache.log4j.Logger;

/**
 * @author majorb
 * 
 * Represents a session running in skaha.
 *
 */
public class Session {
    
    private static final Logger log = Logger.getLogger(Session.class);
    
    public static final String STATUS_TERMINATING = "Terminating";
    public static final String STATUS_SUCCEEDED = "Succeeded";
    
    private String id;
    private String userid;
    private String appid;
    private String image;
    private String type;
    private String status;
    private String name;
    private String startTime;
    private String expiryTime;            // in seconds
    private String connectURL;
    private String requestedRAM;
    private String requestedCPUCores;
    private String requestedGPUCores;
    private String ramInUse;
    private String gpuRAMInUse;
    private String cpuCoresInUse;
    private String gpuUtilization;

    public Session(String id, String userid, String image, String type, String status, String name, String startTime, String connectURL) {
        if (id == null) {
            throw new IllegalArgumentException("id is requried");
        }
        this.id = id;
        this.userid = userid;
        this.image = image;
        this.type = type;
        this.status = status;
        this.name = name;
        this.startTime = startTime;
        this.connectURL = connectURL;
    }
    
    public String getId() {
        return id;
    }
    
    public String getUserid() {
        return userid;
    }
    
    public String getImage() {
        return image;
    }

    public String getType() {
        return type;
    }

    public String getStatus() {
        return status;
    }

    public String getName() {
        return name;
    }

    public String getStartTime() {
        return startTime;
    }

    public String getConnectURL() {
        return connectURL;
    }
    
    public String getRequestedRAM() {
        return requestedRAM;
    }
    
    public void setRequestedRAM(String ram) {
        this.requestedRAM = ram;
    }

    public String getRequestedCPUCores() {
        return requestedCPUCores;
    }

    public void setRequestedCPUCores(String cores) {
        this.requestedCPUCores = cores;
    }

    public String getRequestedGPUCores() {
        return requestedGPUCores;
    }

    public void setRequestedGPUCores(String cores) {
        this.requestedGPUCores = cores;
    }

    public String getCPUCoresInUse() {
        return cpuCoresInUse;
    }

    public void setCPUCoresInUse(String cores) {
        this.cpuCoresInUse = cores;
    }

    public String getGPUUtilization() {
        return gpuUtilization;
    }

    public void setGPUUtilization(String util) {
        this.gpuUtilization = util;
    }

    public String getGPURAMInUse() {
        return gpuRAMInUse;
    }

    public void setGPURAMInUse(String util) {
        this.gpuRAMInUse = util;
    }

    public String getRAMInUse() {
        return ramInUse;
    }

    public void setRAMInUse(String memory) {
        this.ramInUse = memory;
    }

    public String getExpiryTime() {
        return expiryTime;
    }

    public void setExpiryTime(String timeInSeconds) {
        this.expiryTime = timeInSeconds;
    }
    
    public String getAppId() {
        return appid;
    }
    
    public void setAppId(String appId) {
        this.appid = appId;
    }
    
    @Override
    public boolean equals(Object o) {
        if (o instanceof Session) {
            Session s = (Session) o;
            return this.id.equals(s.id);
        }
        return false;
    }
    
    @Override
    public String toString() {
        return String.format(
            "Session[id=%s,userid=%s,image=%s,type=%s,status=%s,name=%s,startTime=%s,connectURL=%s]",
            id, userid, image, type, status, name, startTime, connectURL);
    }
    
}
