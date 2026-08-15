#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OPENAPI_GENERATOR_JAR="$ROOT_DIR/cli/openapi-generator-cli.jar"
YAVER_GENERATOR_JAR="${YAVER_GENERATOR_JAR:-$ROOT_DIR/yaver-codegen/target/yaver-codegen.jar}"
VALID_FIXTURE="$SCRIPT_DIR/fixtures/response-contracts.yaml"
MULTIPLE_SUCCESS_FIXTURE="$SCRIPT_DIR/fixtures/invalid-multiple-success-responses.yaml"
DOMAIN_ERROR_FIXTURE="$SCRIPT_DIR/fixtures/invalid-domain-error-response.yaml"
MULTIPLE_REPRESENTATIONS_FIXTURE="$SCRIPT_DIR/fixtures/invalid-multiple-success-representations.yaml"
COMPOSED_SUCCESS_FIXTURE="$SCRIPT_DIR/fixtures/invalid-composed-success-response.yaml"
NO_CONTENT_BODY_FIXTURE="$SCRIPT_DIR/fixtures/invalid-no-content-body.yaml"
RESET_CONTENT_BODY_FIXTURE="$SCRIPT_DIR/fixtures/invalid-reset-content-body.yaml"
BODYLESS_ERROR_FIXTURE="$SCRIPT_DIR/fixtures/invalid-bodyless-error-response.yaml"
INVALID_PROBLEM_DETAILS_FIXTURE="$SCRIPT_DIR/fixtures/invalid-problem-details-schema.yaml"
DEFAULT_ERROR_FIXTURE="$SCRIPT_DIR/fixtures/invalid-default-error-response.yaml"
SERVER_ERROR_FIXTURE="$SCRIPT_DIR/fixtures/invalid-server-error-response.yaml"
MISSING_SUCCESS_FIXTURE="$SCRIPT_DIR/fixtures/invalid-missing-success-response.yaml"
WILDCARD_SUCCESS_FIXTURE="$SCRIPT_DIR/fixtures/invalid-wildcard-success-response.yaml"
SUCCESS_WITHOUT_SCHEMA_FIXTURE="$SCRIPT_DIR/fixtures/invalid-success-without-schema.yaml"
OUTPUT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/yaver-response-contracts.XXXXXX")"
YAVER_RESULT_VERSION="${YAVER_RESULT_VERSION:-2.3.1}"
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

