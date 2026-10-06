#!/bin/bash
# Offline build: compiles against the Paper API jars in ./libs (or the staged ~/.m2 copy).
set -e
cd "$(dirname "$0")"
LIBS=${LIBS:-/mnt/user-data/uploads/repository}
CP=$(find "$LIBS" libs -name "*.jar" | tr '\n' ':')
rm -rf build && mkdir -p build/classes
javac -nowarn --release 21 -encoding UTF-8 -cp "$CP" -d build/classes $(find src/main/java -name "*.java")
cp -r src/main/resources/* build/classes/
(cd build/classes && jar cf ../PrisonCore-2.14.0.jar .)
echo "Built build/PrisonCore-2.14.0.jar"
