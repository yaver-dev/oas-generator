#!/bin/bash
set -euo pipefail

YAVER_RESULT_VERSION="${YAVER_RESULT_VERSION:-3.0.0}"
YAVER_RESULT_NUGET_SOURCE="${YAVER_RESULT_NUGET_SOURCE:-}"

java -cp ../cli/yaver-generator-cli.jar:../cli/openapi-generator-cli.jar \
	org.openapitools.codegen.OpenAPIGenerator \
	generate \
	-g yaver-proxy \
	-i swagger.yaml \
	-o out \
	--additional-properties=packageName=Yaver.Sample \
	--additional-properties=targetFramework=net10.0 \
	--additional-properties=fastEndpointsVersion=8.2.0 \
	--additional-properties=riokMapperlyVersion=4.3.1 \
	--additional-properties=yaverResultVersion="$YAVER_RESULT_VERSION"

if [[ -n "$YAVER_RESULT_NUGET_SOURCE" ]]; then
	dotnet restore out/src/Yaver.Sample/Yaver.Sample.csproj \
		--source "$YAVER_RESULT_NUGET_SOURCE" \
		--source https://api.nuget.org/v3/index.json
else
	dotnet restore out/src/Yaver.Sample/Yaver.Sample.csproj
fi
dotnet build out/src/Yaver.Sample/Yaver.Sample.csproj --no-restore
# dotnet build out/src/Pairs.BO.Contracts/Pairs.BO.Contracts.csproj
	# -o ~/W/Pairs/Lib \
