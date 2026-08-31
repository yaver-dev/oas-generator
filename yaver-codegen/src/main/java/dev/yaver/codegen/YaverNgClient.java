/*
 * Copyright 2018 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 * Copyright 2018 SmartBear Software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.yaver.codegen;

import static org.openapitools.codegen.utils.CamelizeOption.LOWERCASE_FIRST_LETTER;
import static org.openapitools.codegen.utils.StringUtils.camelize;
import static org.openapitools.codegen.utils.StringUtils.dashize;
import static org.openapitools.codegen.utils.StringUtils.underscore;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.openapitools.codegen.CliOption;
import org.openapitools.codegen.CodegenConstants;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenParameter;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.CodegenResponse;
import org.openapitools.codegen.CodegenType;
import org.openapitools.codegen.SupportingFile;
import org.openapitools.codegen.languages.TypeScriptAngularClientCodegen;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.ModelsMap;
import org.openapitools.codegen.model.OperationMap;
import org.openapitools.codegen.model.OperationsMap;
import org.openapitools.codegen.utils.SemVer;
import org.openapitools.codegen.utils.YamlConfigUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;

/**
 * Yaver Angular 22 generator (CLI id: {@code yaver-ng-client}).
 *
 * <p>Opinionated, breaking redesign of the Angular client:
 * <ul>
 *   <li>safe JSON reads become {@code httpResource}-based injection-context
 *       resource initializers ({@code injectXxxResource});</li>
 *   <li>mutations, uploads, downloads and streams remain typed RxJS
 *       {@code Observable<T>} command APIs;</li>
 *   <li>zoneless by construction: no zone.js, no NgZone, no generated
 *       subscriptions, no {@code provideHttpClient()};</li>
 *   <li>typed Signal Forms schemas in a secondary {@code /forms} entrypoint;</li>
 *   <li>one APF artifact consumed identically from a monorepo or npm.</li>
 * </ul>
 *
 * <p>The class extends the upstream {@link TypeScriptAngularClientCodegen} to
 * reuse schema parsing, TypeScript type mapping, import handling and path
 * parameter expansion. {@link #processOpts()} calls {@code super.processOpts()}
 * and then removes the upstream Angular 9&ndash;21 supporting files
 * (ApiModule, provide-api, api.base.service, ...) before installing the
 * Angular 22 file set; dependency versions are normalized to the Angular 22.1
 * matrix below. Templates come from {@code yaver-ng-client} in this JAR, so
 * same-named upstream supporting files render the Yaver templates.
 */
public class YaverNgClient extends TypeScriptAngularClientCodegen {
    private final Logger LOGGER = LoggerFactory.getLogger(YaverNgClient.class);

    /** Vendor extension selecting the generated API style for an operation. */
    public static final String EXT_NG_MODE = "x-yaver-ng-mode";
    /** Vendor extension carrying explicit, validated form UI metadata (property level only). */
    public static final String EXT_FORM_META = "x-yaver-form";

    public static final String MODE_QUERY = "query";
    public static final String MODE_COMMAND = "command";
    public static final String MODE_STREAM = "stream";

    private static final String SSE_MEDIA_TYPE = "text/event-stream";
    private static final String MULTIPART_MEDIA_TYPE = "multipart/form-data";

    /** Angular peer range declared by generated packages. */
    public static final String ANGULAR_PEER_RANGE = "^22.1.0";
    public static final String DEFAULT_NG_VERSION = "22.1.3";

    public static final String CLIENT_PREFIX = "clientPrefix";

    private static final Pattern PATH_PARAM_VALUE_PATTERN =
            Pattern.compile("value: ([A-Za-z_$][A-Za-z0-9_$]*)");

    private String clientPrefix = "Yaver";

    /** Operation classification, resolved once per operation. */
    private enum OpMode { QUERY, COMMAND, STREAM }

    /** Resource-name deduplication across the whole document. */
    private final Set<String> usedResourceNames = new HashSet<>();

    /** Models referenced by request bodies (accumulated over all API groups). */
    private final Set<String> requestBodyModels = new LinkedHashSet<>();

    private static final String ANGULAR_DEPENDENCIES_RESOURCE =
            "yaver-ng-client/angularDependenciesByVersion.yaml";

