#!/bin/bash
set -euo pipefail

PACKAGE_NAME="Yaver.Sample.Features"
TARGET_FRAMEWORK="net10.0"
FASTENDPOINTS_VERSION="8.1.0"
RIOK_MAPPERLY_VERSION="4.3.0"
YAVER_RESULT_VERSION="2.1.0"

FIXTURE_PATH="fixtures/pairs-auth-admin.yaml"
SPLIT_SCHEMAS="true"
KEEP_OUTPUT="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --fixture)
      FIXTURE_PATH="$2"
      shift 2
      ;;
    --split-schemas)
      SPLIT_SCHEMAS="$2"
      shift 2
      ;;
    --keep-output)
      KEEP_OUTPUT="true"
      shift
      ;;
    *)
      echo "Unknown argument: $1" >&2
      echo "Usage: $0 [--fixture <openapi-spec>] [--split-schemas true|false] [--keep-output]" >&2
      exit 2
      ;;
  esac
done

if [[ "$SPLIT_SCHEMAS" != "true" && "$SPLIT_SCHEMAS" != "false" ]]; then
  echo "--split-schemas must be true or false" >&2
  exit 2
fi

if [[ ! -f "$FIXTURE_PATH" ]]; then
  echo "Fixture not found: $FIXTURE_PATH" >&2
  exit 2
fi

MODE_SUFFIX="split-${SPLIT_SCHEMAS}"
FIXTURE_NAME="$(basename "$FIXTURE_PATH")"
FIXTURE_STEM="${FIXTURE_NAME%.*}"
SCHEMA_NAMESPACE="${PACKAGE_NAME}.Model"
if [[ "$SPLIT_SCHEMAS" == "true" ]]; then
  SCHEMA_NAMESPACE="${PACKAGE_NAME/.Features/.Schemas}"
fi

GENERATED_ROOT="out/gateway/${FIXTURE_STEM}/${MODE_SUFFIX}"
GENERATED_PROJECT="$GENERATED_ROOT/src/$PACKAGE_NAME/$PACKAGE_NAME.csproj"
SMOKE_ROOT="out/gateway-aot-smoke/${FIXTURE_STEM}/${MODE_SUFFIX}"
LOG_ROOT="out/logs/gateway-aot/${FIXTURE_STEM}/${MODE_SUFFIX}"

mkdir -p "$LOG_ROOT"

if [[ "$KEEP_OUTPUT" != "true" ]]; then
  rm -rf "$GENERATED_ROOT" "$SMOKE_ROOT"
fi

echo "=== Gateway AOT smoke ==="
echo "Fixture: $FIXTURE_PATH"
echo "SplitSchemas: $SPLIT_SCHEMAS"
echo "Generated output: $GENERATED_ROOT"
echo "Smoke host: $SMOKE_ROOT"
echo "Logs: $LOG_ROOT"

java -cp ../cli/yaver-generator-cli.jar:../cli/openapi-generator-cli.jar \
  org.openapitools.codegen.OpenAPIGenerator \
  generate \
  -g yaver-cs-gateway \
  -i "$FIXTURE_PATH" \
  -o "$GENERATED_ROOT" \
  --additional-properties=packageName=$PACKAGE_NAME \
  --additional-properties=targetFramework=$TARGET_FRAMEWORK \
  --additional-properties=splitSchemas=$SPLIT_SCHEMAS \
  --additional-properties=fastEndpointsVersion=$FASTENDPOINTS_VERSION \
  --additional-properties=riokMapperlyVersion=$RIOK_MAPPERLY_VERSION \
  --additional-properties=yaverResultVersion=$YAVER_RESULT_VERSION \
  2>&1 | tee "$LOG_ROOT/generate.log"

set +e
dotnet restore "$GENERATED_PROJECT" 2>&1 | tee "$LOG_ROOT/restore.log"
RESTORE_EXIT=${PIPESTATUS[0]}

