#!/bin/bash
java -cp ../cli/yaver-generator-cli.jar:../cli/openapi-generator-cli.jar \
	org.openapitools.codegen.OpenAPIGenerator \
	generate \
	-g yaver-proxy \
	-i swagger.yaml \
	-o out \
	--additional-properties=packageName=Yaver.Sample \
	--additional-properties=targetFramework=net10.0

# dotnet restore

dotnet build out/src/Yaver.Sample/Yaver.Sample.csproj
# dotnet build out/src/Pairs.BO.Contracts/Pairs.BO.Contracts.csproj
	# -o ~/W/Pairs/Lib \