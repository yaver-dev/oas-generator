#!/bin/bash
# rm -rf out/lib

java -cp ../cli/yaver-generator-cli.jar:../cli/openapi-generator-cli.jar \
	org.openapitools.codegen.OpenAPIGenerator \
	generate \
	-g yaver-ts-angular \
	-i swagger.yaml \
	-o out/lib \
	--additional-properties=npmName=@yaver/test \
	--additional-properties=configurationPrefix=YaverTest \
	--additional-properties=npmVersion=7.1.2 \
	--additional-properties=useSingleRequestParameter=true \
	--additional-properties=ngVersion=17

cd out/lib

npm i
npx ng-packagr
