package com.ibm.research.ai.ki.kb.conversion;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.util.*;

import com.ibm.research.ai.ki.formats.*;
import com.ibm.research.ai.ki.util.*;
import org.apache.wink.json4j.*;

public class SelectRelations {
    //The code used to create the original relationSample.txt file
    //this won't be run as part of the dataset construction, just here to document how it was done.

    private static Set<String> getDBpediaMicroDataProperties(String[] microDataPaths) throws Exception {
        Set<String> dbpediaFilter = new HashSet<String>();

        for (String microDataPath: microDataPaths) {
            try (InputStreamReader reader = new InputStreamReader(new FileInputStream(microDataPath))) {
                JSONObject json = new JSONObject(reader);
                JSONArray items = (JSONArray) json.get("items");
                for (Object itemObj: items) {
                    JSONObject item = (JSONObject) itemObj;
                    JSONObject properties = (JSONObject) item.get("properties");

                    Iterator<String> keyIter = properties.keys();
                    while (keyIter.hasNext()) {
                        String key = keyIter.next();
                        dbpediaFilter.add(key);
                    }
                }
            }
        }

        return dbpediaFilter;
    }

    private static void relationCountsAndTypes(DBpediaKBConfig config, String[] microDataPaths) throws Exception {
        File dbpediaDir = config.kbDir();
        //find the most frequently occurring N relations, both object and literal
        //examine what types of arguments they have
        Map<String,MutableDouble> relCount = new HashMap<>();
        for (File f : new FileUtil.FileIterable(dbpediaDir)) {
            if (!f.getName().contains("mappingbased")) {
                continue;
            }
            for (String line : FileUtil.getLines(f.getAbsolutePath())) {
                int rstart = line.indexOf(" <");
                if (rstart == -1)
                    continue;
                int rend = line.indexOf("> ", rstart);
                String rel = line.substring(rstart+2, rend);
                SparseVectors.increase(relCount, rel, 1.0);
            }
        }

        Set<String> dbpediaFilter = getDBpediaMicroDataProperties(microDataPaths);
        HashMapUtil.retainAll(relCount, dbpediaFilter);
        System.out.println(SparseVectors.toString(relCount));
        Map<String,RandomUtil.Sample<String[]>> rel2trips = new HashMap<>();
        for (File f : new FileUtil.FileIterable(dbpediaDir)) {
            if (!f.getName().contains("mappingbased")) {
                continue;
            }
            for (String[] trip : NTriples.getProperties(f)) {
                if (relCount.containsKey(trip[1])) {
                    rel2trips.computeIfAbsent(trip[1], k -> new RandomUtil.Sample<>(20)).maybeSave(trip);
                }
            }
        }
        try (PrintStream out = FileUtil.getFilePrintStream(new File(dbpediaDir, "relationSample.txt").getAbsolutePath())) {
            for (Map.Entry<String, RandomUtil.Sample<String[]>> e : rel2trips.entrySet()) {
                out.println(e.getKey());
                for (String[] trip : e.getValue())
                    out.println("   "+trip[0]+"\t"+trip[2]);
            }
        }
    }
    
    //306 good relations (but some will be duplicates when dates are collapsed to years)
    private static void examineSelected(File dbpediaDir) {
        //after annotating the relation sample with '*' or '-' or '-C' or '-?' or 'P' or 'M'
        //we count up the relations that we have left ('*' and 'P')
        //count up the relations excluded for each reason
        Set<String> goodRelations = new HashSet<>();
        Map<String,ArrayList<String>> badRelations = new HashMap<>();
        for (String line : FileUtil.getLines(new File(dbpediaDir, "relationSample.txt").getAbsolutePath())) {
            if (line.startsWith(" "))
                continue;
            int space = line.indexOf(' ');
            if (space == -1 || space > 3) {
                goodRelations.add(line);
            } else {
                String reason = line.substring(0, space);
                String rel = line.substring(space+1);
                if (reason.equals("*") || reason.equals("P")) {
                    goodRelations.add(rel);
                } else {
                    HashMapUtil.addAL(badRelations, reason, rel);
                }
            }
            
        }
        System.out.println("Good:\n   "+Lang.stringList(goodRelations, "\n   "));
        for (Map.Entry<String, ArrayList<String>> e : badRelations.entrySet()) {
            System.out.println(e.getKey()+":\n   "+Lang.stringList(e.getValue(), "\n   "));
        }
    }
    
    static Set<String> getSelectedRelations() {
        // get set of good relations
        Set<String> goodRelations = new HashSet<>();
        for (String line : FileUtil.readResourceAsString("relationSample.txt").split("\n")) {
            if (line.startsWith(" ") || line.startsWith("#") || line.trim().isEmpty())
                continue;
            int space = line.indexOf(' ');
            if (space == -1 || space > 3) {
                goodRelations.add((line + " x").split("\\s+")[0]);
            } else {
                String reason = line.substring(0, space);
                String rel = line.substring(space + 1);
                if (reason.equals("*") || reason.equals("P")) {
                    goodRelations.add((rel + " x").split("\\s+")[0]);
                }
            }
        }
        return goodRelations;
    }
    
    static void downloadDBpedia(DBpediaKBConfig config) {
        try {
        if (!new File(config.kbDir).exists()) {
            FileUtil.ensureWriteable(new File(config.kbDir, "relationSample.txt"));
        }
        //CONSIDER: could multi-thread? but not kind to dbpedia's hosting
        
        String[] urls = new String[] {
                config.dbpediaOwlUrl, config.objectsUrl, config.literalsUrl, config.labelsUrl, config.typesUrl};
        for (String urlLine : urls) {
            URL url = new URL(urlLine);
            String filename = urlLine.substring(urlLine.lastIndexOf('/')+1);
            if (!new File(config.kbDir, filename).exists()) {
                System.out.println("Downloading "+urlLine);
                ReadableByteChannel rbc = Channels.newChannel(url.openStream());
                try (FileOutputStream fos = new FileOutputStream(new File(config.kbDir, filename))) {
                    fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
                }
            }
        }
        } catch (Exception e) {throw new Error(e);}
    }
    
    /**
     * Example args:
     *   /someLocalDir/dbpedia 400
     * @param args
     */
    public static void main(String[] args) throws Exception {
        DBpediaKBConfig config = new DBpediaKBConfig();
        config.fromProperties(PropertyLoader.loadProperties("dbpediaConfig.properties"));
        config.kbDir = args[0];
        String[] microDataPaths = Arrays.copyOfRange(args, 1, args.length);
        downloadDBpedia(config);
        relationCountsAndTypes(config, microDataPaths);
    }
}