dotnet build "$GENERATED_PROJECT" -c Release 2>&1 | tee "$LOG_ROOT/build.log"
BUILD_EXIT=${PIPESTATUS[0]}
set -e

mkdir -p "$SMOKE_ROOT"
dotnet new web -n Gateway.Aot.Smoke -o "$SMOKE_ROOT" --framework "$TARGET_FRAMEWORK" --force 2>&1 | tee "$LOG_ROOT/new-web.log"

cat > "$SMOKE_ROOT/Gateway.Aot.Smoke.csproj" <<EOF
<Project Sdk="Microsoft.NET.Sdk.Web">
  <PropertyGroup>
    <TargetFramework>$TARGET_FRAMEWORK</TargetFramework>
    <Nullable>enable</Nullable>
    <ImplicitUsings>enable</ImplicitUsings>
  </PropertyGroup>

  <ItemGroup>
  <PackageReference Include="MessagePack" Version="3.1.4" />
  </ItemGroup>

  <ItemGroup>
    <ProjectReference Include="../../../gateway/${FIXTURE_STEM}/${MODE_SUFFIX}/src/Yaver.Sample.Features/Yaver.Sample.Features.csproj" />
  </ItemGroup>
</Project>
EOF

cat > "$SMOKE_ROOT/Program.cs" <<EOF
using MessagePack;
using MessagePack.Resolvers;
using Yaver.Result;
using Yaver.Sample.Features;
using Yaver.Sample.Features.Customers.Service;
using $SCHEMA_NAMESPACE;

IRpcCommand<Result<CustomerDetail>> command = new GetCustomerCommand
{
  CustomerId = Guid.NewGuid()
};

CustomerDetail dto = new()
{
  Id = Guid.NewGuid(),
  DisplayName = "AOT Smoke",
  PhoneNumber = "+900000000000",
  UserType = "customer",
  Status = AccessSubjectStatusEnum.Active,
  ActiveSessionCount = 1,
  TrustedDeviceCount = 1,
  Roles = new List<string> { "portal.admin" },
  SessionsSummary = new AccessSessionsSummary
  {
    ActiveCount = 1,
    Items = new List<AccessSessionSummaryItem>
    {
      new()
      {
        SessionId = "session-1",
        AppKey = "web",
        ClientId = "client-1",
        Status = "active",
        IssuedAt = DateTime.UtcNow,
        ExpiresAt = DateTime.UtcNow.AddHours(1)
      }
    }
  },
  TrustedDevicesSummary = new TrustedDevicesSummary
  {
    TrustedCount = 1,
    Items = new List<TrustedDeviceSummaryItem>
    {
      new()
      {
        DeviceId = "device-1",
        AppKey = "web",
        TrustStatus = "trusted",
        FirstSeenAt = DateTime.UtcNow.AddDays(-2),
        LastSeenAt = DateTime.UtcNow
      }
    }
  },
  CreatedAt = DateTime.UtcNow.AddDays(-10),
  LastPinChangedAt = DateTime.UtcNow.AddDays(-1),
  UpdatedAt = DateTime.UtcNow
};

Result<CustomerDetail> envelope = Result.Success(dto);

IFormatterResolver resolver = CompositeResolver.Create(
  new IFormatterResolver[]
  {
    GeneratedDtoMessagePackResolver.Instance,
    YaverResultTypedMessagePackResolver<CustomerDetail>.Instance,
    YaverResultMessagePackResolver.Instance
  });

MessagePackSerializerOptions options = MessagePackSerializerOptions.Standard.WithResolver(resolver);
byte[] payload = MessagePackSerializer.Serialize(envelope, options);
Result<CustomerDetail> roundTrip = MessagePackSerializer.Deserialize<Result<CustomerDetail>>(payload, options);

if (!roundTrip.IsSuccess)
{
  throw new InvalidOperationException("Round-trip Result<CustomerDetail> was not successful.");
}