assert_empty_response_round_trip() {
  local generator="$1"
  local generated_project="$2"
  local empty_response_namespace="$3"
  local schema_project="${4:-}"
  local smoke_dir="$OUTPUT_DIR/runtime-$generator"

  dotnet new console -n "$generator" -o "$smoke_dir" --framework net10.0 --no-restore
  dotnet add "$smoke_dir/$generator.csproj" reference "$generated_project"
  if [[ -n "$schema_project" ]]; then
    dotnet add "$smoke_dir/$generator.csproj" reference "$schema_project"
  fi
  dotnet add "$smoke_dir/$generator.csproj" package MessagePack --version 3.1.8 --no-restore
  dotnet add "$smoke_dir/$generator.csproj" package Yaver.Result --version "$YAVER_RESULT_VERSION" --no-restore

  cat >"$smoke_dir/Program.cs" <<EOF
using MessagePack;
using MessagePack.Resolvers;
using Yaver.Result;
using RpcEmptyResponse = $empty_response_namespace.EmptyResponse;

var options = MessagePackSerializerOptions.Standard
    .WithResolver(ContractlessStandardResolver.Instance);
var source = Result<RpcEmptyResponse>.Success(RpcEmptyResponse.Instance);
var bytes = MessagePackSerializer.Serialize(source, options);
var clone = MessagePackSerializer.Deserialize<Result<RpcEmptyResponse>>(bytes, options);

if (!clone.IsSuccess || clone.Value is null)
{
    throw new InvalidOperationException("Result<EmptyResponse> MessagePack round-trip failed.");
}

Console.WriteLine("Result<EmptyResponse> MessagePack round-trip OK.");
EOF

  restore_project "$smoke_dir/$generator.csproj"
  dotnet run --project "$smoke_dir/$generator.csproj" --no-restore
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
  assert_contains "$api_file" "await Send.NoContentAsync(ct).ConfigureAwait(false);"
  assert_contains "$api_file" "HttpContext.Response.StatusCode = 205;"
  assert_contains "$api_file" "await HttpContext.Response.CompleteAsync().ConfigureAwait(false);"
  assert_contains "$api_file" ".SendAsync(HttpContext, cancellationToken: ct)"
  assert_contains "$command_file" "IRpcCommand<Result<StatusResponse>>"
  assert_contains "$command_file" "IRpcCommand<Result<EmptyResponse>>"

  empty_response_file="$(find "$output/src" -type f -name 'EmptyResponse.cs' -print -quit)"
  if [[ -z "$empty_response_file" ]]; then
    echo "Expected generated RPC-safe EmptyResponse for $generator" >&2
    exit 1
  fi
  assert_contains "$empty_response_file" "public sealed class EmptyResponse"
  assert_contains "$empty_response_file" "public EmptyResponse()"

  if grep -Fq "IRpcCommand<Yaver.Result.Result>" "$command_file"; then
    echo "$generator must preserve the FastEndpoints EmptyResponse RPC envelope for 204" >&2
    exit 1
  fi
  if grep -Fq ".SendAsync(HttpContext, 204, ct)" "$api_file"; then
    echo "$generator must not serialize the EmptyResponse envelope for HTTP 204" >&2
    exit 1
  fi
  if grep -Fq ".SendAsync(HttpContext, 205, ct)" "$api_file"; then
    echo "$generator must not serialize the EmptyResponse envelope for HTTP 205" >&2
    exit 1
  fi
  if grep -Fq "Result<ProblemDetails>" "$command_file"; then
    echo "$generator must not add error DTOs to the RPC command contract" >&2
    exit 1
  fi

  restore_project "$project_file"
  dotnet build "$project_file" -c Release --nologo --no-restore
  assert_empty_response_round_trip "$generator" "$project_file" "Yaver.Response.Contracts.Model"

  assert_invalid "$generator" "$MULTIPLE_SUCCESS_FIXTURE" \
    "must declare exactly one concrete 2xx response"
  assert_invalid "$generator" "$DOMAIN_ERROR_FIXTURE" \
    "must use application/problem+json with the canonical ProblemDetails schema"
  assert_invalid "$generator" "$MULTIPLE_REPRESENTATIONS_FIXTURE" \
    "must declare at most one success representation"
  assert_invalid "$generator" "$COMPOSED_SUCCESS_FIXTURE" \
    "must not use oneOf or anyOf success alternatives"
  assert_invalid "$generator" "$NO_CONTENT_BODY_FIXTURE" \
    "response '204' must not declare a response body"
  assert_invalid "$generator" "$RESET_CONTENT_BODY_FIXTURE" \
    "response '205' must not declare a response body"
  assert_invalid "$generator" "$BODYLESS_ERROR_FIXTURE" \
    "must use application/problem+json with the canonical ProblemDetails schema"
  assert_invalid "$generator" "$INVALID_PROBLEM_DETAILS_FIXTURE" \
    "must use the canonical ProblemDetails schema: traceId must be required"
  assert_invalid "$generator" "$DEFAULT_ERROR_FIXTURE" \
    "must use application/problem+json with the canonical ProblemDetails schema"
  assert_invalid "$generator" "$SERVER_ERROR_FIXTURE" \
    "must use application/problem+json with the canonical ProblemDetails schema"
  assert_invalid "$generator" "$MISSING_SUCCESS_FIXTURE" \
    "must declare exactly one concrete 2xx response"
  assert_invalid "$generator" "$WILDCARD_SUCCESS_FIXTURE" \
    "must declare a concrete numeric 2xx response code"
  assert_invalid "$generator" "$SUCCESS_WITHOUT_SCHEMA_FIXTURE" \
    "must declare exactly one schema for its success representation"
done

split_output="$OUTPUT_DIR/yaver-cs-gateway-split"
java -cp "$YAVER_GENERATOR_JAR:$OPENAPI_GENERATOR_JAR" \
  org.openapitools.codegen.OpenAPIGenerator generate \
  -g yaver-cs-gateway \
  -i "$VALID_FIXTURE" \
  -o "$split_output" \
  --additional-properties=packageName=Yaver.Response.Contracts.Features \
  --additional-properties=targetFramework=net10.0 \
  --additional-properties=splitSchemas=true \
  --additional-properties=fastEndpointsVersion=8.2.0 \
  --additional-properties=riokMapperlyVersion=4.3.1 \
  --additional-properties=yaverResultVersion="$YAVER_RESULT_VERSION" \
  --additional-properties=messagePackVersion=3.1.8

split_project="$(find "$split_output/src" -type f -name 'Yaver.Response.Contracts.Features.csproj' -print -quit)"
split_schema_project="$(find "$split_output/src" -type f -name 'Yaver.Response.Contracts.Schemas.csproj' -print -quit)"
split_empty_response="$(find "$split_output/src" -type f -path '*Yaver.Response.Contracts.Schemas/EmptyResponse.cs' -print -quit)"
if [[ -z "$split_project" || -z "$split_schema_project" || -z "$split_empty_response" ]]; then
  echo "Expected splitSchemas gateway project and EmptyResponse were not generated" >&2
  exit 1
fi
assert_contains "$split_empty_response" "namespace Yaver.Response.Contracts.Schemas;"
assert_contains "$split_empty_response" "public EmptyResponse()"
restore_project "$split_project"
dotnet build "$split_project" -c Release --nologo --no-restore
assert_empty_response_round_trip \
  "yaver-cs-gateway-split" \
  "$split_project" \
  "Yaver.Response.Contracts.Schemas" \
  "$split_schema_project"

echo "Response contract regression OK"
