#!/usr/bin/env bash

set -e

# fact-graph now lives in the standalone repo (a sibling of the direct-file repo).
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)
cd "$SCRIPT_DIR/../../../fact-graph"
echo "cleaning fact graph..."
sbt clean
echo "compiling fact graph..."
sbt compile
echo "packaging fact graph..."
sbt package
echo "publishing fact graph to local maven repo..."
sbt publishM2