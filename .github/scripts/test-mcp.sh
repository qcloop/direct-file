#!/usr/bin/env bash
set -euo pipefail
ci_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source_dir=$PWD

# A fresh Maven repository is intentional: never rely on a developer's ~/.m2.
# Rebuild the qcloop/fact-graph 3.1.1 release, then the MCP dependency closure.
# The immutable source pin and checksum identify this fork's release independently of Maven caches.
factgraph_commit=fe41ec31b38e7d4389d8e0a5258941cdfdf19782
mkdir -p /tmp/factgraph
curl --fail --location --retry 3 \
  "https://codeload.github.com/qcloop/fact-graph/tar.gz/$factgraph_commit" \
  --output /tmp/factgraph.tar.gz
echo 'ca93ae31a49b77e55c332998d44b09c6bdb0886dd7deb84fbe82129a8ac0f05a  /tmp/factgraph.tar.gz' | sha256sum --check
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
