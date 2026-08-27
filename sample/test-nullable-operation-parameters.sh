#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OPENAPI_GENERATOR_JAR="$ROOT_DIR/cli/openapi-generator-cli.jar"
YAVER_GENERATOR_JAR="${YAVER_GENERATOR_JAR:-$ROOT_DIR/yaver-codegen/target/yaver-codegen.jar}"
FIXTURE="$SCRIPT_DIR/fixtures/nullable-operation-parameters.yaml"
OUTPUT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/yaver-nullable-operation-parameters.XXXXXX")"
PACKAGE_NAME="Yaver.Nullable.Parameters"
YAVER_RESULT_VERSION="${YAVER_RESULT_VERSION:-2.3.1}"
YAVER_RESULT_NUGET_SOURCE="${YAVER_RESULT_NUGET_SOURCE:-}"

cleanup() {
  rm -rf "$OUTPUT_DIR"
}
trap cleanup EXIT

if [[ ! -f "$YAVER_GENERATOR_JAR" ]]; then
  echo "Generator JAR not found: $YAVER_GENERATOR_JAR" >&2
  echo "Build it with: ./build.sh" >&2
  exit 2
fi

generate() {
  local generator="$1"
  local output="$2"

  java -cp "$YAVER_GENERATOR_JAR:$OPENAPI_GENERATOR_JAR" \
    org.openapitools.codegen.OpenAPIGenerator generate \
    -g "$generator" \
    -i "$FIXTURE" \
    -o "$output" \
    --additional-properties=packageName="$PACKAGE_NAME" \
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
    echo "Expected generated signature was not found in $file: $expected" >&2
    exit 1
  fi
}

assert_parameter_signatures() {
  local file="$1"

  assert_contains "$file" "public bool? NullableBoolean {"
  assert_contains "$file" "public int? NullableInt32 {"
  assert_contains "$file" "public long? NullableInt64 {"
  assert_contains "$file" "public double? NullableDouble {"
  assert_contains "$file" "public Guid? NullableUuid {"
  assert_contains "$file" "public DateTime? NullableDateTime {"
  assert_contains "$file" "public ParameterState? NullableState {"
  assert_contains "$file" "public bool NonNullableBoolean {"
  assert_contains "$file" "public List<int>? NullableValues {"

  if grep -Fq "??" "$file"; then
    echo "Generated nullable syntax was applied twice in $file" >&2
    exit 1
  fi
  if grep -Fq "&lt;" "$file"; then
    echo "Generated container type was HTML-escaped in $file" >&2
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
    dotnet restore "$project_file" -p:NuGetAudit=false
  fi
}

assert_boolean_mapping() {
  local generator="$1"
  local generated_project="$2"
  local smoke_dir="$OUTPUT_DIR/mapping-$generator"

  dotnet new console -n NullableParameterMapping -o "$smoke_dir" --framework net10.0 --no-restore
  dotnet add "$smoke_dir/NullableParameterMapping.csproj" reference "$generated_project"
  dotnet add "$smoke_dir/NullableParameterMapping.csproj" package Yaver.Result \
    --version "$YAVER_RESULT_VERSION" \
    --no-restore

  cat >"$smoke_dir/Program.cs" <<'EOF'
using Yaver.Nullable.Parameters.NullableParameters.Api;

var omitted = new InspectNullableParametersRequest().ToCommand();
var explicitFalse = new InspectNullableParametersRequest { NullableBoolean = false }.ToCommand();
var explicitTrue = new InspectNullableParametersRequest { NullableBoolean = true }.ToCommand();

if (omitted.NullableBoolean is not null)
{
    throw new InvalidOperationException("Omitted/default nullable boolean must map to null.");
}
if (explicitFalse.NullableBoolean is not false)
{
    throw new InvalidOperationException("Explicit false nullable boolean must remain false.");
}
if (explicitTrue.NullableBoolean is not true)
{
    throw new InvalidOperationException("Explicit true nullable boolean must remain true.");
}

Console.WriteLine("Nullable boolean request-to-command mapping OK.");
EOF

  restore_project "$smoke_dir/NullableParameterMapping.csproj"
  dotnet run --project "$smoke_dir/NullableParameterMapping.csproj" --no-restore
}

for generator in yaver-proxy yaver-cs-gateway yaver-cs-fastendpoints; do
  output="$OUTPUT_DIR/$generator"
  generate "$generator" "$output"

  project_file="$(find "$output/src" -type f -name '*.csproj' -print -quit)"
  if [[ -z "$project_file" ]]; then
    echo "Generated project was not found for $generator" >&2
    exit 1
  fi

  if [[ "$generator" == "yaver-cs-fastendpoints" ]]; then
    request_file="$(find "$output/src" -type f -name 'NullableParametersApi.cs' -print -quit)"
  else
    request_file="$(find "$output/src" -type f -name 'NullableParametersRequests.cs' -print -quit)"
  fi
  if [[ -z "$request_file" ]]; then
    echo "Generated request file was not found for $generator" >&2
    exit 1
  fi
  assert_parameter_signatures "$request_file"

  if [[ "$generator" != "yaver-cs-fastendpoints" ]]; then
    command_file="$(find "$output/src" -type f -name 'NullableParametersCommands.cs' -print -quit)"
    if [[ -z "$command_file" ]]; then
      echo "Generated command file was not found for $generator" >&2
      exit 1
    fi
    assert_parameter_signatures "$command_file"
  fi

  restore_project "$project_file"
  dotnet build "$project_file" -c Release --nologo --no-restore

  if [[ "$generator" != "yaver-cs-fastendpoints" ]]; then
    assert_boolean_mapping "$generator" "$project_file"
  fi
done

echo "Nullable operation parameter regression OK"