    public YaverNgClient() {
        super();

        outputFolder = "generated-code/yaver-ng-client";
        embeddedTemplateDir = templateDir = "yaver-ng-client";
        apiPackage = "src/api";
        modelPackage = "src/models";
        serviceSuffix = "Api";
        serviceFileSuffix = ".api";
        modelSuffix = "";
        modelFileSuffix = "";

        // Type-mapping hardening: no public `any` in generated code.
        typeMapping.put("AnyType", "unknown");
        typeMapping.put("Map", "Record<string, unknown>");
        typeMapping.put("map", "Record<string, unknown>");
        typeMapping.put("binary", "Blob");
        typeMapping.put("object", "Record<string, unknown>");
        languageSpecificPrimitives.add("unknown");

        ngVersion = DEFAULT_NG_VERSION;

        // Curated CLI options: everything the upstream Angular 9-21 generator
        // exposed (ApiModule prefixes, providedIn, observe overloads, zone.js
        // version, ...) is intentionally dropped. This generator is opinionated.
        cliOptions.clear();
        cliOptions.add(new CliOption(NPM_NAME,
                "The name under which to publish the generated npm package. Required to generate a full library."));
        cliOptions.add(new CliOption(NPM_VERSION,
                "The version of the generated npm package. Defaults to the OpenAPI document version."));
        cliOptions.add(new CliOption(NG_VERSION,
                "The Angular version to target. Only Angular 22.x (>= 22.1) is supported.")
                .defaultValue(DEFAULT_NG_VERSION));
        cliOptions.add(new CliOption(CLIENT_PREFIX,
                "Prefix for generated configuration symbols "
                        + "(<Prefix>ClientConfig, <PREFIX>_CLIENT_CONFIG, provide<Prefix>Client).")
                .defaultValue(clientPrefix));
        cliOptions.add(new CliOption(FILE_NAMING, "Naming convention for the output files: 'camelCase', 'kebab-case'.")
                .defaultValue(fileNaming));
        cliOptions.add(new CliOption(STRING_ENUMS, STRING_ENUMS_DESC)
                .defaultValue(String.valueOf(stringEnums)));
        cliOptions.add(new CliOption(TAGGED_UNIONS,
                "Use discriminators to create tagged unions instead of extending interfaces.")
                .defaultValue("false"));
        cliOptions.add(new CliOption(CodegenConstants.ENUM_NAME_SUFFIX, CodegenConstants.ENUM_NAME_SUFFIX_DESC)
                .defaultValue(this.enumSuffix));
        cliOptions.add(new CliOption(CodegenConstants.ENUM_PROPERTY_NAMING, CodegenConstants.ENUM_PROPERTY_NAMING_DESC)
                .defaultValue(this.enumPropertyNaming.name()));
        cliOptions.add(new CliOption(CodegenConstants.MODEL_PROPERTY_NAMING, MODEL_PROPERTY_NAMING_DESC_WITH_WARNING)
                .defaultValue(this.modelPropertyNaming.name()));
        cliOptions.add(new CliOption(CodegenConstants.PARAM_NAMING, CodegenConstants.PARAM_NAMING_DESC)
                .defaultValue(this.paramNaming.name()));
        cliOptions.add(new CliOption(CodegenConstants.LICENSE_NAME, CodegenConstants.LICENSE_NAME_DESC)
                .defaultValue(this.licenseName));

        apiTemplateFiles.clear();
        apiTemplateFiles.put("api.mustache", ".ts");
    }

    /** Public view of the curated CLI options (used by tests and tooling). */
    public List<String> cliOptionNames() {
        List<String> names = new ArrayList<>();
        for (CliOption option : cliOptions) {
            names.add(option.getOpt());
        }
        return names;
    }

    @Override
    public String getName() {
        return "yaver-ng-client";
    }

    @Override
    public String getHelp() {
        return "Generates an Angular 22.1 zoneless client library: httpResource queries, "
                + "typed Observable commands, Signal Forms schemas, APF package.";
    }

    @Override
    public CodegenType getTag() {
        return CodegenType.CLIENT;
    }

    // ------------------------------------------------------------------
    // Vendor extension validation (fail fast, before anything is written)
    // ------------------------------------------------------------------

    @Override
    public void preprocessOpenAPI(OpenAPI openAPI) {
        super.preprocessOpenAPI(openAPI);
        if (openAPI.getPaths() == null) {
            return;
        }
        openAPI.getPaths().values().stream()
                .filter(Objects::nonNull)
                .flatMap(pathItem -> pathItem.readOperations().stream())
                .filter(Objects::nonNull)
                .forEach(operation -> {
                    validateNgModeExtension(operation);
                    validateNoOperationLevelFormMeta(operation);
                });
    }

