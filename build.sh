#!/bin/bash
set -euo pipefail

mvn -f ./yaver-codegen/pom.xml clean package
cp yaver-codegen/target/yaver-codegen.jar ./cli/yaver-generator-cli.jar

# mkdir tmp
# (
# 	cd tmp
# 	unzip -uoq ../yaver-codegen/target/yaver-codegen.jar
# )
# (
# 	cd tmp
# 	unzip -uoq ../cli/openapi-generator-cli.jar
# )
# jar -cvf cli/yaver-generator-cli.jar -C tmp .
