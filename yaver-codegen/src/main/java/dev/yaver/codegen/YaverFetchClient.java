/*
 * Copyright 2024 Yaver Contributors
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

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

import org.openapitools.codegen.*;
import org.openapitools.codegen.languages.AbstractTypeScriptClientCodegen;
import org.openapitools.codegen.meta.features.*;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.ModelsMap;
import org.openapitools.codegen.model.OperationMap;
import org.openapitools.codegen.model.OperationsMap;
import org.openapitools.codegen.utils.ModelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.v3.oas.models.media.Schema;

/**
 * Custom TypeScript Fetch client generator for Yaver/Pairs projects.
 * Generates a minimal, modern TypeScript client using the Fetch API.
 *
 * Key features over stock typescript-fetch:
 * - Embedded tsconfig/package.json customizations (no post-generation patching)
 * - Modern ESM output with import type usage
 * - Simpler runtime with middleware support
 * - useSingleRequestParameter by default
 */
public class YaverFetchClient extends AbstractTypeScriptClientCodegen {
    private final Logger LOGGER = LoggerFactory.getLogger(YaverFetchClient.class);

    public static final String NPM_REPOSITORY = "npmRepository";
    public static final String USE_SINGLE_REQUEST_PARAMETER = "useSingleRequestParameter";
    public static final String TYPESCRIPT_VERSION = "typescriptVersion";
    public static final String PREFIX_PARAMETER_INTERFACES = "prefixParameterInterfaces";

    protected String npmRepository = null;
    protected boolean useSingleRequestParameter = true;
    protected String typescriptVersion = "^5.9.0";
    protected boolean prefixParameterInterfaces = true;

    public YaverFetchClient() {
        super();

        modifyFeatureSet(features -> features
                .includeDocumentationFeatures(DocumentationFeature.Readme)
                .includeSecurityFeatures(
                        SecurityFeature.BearerToken,
                        SecurityFeature.OAuth2_Implicit,
                        SecurityFeature.ApiKey)
                .includeGlobalFeatures(GlobalFeature.ParameterStyling));

        this.outputFolder = "generated-code/yaver-fetch-client";

        supportsMultipleInheritance = true;

        embeddedTemplateDir = templateDir = "yaver-fetch-client";
        modelTemplateFiles.put("model.mustache", ".ts");
        apiTemplateFiles.put("api.mustache", ".ts");
        languageSpecificPrimitives.add("Blob");
        typeMapping.put("file", "Blob");
        apiPackage = "src/apis";
        modelPackage = "src/models";

        this.cliOptions.add(new CliOption(NPM_REPOSITORY,
                "Use this property to set an url for your private npm repo in package.json"));
        this.cliOptions.add(CliOption.newBoolean(USE_SINGLE_REQUEST_PARAMETER,
                "Setting this property to true will generate functions with a single argument containing all API endpoint parameters instead of one argument per parameter.",
                true));
        this.cliOptions.add(new CliOption(TYPESCRIPT_VERSION,
                "TypeScript version for devDependencies").defaultValue(this.typescriptVersion));
        this.cliOptions.add(CliOption.newBoolean(PREFIX_PARAMETER_INTERFACES,
                "Whether to prefix request parameter interfaces with the API class name.",
                true));
    }

    @Override
    public String getName() {
        return "yaver-fetch-client";
    }

    @Override
    public String getHelp() {
        return "Generates a modern TypeScript Fetch client library for Yaver projects.";
    }

    @Override
    public CodegenType getTag() {
        return CodegenType.CLIENT;
    }

    @Override
    protected void addAdditionPropertiesToCodeGenModel(CodegenModel codegenModel, Schema schema) {
        codegenModel.additionalPropertiesType = getTypeDeclaration(ModelUtils.getAdditionalProperties(schema));
        addImport(codegenModel, codegenModel.additionalPropertiesType);
    }

    @Override
    public void processOpts() {
        super.processOpts();

        if (additionalProperties.containsKey(USE_SINGLE_REQUEST_PARAMETER)) {
            this.useSingleRequestParameter = convertPropertyToBoolean(USE_SINGLE_REQUEST_PARAMETER);
        }
        writePropertyBack(USE_SINGLE_REQUEST_PARAMETER, useSingleRequestParameter);

        if (additionalProperties.containsKey(NPM_REPOSITORY)) {
            this.npmRepository = additionalProperties.get(NPM_REPOSITORY).toString();
        }

        if (additionalProperties.containsKey(TYPESCRIPT_VERSION)) {
            this.typescriptVersion = additionalProperties.get(TYPESCRIPT_VERSION).toString();
        }
        additionalProperties.put(TYPESCRIPT_VERSION, this.typescriptVersion);

        if (additionalProperties.containsKey(PREFIX_PARAMETER_INTERFACES)) {
            this.prefixParameterInterfaces = convertPropertyToBoolean(PREFIX_PARAMETER_INTERFACES);
        }
        writePropertyBack(PREFIX_PARAMETER_INTERFACES, prefixParameterInterfaces);

        String srcDirectory = "";
        supportingFiles.add(new SupportingFile("runtime.mustache", "src", "runtime.ts"));
        supportingFiles.add(new SupportingFile("index.mustache", "src", "index.ts"));
        supportingFiles.add(new SupportingFile("apis.index.mustache", apiPackage, "index.ts"));
        supportingFiles.add(new SupportingFile("models.index.mustache", modelPackage, "index.ts"));
        supportingFiles.add(new SupportingFile("package.mustache", srcDirectory, "package.json"));
        supportingFiles.add(new SupportingFile("tsconfig.mustache", srcDirectory, "tsconfig.json"));
        supportingFiles.add(new SupportingFile("README.mustache", srcDirectory, "README.md"));
        supportingFiles.add(new SupportingFile("npmignore.mustache", srcDirectory, ".npmignore"));
        supportingFiles.add(new SupportingFile("gitignore", srcDirectory, ".gitignore"));
    }

