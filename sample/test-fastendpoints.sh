#!/bin/bash
java -cp ../cli/yaver-generator-cli.jar:../cli/openapi-generator-cli.jar \
	org.openapitools.codegen.OpenAPIGenerator \
	generate \
	-g yaver-cs-fastendpoints \
	-i swagger.yaml \
	-o out \
	--additional-properties=packageName=Yaver.Sample \
	--additional-properties=targetFramework=net8.0

dotnet restore

dotnet build ./out/src/Yaver.Sample/Yaver.Sample.csproj
