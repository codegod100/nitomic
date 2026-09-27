#!/usr/bin/env bash
# Regenerate test/expected/ by running the test programs on the JVM against a
# Datomic Pro distribution, e.g.
#
#   curl -O https://datomic-pro-downloads.s3.amazonaws.com/1.0.7705/datomic-pro-1.0.7705.zip
#   unzip datomic-pro-1.0.7705.zip
#   script/reference.sh datomic-pro-1.0.7705
set -euo pipefail
cd "$(dirname "$0")/.."
dist=$(cd "$1" && pwd)
peer=$(ls "$dist"/peer-*.jar | head -1)
for f in examples/seattle/getting_started.clj test/features.clj; do
  name=$(basename "$f" .clj)
  # Drop the JVM's log lines and reflection warnings; keep program output.
  java -cp "$peer:$dist/lib/*" clojure.main "$f" 2>&1 \
    | grep -v -E '^Picked up|^[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]+ \[|^Reflection warning' \
    > "test/expected/$name.out"
  echo "recorded $name"
done
