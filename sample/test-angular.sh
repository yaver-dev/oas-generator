#!/bin/bash

java -cp ../cli/yaver-generator-cliiki jar i .jar \
	org.openapitools.codegen.OpenAPIGenerator \
	generate \
	-g yaver-ts-angular \
	-i swagger.yaml \
	-o out \
	--additional-properties=npmName=Yaver.Sample.Client \
	--additional-properties=ngVersion=16

cd out

npm i
npm run build
