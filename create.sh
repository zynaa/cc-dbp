#!/bin/bash

#stop at first error, unset variables are errors
set -o nounset
set -o errexit

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# Base directory to save the cc-dbp dataset in
baseDir=$1
# Configuration file to use
config=${2:-config.properties}
#the list of warc files, get from http://commoncrawl.org/connect/blog/
warcUrlList=${3:-https://data.commoncrawl.org/crawl-data/CC-MAIN-2017-51/warc.paths.gz}

mvn clean compile package install

cd com.ibm.research.ai.ki.kb
mvn assembly:single
cd ..
cd com.ibm.research.ai.ki.corpus
mvn assembly:single
cd ..
cd com.ibm.research.ai.ki.kbp
mvn assembly:single
cd ..

# Download DBpedia files and create initial kb files
java -Xmx4G -cp com.ibm.research.ai.ki.kb/target/kb-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
com.ibm.research.ai.ki.kb.conversion.ConvertDBpedia -config $config -kb $baseDir/kb

# Download Common Crawl, get -urlList from http://commoncrawl.org/connect/blog/
java -Xmx4G -cp com.ibm.research.ai.ki.corpus/target/corpora-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
com.ibm.research.ai.ki.corpora.crawl.SaveCommonCrawl -config $config -urlList $warcUrlList -out $baseDir/docs.json.gz.b64

# get node corpus counts by baseline EDL
java -Xmx8G -cp com.ibm.research.ai.ki.kbp/target/kbp-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
com.ibm.research.ai.ki.kbp.GazetteerEDL -gazEntries $baseDir/kb/gazEntries.ser.gz -in $baseDir/docs.json.gz.b64 -idCounts $baseDir/kb/idCounts.tsv

# create remaining KB files
java -Xmx8G -cp com.ibm.research.ai.ki.kb/target/kb-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
com.ibm.research.ai.ki.kb.conversion.ConvertDBpedia -config $config -kb $baseDir/kb

# annotate corpus with baseline EDL
java -Xmx8G -cp com.ibm.research.ai.ki.kbp/target/kbp-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
com.ibm.research.ai.ki.kbp.GazetteerEDL -gazEntries $baseDir/kb/gazEntriesFiltered.ser.gz -in $baseDir/docs.json.gz.b64 -out $baseDir/docs-gaz.json.gz.b64

# baseline context set construction
java -Xmx8G -cp com.ibm.research.ai.ki.kbp/target/kbp-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
com.ibm.research.ai.ki.kbp.KBPBuildDataset -config $config -in $baseDir/docs-gaz.json.gz.b64 -out $baseDir/dataset -kb $baseDir/kb

# dataset processing for seq2seq task
java -Xmx8G -cp com.ibm.research.ai.ki.kb/target/kb-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
com.ibm.research.ai.ki.kb.conversion.MakeDatasetGeneric -config $config -kb $baseDir/kb -dataset $baseDir/dataset

# show sample of positive context sets
awk  -F $'\t' '$7!=""' $baseDir/dataset/contextSets/contexts-part0.tsv | head
