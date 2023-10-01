#!/bin/bash
# rm -rf out/lib

java -cp ../cli/yaver-generator-cli.jar:../cli/openapi-generator-cli.jar \
	org.openapitools.codegen.OpenAPIGenerator \
	generate \
	-g dart-dio \
	-i swagger.yaml \
	-o out/dart-dio
# cd out/lib

# npm i
# npm run build
