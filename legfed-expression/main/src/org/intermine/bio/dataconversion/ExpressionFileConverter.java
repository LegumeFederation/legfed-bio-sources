package org.intermine.bio.dataconversion;

/*
 * Copyright (C) 2002-2016 FlyMine, Legume Federation
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.io.File;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.Reader;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

import org.apache.log4j.Logger;
import org.intermine.dataconversion.ItemWriter;
import org.intermine.metadata.Model;
import org.intermine.objectstore.ObjectStoreException;
import org.intermine.xml.full.Item;

import org.ncgr.intermine.PubMedPublication;

/**
 * DataConverter to create ExpressionSource, ExpressionSample and ExpressionValue items from tab-delimited expression files.
 *
 * # this is a standard expression file to be read by InterMine source legfed-expression   
 * #
 * ID      GlycineAtlas
 * Description     RNA-Seq Atlas of Glycine max: a guide to the soybean transcriptome.
 * PMID    20687943
 * BioProject      PRJNA208048
 * SRA     SRP025919
 * GEO     GSE123456
 * Unit    TPM
 * URL     http://soybase.org/expression
 * # Number of samples: sample records follow immediately
 * Samples 14
 * # 4. Sample number, name and description in same order as the columns
 * 1       young_leaf      Young Leaf: 0.4 Leaflets unfurled (SOY:0000252)
 * 2       flower  Flower: F0.4 Open flower (SOY:0001277)
 * ...     ...
 * # Gene  [expression in order of samples above]
 * Glyma.20G056700 0.000   0.000   0.000   0.000   0.000   0.000   0.000   0.000   0.000   0.000   0.000   0.000   0.000   0.000
 * Glyma.20G056600 4.318   5.015   5.022   5.133   2.858   12.124  3.965   3.834   3.769   0.000   0.000   0.000   4.812   2.054
 * ...
 *
 * @author Sam Hokin
 */
public class ExpressionFileConverter extends BioFileConverter {
    
    private static final Logger LOG = Logger.getLogger(ExpressionFileConverter.class);

    // things stored at end
    Map<String,Item> geneMap = new HashMap<String,Item>();
    Set<Item> sourceSet = new HashSet<Item>();
    Set<Item> sampleSet = new HashSet<Item>();
    Set<Item> valueSet = new HashSet<Item>();
    
    /**
     * Constructor.
     *
     * @param writer the ItemWriter used to handle the resultant items
     * @param model the Model
     * @throws ObjectStoreException os
     */
    public ExpressionFileConverter(ItemWriter writer, Model model) throws ObjectStoreException {
        super(writer, model);
    }