if (roundTrip.Value.Id != dto.Id)
{
  throw new InvalidOperationException("Round-trip CustomerDetail payload did not preserve Id.");
}

Console.WriteLine("MessagePack round-trip OK for Result<CustomerDetail>.");

var builder = WebApplication.CreateSlimBuilder(args);
var app = builder.Build();
app.MapGet("/", () => Results.Ok("gateway-aot-smoke"));

app.Run();
EOF

RID="osx-arm64"
if [[ "$(uname -m)" != "arm64" ]]; then
  RID="osx-x64"
fi

PUBLISH_EXIT=0
RUNTIME_EXIT=0
RUNTIME_STATUS="skipped"
if [[ $RESTORE_EXIT -eq 0 && $BUILD_EXIT -eq 0 ]]; then
  set +e
  dotnet publish "$SMOKE_ROOT/Gateway.Aot.Smoke.csproj" \
    -c Release \
    -r "$RID" \
    --self-contained true \
    -p:PublishAot=true \
    -warnaserror:IL2026,IL2070,IL2072,IL2075,IL3050 \
    2>&1 | tee "$LOG_ROOT/publish.log"
  PUBLISH_EXIT=${PIPESTATUS[0]}

  if [[ $PUBLISH_EXIT -eq 0 ]]; then
    PUBLISHED_BINARY="$SMOKE_ROOT/bin/Release/$TARGET_FRAMEWORK/$RID/publish/Gateway.Aot.Smoke"
    RUNTIME_LOG="$LOG_ROOT/runtime.log"
    RUNTIME_TIMEOUT_SECONDS="${AOT_RUNTIME_TIMEOUT_SECONDS:-12}"

    if [[ ! -x "$PUBLISHED_BINARY" ]]; then
      echo "Published binary not found or not executable: $PUBLISHED_BINARY" | tee "$RUNTIME_LOG"
      RUNTIME_EXIT=91
      RUNTIME_STATUS="binary-missing"
    else
      "$PUBLISHED_BINARY" > "$RUNTIME_LOG" 2>&1 &
      RUNTIME_PID=$!

      sleep "$RUNTIME_TIMEOUT_SECONDS"

      if kill -0 "$RUNTIME_PID" 2>/dev/null; then
        kill "$RUNTIME_PID" 2>/dev/null || true
        wait "$RUNTIME_PID" 2>/dev/null || true
        RUNTIME_STATUS="timed-out"
      else
        wait "$RUNTIME_PID"
        RUNTIME_EXIT=$?
        if [[ $RUNTIME_EXIT -eq 0 ]]; then
          RUNTIME_STATUS="exited-early-zero"
        else
          RUNTIME_STATUS="exited-early-nonzero"
        fi
      fi

      if ! grep -q "MessagePack round-trip OK for Result<CustomerDetail>." "$RUNTIME_LOG"; then
        echo "Runtime smoke did not print MessagePack round-trip success marker." >> "$RUNTIME_LOG"
        if [[ "$RUNTIME_STATUS" == "timed-out" ]]; then
          RUNTIME_EXIT=92
          RUNTIME_STATUS="roundtrip-marker-missing"
        fi
      fi

      if grep -Eiq "Unhandled exception|Exception:" "$RUNTIME_LOG"; then
        if [[ "$RUNTIME_STATUS" == "timed-out" ]]; then
          RUNTIME_EXIT=93
        fi
        RUNTIME_STATUS="exception"
      fi
    fi
  fi
  set -e
else
  echo "Publish skipped because restore/build failed." | tee "$LOG_ROOT/publish.log"
fi

