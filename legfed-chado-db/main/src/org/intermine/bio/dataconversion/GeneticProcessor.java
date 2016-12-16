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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.Set;

import org.apache.log4j.Logger;

import org.ncgr.pubmed.PubMedSearch;

import org.intermine.bio.util.OrganismData;
import org.intermine.objectstore.ObjectStoreException;
import org.intermine.xml.full.Attribute;
import org.intermine.xml.full.Item;
import org.intermine.xml.full.Reference;

/**
 * Store the various genetic data from the LegFed/LIS chado database.
 * These come from featuremap, featurepos, featureloc, feature_relationship and, of course, feature.
 *
 * Since this processer deals only with chado data, Items are stored in maps with Integer keys equal to
 * the chado feature.feature_id.
 *
 * @author Sam Hokin, NCGR
 */
public class GeneticProcessor extends ChadoProcessor {
	
    private static final Logger LOG = Logger.getLogger(GeneticProcessor.class);

    /**
     * Create a new GeneticProcessor
     * @param chadoDBConverter the ChadoDBConverter that is controlling this processor
     */
    public GeneticProcessor(ChadoDBConverter chadoDBConverter) {
        super(chadoDBConverter);
    }

    /**
     * {@inheritDoc}
     * We process the chado database by reading the feature, featureloc, featurepos, featuremap, feature_relationship and featureprop tables.
     */
    @Override
    public void process(Connection connection) throws SQLException, ObjectStoreException {
        
        // initialize our DB statement
        Statement stmt = connection.createStatement();
        ResultSet rs; // general-purpose use
        String query; // general-purpose use
        
        // ---------------------------------------------------------
        // ---------------- INITIAL DATA LOADING -------------------
        // ---------------------------------------------------------
        
        // set the cv terms for our items of interest
        String geneCVTerm = "gene";
        String linkageGroupCVTerm = "linkage_group";
        String geneticMarkerCVTerm = "genetic_marker";
        String qtlCVTerm = "QTL";
        String consensusRegionCVTerm = "consensus_region";
        
        // build the Organism map from the supplied taxon IDs
        Map<Integer,Item> organismMap = new HashMap<Integer,Item>();
        Map<Integer,OrganismData> chadoToOrgData = getChadoDBConverter().getChadoIdToOrgDataMap();
        for (Map.Entry<Integer,OrganismData> entry : chadoToOrgData.entrySet()) {
            Integer organismId = entry.getKey();
            OrganismData organismData = entry.getValue();
            int taxonId = organismData.getTaxonId();
            Item organism = getChadoDBConverter().createItem("Organism");
            BioStoreHook.setSOTerm(getChadoDBConverter(), organism, "organism", getChadoDBConverter().getSequenceOntologyRefId());
            organism.setAttribute("taxonId", String.valueOf(taxonId));
            store(organism);
            organismMap.put(organismId, organism);
        }
        LOG.info("Created and stored "+organismMap.size()+" organism Items.");

        // create a comma-separated list of organism IDs for WHERE organism_id IN query clauses
        String organismSQL = "(";
        boolean first = true;
        for (Integer organismId : organismMap.keySet()) {
            if (first) {
                organismSQL += organismId;
                first = false;
            } else {
                organismSQL += ","+organismId;
            }
        }
        organismSQL += ")";

        // store the Items in maps
        Map<Integer,Item> linkageGroupMap = new HashMap<Integer,Item>();
        Map<Integer,Item> geneticMarkerMap = new HashMap<Integer,Item>();
        Map<Integer,Item> qtlMap = new HashMap<Integer,Item>();
        Map<Integer,Item> geneticMapMap = new HashMap<Integer,Item>();

        // loop over the organisms to fill the Item maps 
        for (Map.Entry<Integer,Item> orgEntry : organismMap.entrySet()) {
            int organism_id = orgEntry.getKey().intValue();
            Item organism = orgEntry.getValue();

            // fill these maps from the feature table
            populateMap(stmt, linkageGroupMap, "LinkageGroup", linkageGroupCVTerm, organism_id, organism);
            populateMap(stmt, geneticMarkerMap, "GeneticMarker", geneticMarkerCVTerm, organism_id, organism);
            populateMap(stmt, qtlMap, "QTL", qtlCVTerm, organism_id, organism);

            // genetic maps are not in the feature table, so we use linkage groups to query them for this organism
            // we'll assume the units are cM, although we can query them if we want to be really pedantic
            query = "SELECT * FROM featuremap WHERE featuremap_id IN (" +
                "SELECT DISTINCT featuremap_id FROM featurepos WHERE feature_id IN (" +
                "SELECT feature_id FROM feature,cvterm WHERE organism_id="+organism_id+" AND type_id=cvterm_id AND cvterm.name='"+linkageGroupCVTerm+"'))";
            LOG.info("executing query: "+query);
            rs = stmt.executeQuery(query);
            while (rs.next()) {
                // featuremap fields
                int featuremap_id = rs.getInt("featuremap_id");
                String name = rs.getString("name");
                String description = rs.getString("description");
                // build the GeneticMap item
                Item geneticMap = getChadoDBConverter().createItem("GeneticMap");
                // genetic map has no SO term
                geneticMap.setAttribute("primaryIdentifier", name);
                geneticMap.setAttribute("description", description);
                geneticMap.setAttribute("unit", "cM");
                geneticMap.setReference("organism", organism);
                // put it in its map for further processing
                geneticMapMap.put(new Integer(featuremap_id), geneticMap);
            }
            rs.close();
        }

        LOG.info("Created "+linkageGroupMap.size()+" LinkageGroup items.");
        LOG.info("Created "+geneticMarkerMap.size()+" GeneticMarker items.");
        LOG.info("Created "+qtlMap.size()+" QTL items.");
        LOG.info("Created "+geneticMapMap.size()+" GeneticMap items.");

        //---------------------------------------------------------
        // ---------------- DATA ASSOCIATION ----------------------
        //---------------------------------------------------------

        // query the pub table to retrieve and link Publications that are referenced by our genetic maps in featuremap_pub
        Map<Integer,Item> gmPubMap = new HashMap<Integer,Item>();
        for (Map.Entry<Integer,Item> entry : geneticMapMap.entrySet()) {
            int featuremap_id = (int) entry.getKey();
            Item geneticMap = entry.getValue();
            ResultSet rs1 = stmt.executeQuery("SELECT * FROM pub WHERE pub_id IN (SELECT pub_id FROM featuremap_pub WHERE featuremap_id="+featuremap_id+")");
            while (rs1.next()) {
                Item publication;
                int pub_id = rs1.getInt("pub_id");
                if (gmPubMap.containsKey(new Integer(pub_id))) {
                    // pub already exists, so get from the map
                    publication = gmPubMap.get(new Integer(pub_id));
                } else {
                    // create it, set the attributes from the ResultSet, add it to its map
                    publication = getChadoDBConverter().createItem("Publication");
                    setPublicationAttributes(publication, rs1);
                    gmPubMap.put(new Integer(pub_id), publication);
                }
                // add the GeneticMap reference to the Publication (reverse-referenced)
                publication.addToCollection("bioEntities", geneticMap);
            }
            rs1.close();
        }
        LOG.info("***** Done creating "+gmPubMap.size()+" Publication items associated with genetic maps.");

        // query the pub table to retrieve and link Publications that are referenced by our QTLs in feature_cvterm
        Map<Integer,Item> qtlPubMap = new HashMap<Integer,Item>();
        for (Map.Entry<Integer,Item> entry : qtlMap.entrySet()) {
            int feature_id = entry.getKey().intValue();
            Item qtl = entry.getValue();
            rs = stmt.executeQuery("SELECT * FROM pub WHERE pub_id IN (SELECT pub_id FROM feature_cvterm WHERE feature_id="+feature_id+")");
            while (rs.next()) {
                Item publication;
                Integer pubId = new Integer(rs.getInt("pub_id"));
                if (qtlPubMap.containsKey(pubId)) {
                    // pub already exists, so get from the map
                    publication = qtlPubMap.get(pubId);
                } else {
                    // create it, set the attributes from the ResultSet, add it to its map
                    publication = getChadoDBConverter().createItem("Publication");
                    setPublicationAttributes(publication, rs);
                    qtlPubMap.put(pubId, publication);
                }
                // add the QTL reference to the Publication (reverse-referenced)
                publication.addToCollection("bioEntities", qtl);
            }
            rs.close();
        }
        LOG.info("***** Done creating "+qtlPubMap.size()+" Publication items associated with QTLs.");
        
        // query the featurepos table for linkage groups and genetic markers associated with our genetic maps
        // Note: for linkage groups, ignore the mappos=0 start entry; use mappos>0 entry to get the length of the linkage group
        for (Map.Entry<Integer,Item> entry : geneticMapMap.entrySet()) {
            int featuremap_id = (int) entry.getKey(); // genetic map
            Item geneticMap = entry.getValue();
            // genetic markers
            ResultSet rs1 = stmt.executeQuery("SELECT * FROM featurepos WHERE featuremap_id="+featuremap_id+" AND feature_id<>map_feature_id");
            while (rs1.next()) {
                int feature_id = rs1.getInt("feature_id"); // genetic marker
                int map_feature_id = rs1.getInt("map_feature_id"); // linkage group
                double position = rs1.getDouble("mappos");
                Item geneticMarker = geneticMarkerMap.get(new Integer(feature_id));
                Item linkageGroup = linkageGroupMap.get(new Integer(map_feature_id));
                if (geneticMarker!=null) {
                    geneticMap.addToCollection("geneticMarkers", geneticMarker);
                    if (linkageGroup!=null) {
                        linkageGroup.addToCollection("geneticMarkers", geneticMarker);
                        Item linkageGroupPosition = getChadoDBConverter().createItem("LinkageGroupPosition");
                        linkageGroupPosition.setAttribute("position", String.valueOf(position));
                        linkageGroupPosition.setReference("linkageGroup", linkageGroup);
                        store(linkageGroupPosition); // store it now, no further changes
                        geneticMarker.addToCollection("linkageGroupPositions", linkageGroupPosition);
                    }
                }
            }
            rs1.close();
            // linkage groups
            ResultSet rs2 = stmt.executeQuery("SELECT * FROM featurepos WHERE featuremap_id="+featuremap_id+" AND feature_id=map_feature_id AND mappos>0");
            while (rs2.next()) {
                int feature_id = rs2.getInt("feature_id"); // linkage group
                double length = rs2.getDouble("mappos");
                Item linkageGroup = linkageGroupMap.get(new Integer(feature_id));
                if (linkageGroup!=null) {
                    linkageGroup.setAttribute("length", String.valueOf(length));
                    linkageGroup.setReference("geneticMap", geneticMap);
                }
            }
            rs2.close();
        }
        LOG.info("***** Done associating genetic maps and genetic markers and linkage groups from featurepos.");
        
        // query the featureloc table for the linkage group range (begin, end) per QTL
        // NOTE 1: Ethy hack: fmin,fmax are actually begin,end in cM x 100, so divide by 100 for cM!
        for (Map.Entry<Integer,Item> entry : qtlMap.entrySet()) {
            int feature_id = (int) entry.getKey(); // QTL in featureloc
            Item qtl = entry.getValue();
            // the Big Query to get the specific linkage group and flanking markers
            ResultSet rs1 = stmt.executeQuery("SELECT * FROM featureloc WHERE feature_id="+feature_id);
            while (rs1.next()) {
                int linkage_group_id = rs1.getInt("srcfeature_id");
                double begin = rs1.getDouble("fmin")/100.0; // Ethy hack to store cM locations as integers
                double end = rs1.getDouble("fmax")/100.0;
                Item linkageGroup = linkageGroupMap.get(new Integer(linkage_group_id));
                if (linkageGroup!=null) {
                    Item linkageGroupRange = getChadoDBConverter().createItem("LinkageGroupRange");
                    linkageGroupRange.setAttribute("begin", String.valueOf(begin));
                    linkageGroupRange.setAttribute("end", String.valueOf(end));
                    linkageGroupRange.setAttribute("length", String.valueOf(round(end-begin,2)));
                    linkageGroupRange.setReference("linkageGroup", linkageGroup);
                    store(linkageGroupRange); // we're done with it
                    qtl.addToCollection("linkageGroupRanges", linkageGroupRange);
                    linkageGroup.addToCollection("QTLs", qtl);
                }
            }
            rs1.close();
        }
        LOG.info("***** Done populating QTLs with linkage group ranges from featureloc.");

        // Query the feature_relationship table for direct relations between QTLs and genetic markers
        for (Map.Entry<Integer,Item> entry : qtlMap.entrySet()) {
            int subject_id = (int) entry.getKey(); // QTL in feature_relationship
            Item qtl = entry.getValue();
            // int[] geneticMarkerIds = new int[3]; // there is one (nearest marker) or three (nearest, flanking low, flanking high)
            // int k = 0; // index of geneticMarkerIds
            ResultSet rs1 = stmt.executeQuery("SELECT * FROM feature_relationship WHERE subject_id="+subject_id);
            while (rs1.next()) {
                int object_id = rs1.getInt("object_id"); // genetic marker
                int type_id = rs1.getInt("type_id"); // Nearest, Flanking Low, Flanking High; we don't store this at present
                Item geneticMarker = geneticMarkerMap.get(new Integer(object_id));
                if (geneticMarker!=null) {
                    // add to QTL's associatedGeneticMarkers collection; reverse-reference will automatically associate qtl in genetic marker's QTLs collection
                    qtl.addToCollection("associatedGeneticMarkers", geneticMarker);
                    // store genetic marker for gene finding to come
                    // geneticMarkerIds[k++] = object_id;
                }
            }
            rs1.close(); // done collecting genetic markers
            
        } // QTL loop
        LOG.info("***** Done populating QTLs with genetic markers from feature_relationship.");
            
        // ----------------------------------------------------------------
        // we're done, so store everything
        // ----------------------------------------------------------------

        LOG.info("Storing genetic maps...");
        store(geneticMapMap.values());

        LOG.info("Storing genetic map publications...");
        store(gmPubMap.values());

        LOG.info("Storing linkage groups...");
        store(linkageGroupMap.values());

        LOG.info("Storing genetic markers...");
        store(geneticMarkerMap.values());
 
        LOG.info("Storing QTLs...");
        store(qtlMap.values());

        LOG.info("Storing QTL publications...");
        store(qtlPubMap.values());

    }

