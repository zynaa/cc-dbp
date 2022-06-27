package com.ibm.research.ai.ki.kb.conversion;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import com.ibm.reseach.ai.ki.nlp.*;
import com.ibm.reseach.ai.ki.nlp.types.*;
import com.ibm.research.ai.ki.util.*;

import org.apache.commons.cli.*;
import org.apache.commons.io.filefilter.WildcardFileFilter;

import static com.ibm.reseach.ai.ki.nlp.types.XmlTag.TEXT_CONTENT;
import static com.ibm.research.ai.ki.kb.conversion.ConvertDBpedia.useNamespacePrefix;

public class MakeDatasetGeneric {
    private static final String owlFile = "dbpedia_2016-10.owl";

    /**
     * Type to name from the DBpedia owl file.
     * @return
     */
    public static Map<String,String > typeToNameMap(File dbpediaOwl) {
        Map<String, String> type2Name = new HashMap<>();

        Document doc = XmlTag.xmlToOffsetAnnotation(FileUtil.readFileAsString(dbpediaOwl), true);
        for (XmlTag t : doc.getAnnotations(XmlTag.class)) {
            if (t.name.equals("owl:Class") ||
                    t.name.equals("owl:ObjectProperty") ||
                    t.name.equals("owl:DatatypeProperty")) {
                String about = t.attributes.get("rdf:about");

                //get prefix format instead of URL:,
                String type = useNamespacePrefix(about);
                String name = null;

                outer: for (XmlTag ti : t.getChildren()) {
                    if (ti.name.equals("rdfs:label")) {
                        String lang = ti.attributes.get("xml:lang");
                        if (!lang.equals("en"))
                            continue;


                        for (XmlTag tii: ti.getChildren()) {
                            if (tii.name.equals("#text")) {
                                name = tii.attributes.get(TEXT_CONTENT);
                                break outer;
                            }
                        }
                    }
                }

                // reserved for dbpedia
                if (name == null && !type.equals("dbo:hasSurfaceForm")) {
                    System.err.printf("Name for type %s is null!%n", type);
                    continue;
                }
                type2Name.put(type, name);
            }
        }

        return type2Name;
    }

    /**
     * Remove Dbpedia prefix and use spaces instead of _
     *
     * @param mention the raw mention e.g. dbl:Barack_Obama
     * @return the normal text e.g. Barack Obama
     */
    private static String convertMention(String mention) {
        String mentionNoPrefix = mention.substring(4);
        return mentionNoPrefix.replace("_", " ");
    }

    private static String convertRelations(String relations, Map<String, String> typeToName,
                                           Set<String> unknownTypes) {
        return Arrays.stream(relations.split(","))
                .filter(relationType -> !relationType.isEmpty())
                .map(relationType -> relationType.substring(1))
                .filter(relationType -> {
                    boolean contains = typeToName.containsKey(relationType);
                    if (!contains && !unknownTypes.contains(relationType)) {
                        System.err.printf("Warning: Unable to find key \"%s\" in type-name map! Removing ...%n",
                                relationType);
                        unknownTypes.add(relationType);
                    }
                    return contains;
                })
                .map(typeToName::get)
                .collect(Collectors.joining("|"));
    }

    private static void process(DBpediaKBConfig config, String datasetDirPath) throws Exception {
        Map<String, String > typeToName = typeToNameMap(config.dbpediaOwlFile());
        Set<String> unknownTypes = new HashSet<>();
        File inputDir = Paths.get(datasetDirPath, "contextSets").toFile();

        FileFilter fileFilter = new WildcardFileFilter("contexts*.tsv");
        for (File inputFile: Objects.requireNonNull(inputDir.listFiles(fileFilter))) {
            Path outputFilePath = Paths.get(datasetDirPath,"contextSetsProcessed", inputFile.getName());
            Files.createDirectories(outputFilePath.getParent());

            try (BufferedReader br = new BufferedReader(new FileReader(inputFile))) {
                try (BufferedWriter bw = new BufferedWriter(new FileWriter(outputFilePath.toFile()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String[] splitLine = line.split("\t");

                        String mentionOne = splitLine[0];
                        String mentionTwo = splitLine[1];
                        String typeOne = splitLine[2];
                        String typeTwo = splitLine[3];
                        String spanOne = splitLine[4];
                        String spawnTwo = splitLine[5];
                        String relations = splitLine[6];
                        String text = splitLine[7];

                        String subTextOne = convertMention(mentionOne);
                        String subTextTwo = convertMention(mentionTwo);
                        String namedRelations = convertRelations(relations, typeToName, unknownTypes);

                        String output = String.join("\t", subTextOne, subTextTwo,
                                typeOne, typeTwo, spanOne, spawnTwo, namedRelations, text);
                        bw.write(output);
                        bw.write("\n");
                    }
                }
            }
        }
    }

    /**
     * Example args:
       -config dbpediaConfig.properties
       -kb /my/local/dir/dbpediaKB
       -dataset /my/local/dir/dbpediaDataset
     * @param args
     */
    public static void main(String[] args) throws Exception {
        Options options = new Options();
        options.addOption("config", true, "A DBpediaKBConfig in properties file format");   
        options.addOption("kb", true, "The kb directory to create or read the owl from");
        options.addOption("dataset", true, "The dataset directory containing the TSV dataset");

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = null;
        try {
            cmd = parser.parse(options, args);  
        } catch (ParseException pe) {
            Lang.error(pe);
        }

        String configProperties = cmd.getOptionValue("config");
        DBpediaKBConfig config = new DBpediaKBConfig();
        config.fromProperties(PropertyLoader.loadProperties(configProperties));
        config.kbDir = cmd.getOptionValue("kb");

        File dbpediaDir = config.kbDir();

        if (!new File(dbpediaDir, owlFile).exists()) {
            SelectRelations.downloadDBpedia(config); //download dbpedia files if not present
        }

        String datasetDir = cmd.getOptionValue("dataset");
        if (datasetDir == null)
            datasetDir = "dataset";

        process(config, datasetDir);
    }

}