cat "$LOG_ROOT"/*.log | grep -E "warning [A-Za-z]+[0-9]+:" > "$LOG_ROOT/warnings-all.txt" || true
cat "$LOG_ROOT"/*.log | grep -E "warning RMG066:" > "$LOG_ROOT/warnings-rmg066.txt" || true
cat "$LOG_ROOT"/*.log | grep -E "warning MsgPack017:" > "$LOG_ROOT/warnings-msgpack017.txt" || true
cat "$LOG_ROOT"/*.log | grep -E "warning IL[0-9]+:" > "$LOG_ROOT/warnings-il.txt" || true
cat "$LOG_ROOT"/*.log | grep -E "warning IL(2026|3050|207[0-9]+):" > "$LOG_ROOT/warnings-il-blocking.txt" || true
cat "$LOG_ROOT"/*.log | grep -E "(warning IL(2104|3053):.*MessagePack\.dll|MessagePack\.dll.*warning IL(2104|3053):)" > "$LOG_ROOT/warnings-il-messagepack-external.txt" || true

echo "=== Runtime smoke ==="
if [[ -f "$LOG_ROOT/runtime.log" ]]; then
  echo "Runtime status: $RUNTIME_STATUS"
  cat "$LOG_ROOT/runtime.log"
else
  echo "Runtime status: skipped"
fi

echo "=== Warning inventory ==="
if [[ -s "$LOG_ROOT/warnings-all.txt" ]]; then
  echo "All warnings:" 
  cat "$LOG_ROOT/warnings-all.txt"
else
  echo "All warnings: none"
fi

if [[ -s "$LOG_ROOT/warnings-il.txt" ]]; then
  echo "AOT/trim IL warnings:" 
  cat "$LOG_ROOT/warnings-il.txt"
else
  echo "AOT/trim IL warnings: none"
fi

if [[ -s "$LOG_ROOT/warnings-rmg066.txt" ]]; then
  echo "RMG066 warnings:"
  cat "$LOG_ROOT/warnings-rmg066.txt"
else
  echo "RMG066 warnings: none"
fi

if [[ -s "$LOG_ROOT/warnings-msgpack017.txt" ]]; then
  echo "MsgPack017 warnings:"
  cat "$LOG_ROOT/warnings-msgpack017.txt"
else
  echo "MsgPack017 warnings: none"
fi

if [[ -s "$LOG_ROOT/warnings-il-blocking.txt" ]]; then
  echo "Blocking IL warnings (IL2026/IL3050/IL207x):"
  cat "$LOG_ROOT/warnings-il-blocking.txt"
fi

if [[ -s "$LOG_ROOT/warnings-il-messagepack-external.txt" ]]; then
  echo "External package IL warnings (MessagePack.dll IL2104/IL3053):"
  cat "$LOG_ROOT/warnings-il-messagepack-external.txt"
fi

if [[ $RESTORE_EXIT -ne 0 ]]; then
  echo "Restore failed with exit code $RESTORE_EXIT" >&2
  exit $RESTORE_EXIT
fi

if [[ $BUILD_EXIT -ne 0 ]]; then
  echo "Build failed with exit code $BUILD_EXIT" >&2
  exit $BUILD_EXIT
fi

if [[ $PUBLISH_EXIT -ne 0 ]]; then
  echo "Publish failed with exit code $PUBLISH_EXIT" >&2
  exit $PUBLISH_EXIT
fi

if [[ "$RUNTIME_STATUS" == "exception" || "$RUNTIME_STATUS" == "binary-missing" || "$RUNTIME_STATUS" == "roundtrip-marker-missing" || "$RUNTIME_STATUS" == "exited-early-nonzero" || "$RUNTIME_STATUS" == "exited-early-zero" ]]; then
  echo "Runtime smoke failed with status: $RUNTIME_STATUS" >&2
  exit 4
fi

if [[ -s "$LOG_ROOT/warnings-il-blocking.txt" ]]; then
  echo "Blocking IL warnings were detected." >&2
  exit 3
fi

echo "Publish succeeded. Output: $SMOKE_ROOT/bin/Release/$TARGET_FRAMEWORK/$RID/publish/"
