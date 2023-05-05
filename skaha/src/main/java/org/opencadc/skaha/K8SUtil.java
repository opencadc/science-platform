/*
************************************************************************
*******************  CANADIAN ASTRONOMY DATA CENTRE  *******************
**************  CENTRE CANADIEN DE DONNÉES ASTRONOMIQUES  **************
*
*  (c) 2019.                            (c) 2019.
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

package org.opencadc.skaha;


public class K8SUtil {

    static final String ARC_USER_QUOTA_IN_GB_NAME = "skaha.defaultquotagb";

    public static String getHostName() {
        return System.getenv("skaha.hostname");
    }

    /**
     * Helps reduce string constants in many places.
     * @return  The Skaha namespace
     */
    public static String getNamespace() {
        return "skaha-system";
    }

    public static String getWorkloadNamespace() {
        return System.getenv("skaha.namespace");
    }

    /**
     * Filter out anything not in the alphanumeric or hyphen character set.
     * @see <a href="https://kubernetes.io/docs/concepts/overview/working-with-objects/names/">Kubernetes Object names</a>
     * @param sessionID     The provided session ID.
     * @param type          The defined type (desktop, notebook, etc.)
     * @param userID        The running User's ID.
     * @return              String sanitized name.  Never null.
     */
    public static String getJobName(String sessionID, String type, String userID) {
        // Replace values that are NOT alphanumeric or a hyphen.
        final String userJobID = userID.replaceAll("[^0-9a-zA-Z-]", "-");
        return ("skaha-" + type + "-" + userJobID + "-" + sessionID).toLowerCase();
    }
    
    //skaha-notebook-svc-rdcc0219
    public static String getServiceName(String sessionID, String type) {
        return "skaha-" + type + "-svc-" + sessionID;
    }
    
    public static String getIngressName(String sessionID, String type) {
        return "skaha-" + type + "-ingress-" + sessionID;
    }
    
    public static String getMiddlewareName(String sessionID, String type) {
        return "skaha-" + type + "-middleware-" + sessionID;
    }
    
    public static String getHomeDir() {
        return System.getenv("skaha.homedir");
    }
    
    public static String getScratchDir() {
        return System.getenv("skaha.scratchdir");
    }

    public static String getSessionExpiry() {
        return System.getenv("skaha.sessionexpiry");
    }

    /**
     * Obtain the configured default quota size in Gigabytes.
     * @return  integer in GB.
     */
    public static String getDefaultQuota() {
        return System.getenv(K8SUtil.ARC_USER_QUOTA_IN_GB_NAME);
    }
}