    /**
     * Called for each file found by ant.
     *
     * {@inheritDoc}
     */
    public void process(Reader reader) throws Exception {

        if (getCurrentFile().getName().equals("README")) return;
        LOG.info("Processing expression file:"+getCurrentFile().getName()+"...");
        
        Item source = createItem("ExpressionSource");
        sourceSet.add(source);

        // quantities held over line reads
        String geneId = "";
        int numSamples = 0;
        double[] exprs = null;
        Item[] samples = null;
        Item[] values = null;
        
        BufferedReader br = new BufferedReader(reader);
        String line = null;
	int lineCount = 0;
        while ((line=br.readLine())!=null) {
	    lineCount++;
            if  (line.startsWith("#")) continue; // comment
            String[] parts = line.split("\t");
            if (parts[0].equals("ID")) {
                source.setAttribute("primaryIdentifier", parts[1]);
                LOG.info("Loading expression source:"+parts[1]);
            } else if (parts[0].equals("Description")) {
                source.setAttribute("description", parts[1]);
            } else if (parts[0].equals("BioProject")) {
                source.setAttribute("bioProject", parts[1]);
            } else if (parts[0].equals("SRA")) {
                source.setAttribute("sra", parts[1]);
            } else if (parts[0].equals("GEO")) {
                source.setAttribute("geo", parts[1]);
            } else if (parts[0].equals("URL")) {
                source.setAttribute("url", parts[1]);
            } else if (parts[0].equals("Unit")) {
                source.setAttribute("unit", parts[1]);
            } else if (parts[0].equals("PMID")) {
                // create and store the publication if it exists; this requires Internet access
                int id = Integer.parseInt(parts[1]);
                PubMedPublication pubMedPub = new PubMedPublication(this, id);
                Item publication = pubMedPub.getPublication();
                if (publication!=null) {
                    // store the authors and add them to the publication collection
                    for (Item author : pubMedPub.getAuthors()) {
                        store(author);
                        publication.addToCollection("authors", author);
                    }
                    // store the publication and add reference to ExpressionSource
                    store(publication);
                    source.setReference("publication", publication);
                }
            } else if (parts[0].equals("Samples")) {
                // load the samples into an array
                numSamples = Integer.parseInt(parts[1]); // if this doesn't parse, we should crash out
                samples = new Item[numSamples];
                values = new Item[numSamples];
                for (int i=0; i<numSamples; i++) {
                    String sampleLine = br.readLine();
                    String[] sampleParts = sampleLine.split("\t");
                    samples[i] = createItem("ExpressionSample");
                    samples[i].setAttribute("num", sampleParts[0]);
                    samples[i].setAttribute("primaryIdentifier", sampleParts[1]);
                    samples[i].setAttribute("description", sampleParts[2]);
                    samples[i].setReference("source", source);
                    sampleSet.add(samples[i]);
                }
            } else {
                // must be an expression line - could be a transcript ID or gene ID
                String transcriptId = parts[0];
                // get the gene ID from the transcript/gene identifier
                String thisGeneId = "";
		try {
		    if (transcriptId.charAt(transcriptId.length()-3)=='.') {            // Foobar12345.11
			thisGeneId = transcriptId.substring(0,transcriptId.length()-3);
                    } else if (transcriptId.charAt(transcriptId.length()-2)=='.') {     // Foobar12345.3
			thisGeneId = transcriptId.substring(0,transcriptId.length()-2);
		    } else {                                                            // Foobar12345
			thisGeneId = transcriptId;
		    }
		} catch (Exception e) {
		    throw new RuntimeException("Error at line "+lineCount+": transcriptId="+transcriptId);
		}
                // clear the exprs and values arrays if this is a new gene
                boolean newGene = !thisGeneId.equals(geneId);
                if (newGene) {
                    geneId = thisGeneId;
                    exprs = new double[numSamples];
                    for (int i=0; i<numSamples; i++) {
                        values[i] = createItem("ExpressionValue");
                        valueSet.add(values[i]);
                    }
                }
                // create/grab the gene item and update map
                Item gene = null;
                if (geneMap.containsKey(geneId)) {
                    gene = geneMap.get(geneId);
                } else {
                    gene  = createItem("Gene");
                }
                gene.setAttribute("primaryIdentifier", geneId);
                geneMap.put(geneId, gene);
                // add the expression values for this gene
                for (int i=0; i<numSamples; i++) {
                    exprs[i] += Double.parseDouble(parts[i+1]);
                }
                // (re)set the values attributes
                for (int i=0; i<numSamples; i++) {
                    values[i].setAttribute("value", String.valueOf(exprs[i]));
                    values[i].setReference("sample", samples[i]);
                    values[i].setReference("gene", gene);
                }
            }
        }
    }

    /**
     * Store the items we've held in sets or maps.
     */
    @Override
    public void close() throws Exception {

        LOG.info("Storing "+geneMap.size()+" gene items...");
        for (Item gene : geneMap.values()) store(gene);

        LOG.info("Storing "+sourceSet.size()+" ExpressionSource items...");
        for (Item source : sourceSet) store(source);
        
        LOG.info("Storing "+sampleSet.size()+" ExpressionSample items...");
        for (Item sample : sampleSet) store(sample);

        LOG.info("Storing "+valueSet.size()+" ExpressionValue items...");
        for (Item value : valueSet) store(value);

    }

}