    @Override
    public boolean isDataTypeFile(final String dataType) {
        return "Blob".equals(dataType);
    }

    @Override
    public String getTypeDeclaration(Schema p) {
        if (ModelUtils.isFileSchema(p)) {
            return "Blob";
        } else {
            return super.getTypeDeclaration(p);
        }
    }

    private String applyLocalTypeMapping(String type) {
        if (typeMapping.containsKey(type)) {
            type = typeMapping.get(type);
        }
        return type;
    }

    @Override
    public void postProcessParameter(CodegenParameter parameter) {
        super.postProcessParameter(parameter);
        parameter.dataType = applyLocalTypeMapping(parameter.dataType);
    }

    @Override
    public OperationsMap postProcessOperationsWithModels(OperationsMap operations, List<ModelMap> allModels) {
        OperationMap objs = operations.getOperations();
        List<CodegenOperation> ops = objs.getOperation();

        // Add the api classname for import
        objs.put("apiFilename", getApiFilenameFromClassname(objs.getClassname()));

        boolean hasEnum = false;
        for (CodegenOperation op : ops) {
            op.httpMethod = op.httpMethod.toUpperCase(Locale.ENGLISH);

            // Track if this operation has any form params
            if (op.getHasFormParams()) {
                operations.put("hasSomeFormParams", true);
            }

            // Prefix inline enum type names with operationIdCamelCase
            for (CodegenParameter param : op.allParams) {
                if (Boolean.TRUE.equals(param.isEnum)) {
                    hasEnum = true;
                    param.datatypeWithEnum = param.datatypeWithEnum
                            .replace(param.enumName, op.operationIdCamelCase + param.enumName);
                }
            }
        }
        operations.put("hasEnums", hasEnum);

        // Add additional filename information for model imports
        List<Map<String, String>> imports = operations.getImports();
        for (Map<String, String> im : imports) {
            im.put("filename", im.get("import"));
            im.put("classname", im.get("classname"));
        }

        return operations;
    }

    @Override
    public ModelsMap postProcessModels(ModelsMap objs) {
        ModelsMap result = super.postProcessModels(objs);
        return postProcessModelsEnum(result);
    }

    @Override
    public Map<String, ModelsMap> postProcessAllModels(Map<String, ModelsMap> objs) {
        Map<String, ModelsMap> result = super.postProcessAllModels(objs);
        for (ModelsMap entry : result.values()) {
            for (ModelMap mo : entry.getModels()) {
                CodegenModel cm = mo.getModel();

                // Fix inner enum type references to use concatenated name (not namespace dot notation)
                for (CodegenProperty cpVar : cm.vars) {
                    if (Boolean.TRUE.equals(cpVar.isEnum) || Boolean.TRUE.equals(cpVar.isInnerEnum)) {
                        cpVar.datatypeWithEnum = cm.classname + cpVar.enumName;
                    }
                }

                // Add additional filename information for imports
                Set<String> parsedImports = parseImports(cm);
                List<Map<String, String>> tsImportsList = toTsImports(cm, parsedImports);
                mo.put("tsImports", tsImportsList);
                mo.put("hasImports", !tsImportsList.isEmpty());
            }
        }
        return result;
    }

    /**
     * Parse imports
     */
    private Set<String> parseImports(CodegenModel cm) {
        Set<String> newImports = new HashSet<>();
        if (cm.imports.size() > 0) {
            for (String name : cm.imports) {
                if (name.indexOf(" | ") >= 0) {
                    String[] parts = name.split(" \\| ");
                    Collections.addAll(newImports, parts);
                } else {
                    newImports.add(name);
                }
            }
        }
        return newImports;
    }

    private List<Map<String, String>> toTsImports(CodegenModel cm, Set<String> imports) {
        List<Map<String, String>> tsImports = new ArrayList<>();
        for (String im : imports) {
            if (!im.equals(cm.classname)) {
                HashMap<String, String> tsImport = new HashMap<>();
                tsImport.put("classname", im);
                tsImport.put("filename", toModelFilename(im));
                tsImports.add(tsImport);
            }
        }
        return tsImports;
    }

    @Override
    public String toApiName(String name) {
        if (name.length() == 0) {
            return "DefaultApi";
        }
        return camelize(name) + "Api";
    }

    @Override
    public String toApiFilename(String name) {
        return camelize(name) + "Api";
    }

    @Override
    public String toApiImport(String name) {
        if (importMapping.containsKey(name)) {
            return importMapping.get(name);
        }
        return apiPackage() + "/" + toApiFilename(name);
    }

    @Override
    public String toModelFilename(String name) {
        if (importMapping.containsKey(name)) {
            return importMapping.get(name);
        }
        return camelize(name);
    }

    @Override
    public String toModelImport(String name) {
        if (importMapping.containsKey(name)) {
            return importMapping.get(name);
        }
        return "../" + modelPackage() + "/" + camelize(name);
    }

    public String getNpmRepository() {
        return npmRepository;
    }

    public void setNpmRepository(String npmRepository) {
        this.npmRepository = npmRepository;
    }

    private String getApiFilenameFromClassname(String classname) {
        String name = classname.substring(0, classname.length() - "Api".length());
        return toApiFilename(name);
    }
}
