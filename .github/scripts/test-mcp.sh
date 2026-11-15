#!/usr/bin/env bash
set -euo pipefail
ci_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source_dir=$PWD

# A fresh Maven repository is intentional: never rely on a developer's ~/.m2.
# Rebuild the pinned fact-graph JVM source, then the MCP dependency closure.
factgraph_commit=3c9c2a83657be3d8e1beb9a9530c376a6dc15911
mkdir -p /tmp/factgraph
curl --fail --location --retry 3 \
  "https://codeload.github.com/qcloop/fact-graph/tar.gz/$factgraph_commit" \
  --output /tmp/factgraph.tar.gz
echo 'd1291777df0036d39a632f974ec185fc13829891fd69d2bb9b5e73ecc849cf08  /tmp/factgraph.tar.gz' | sha256sum --check
tar -xzf /tmp/factgraph.tar.gz --strip-components=1 -C /tmp/factgraph
curl --fail --location --retry 3 \
  https://repo.maven.apache.org/maven2/org/scala-sbt/sbt-launch/1.11.4/sbt-launch-1.11.4.jar \
  --output /tmp/sbt-launch.jar
echo 'a566d4b8958b9dd548761ea357b637afb4c0f20da26244ed86f9b11adbaf1cc9  /tmp/sbt-launch.jar' | sha256sum --check
cd /tmp/factgraph
java -Dsbt.supershell=false -Dsbt.log.noformat=true -jar /tmp/sbt-launch.jar \
  'factGraphJVM/test' 'factGraphJVM/publishM2'

cd "$source_dir/direct-file"
mvn -B -ntp -N install -f boms/irs-spring-boot-starter-parent/pom.xml
mvn -B -ntp clean install -f libs/data-models/pom.xml
mvn -B -ntp clean install -f libs/loaders/pom.xml
mvn -B -ntp clean verify -f tax-advisor/pom.xml

# A green Maven invocation with skipped Redis tests is not a successful CI run.
python3 "$ci_dir/verify-redis-tests.py" tax-advisor/target/surefire-reports
