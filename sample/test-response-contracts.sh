#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OPENAPI_GENERATOR_JAR="$ROOT_DIR/cli/openapi-generator-cli.jar"
YAVER_GENERATOR_JAR="${YAVER_GENERATOR_JAR:-$ROOT_DIR/yaver-codegen/target/yaver-codegen.jar}"
VALID_FIXTURE="$SCRIPT_DIR/fixtures/response-contracts.yaml"
MULTIPLE_SUCCESS_FIXTURE="$SCRIPT_DIR/fixtures/invalid-multiple-success-responses.yaml"
DOMAIN_ERROR_FIXTURE="$SCRIPT_DIR/fixtures/invalid-domain-error-response.yaml"
OUTPUT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/yaver-response-contracts.XXXXXX")"
YAVER_RESULT_VERSION="${YAVER_RESULT_VERSION:-3.0.0}"
YAVER_RESULT_NUGET_SOURCE="${YAVER_RESULT_NUGET_SOURCE:-}"

cleanup() {
  rm -rf "$OUTPUT_DIR"
}
trap cleanup EXIT

if [[ ! -f "$YAVER_GENERATOR_JAR" ]]; then
  echo "Generator JAR not found: $YAVER_GENERATOR_JAR" >&2
  echo "Build it with: mvn -f yaver-codegen/pom.xml clean package" >&2
  exit 2
fi

generate() {
  local generator="$1"
  local fixture="$2"
  local output="$3"

  java -cp "$YAVER_GENERATOR_JAR:$OPENAPI_GENERATOR_JAR" \
    org.openapitools.codegen.OpenAPIGenerator generate \
    -g "$generator" \
    -i "$fixture" \
    -o "$output" \
    --additional-properties=packageName=Yaver.Response.Contracts \
    --additional-properties=targetFramework=net10.0 \
    --additional-properties=fastEndpointsVersion=8.2.0 \
    --additional-properties=riokMapperlyVersion=4.3.1 \
    --additional-properties=yaverResultVersion="$YAVER_RESULT_VERSION" \
    --additional-properties=messagePackVersion=3.1.8
}

assert_contains() {
  local file="$1"
  local expected="$2"
  if ! grep -Fq "$expected" "$file"; then
    echo "Expected generated contract was not found in $file: $expected" >&2
    exit 1
  fi
}

assert_invalid() {
  local generator="$1"
  local fixture="$2"
  local expected="$3"
  local output="$OUTPUT_DIR/invalid-${generator}-$(basename "$fixture" .yaml)"
  local log="$output.log"

  set +e
  generate "$generator" "$fixture" "$output" >"$log" 2>&1
  local exit_code=$?
  set -e

  if [[ $exit_code -eq 0 ]]; then
    echo "Expected $generator generation to fail for $fixture" >&2
    exit 1
  fi
  assert_contains "$log" "$expected"
}

for generator in yaver-proxy yaver-cs-gateway; do
  output="$OUTPUT_DIR/$generator"
  generate "$generator" "$VALID_FIXTURE" "$output"

  api_file="$(find "$output/src" -type f -name 'ResponsesApi.cs' -print -quit)"
  command_file="$(find "$output/src" -type f -name 'ResponsesCommands.cs' -print -quit)"
  project_file="$(find "$output/src" -type f -name '*.csproj' -print -quit)"

  if [[ -z "$api_file" || -z "$command_file" || -z "$project_file" ]]; then
    echo "Expected generated files were not found for $generator" >&2
    exit 1
  fi

  assert_contains "$api_file" ".SendAsync(HttpContext, 200, ct)"
  assert_contains "$api_file" ".SendAsync(HttpContext, 201, ct)"
  assert_contains "$api_file" ".SendAsync(HttpContext, 204, ct)"
  assert_contains "$command_file" "IRpcCommand<Result<StatusResponse>>"
  assert_contains "$command_file" "IRpcCommand<Yaver.Result.Result>"

  if grep -Fq "Result<EmptyResponse>" "$command_file"; then
    echo "$generator must use the bodyless Result envelope for 204" >&2
    exit 1
  fi
  if grep -Fq "Result<ProblemDetails>" "$command_file"; then
    echo "$generator must not add error DTOs to the RPC command contract" >&2
    exit 1
  fi

  if [[ -n "$YAVER_RESULT_NUGET_SOURCE" ]]; then
    dotnet restore "$project_file" \
      --source "$YAVER_RESULT_NUGET_SOURCE" \
      --source https://api.nuget.org/v3/index.json
  else
    dotnet restore "$project_file"
  fi
  dotnet build "$project_file" -c Release --nologo --no-restore

  assert_invalid "$generator" "$MULTIPLE_SUCCESS_FIXTURE" \
    "must declare exactly one concrete 2xx response"
  assert_invalid "$generator" "$DOMAIN_ERROR_FIXTURE" \
    "must use application/problem+json with the canonical ProblemDetails schema"
done

echo "Response contract regression OK"