    /**
     * Store the item.
     * @param item the Item
     * @return the database id of the new Item
     * @throws ObjectStoreException if an error occurs while storing
     */
    protected Integer store(Item item) throws ObjectStoreException {
        return getChadoDBConverter().store(item);
    }
    
    /**
     * Do any extra processing that is needed before the converter starts querying features
     * @param connection the Connection
     * @throws ObjectStoreException if there is a object store problem
     * @throws SQLException if there is a database problem
     */
    protected void earlyExtraProcessing(Connection connection) throws ObjectStoreException, SQLException {
        // override in subclasses as necessary
    }

    /**
     * Do any extra processing for this database, after all other processing is done
     * @param connection the Connection
     * @param featureDataMap a map from chado feature_id to data for that feature
     * @throws ObjectStoreException if there is a problem while storing
     * @throws SQLException if there is a problem
     */
    protected void extraProcessing(Connection connection, Map<Integer, FeatureData> featureDataMap)
        throws ObjectStoreException, SQLException {
        // override in subclasses as necessary
    }

    /**
     * Perform any actions needed after all processing is finished.
     * @param connection the Connection
     * @param featureDataMap a map from chado feature_id to data for that feature
     * @throws SQLException if there is a problem
     */
    protected void finishedProcessing(Connection connection, Map<Integer, FeatureData> featureDataMap) throws SQLException {
        // override in subclasses as necessary
    }

