#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OPENAPI_GENERATOR_JAR="$ROOT_DIR/cli/openapi-generator-cli.jar"
YAVER_GENERATOR_JAR="${YAVER_GENERATOR_JAR:-$ROOT_DIR/yaver-codegen/target/yaver-codegen.jar}"
FIXTURE="$SCRIPT_DIR/fixtures/response-contracts.yaml"
OUTPUT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/yaver-generator-defaults.XXXXXX")"
YAVER_RESULT_NUGET_SOURCE="${YAVER_RESULT_NUGET_SOURCE:-}"

cleanup() {
  rm -rf "$OUTPUT_DIR"
}
trap cleanup EXIT

assert_contains() {
  local file="$1"
  local expected="$2"
  if ! grep -Fq "$expected" "$file"; then
    echo "Expected default was not found in $file: $expected" >&2
    exit 1
  fi
}

restore_project() {
  local project_file="$1"
  if [[ -n "$YAVER_RESULT_NUGET_SOURCE" ]]; then
    dotnet restore "$project_file" \
      --source "$YAVER_RESULT_NUGET_SOURCE" \
      -p:NuGetAudit=false
  else
    dotnet restore "$project_file"
  fi
}

for generator in yaver-proxy yaver-cs-gateway; do
  output="$OUTPUT_DIR/$generator"
  java -cp "$YAVER_GENERATOR_JAR:$OPENAPI_GENERATOR_JAR" \
    org.openapitools.codegen.OpenAPIGenerator generate \
    -g "$generator" \
    -i "$FIXTURE" \
    -o "$output" \
    --additional-properties=packageName=Yaver.Generator.Defaults

  project_file="$(find "$output/src" -type f -name '*.csproj' -print -quit)"
  if [[ -z "$project_file" ]]; then
    echo "Generated project was not found for $generator" >&2
    exit 1
  fi

  assert_contains "$project_file" "<TargetFramework>net10.0</TargetFramework>"
  assert_contains "$project_file" "<FastEndpointsVersion>8.2.0</FastEndpointsVersion>"
  assert_contains "$project_file" "<RiokMapperlyVersion>4.3.0</RiokMapperlyVersion>"
  assert_contains "$project_file" "<YaverResultVersion>2.3.1</YaverResultVersion>"

  if [[ "$generator" == "yaver-cs-gateway" ]]; then
    assert_contains "$project_file" "<MessagePackVersion>3.1.8</MessagePackVersion>"
  fi

  restore_project "$project_file"
  dotnet build "$project_file" -c Release --nologo --no-restore
done

echo "Generator default dependency regression OK"
