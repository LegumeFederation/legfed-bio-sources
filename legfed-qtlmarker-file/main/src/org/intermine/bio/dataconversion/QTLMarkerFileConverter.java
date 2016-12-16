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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;

import org.intermine.dataconversion.ItemWriter;
import org.intermine.metadata.Model;
import org.intermine.xml.full.Item;

/**
 * Store the QTL-marker data and relationships from a tab-delimited file.
 *
 * The file name gives the taxon ID of the organism. This allows you to have several files in a single src.data.dir 
 * that are run in a single invocation of this converter. The format is: anything-other-than-underscore_3845.txt.
 *
 * @author Sam Hokin, NCGR
 */
public class QTLMarkerFileConverter extends BioFileConverter {
	
    private static final Logger LOG = Logger.getLogger(QTLMarkerFileConverter.class);

    // store items at end in close() method to avoid dupes
    Set<Item> organismSet = new HashSet<Item>();
    Map<String,Item> qtlMap = new HashMap<String,Item>();
    Map<String,Item> markerMap = new HashMap<String,Item>();

    /**
     * Create a new QTLMarkerFileConverter
     * @param writer the ItemWriter to write out new items
     * @param model the data model
     */
    public QTLMarkerFileConverter(ItemWriter writer, Model model) {
        super(writer, model);
    }

    /**
     * Get the taxon ID from the current file name, e.g. soybean_3847.gff3
     */
    public String getTaxonId() {
        try {
            String fileName = getCurrentFile().getName();
            String[] chunks = fileName.split("_");
            String[] parts = chunks[1].split("\\.");
            return parts[0];
        } catch (Exception ex) {
            throw new RuntimeException("Could not parse GFF filename; format should be: no-underscores_12345.gff3 where taxonID=12345. "+ex.getMessage());
        }
    }

    /**
     * {@inheritDoc}
     * We process the QTL-marker relationships by reading in from a tab-delimited file.
     */
    @Override
    public void process(Reader reader) throws Exception {

        // don't process README files
        if (getCurrentFile().getName().equals("README")) return;

        LOG.info("Processing file "+getCurrentFile().getName()+"...");

        Item organism = createItem("Organism");
        BioStoreHook.setSOTerm(this, organism, "organism", getSequenceOntologyRefId());
        organism.setAttribute("taxonId", getTaxonId());
        organismSet.add(organism);

        // -------------------------------------------------------------------------------------------------
        // Run through the QTL-Markers file and add the associated markers to the given QTLs
        // NOTE1: marker ZZ is a placeholder, not a real marker
        // NOTE2: given names are used as _primary_ identifiers
        // -------------------------------------------------------------------------------------------------
        
        BufferedReader qtlMarkerReader = new BufferedReader(reader);
        String line = qtlMarkerReader.readLine(); // header
        while ((line=qtlMarkerReader.readLine())!=null) {

            QTLMarkerRecord rec = new QTLMarkerRecord(line);
            if (rec.qtlName!=null && rec.markerName!=null && !rec.markerName.equals("ZZ")) {

                // find the QTL in the map, or add it with qtlName=primaryIdentifier
                Item qtl = null;
                if (qtlMap.containsKey(rec.qtlName)) {
                    qtl = qtlMap.get(rec.qtlName);
                } else {
                    qtl = createItem("QTL");
                    BioStoreHook.setSOTerm(this, qtl, "QTL", getSequenceOntologyRefId());
                    qtl.setReference("organism", organism);
                    qtl.setAttribute("primaryIdentifier", rec.qtlName);
                    qtlMap.put(rec.qtlName, qtl);
                }
                
                // find the genetic marker in the map, or add it with markerName=primaryIdentifier
                Item marker = null;
                if (markerMap.containsKey(rec.markerName)) {
                    marker = markerMap.get(rec.markerName);
                } else {
                    marker = createItem("GeneticMarker");
                    BioStoreHook.setSOTerm(this, marker, "genetic_marker", getSequenceOntologyRefId());
                    marker.setReference("organism", organism);
                    marker.setAttribute("primaryIdentifier", rec.markerName);
                    markerMap.put(rec.markerName, marker);
                }
                // add this genetic marker to this QTL's collection
                LOG.info("Adding QTL="+rec.qtlName+" Genetic Marker="+rec.markerName+" to associatedMarkers.");
                qtl.addToCollection("associatedMarkers", marker);
            }

        }
            
        qtlMarkerReader.close();

    }

    /**
     * Store the items we've collected from the files
     */
    @Override
    public void close() throws Exception {

        LOG.info("Storing "+organismSet.size()+" organism items...");
        store(organismSet);
        
        LOG.info("Storing "+qtlMap.size()+" QTL items...");
        store(qtlMap.values());

        LOG.info("Storing "+markerMap.size()+" genetic marker items...");
        store(markerMap.values());

    }
    
}
