#!/usr/bin/env bash
# End-to-end test for the yaver-ng-client generator.
#
# Pipeline (no step swallows a non-zero exit code):
#   1. generate the fixture library with yaver-ng-client
#   2. build it with ng-packagr (Angular Package Format)
#   3. static quality gates over the built dist
#   4. npm pack the dist artifact
#   5. determinism check (generate twice, byte-compare)
#   6. wire-equivalence spot check against yaver-ts-angular
#   7. zoneless consumer smoke tests (Vitest + HttpTestingController):
#        - local mode:  dependency -> built dist directory
#        - npm mode:    dependency -> packed tarball
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/sample/out/ng-client"
FIXTURE="$ROOT/sample/fixtures/ng-client.yaml"
NPM_NAME="@yaver/test"
NPM_VERSION="1.0.0"

MODE="${1:-both}"   # local | tarball | both

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  echo "==> Building generator JAR"
  (cd "$ROOT" && ./build.sh >/dev/null)
fi

CP="$ROOT/cli/yaver-generator-cli.jar:$ROOT/cli/openapi-generator-cli.jar"

echo "==> [1/7] Generating $NPM_NAME from fixture"
rm -rf "$OUT/lib" "$OUT/lib2" "$OUT/consumer-local" "$OUT/consumer-npm"
mkdir -p "$OUT"
java -cp "$CP" org.openapitools.codegen.OpenAPIGenerator generate \
  -g yaver-ng-client -i "$FIXTURE" -o "$OUT/lib" \
  --additional-properties="npmName=$NPM_NAME" \
  --additional-properties="npmVersion=$NPM_VERSION" \
  --additional-properties=clientPrefix=YaverTest

echo "==> [2/7] Building Angular package (ng-packagr)"
(cd "$OUT/lib" && npm install --no-audit --no-fund >/dev/null && npx ng-packagr -p ng-package.json)

echo "==> [3/7] Static quality gates"
python3 "$ROOT/sample/ng-client-quality-gate.py" "$OUT/lib/dist"

echo "==> [4/7] npm pack"
(cd "$OUT/lib" && npm pack ./dist >/dev/null)
TARBALL="$OUT/lib/yaver-test-$NPM_VERSION.tgz"
[[ -f "$TARBALL" ]] || { echo "FATAL: tarball missing: $TARBALL"; exit 1; }
echo "    packed: $TARBALL"

echo "==> [5/7] Determinism check"
java -cp "$CP" org.openapitools.codegen.OpenAPIGenerator generate \
  -g yaver-ng-client -i "$FIXTURE" -o "$OUT/lib2" \
  --additional-properties="npmName=$NPM_NAME" \
  --additional-properties="npmVersion=$NPM_VERSION" \
  --additional-properties=clientPrefix=YaverTest >/dev/null
# Compare generated sources only; build artifacts (dist/, node_modules/,
# lockfile, tarball) are outputs of the build, not of generation.
diff -r --exclude=dist --exclude=node_modules --exclude=package-lock.json \
  --exclude="*.tgz" "$OUT/lib" "$OUT/lib2" \
  && echo "    deterministic: two generations are identical" \
  || { echo "FATAL: generation is not deterministic"; exit 1; }

echo "==> [6/7] Wire-equivalence spot check vs yaver-ts-angular"
rm -rf "$OUT/wire-ts-angular" "$OUT/wire-ng-client"
java -cp "$CP" org.openapitools.codegen.OpenAPIGenerator generate \
  -g yaver-ts-angular -i "$FIXTURE" -o "$OUT/wire-ts-angular" \
  --additional-properties=useSingleRequestParameter=true >/dev/null
cp -R "$OUT/lib" "$OUT/wire-ng-client"
python3 "$ROOT/sample/ng-client-wire-check.py" \
  "$OUT/wire-ts-angular/src" "$OUT/wire-ng-client/src"

run_consumer() {
  local mode="$1" dest="$2" dep="$3"
  echo "==> [7/7] Consumer smoke tests ($mode mode)"
  cp -R "$ROOT/sample/ng-client-consumer" "$dest"
  if [[ "$mode" == "local" ]]; then
    # Local mode: the built dist artifact is vendored INSIDE the consumer
    # project (like a hoisted workspace package) so that peer-dependency
    # resolution finds the consumer's @angular packages. npm symlinks file:
    # dependencies, so the package directory must not sit outside the
    # consumer's node_modules lookup path.
    mkdir -p "$dest/local/@yaver"
    rm -rf "$dest/local/@yaver/test"
    cp -R "$OUT/lib/dist" "$dest/local/@yaver/test"
    dep="file:local/@yaver/test"
  fi
  python3 - "$dest/package.json" "$dep" <<'PYEOF'
import json, sys
path, dep = sys.argv[1], sys.argv[2]
manifest = json.load(open(path))
manifest["devDependencies"]["@yaver/test"] = dep
json.dump(manifest, open(path, "w"), indent=2)
PYEOF
  (cd "$dest" && npm install --no-audit --no-fund >/dev/null)
  (cd "$dest" && npm test)
  if [[ -d "$dest/node_modules/zone.js" ]]; then
    echo "FATAL: zone.js installed in consumer"; exit 1
  fi
  echo "    consumer ($mode) passed; zone.js not installed"
}

case "$MODE" in
  local)   run_consumer local "$OUT/consumer-local" "unused" ;;
  tarball) run_consumer tarball "$OUT/consumer-npm" "file:../lib/yaver-test-$NPM_VERSION.tgz" ;;
  both)
    run_consumer local "$OUT/consumer-local" "unused"
    run_consumer tarball "$OUT/consumer-npm" "file:../lib/yaver-test-$NPM_VERSION.tgz"
    ;;
  *) echo "usage: $0 [local|tarball|both]"; exit 2 ;;
esac

echo
echo "ALL yaver-ng-client TESTS PASSED"
