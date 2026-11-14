#!/usr/bin/env bash
# Builds the app and runs the test suite (exit code 1 on failure).
set -euo pipefail
cd "$(dirname "$0")"
./build.sh
rm -rf out-test && mkdir out-test
javac --release 17 -cp out -d out-test src/test/java/com/geetansh/expensetracker/AppTests.java
java -cp out:out-test com.geetansh.expensetracker.AppTests
