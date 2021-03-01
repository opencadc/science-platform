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
package org.opencadc.skaha.image;

import ca.nrc.cadc.net.HttpGet;
import ca.nrc.cadc.rest.InlineContentHandler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.opencadc.skaha.SkahaAction;

/**
 * @author majorb
 *
 */
public class GetAction extends SkahaAction {
    
    private static final Logger log = Logger.getLogger(GetAction.class);
    
    // Consider adding a cache
    Map<List<Image>, String> cache = Collections.synchronizedMap(
        new WeakHashMap<List<Image>, String>());
    
    public GetAction() {
        super();
    }

    @Override
    public void doAction() throws Exception {
        super.initRequest();
        
        String type = syncInput.getParameter("type");
        if (type != null && !SESSION_TYPES.contains(type)) {
            throw new IllegalArgumentException("unknown type: " + type);
        }
        
        String idToken = super.getIdToken();
        List<Image> images = getImages(idToken, type);
        Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
        String json = gson.toJson(images);
        
        syncOutput.setHeader("Content-Type", "application/json");
        syncOutput.getOutputStream().write(json.getBytes());
        
    }
        
    protected List<Image> getImages(String idToken, String typeFilter) throws Exception {
        
        List<Image> images = new ArrayList<Image>();
        
        for (String harborHost : super.harborHosts) {
            
            String projects = callHarbor(idToken, harborHost, null, null);
            JSONTokener tokener = new JSONTokener(projects);
            JSONArray jProjects = new JSONArray(tokener);
            
            // only projects to which the user has read access are returned
            for (int p=0; p<jProjects.length(); p++) {
                JSONObject jProject = jProjects.getJSONObject(p);
                String pName = jProject.getString("name");
                log.debug("processing project " + pName);
                
                String repos = callHarbor(idToken, harborHost, pName, null);
                JSONArray jRepos = new JSONArray(repos);

                for (int r=0; r<jRepos.length(); r++) {
                    JSONObject jRepo = jRepos.getJSONObject(r);
                    String rName = jRepo.getString("name");
                    // remove the leading project name and slash
                    String rNameShort = rName.substring(pName.length() + 1);
                    log.debug("processing repo " + rNameShort);
                    
                    String artifacts = callHarbor(idToken, harborHost, pName, rNameShort);
                    JSONArray jArtifacts = new JSONArray(artifacts);

                    for (int a=0; a<jArtifacts.length(); a++) {
                        JSONObject jArtifact = jArtifacts.getJSONObject(a);
                        
                        if (!jArtifact.isNull("labels")) {
                            JSONArray labels = jArtifact.getJSONArray("labels");
                            String type = getTypeFromLabels(labels);
                            if (type != null) {
                                if (typeFilter == null || typeFilter.equals(type)) {
                                    String digest = jArtifact.getString("digest");
                                    if (!jArtifact.isNull("tags")) {
                                        JSONArray tags = jArtifact.getJSONArray("tags");
                                        for (int j=0; j<tags.length(); j++) {
                                            JSONObject jTag = tags.getJSONObject(j);
                                            String tag = jTag.getString("name");
                                            String imageID = harborHost + "/" + rName + ":" + tag;
                                            Image image = new Image(imageID, type, digest);
                                            images.add(image);
                                            log.debug("Added image: " + imageID);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                }
            }
            
        }

        return images;
        
    }
    

    


    @Override
    protected InlineContentHandler getInlineContentHandler() {
        return null;
    }

}