    /**
     * Set the attributes of the given publication Item from the given pub ResultSet.
     */
    void setPublicationAttributes(Item publication, ResultSet rs) throws SQLException {
        String title = rs.getString("title");
        String volume = rs.getString("volume");
        String journal = rs.getString("series_name");
        String issue = rs.getString("issue");
        String year = rs.getString("pyear");
        String pages = rs.getString("pages");
        String uniquename = rs.getString("uniquename");
        String[] parts = uniquename.split("et al");
        if (parts.length==1) parts = parts[0].split(",");
        String firstAuthor = parts[0];
        int pubMedId = 0;
        try {
            // search for the PubMedId, hopefully find it with just first author
            String[] authors = new String[1];
            authors[0] = firstAuthor;
            pubMedId = PubMedSearch.getPubMedId(journal, Integer.parseInt(year), authors);
        } catch (Exception ex) {
            // do nothing
        }
        // build the Publication item
        if (title!=null && title.length()>0 && !title.equals("NULL")) publication.setAttribute("title", title);
        if (volume!=null && volume.length()>0 && !volume.equals("NULL")) publication.setAttribute("volume", volume);
        if (journal!=null && journal.length()>0 && !journal.equals("NULL")) publication.setAttribute("journal", journal);
        if (issue!=null && issue.length()>0 && !issue.equals("NULL")) publication.setAttribute("issue", issue);
        if (year!=null && year.length()>0 && !year.equals("NULL")) publication.setAttribute("year", year);
        if (pages!=null && pages.length()>0 && !pages.equals("NULL")) publication.setAttribute("pages", pages);
        if (firstAuthor!=null && firstAuthor.length()>0) publication.setAttribute("firstAuthor", firstAuthor);
        if (pubMedId!=0) publication.setAttribute("pubMedId", String.valueOf(pubMedId));
    }

    /**
     * Round a double to the given number of places
     */
    public static double round(double value, int places) {
        if (places < 0) throw new IllegalArgumentException();
        BigDecimal bd = new BigDecimal(value);
        bd = bd.setScale(places, RoundingMode.HALF_UP);
        return bd.doubleValue();
    }

    /**
     * Populate a map with records from the feature table
     */
    void populateMap(Statement stmt, Map<Integer,Item> map, String className, String cvTerm, int organism_id, Item organism) throws SQLException {
        ResultSet rs = stmt.executeQuery("SELECT feature.* FROM feature,cvterm WHERE organism_id="+organism_id+" AND type_id=cvterm_id AND cvterm.name='"+cvTerm+"'");
        while (rs.next()) {
            ChadoFeature cf = new ChadoFeature(rs);
            Item item = getChadoDBConverter().createItem(className);
            cf.populateBioEntity(item, organism);
            BioStoreHook.setSOTerm(getChadoDBConverter(), item, cvTerm, getChadoDBConverter().getSequenceOntologyRefId());
            map.put(new Integer(cf.feature_id), item);
        }
        rs.close();
    }


}
