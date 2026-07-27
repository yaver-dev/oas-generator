#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
FIXTURE="$SCRIPT_DIR/fixtures/proxy-success-statuses.yaml"
OPENAPI_GENERATOR_JAR="$ROOT_DIR/cli/openapi-generator-cli.jar"
YAVER_GENERATOR_JAR="${YAVER_GENERATOR_JAR:-$ROOT_DIR/yaver-codegen/target/yaver-codegen.jar}"
OUTPUT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/yaver-proxy-success-statuses.XXXXXX")"

cleanup() {
  rm -rf "$OUTPUT_DIR"
}
trap cleanup EXIT

if [[ ! -f "$YAVER_GENERATOR_JAR" ]]; then
  echo "Generator JAR not found: $YAVER_GENERATOR_JAR" >&2
  echo "Build it with: mvn -f yaver-codegen/pom.xml clean package" >&2
  exit 2
fi

java -cp "$YAVER_GENERATOR_JAR:$OPENAPI_GENERATOR_JAR" \
  org.openapitools.codegen.OpenAPIGenerator \
  generate \
  -g yaver-proxy \
  -i "$FIXTURE" \
  -o "$OUTPUT_DIR" \
  --additional-properties=packageName=Yaver.Proxy.StatusCodes \
  --additional-properties=targetFramework=net10.0 \
  --additional-properties=fastEndpointsVersion=7.1.1 \
  --additional-properties=riokMapperlyVersion=4.3.0 \
  --additional-properties=yaverResultVersion=1.1.0

API_FILE="$(find "$OUTPUT_DIR/src" -type f -name 'StatusApi.cs' -print -quit)"
PROJECT_FILE="$(find "$OUTPUT_DIR/src" -type f -name '*.csproj' -print -quit)"

if [[ -z "$API_FILE" || -z "$PROJECT_FILE" ]]; then
  echo "Expected generated API and project files were not found." >&2
  exit 1
fi

assert_contains() {
  local expected="$1"
  if ! grep -Fq "$expected" "$API_FILE"; then
    echo "Expected generated status mapping was not found: $expected" >&2
    exit 1
  fi
}

assert_contains ".SendAsync(HttpContext, 200, ct)"
assert_contains ".SendAsync(HttpContext, 201, ct)"
assert_contains ".SendAsync(HttpContext, 202, ct)"
assert_contains ".SendAsync(HttpContext, 206, ct)"
assert_contains "await Send.NoContentAsync(ct).ConfigureAwait(false);"
assert_contains ".SendAsync(HttpContext, cancellationToken: ct)"

if grep -Fq ".SendAsync(HttpContext, 204, ct)" "$API_FILE"; then
  echo "A 204 success response must not serialize a response body." >&2
  exit 1
fi

dotnet build "$PROJECT_FILE" -c Release --nologo

echo "Proxy success status regression OK"
