#!/usr/bin/env bash
# Builds and starts the app. Extra options are passed through, e.g.:
#   ./run.sh --demo            load sample data on first run
#   ./run.sh --port 9090       use another port
set -euo pipefail
cd "$(dirname "$0")"
./build.sh
exec java -cp out com.geetansh.expensetracker.Main "$@"
