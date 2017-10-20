package org.intermine.bio.dataconversion;

/*
 * Copyright (C) 2015-2016 NCGR
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.io.BufferedReader;
import java.io.Reader;

import java.util.Map;
import java.util.HashMap;

import org.apache.log4j.Logger;

import org.ncgr.intermine.PublicationTools;
import org.ncgr.pubmed.PubMedSummary;

import org.intermine.dataconversion.ItemWriter;
import org.intermine.metadata.Model;
import org.intermine.xml.full.Item;

/**
 * Store genetic marker linkage group positions
 *
 * Marker LinkageGroup Position
 *
 * @author Sam Hokin, NCGR
 */
public class MarkerLinkageGroupFileConverter extends BioFileConverter {
	
    private static final Logger LOG = Logger.getLogger(MarkerLinkageGroupFileConverter.class);

    // store markers and linkage groups in maps for repeated use
    Map<String,Item> markerMap = new HashMap<String,Item>();
    Map<String,Item> linkageGroupMap = new HashMap<String,Item>();

    /**
     * Create a new MarkerLinkageGroupFileConverter
     * @param writer the ItemWriter to write out new items
     * @param model the data model
     */
    public MarkerLinkageGroupFileConverter(ItemWriter writer, Model model) {
        super(writer, model);
    }

    /**
     * {@inheritDoc}
     * Process the marker-linkage group relationships by reading in from a tab-delimited file.
     */
    @Override
    public void process(Reader reader) throws Exception {

        // don't process README files
        if (getCurrentFile().getName().equals("README")) return;

        LOG.info("Processing file "+getCurrentFile().getName()+"...");

        BufferedReader markerReader = new BufferedReader(reader);
	String line;
        while ((line=markerReader.readLine())!=null) {
            if (!line.startsWith("#")) {

                // parsing
                String[] parts = line.split("\t");
                String markerID = parts[0];
                String lgID = parts[1];
                double position = Double.parseDouble(parts[2]);

                // retrieve or create the marker item
                Item marker = null;
                if (markerMap.containsKey(markerID)) {
                    marker = markerMap.get(markerID);
                } else {
                    marker = createItem("GeneticMarker");
                    marker.setAttribute("primaryIdentifier", markerID);
                    markerMap.put(markerID, marker);
                }

                // retrieve or create the linkage group item, add this marker to its collection
                Item linkageGroup = null;
                if (linkageGroupMap.containsKey(lgID)) {
                    linkageGroup = linkageGroupMap.get(lgID);
                } else {
                    linkageGroup = createItem("LinkageGroup");
                    linkageGroup.setAttribute("primaryIdentifier", lgID);
                    linkageGroupMap.put(lgID, linkageGroup);
                }
                linkageGroup.addToCollection("geneticMarkers", marker);

                // create and store the LinkageGroupPosition item
                Item lgp = createItem("LinkageGroupPosition");
                lgp.setReference("linkageGroup", linkageGroup);
                lgp.setAttribute("position", String.valueOf(position));
                store(lgp);

                // associate this marker with this linkage group position
                marker.addToCollection("linkageGroupPositions", lgp);

            }
        }
        
        markerReader.close();

    }

    /**
     * Store the markers and linkage groups
     */
    @Override
    public void close() throws Exception {

        LOG.info("Storing "+markerMap.size()+" GeneticMarker items...");
        store(markerMap.values());

        LOG.info("Storing "+linkageGroupMap.size()+" LinkageGroup items...");
        store(linkageGroupMap.values());
        
    }
    
}