# Yaver OAS Generator

Custom [OpenAPI Generator](https://openapi-generator.tech/) plugins for the Pairs platform.
Built on top of **openapi-generator 7.21.0**, targeting **Java 21** and **Maven**.

## Generators

| Generator name           | Language   | Description                                                  |
| ------------------------ | ---------- | ------------------------------------------------------------ |
| `yaver-cs-fastendpoints` | C#         | FastEndpoints server-side endpoints, request/response models |
| `yaver-cs-gateway`       | C#         | YARP gateway/proxy route configurations                      |
| `yaver-proxy`            | C#         | gRPC service contracts + Mapperly mappers                    |
| `yaver-fetch-client`     | TypeScript | Modern Fetch API client (ESM, middleware support, TS ≥ 5.9)  |
| `yaver-ts-angular`       | TypeScript | Angular client library (Angular 9.x–18.x, ng-packagr build)  |

## Build

```bash
# Build the custom codegen JAR
./build.sh

# Build + run sample tests
./run-all.sh

# Focused Native AOT smoke test for yaver-cs-gateway
cd sample && ./test-gateway-aot.sh

# Run the same smoke for splitSchemas=false
cd sample && ./test-gateway-aot.sh --split-schemas false

# Run against explicit fixture
cd sample && ./test-gateway-aot.sh --fixture fixtures/pairs-auth-admin.yaml --split-schemas true
```

`build.sh` runs `mvn clean install` and copies the output JAR to `cli/yaver-generator-cli.jar`.

`sample/test-gateway-aot.sh` generates `yaver-cs-gateway` output to `sample/out/gateway/...`, creates a separate smoke host under `sample/out/gateway-aot-smoke/...`, then publishes with `PublishAot=true`.

The script keeps full raw logs under `sample/out/logs/gateway-aot/...` and reports complete warning inventory (`warnings-all.txt`) plus IL AOT/trimming subset (`warnings-il.txt`).

## Usage

```bash
java -cp cli/openapi-generator-cli.jar:cli/yaver-generator-cli.jar \
  org.openapitools.codegen.OpenAPIGenerator generate \
  -g yaver-cs-gateway \
  -i spec.yaml \
  -o out/ \
  --additional-properties packageName=Pairs.App.Features,...
```

### Key Additional Properties (C# generators)

| Property                  | Default   | Description                                              |
| ------------------------- | --------- | -------------------------------------------------------- |
| `packageName`             | —         | Root namespace / project name                            |
| `targetFramework`         | `net10.0` | .NET TFM                                                 |
| `fastEndpointsVersion`    | —         | FastEndpoints NuGet version                              |
| `riokMapperlyVersion`     | —         | Riok.Mapperly NuGet version                              |
| `yaverResultVersion`      | —         | Yaver.Result NuGet version                               |
| `splitSchemas`            | `false`   | Split DTOs/validators into a separate `.Schemas` project |
| `fluentValidationVersion` | `12.1.1`  | FluentValidation version (used when `splitSchemas=true`) |

### splitSchemas Feature

When `splitSchemas=true`, the `yaver-cs-gateway` generator produces **two** projects:

- **`*.Schemas`** — DTOs and `AbstractValidator<T>` validators. Depends only on FluentValidation (no FastEndpoints dependency).
- **`*.Features`** — Endpoints, request handlers, mappers. References the Schemas project via `<ProjectReference>`.

This allows non-endpoint projects (e.g., gRPC services, background workers) to reference DTOs without pulling in the FastEndpoints dependency chain.

## Release

Pushing a `v*` tag triggers the [GitHub Actions workflow](.github/workflows/relase.yml):

1. Builds the Maven project
2. Packages `openapi-generator-cli.jar` + `yaver-generator-cli.jar` into `codegen.cli.zip`
3. Publishes as a GitHub Release

Consumers download the release ZIP at:
```
https://github.com/yaver-dev/oas-generator/releases/download/<tag>/codegen.cli.zip
```

## Directory Structure

```
cli/                          # Runtime JARs (openapi-generator-cli + built output)
sample/                       # Sample specs and test scripts
yaver-codegen/                # Maven project with all custom generators
  src/main/java/dev/yaver/codegen/
    YaverCsFastendpoints.java
    YaverCsGateway.java
    YaverFetchClient.java
    YaverProxyCodegen.java
    YaverTsAngular.java
  src/main/resources/
    yaver-cs-gateway/         # Mustache templates for C# gateway generator
    yaver-fetch-client/       # Mustache templates for Fetch client
    ...
```
