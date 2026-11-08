#!/usr/bin/env bash
# Runs one load scenario against a running stack and prints Gatling's summary (requests, failures, percentiles,
# throughput). Usage: ./run.sh topup|transfer [users] [seconds]   (backend at http://localhost:8090 by default)
set -euo pipefail
cd "$(dirname "$0")"
SCENARIO=${1:-transfer}
USERS=${2:-50}
SECONDS_=${3:-60}
mkdir -p target
../backend/mvnw -B -q -f pom.xml gatling:test -Dscenario="$SCENARIO" -Dusers="$USERS" -Dseconds="$SECONDS_" \
  -DbaseUrl="${BASE_URL:-http://localhost:8090}" > "target/last-$SCENARIO.log" 2>&1 || true
echo "$SCENARIO users=$USERS seconds=$SECONDS_ report=$(ls -td target/gatling/*/ | head -1)"
sed -n '/---- Global Information/,/mean requests\/sec/p' "target/last-$SCENARIO.log" | grep -E "request count|percentile|requests/sec"
