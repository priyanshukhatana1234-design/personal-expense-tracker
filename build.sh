#!/usr/bin/env bash
# Compiles the app into ./out  (needs JDK 17 or newer)
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out && mkdir out
javac --release 17 -d out $(find src/main/java -name '*.java')
cp -r src/main/resources/. out/
echo "Build OK"