    private void validateNgModeExtension(Operation operation) {
        Object mode = operation.getExtensions() == null ? null : operation.getExtensions().get(EXT_NG_MODE);
        if (mode != null && !MODE_QUERY.equals(mode) && !MODE_COMMAND.equals(mode) && !MODE_STREAM.equals(mode)) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Invalid value '%s' for %s (operation '%s'). Must be one of: query, command, stream.",
                    mode, EXT_NG_MODE, operation.getOperationId()));
        }
    }

    private void validateNoOperationLevelFormMeta(Operation operation) {
        if (operation.getExtensions() != null && operation.getExtensions().containsKey(EXT_FORM_META)) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "%s is a property-level extension and must not be used on operation '%s'.",
                    EXT_FORM_META, operation.getOperationId()));
        }
    }

    // ------------------------------------------------------------------
    // Option processing
    // ------------------------------------------------------------------

    @Override
    public void processOpts() {
        super.processOpts(); // upstream option handling + supporting file registration

        // Validate the Angular target: this generator emits Angular 22.1+ code
        // (httpResource, Signal Forms) and nothing else.
        SemVer resolvedNgVersion = additionalProperties.containsKey(NG_VERSION)
                ? new SemVer(additionalProperties.get(NG_VERSION).toString())
                : new SemVer(ngVersion);
        if (!resolvedNgVersion.atLeast("22.1.0") || resolvedNgVersion.atLeast("23.0.0")) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Invalid ngVersion: %s. yaver-ng-client only supports Angular 22.x (>= 22.1).",
                    resolvedNgVersion));
        }
        if (!resolvedNgVersion.atLeast("22.1.0")) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Invalid ngVersion: %s. yaver-ng-client requires Angular >= 22.1 "
                            + "(httpResource and Signal Forms are only stable from 22.0/22.1 onwards).",
                    resolvedNgVersion));
        }
        additionalProperties.put("angularPeerRange", ANGULAR_PEER_RANGE);
        additionalProperties.put("defaultHttpResourceApiVersion", resolvedNgVersion.toString());

        // Drop upstream CLI options that would silently do nothing; keep the
        // help output honest.
        removeCliOption(WITH_INTERFACES);
        removeCliOption(USE_SINGLE_REQUEST_PARAMETER);
        removeCliOption(PROVIDED_IN);
        removeCliOption(API_MODULE_PREFIX);
        removeCliOption(CONFIGURATION_PREFIX);
        removeCliOption(SERVICE_SUFFIX);
        removeCliOption(SERVICE_FILE_SUFFIX);
        removeCliOption(MODEL_SUFFIX);
        removeCliOption(MODEL_FILE_SUFFIX);
        removeCliOption(QUERY_PARAM_OBJECT_FORMAT);
        removeCliOption(RXJS_VERSION);
        removeCliOption(NGPACKAGR_VERSION);
        removeCliOption(ZONEJS_VERSION);
        removeCliOption(NPM_REPOSITORY);
        removeCliOption(HTTP_CONTEXT_IN_OPTIONS);
        removeCliOption(HTTP_TRANSFER_CACHE_IN_OPTIONS);
        removeCliOption(ENFORCE_GENERIC_MODULE_WITH_PROVIDERS);
        removeCliOption("useSquareBracketsInArrayNames");
        // Guard against upstream template registration triggered by options
        // this generator does not support.
        apiTemplateFiles.remove("apiInterface.mustache");

        // Configuration symbol prefix (collision-safe naming).
        if (additionalProperties.containsKey(CLIENT_PREFIX)) {
            clientPrefix = additionalProperties.get(CLIENT_PREFIX).toString();
            if (!clientPrefix.matches("^[a-zA-Z][a-zA-Z0-9]*$")) {
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "Invalid clientPrefix '%s'. Must start with a letter and contain "
                                + "alphanumeric characters only.", clientPrefix));
            }
        }
        additionalProperties.put("clientPrefix", clientPrefix);
        additionalProperties.put("configurationClassName", clientPrefix + "ClientConfig");
        additionalProperties.put("configurationTokenName",
                underscore(clientPrefix).toUpperCase(Locale.ROOT) + "_CLIENT_CONFIG");
        additionalProperties.put("providerFnName", "provide" + clientPrefix + "Client");

        // Remove the upstream Angular 9-21 supporting files, then register the
        // Angular 22 file set. models.ts / api.ts barrels are kept from upstream
        // registration (rendered from this generator's template dir).
        removeSupportingFile("index.mustache");
        removeSupportingFile("api.module.mustache");
        removeSupportingFile("provide-api.mustache");
        removeSupportingFile("api.base.service.mustache");
        removeSupportingFile("configuration.mustache");
        removeSupportingFile("variables.mustache");
        removeSupportingFile("encoder.mustache");
        removeSupportingFile("param.mustache");
        removeSupportingFile("queryParams.mustache");
        removeSupportingFile("README.mustache");
        removeSupportingFile("gitignore");
        removeSupportingFile("git_push.sh.mustache");
        removeSupportingFile("ng-package.mustache");
        removeSupportingFile("package.mustache");
        removeSupportingFile("tsconfig.mustache");

        String srcDirectory = "src";
        String internalDirectory = "src/internal";

        supportingFiles.add(new SupportingFile("configuration.mustache", srcDirectory, "configuration.ts"));
        supportingFiles.add(new SupportingFile("request.mustache", internalDirectory, "request.ts"));
        supportingFiles.add(new SupportingFile("encoder.mustache", internalDirectory, "encoder.ts"));
        supportingFiles.add(new SupportingFile("param.mustache", internalDirectory, "param.ts"));
        supportingFiles.add(new SupportingFile("variables.mustache", internalDirectory, "variables.ts"));
        supportingFiles.add(new SupportingFile("public-api.mustache", srcDirectory, "public-api.ts"));
        supportingFiles.add(new SupportingFile("README.mustache", srcDirectory, "README.md"));
        supportingFiles.add(new SupportingFile("gitignore.mustache", "", ".gitignore"));

        // Secondary entrypoints (root-level dirs so ng-packagr derives the
        // module ids `<npmName>/forms` and `<npmName>/testing`).
        supportingFiles.add(new SupportingFile("forms-ng-package.mustache", "forms", "ng-package.json"));
        supportingFiles.add(new SupportingFile("forms-public-api.mustache", "forms", "public-api.ts"));
        supportingFiles.add(new SupportingFile("forms.mustache", "forms", "forms.ts"));
        supportingFiles.add(new SupportingFile("form-meta.mustache", "forms", "form-meta.ts"));
        supportingFiles.add(new SupportingFile("testing-ng-package.mustache", "testing", "ng-package.json"));
        supportingFiles.add(new SupportingFile("testing-public-api.mustache", "testing", "public-api.ts"));
        supportingFiles.add(new SupportingFile("testing.mustache", "testing", "testing.ts"));

        // Normalize build-dependency versions to the Angular 22.1 matrix
        // (upstream npm generation may have written versions for another line).
        if (additionalProperties.containsKey(NPM_NAME)) {
            AngularDependencies deps = resolveAngularDependencies(resolvedNgVersion);
            additionalProperties.put(TS_VERSION, deps.tsVersion);
            additionalProperties.put(RXJS_VERSION, deps.rxjsVersion);
            additionalProperties.put(NGPACKAGR_VERSION, deps.ngPackagrVersion);
            // Exact Angular build version for reproducible library builds
            // (peer ranges remain ranges for consumers).
            additionalProperties.put("ngVersionExact", resolvedNgVersion.toString());

            supportingFiles.add(new SupportingFile("package.mustache", "", "package.json"));
            supportingFiles.add(new SupportingFile("tsconfig.mustache", "", "tsconfig.json"));
            supportingFiles.add(new SupportingFile("ng-package.mustache", "", "ng-package.json"));
        }
    }

    private void removeCliOption(String name) {
        cliOptions.removeIf(option -> name.equals(option.getOpt()));
    }

    private void removeSupportingFile(String templateFile) {
        supportingFiles.removeIf(file -> templateFile.equals(file.getTemplateFile()));
    }

    static class AngularDependencies {
        private String tsVersion;
        private String rxjsVersion;
        private String ngPackagrVersion;

        public String getTsVersion() {
            return tsVersion;
        }

        public void setTsVersion(String tsVersion) {
            this.tsVersion = tsVersion;
        }

        public String getRxjsVersion() {
            return rxjsVersion;
        }

        public void setRxjsVersion(String rxjsVersion) {
            this.rxjsVersion = rxjsVersion;
        }

        public String getNgPackagrVersion() {
            return ngPackagrVersion;
        }

        public void setNgPackagrVersion(String ngPackagrVersion) {
            this.ngPackagrVersion = ngPackagrVersion;
        }
    }

    private AngularDependencies resolveAngularDependencies(SemVer ngVersion) {
        Map<String, AngularDependencies> byVersion =
                YamlConfigUtils.loadAsMap(ANGULAR_DEPENDENCIES_RESOURCE, AngularDependencies.class);
        return byVersion.entrySet().stream()
                .filter(entry -> ngVersion.atLeast(entry.getKey()))
                .max(Comparator.comparing(entry -> new SemVer(entry.getKey())))
                .map(Map.Entry::getValue)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No build dependency matrix configured for Angular " + ngVersion));
    }

    // ------------------------------------------------------------------
    // Operation classification
    // ------------------------------------------------------------------

    @Override
    public OperationsMap postProcessOperationsWithModels(OperationsMap operations, List<ModelMap> allModels) {
        OperationsMap result = super.postProcessOperationsWithModels(operations, allModels);
        OperationMap objs = result.getOperations();
        List<CodegenOperation> ops = objs.getOperation();

        Map<String, CodegenModel> modelsByClassname = new HashMap<>();
        for (ModelMap modelMap : allModels) {
            CodegenModel model = modelMap.getModel();
            if (model != null) {
                modelsByClassname.put(model.classname, model);
            }
        }

        for (CodegenOperation op : ops) {
            // Stable, documented naming: the request body is always exposed as
            // `body` (see docs), unless another parameter already uses that name.
            // bodyParam and its allParams entry are distinct objects; rename both.
            if (op.bodyParam != null) {
                boolean collision = op.allParams.stream()
                        .anyMatch(param -> param != op.bodyParam && "body".equals(param.paramName));
                if (!collision) {
                    op.bodyParam.paramName = "body";
                    op.allParams.stream()
                            .filter(param -> param.isBodyParam)
                            .forEach(param -> param.paramName = "body");
                }
            }
            classifyOperation(op);
            // Track request models for the Signal Forms entrypoint.
            if (op.bodyParam != null && op.bodyParam.isModel) {
                requestBodyModels.add(op.bodyParam.dataType);
            }
        }

        result.put("hasQueryOperations", ops.stream().anyMatch(op -> Boolean.TRUE.equals(
                op.vendorExtensions.get("isResourceOperation"))));
        // The Api class hosts command methods and stream event APIs.
        result.put("hasApiClass", ops.stream().anyMatch(op ->
                Boolean.TRUE.equals(op.vendorExtensions.get("isCommandOperation"))
                        || Boolean.TRUE.equals(op.vendorExtensions.get("isStreamOperation"))));

        // Finalize the Signal Forms payload. postProcessOperationsWithModels is
        // invoked once per API group before supporting files render; rebuilding
        // on every call means the final render sees the complete document.
        additionalProperties.put("formSchemas", buildFormsPayload(allModels, requestBodyModels));
        additionalProperties.put("hasFormSchemas", !requestBodyModels.isEmpty());

        return result;
    }

    /**
     * Classify an operation and expose computed flags to the templates.
     *
     * <p>Default classification:
     * <ul>
     *   <li>GET/HEAD whose selected Accept header is JSON -> {@code httpResource} query,</li>
     *   <li>GET returning text/event-stream -> Observable stream API,</li>
     *   <li>GET returning binary/file/text -> Observable download command,</li>
     *   <li>POST/PUT/PATCH/DELETE -> Observable command.</li>
     * </ul>
     * {@code x-yaver-ng-mode} may override: {@code command} is always allowed;
     * {@code query} only for safe reads compatible with {@code httpResource};
     * {@code stream} only for event-stream responses. Unsafe overrides fail
     * generation.
     */
    private void classifyOperation(CodegenOperation op) {
        List<String> produces = mediaTypes(op.produces);
        String selectedAccept = selectHeaderAccept(produces);

        boolean isGetOrHead = "get".equals(op.httpMethod) || "head".equals(op.httpMethod);
        boolean isEventStream = produces.contains(SSE_MEDIA_TYPE);
        boolean isBlobResponse = selectedAccept != null && !isJsonMime(selectedAccept)
                && !selectedAccept.startsWith("text");
        boolean isTextResponse = selectedAccept != null && selectedAccept.startsWith("text");
        boolean isJsonResponse = selectedAccept == null || isJsonMime(selectedAccept);
        boolean hasUploadForm = op.getHasFormParams() && consumesMultipart(op);
        boolean isBinaryBody = op.bodyParam != null && ("Blob".equals(op.bodyParam.dataType));

        boolean isDownload = isGetOrHead && isBlobResponse && !isEventStream;

        OpMode mode = defaultMode(isGetOrHead, isEventStream, isDownload, isBlobResponse, isTextResponse);
        String requested = stringExtension(op, EXT_NG_MODE);
        if (requested != null) {
            mode = applyModeOverride(op, requested, mode, isGetOrHead, isEventStream, isDownload,
                    hasUploadForm, isBinaryBody);
        }

        boolean isQuery = mode == OpMode.QUERY;
        boolean isStream = mode == OpMode.STREAM;
        boolean isCommand = mode == OpMode.COMMAND;

        String methodName = camelize(op.operationId == null ? op.nickname : op.operationId, LOWERCASE_FIRST_LETTER);
        String pascalName = camelize(op.operationId == null ? op.nickname : op.operationId);
        String paramsInterfaceName = pascalName + "Params";

        String resourceFnName = null;
        String resourceOptionsName = null;
        if (isQuery) {
            resourceFnName = "inject" + pascalName + "Resource";
            int attempt = 2;
            while (!usedResourceNames.add(resourceFnName)) {
                resourceFnName = "inject" + pascalName + "Resource" + attempt++;
            }
            resourceOptionsName = resourceFnName.substring("inject".length()) + "Options";
        }

        // Observable value type for command APIs.
        String commandValueType;
        if (isEventStream) {
            commandValueType = "string";
        } else if (isBlobResponse) {
            commandValueType = "Blob";
        } else if (isTextResponse) {
            commandValueType = "string";
        } else if (op.returnType == null || op.returnType.isEmpty()) {
            commandValueType = "void";
        } else {
            commandValueType = sanitizeAny(op.returnType);
        }

        String responseType = isEventStream ? "text" : isBlobResponse ? "blob"
                : isTextResponse ? "text" : "json";

        // Response/event variants: only where meaningful.
        boolean hasResponseVariant = isCommand || isStream;
        boolean hasEventsVariant = (isCommand && (hasUploadForm || isBlobResponse || isBinaryBody)) || isStream;

        op.vendorExtensions.put("isResourceOperation", isQuery);
        op.vendorExtensions.put("isCommandOperation", isCommand);
        op.vendorExtensions.put("isStreamOperation", isStream);
        op.vendorExtensions.put("isDownloadOperation", isDownload);
        op.vendorExtensions.put("isUploadOperation", hasUploadForm);
        op.vendorExtensions.put("useFormData", hasUploadForm);
        op.vendorExtensions.put("hasRequestBody", op.bodyParam != null || op.getHasFormParams());
        op.vendorExtensions.put("supportsSignalFormSchema",
                isCommand && op.bodyParam != null && op.bodyParam.getIsModel());
        op.vendorExtensions.put("methodName", methodName);
        op.vendorExtensions.put("operationIdPascal", pascalName);
        op.vendorExtensions.put("paramsInterfaceName", paramsInterfaceName);
        op.vendorExtensions.put("resourceFnName", resourceFnName);
        op.vendorExtensions.put("resourceOptionsName", resourceOptionsName);
        op.vendorExtensions.put("commandValueType", commandValueType);
        op.vendorExtensions.put("responseType", responseType);
        op.vendorExtensions.put("isBlobResponse", isBlobResponse);
        op.vendorExtensions.put("isJsonResponseType", "json".equals(responseType));
        op.vendorExtensions.put("hasResponseVariant", hasResponseVariant);
        op.vendorExtensions.put("hasEventsVariant", hasEventsVariant);
        op.vendorExtensions.put("errorStatuses", errorStatuses(op));
        op.vendorExtensions.put("pathWithParams", interpolatePath(op.path));

        // Resource value type: precise model type, or `unknown` when the
        // operation does not declare a success body.
        String resourceValueType = (op.returnType == null || op.returnType.isEmpty())
                ? "unknown" : sanitizeAny(op.returnType);
        op.vendorExtensions.put("resourceValueType", resourceValueType);
    }

    private OpMode defaultMode(boolean isGetOrHead, boolean isEventStream, boolean isDownload,
            boolean isBlobResponse, boolean isTextResponse) {
        if (isEventStream) {
            return OpMode.STREAM;
        }
        if (isGetOrHead && !isDownload && !isBlobResponse && !isTextResponse) {
            return OpMode.QUERY;
        }
        return OpMode.COMMAND;
    }

    private OpMode applyModeOverride(CodegenOperation op, String requested, OpMode computed,
            boolean isGetOrHead, boolean isEventStream, boolean isDownload,
            boolean hasUploadForm, boolean isBinaryBody) {
        switch (requested) {
            case MODE_COMMAND:
                // Documented escape hatch: force the imperative Observable API.
                return OpMode.COMMAND;
            case MODE_QUERY:
                if (!isGetOrHead) {
                    throw new IllegalArgumentException(String.format(Locale.ROOT,
                            "Operation '%s' declares %s=%s but uses HTTP method %s. Queries must be GET or HEAD "
                                    + "and must not have unsafe side effects.",
                            op.operationId, EXT_NG_MODE, MODE_QUERY, op.httpMethod.toUpperCase(Locale.ROOT)));
                }
                if (isEventStream) {
                    throw new IllegalArgumentException(String.format(Locale.ROOT,
                            "Operation '%s' declares %s=%s but responds with a stream. Streaming operations must "
                                    + "not be represented by httpResource.",
                            op.operationId, EXT_NG_MODE, MODE_QUERY));
                }
                if (isDownload || hasUploadForm || isBinaryBody) {
                    throw new IllegalArgumentException(String.format(Locale.ROOT,
                            "Operation '%s' declares %s=%s but transfers binary data. Binary downloads must not "
                                    + "silently become JSON resources.",
                            op.operationId, EXT_NG_MODE, MODE_QUERY));
                }
                if (op.bodyParam != null) {
                    throw new IllegalArgumentException(String.format(Locale.ROOT,
                            "Operation '%s' declares %s=%s but has a request body. Operations with bodies must "
                                    + "be commands.",
                            op.operationId, EXT_NG_MODE, MODE_QUERY));
                }
                return OpMode.QUERY;
            case MODE_STREAM:
                if (!isEventStream) {
                    throw new IllegalArgumentException(String.format(Locale.ROOT,
                            "Operation '%s' declares %s=%s but does not respond with '%s'. Streams require an "
                                    + "event-stream media type.",
                            op.operationId, EXT_NG_MODE, MODE_STREAM, SSE_MEDIA_TYPE));
                }
                return OpMode.STREAM;
            default:
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "Invalid value '%s' for %s (operation '%s'). Must be one of: query, command, stream.",
                        requested, EXT_NG_MODE, op.operationId));
        }
    }

    private String stringExtension(CodegenOperation op, String key) {
        Object value = op.vendorExtensions.get(key);
        return value == null ? null : value.toString();
    }

    private List<String> mediaTypes(List<Map<String, String>> contentList) {
        if (contentList == null) {
            return List.of();
        }
        return contentList.stream()
                .map(entry -> entry.get("mediaType"))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private boolean consumesMultipart(CodegenOperation op) {
        return mediaTypes(op.consumes).contains(MULTIPART_MEDIA_TYPE);
    }

    private String errorStatuses(CodegenOperation op) {
        if (op.responses == null) {
            return "";
        }
        return op.responses.stream()
                .map(response -> response.code)
                .filter(Objects::nonNull)
                .filter(code -> code.matches("[45]\\d\\d"))
                .distinct()
                .collect(Collectors.joining(", "));
    }

    /**
     * Adapts the upstream path-template expansion to the internal request
     * builders.
     *
     * <p>The upstream {@code ParameterExpander} emits
     * {@code ${this.configuration.encodeParam({name: "x", value: x, ...})}}.
     * The internal builders reference {@code params.<name>} instead of method
     * locals and import {@code encodeParam} from the internal param module,
     * preserving the exact encoding behavior (ISO date-time + URI encoding).
     */
    private String interpolatePath(String convertedPath) {
        String adapted = convertedPath.replace("this.configuration.", "");
        Matcher valueMatcher = PATH_PARAM_VALUE_PATTERN.matcher(adapted);
        return valueMatcher.replaceAll("value: params.$1");
    }

    /** Replicates the wire behavior of the internal param helper. */
    private String selectHeaderAccept(List<String> accepts) {
        if (accepts.isEmpty()) {
            return null;
        }
        return accepts.stream().filter(this::isJsonMime).findFirst().orElse(accepts.get(0));
    }

    private boolean isJsonMime(String mime) {
        return mime.toLowerCase(Locale.ROOT).matches("^(application/json|[^;/ \\t]+/[^;/ \\t]+[+]json)[ \\t]*(;.*)?$")
                || mime.toLowerCase(Locale.ROOT).equals("application/json-patch+json");
    }

    /** Guarantees no `any` survives into public model/operation types. */
    private String sanitizeAny(String type) {
        return type == null ? null : type.replaceAll("\\bany\\b", "unknown");
    }

    // ------------------------------------------------------------------
    // Model post-processing (no public `any`, forms data)
    // ------------------------------------------------------------------

    @Override
    public Map<String, ModelsMap> postProcessAllModels(Map<String, ModelsMap> objs) {
        Map<String, ModelsMap> result = super.postProcessAllModels(objs);
        for (ModelsMap entry : result.values()) {
            for (ModelMap mo : entry.getModels()) {
                CodegenModel cm = mo.getModel();
                if (cm == null) {
                    continue;
                }
                for (CodegenProperty var : cm.allVars) {
                    var.dataType = sanitizeAny(var.dataType);
                    if (var.datatypeWithEnum != null) {
                        var.datatypeWithEnum = sanitizeAny(var.datatypeWithEnum);
                    }
                }
            }
        }
        return result;
    }

    @Override
    public void postProcessParameter(CodegenParameter parameter) {
        super.postProcessParameter(parameter);
        parameter.dataType = sanitizeAny(parameter.dataType);
    }

    // ------------------------------------------------------------------
    // Signal Forms data (rendered by supporting files after all groups)
    // ------------------------------------------------------------------

    /**
     * Build the Signal Forms schema + form metadata payload. Invoked lazily
     * by {@link #formsPayload(java.util.List)} during supporting-file
     * rendering; kept deterministic and sorted by class name.
     */
    List<Map<String, Object>> buildFormsPayload(List<ModelMap> allModels, Set<String> requestModels) {
        List<Map<String, Object>> payload = new ArrayList<>();
        if (requestModels.isEmpty()) {
            return payload;
        }
        Set<String> mergedImports = new LinkedHashSet<>();
        Map<String, CodegenModel> modelsByClassname = new LinkedHashMap<>();
        for (ModelMap modelMap : allModels) {
            CodegenModel model = modelMap.getModel();
            if (model != null) {
                modelsByClassname.put(model.classname, model);
            }
        }

        // Transitive closure: nested models of request models are request models too.
        Deque<String> queue = new ArrayDeque<>(requestModels);
        while (!queue.isEmpty()) {
            String name = queue.poll();
            CodegenModel model = modelsByClassname.get(name);
            if (model == null) {
                continue;
            }
            for (CodegenProperty var : model.allVars) {
                String nested = referencedModel(var);
                if (nested != null && requestBodyModels.add(nested)) {
                    queue.add(nested);
                }
            }
        }

        for (String classname : requestBodyModels.stream().sorted().collect(Collectors.toList())) {
            CodegenModel model = modelsByClassname.get(classname);
            if (model == null) {
                continue;
            }
            payload.add(buildFormSchemaEntry(model, modelsByClassname));
        }
        // One merged, sorted type import for the whole forms module: separate
        // per-schema imports would produce duplicate identifiers.
        for (Map<String, Object> entry : payload) {
            @SuppressWarnings("unchecked")
            List<String> imports = (List<String>) entry.get("imports");
            mergedImports.addAll(imports);
        }
        additionalProperties.put("formImports", mergedImports.stream().sorted().collect(Collectors.toList()));
        return payload;
    }

    private String referencedModel(CodegenProperty var) {
        if (var.isModel) {
            return var.dataType;
        }
        if (var.isArray && var.items != null && var.items.isModel) {
            return var.items.dataType;
        }
        return null;
    }

    private Map<String, Object> buildFormSchemaEntry(CodegenModel model, Map<String, CodegenModel> models) {
        Map<String, Object> entry = new HashMap<>();
        String schemaName = camelize(model.classname, LOWERCASE_FIRST_LETTER) + "Schema";
        entry.put("classname", model.classname);
        entry.put("schemaName", schemaName);

        List<Map<String, Object>> props = new ArrayList<>();
        // The model's own type is always imported first: schemas reference it
        // in `schema<T>(...)` even when no nested models exist.
        List<String> imports = new ArrayList<>();
        imports.add(model.classname);
        boolean hasMeta = false;
        List<Map<String, Object>> metaProps = new ArrayList<>();

        for (CodegenProperty var : model.allVars) {
            Map<String, Object> prop = new HashMap<>();
            String plainPath = "p." + var.name;
            prop.put("propName", var.name);

            // required validation only for required, non-nullable properties
            // (nullable fields may legitimately hold null; optional fields
            // must not be converted into required fields).
            boolean alwaysPresent = Boolean.TRUE.equals(var.required) && !var.isNullable;
            prop.put("hasRequired", alwaysPresent);

            // Direct validators require a value type without null/undefined.
            Map<String, Object> direct = new HashMap<>();
            direct.put("path", plainPath);
            putValidatorFlags(direct, var);
            addStructure(direct, var, imports);
            boolean directAny = hasAnyValidator(direct);

            // Optional/nullable properties get their validators inside an
            // `applyWhenValue` guard; the `!` on the path strips the
            // type-level `undefined` branch of the field-path union (the
            // path object itself always exists).
            Map<String, Object> guarded = new HashMap<>();
            // `path` is the in-block variable used by the validators partial;
            // `guardPath` is the field path passed to applyWhenValue.
            guarded.put("path", "v");
            guarded.put("guardPath", plainPath + "!");
            guarded.put("baseType", var.dataType);
            putValidatorFlags(guarded, var);
            addStructure(guarded, var, imports);
            boolean guardedAny = !alwaysPresent && hasAnyValidator(guarded);

            prop.put("direct", direct);
            prop.put("showDirect", alwaysPresent);
            prop.put("guarded", guarded);
            prop.put("hasGuarded", guardedAny);
            if (alwaysPresent || directAny || guardedAny) {
                props.add(prop);
            }

            // x-yaver-form metadata (explicit extension only).
            Map<String, Object> meta = formMeta(var);
            if (meta != null) {
                hasMeta = true;
                meta.put("name", var.name);
                metaProps.add(meta);
            }
        }

        entry.put("hasProps", !props.isEmpty());
        entry.put("props", props);
        entry.put("imports", imports);
        entry.put("hasMeta", hasMeta);
        entry.put("metaName", camelize(model.classname, LOWERCASE_FIRST_LETTER) + "FormMeta");
        entry.put("metaProps", metaProps);
        return entry;
    }

    /** Standard OpenAPI constraints that map to Signal Forms validators. */
    private void putValidatorFlags(Map<String, Object> target, CodegenProperty var) {
        if (var.getMinLength() != null) {
            target.put("hasMinLength", true);
            target.put("minLength", var.getMinLength());
        }
        if (var.getMaxLength() != null) {
            target.put("hasMaxLength", true);
            target.put("maxLength", var.getMaxLength());
        }
        if (var.getPattern() != null && !var.getPattern().isEmpty()) {
            target.put("hasPattern", true);
            target.put("patternEscaped", escapeRegExp(var.getPattern()));
        }
        if (var.getMinimum() != null && !Boolean.TRUE.equals(var.getExclusiveMinimum())) {
            target.put("hasMin", true);
            target.put("min", new BigDecimal(var.getMinimum()).toPlainString());
        }
        if (var.getMaximum() != null && !Boolean.TRUE.equals(var.getExclusiveMaximum())) {
            target.put("hasMax", true);
            target.put("max", new BigDecimal(var.getMaximum()).toPlainString());
        }
        if (var.isEmail || "email".equals(var.getDataFormat())) {
            target.put("isEmail", true);
        }
    }

    /**
     * Adds structural rules (nested schemas and array iteration) for a property.
     */
    private void addStructure(Map<String, Object> target, CodegenProperty var, List<String> imports) {
        String nestedModel = var.isModel ? var.dataType : null;
        if (nestedModel != null) {
            target.put("nestedSchemaName", camelize(nestedModel, LOWERCASE_FIRST_LETTER) + "Schema");
            if (!imports.contains(nestedModel)) {
                imports.add(nestedModel);
            }
            return;
        }
        if (!var.isArray || var.items == null) {
            return;
        }
        CodegenProperty items = var.items;
        if (items.isModel) {
            target.put("itemSchemaName", camelize(items.dataType, LOWERCASE_FIRST_LETTER) + "Schema");
            if (!imports.contains(items.dataType)) {
                imports.add(items.dataType);
            }
            return;
        }
        // Primitive items with constraints: inline per-item validators. Items
        // that are nullable are skipped (no typed validator exists).
        if (items.isNullable) {
            return;
        }
        Map<String, Object> item = new HashMap<>();
        putValidatorFlags(item, items);
        if (hasAnyValidator(item)) {
            target.put("hasItemValidators", true);
            target.put("itemValidators", List.of(item));
        }
    }

    private boolean hasAnyValidator(Map<String, Object> flags) {
        return flags.containsKey("hasMinLength") || flags.containsKey("hasMaxLength")
                || flags.containsKey("hasPattern") || flags.containsKey("hasMin")
                || flags.containsKey("hasMax") || flags.containsKey("isEmail")
                || flags.containsKey("nestedSchemaName") || flags.containsKey("itemSchemaName")
                || flags.containsKey("hasItemValidators");
    }

    /**
     * Renders an OpenAPI pattern as a JS RegExp source string.
     * {@link CodegenProperty#getPattern()} keeps the original `/.../flags`
     * delimiters; they are stripped before escaping.
     */
    private String escapeRegExp(String pattern) {
        String source = pattern;
        if (source.startsWith("/")) {
            int lastSlash = source.lastIndexOf('/');
            if (lastSlash > 0) {
                source = source.substring(1, lastSlash) + source.substring(lastSlash + 1)
                        .replace("i", "").replace("g", "").replace("m", "").replace("s", "")
                        .replace("u", "").replace("y", "");
            }
        }
        return source.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private Map<String, Object> formMeta(CodegenProperty var) {
        Object raw = var.getVendorExtensions() == null ? null : var.getVendorExtensions().get(EXT_FORM_META);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map)) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Invalid %s on property '%s': must be an object.", EXT_FORM_META, var.baseName));
        }
        Map<?, ?> ext = (Map<?, ?>) raw;
        for (Object key : ext.keySet()) {
            if (!Set.of("label", "placeholder", "control", "options").contains(key.toString())) {
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "Unknown key '%s' in %s on property '%s'. Supported keys: label, placeholder, "
                                + "control, options.", key, EXT_FORM_META, var.baseName));
            }
        }
        Map<String, Object> meta = new HashMap<>();
        if (ext.containsKey("label")) {
            meta.put("label", ext.get("label"));
        }
        if (ext.containsKey("placeholder")) {
            meta.put("placeholder", ext.get("placeholder"));
        }
        if (ext.containsKey("control")) {
            String control = ext.get("control").toString();
            if (!Set.of("text", "textarea", "number", "email", "password", "checkbox", "select", "date",
                    "hidden") .contains(control)) {
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "Invalid control '%s' in %s on property '%s'.", control, EXT_FORM_META, var.baseName));
            }
            meta.put("control", control);
        }
        if (ext.containsKey("options")) {
            if (!(ext.get("options") instanceof List)) {
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "Invalid options in %s on property '%s': must be an array of {value, label}.",
                        EXT_FORM_META, var.baseName));
            }
            List<Map<String, Object>> options = new ArrayList<>();
            for (Object o : (List<?>) ext.get("options")) {
                if (!(o instanceof Map) || !(((Map<?, ?>) o).containsKey("value"))
                        || !(((Map<?, ?>) o).containsKey("label"))) {
                    throw new IllegalArgumentException(String.format(Locale.ROOT,
                            "Invalid option in %s on property '%s': each option needs value and label.",
                            EXT_FORM_META, var.baseName));
                }
                Map<?, ?> option = (Map<?, ?>) o;
                options.add(Map.of("value", option.get("value").toString(), "label", option.get("label").toString()));
            }
            meta.put("hasOptions", true);
            meta.put("options", options);
        }
        return meta;
    }

    @Override
    public String toApiName(String name) {
        if (name.isEmpty()) {
            return "DefaultApi";
        }
        return camelize(name) + serviceSuffix;
    }

    @Override
    public String toApiFilename(String name) {
        if (name.isEmpty()) {
            return "default.api";
        }
        return convertUsingFileNamingConvention(name) + serviceFileSuffix;
    }

    private String convertUsingFileNamingConvention(String originalName) {
        String name = removeModelPrefixSuffix(originalName);
        if ("kebab-case".equals(fileNaming)) {
            return dashize(underscore(name));
        }
        return camelize(name, LOWERCASE_FIRST_LETTER);
    }
}
