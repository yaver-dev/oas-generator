/*
 * Copyright 2018 OpenAPI-Generator Contributors (https://openapi-generator.tech)
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

import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.openapitools.codegen.utils.StringUtils.camelize;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
import org.openapitools.codegen.languages.AbstractCSharpCodegen;
import org.openapitools.codegen.meta.features.ClientModificationFeature;
import org.openapitools.codegen.meta.features.DocumentationFeature;
import org.openapitools.codegen.meta.features.GlobalFeature;
import org.openapitools.codegen.meta.features.ParameterFeature;
import org.openapitools.codegen.meta.features.SchemaSupportFeature;
import org.openapitools.codegen.meta.features.SecurityFeature;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.ModelsMap;
import org.openapitools.codegen.model.OperationMap;
import org.openapitools.codegen.model.OperationsMap;
import org.openapitools.codegen.utils.ModelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;
import com.samskivert.mustache.Mustache;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.servers.Server;

@SuppressWarnings("Duplicates")
public class YaverProxyCodegen extends AbstractCSharpCodegen {
    protected String apiName = "ZApi";

    // Defines the sdk option for targeted frameworks, which differs from
    // targetFramework and targetFrameworkNuget
    protected static final String MCS_NET_VERSION_KEY = "x-mcs-sdk";
    protected static final String SUPPORTS_UWP = "supportsUWP";
    protected static final String SUPPORTS_RETRY = "supportsRetry";

    protected static final String NET_STANDARD = "netStandard";

    // Project Variable, determined from target framework. Not intended to be
    // user-settable.
    protected static final String TARGET_FRAMEWORK_IDENTIFIER = "targetFrameworkIdentifier";
    // Project Variable, determined from target framework. Not intended to be
    // user-settable.
    protected static final String TARGET_FRAMEWORK_VERSION = "targetFrameworkVersion";

    protected static final String NET_70_OR_LATER = "net70OrLater";
    protected static final String NET_80_OR_LATER = "net80OrLater";
    protected static final String NET_100_OR_LATER = "net100OrLater";

    @SuppressWarnings("hiding")
    private final Logger LOGGER = LoggerFactory.getLogger(YaverProxyCodegen.class);
    private static final List<FrameworkStrategy> frameworkStrategies = Arrays.asList(
            FrameworkStrategy.NET_7_0,
            FrameworkStrategy.NET_8_0,
            FrameworkStrategy.NET_10_0);
    private static FrameworkStrategy latestFramework = frameworkStrategies.get(frameworkStrategies.size() - 1);
    protected final Map<String, String> frameworks;
    protected String packageGuid = "{" + java.util.UUID.randomUUID().toString().toUpperCase(Locale.ROOT) + "}";
    protected String clientPackage = "Client";
    protected String authFolder = "Auth";
    protected String apiDocPath = "docs/";
    protected String modelDocPath = "docs/";

    // Defines TargetFrameworkVersion in csproj files
    protected String targetFramework = latestFramework.name;
    protected String testTargetFramework = latestFramework.testTargetFramework;

    // Defines nuget identifiers for target framework
    protected String targetFrameworkNuget = targetFramework;

    protected boolean supportsRetry = Boolean.TRUE;
    protected boolean supportsAsync = Boolean.TRUE;
    protected boolean netStandard = Boolean.FALSE;
    protected boolean supportsFileParameters = Boolean.TRUE;

    protected boolean validatable = Boolean.TRUE;
    protected boolean equatable = Boolean.TRUE;
    // By default, generated code is considered public
    protected boolean nonPublicApi = Boolean.FALSE;

    protected boolean caseInsensitiveResponseHeaders = Boolean.FALSE;
    protected String releaseNote = "Minor update";
    protected String licenseId;
    protected String packageTags;
    protected boolean useOneOfDiscriminatorLookup = false; // use oneOf discriminator's mapping for model lookup

    protected boolean needsCustomHttpMethod = false;
    protected boolean needsUriBuilder = false;

    public YaverProxyCodegen() {
        super();

        modifyFeatureSet(features -> features
                .includeDocumentationFeatures(DocumentationFeature.Readme)
                .securityFeatures(EnumSet.of(
                        SecurityFeature.OAuth2_Implicit,
                        SecurityFeature.OAuth2_ClientCredentials,
                        SecurityFeature.BasicAuth,
                        SecurityFeature.BearerToken,
                        SecurityFeature.ApiKey,
                        SecurityFeature.SignatureAuth))
                .excludeGlobalFeatures(
                        GlobalFeature.XMLStructureDefinitions,
                        GlobalFeature.Callbacks,
                        GlobalFeature.LinkObjects,
                        GlobalFeature.ParameterStyling)
                .includeSchemaSupportFeatures(
                        SchemaSupportFeature.Polymorphism)
                .includeParameterFeatures(
                        ParameterFeature.Cookie)
                .includeClientModificationFeatures(
                        ClientModificationFeature.BasePath,
                        ClientModificationFeature.UserAgent));

        setSupportNullable(Boolean.TRUE);
        hideGenerationTimestamp = Boolean.TRUE;
        supportsInheritance = false;
        modelTemplateFiles.put("model.mustache", ".cs");
        apiTemplateFiles.put("rest-endpoint.mustache", "Api.cs");
        apiTemplateFiles.put("rest-request.mustache", "Requests.cs");
        apiTemplateFiles.put("rest-mapper.mustache", "Mapper.cs");
        apiTemplateFiles.put("service-command.mustache", "Commands.cs");
        // apiTemplateFiles.put("endpoint.mustache", "Endpoints.cs");
        embeddedTemplateDir = templateDir = "yaver-proxy";

        cliOptions.clear();

        // CLI options
        addOption(CodegenConstants.PACKAGE_NAME,
                "C# package name (convention: Title.Case).",
                this.packageName);

        addOption(CodegenConstants.API_NAME,
                "Must be a valid C# class name. Only used in Generic Host library. Default: " + this.apiName,
                this.apiName);

        addOption(CodegenConstants.PACKAGE_VERSION,
                "C# package version.",
                this.packageVersion);

        addOption(CodegenConstants.SOURCE_FOLDER,
                CodegenConstants.SOURCE_FOLDER_DESC,
                sourceFolder);

        addOption(CodegenConstants.OPTIONAL_PROJECT_GUID,
                CodegenConstants.OPTIONAL_PROJECT_GUID_DESC,
                null);

        addOption(CodegenConstants.INTERFACE_PREFIX,
                CodegenConstants.INTERFACE_PREFIX_DESC,
                interfacePrefix);

        addOption(CodegenConstants.LICENSE_ID,
                CodegenConstants.LICENSE_ID_DESC,
                this.licenseId);

        addOption(CodegenConstants.RELEASE_NOTE,
                CodegenConstants.RELEASE_NOTE_DESC,
                this.releaseNote);

        addOption(CodegenConstants.PACKAGE_TAGS,
                CodegenConstants.PACKAGE_TAGS_DESC,
                this.packageTags);

        addOption(DATE_FORMAT,
                "The default Date format (only `generichost` library supports this option).",
                this.dateFormat);

        addOption(DATETIME_FORMAT,
                "The default DateTime format (only `generichost` library supports this option).",
                this.dateTimeFormat);

        addOption("zeroBasedEnums",
                "Enumerations with string values will start from 0 when true, 1 when false. If not set, enumerations with string values will start from 0 if the first value is 'unknown', case insensitive.",
                null);

        CliOption framework = new CliOption(
                CodegenConstants.DOTNET_FRAMEWORK,
                CodegenConstants.DOTNET_FRAMEWORK_DESC);

        CliOption disallowAdditionalPropertiesIfNotPresentOpt = CliOption.newBoolean(
                CodegenConstants.DISALLOW_ADDITIONAL_PROPERTIES_IF_NOT_PRESENT,
                CodegenConstants.DISALLOW_ADDITIONAL_PROPERTIES_IF_NOT_PRESENT_DESC)
                .defaultValue(Boolean.TRUE.toString());
        Map<String, String> disallowAdditionalPropertiesIfNotPresentOpts = new HashMap<>();
        disallowAdditionalPropertiesIfNotPresentOpts.put("false",
                "The 'additionalProperties' implementation is compliant with the OAS and JSON schema specifications.");
        disallowAdditionalPropertiesIfNotPresentOpts.put("true",
                "Keep the old (incorrect) behaviour that 'additionalProperties' is set to false by default.");
        disallowAdditionalPropertiesIfNotPresentOpt.setEnum(disallowAdditionalPropertiesIfNotPresentOpts);
        cliOptions.add(disallowAdditionalPropertiesIfNotPresentOpt);
        this.setDisallowAdditionalPropertiesIfNotPresent(true);

        ImmutableMap.Builder<String, String> frameworkBuilder = new ImmutableMap.Builder<>();
        for (FrameworkStrategy frameworkStrategy : frameworkStrategies) {
            frameworkBuilder.put(frameworkStrategy.name, frameworkStrategy.description);
        }

        frameworks = frameworkBuilder.build();

        framework.defaultValue(this.targetFramework);
        framework.setEnum(frameworks);
        cliOptions.add(framework);

        // CliOption modelPropertyNaming = new
        // CliOption(CodegenConstants.MODEL_PROPERTY_NAMING,
        // CodegenConstants.MODEL_PROPERTY_NAMING_DESC);
        // cliOptions.add(modelPropertyNaming.defaultValue("PascalCase"));

        // CLI Switches
        addSwitch(CodegenConstants.NULLABLE_REFERENCE_TYPES,
                CodegenConstants.NULLABLE_REFERENCE_TYPES_DESC + " Starting in .NET 6.0 the default is true.",
                this.nullReferenceTypesFlag);

        addSwitch(CodegenConstants.HIDE_GENERATION_TIMESTAMP,
                CodegenConstants.HIDE_GENERATION_TIMESTAMP_DESC,
                this.hideGenerationTimestamp);

        addSwitch(CodegenConstants.USE_DATETIME_OFFSET,
                CodegenConstants.USE_DATETIME_OFFSET_DESC,
                this.useDateTimeOffsetFlag);

        addSwitch(CodegenConstants.USE_COLLECTION,
                CodegenConstants.USE_COLLECTION_DESC,
                this.useCollection);

        addSwitch(CodegenConstants.RETURN_ICOLLECTION,
                CodegenConstants.RETURN_ICOLLECTION_DESC,
                this.returnICollection);

        addSwitch(CodegenConstants.OPTIONAL_METHOD_ARGUMENT,
                "C# Optional method argument, e.g. void square(int x=10) (.net 4.0+ only).",
                this.optionalMethodArgumentFlag);

        addSwitch(CodegenConstants.OPTIONAL_ASSEMBLY_INFO,
                CodegenConstants.OPTIONAL_ASSEMBLY_INFO_DESC,
                this.optionalAssemblyInfoFlag);

        addSwitch(CodegenConstants.OPTIONAL_EMIT_DEFAULT_VALUES,
                CodegenConstants.OPTIONAL_EMIT_DEFAULT_VALUES_DESC,
                this.optionalEmitDefaultValuesFlag);

        addSwitch(CodegenConstants.OPTIONAL_CONDITIONAL_SERIALIZATION,
                CodegenConstants.OPTIONAL_CONDITIONAL_SERIALIZATION_DESC,
                this.conditionalSerialization);

        addSwitch(CodegenConstants.OPTIONAL_PROJECT_FILE,
                CodegenConstants.OPTIONAL_PROJECT_FILE_DESC,
                this.optionalProjectFileFlag);

        // NOTE: This will reduce visibility of all public members in templates. Users
        // can use InternalsVisibleTo
        // https://msdn.microsoft.com/en-us/library/system.runtime.compilerservices.internalsvisibletoattribute(v=vs.110).aspx
        // to expose to shared code if the generated code is not embedded into another
        // project. Otherwise, users of codegen
        // should rely on default public visibility.
        addSwitch(CodegenConstants.NON_PUBLIC_API,
                CodegenConstants.NON_PUBLIC_API_DESC,
                this.nonPublicApi);

        addSwitch(CodegenConstants.ALLOW_UNICODE_IDENTIFIERS,
                CodegenConstants.ALLOW_UNICODE_IDENTIFIERS_DESC,
                this.allowUnicodeIdentifiers);

        addSwitch(CodegenConstants.NETCORE_PROJECT_FILE,
                CodegenConstants.NETCORE_PROJECT_FILE_DESC,
                this.netCoreProjectFileFlag);

        addSwitch(CodegenConstants.VALIDATABLE,
                CodegenConstants.VALIDATABLE_DESC,
                this.validatable);

        addSwitch(CodegenConstants.USE_ONEOF_DISCRIMINATOR_LOOKUP,
                CodegenConstants.USE_ONEOF_DISCRIMINATOR_LOOKUP_DESC,
                this.useOneOfDiscriminatorLookup);

        addSwitch(CodegenConstants.CASE_INSENSITIVE_RESPONSE_HEADERS,
                CodegenConstants.CASE_INSENSITIVE_RESPONSE_HEADERS_DESC,
                this.caseInsensitiveResponseHeaders);

        addSwitch(CodegenConstants.EQUATABLE,
                CodegenConstants.EQUATABLE_DESC,
                this.equatable);

        addSwitch("useSourceGeneration",
                "Use source generation where available (only `generichost` library supports this option).",
                this.getUseSourceGeneration());
    }

    @Override
    public CodegenModel fromModel(String name, Schema model) {
        Map<String, Schema> allDefinitions = ModelUtils.getSchemas(this.openAPI);
        CodegenModel codegenModel = super.fromModel(name, model);
        if (allDefinitions != null && codegenModel != null && codegenModel.parent != null) {
            final Schema parentModel = allDefinitions.get(toModelName(codegenModel.parent));
            if (parentModel != null) {
                final CodegenModel parentCodegenModel = super.fromModel(codegenModel.parent, parentModel);
                if (codegenModel.hasEnums) {
                    codegenModel = this.reconcileInlineEnums(codegenModel, parentCodegenModel);
                }

                Map<String, CodegenProperty> propertyHash = new HashMap<>(codegenModel.vars.size());
                for (final CodegenProperty property : codegenModel.vars) {
                    propertyHash.put(property.name, property);
                }

                for (final CodegenProperty property : codegenModel.readWriteVars) {
                    if (property.defaultValue == null && parentCodegenModel.discriminator != null
                            && property.name.equals(parentCodegenModel.discriminator.getPropertyName())) {
                        property.defaultValue = "\"" + name + "\"";
                    }
                }

                CodegenProperty last = null;
                for (final CodegenProperty property : parentCodegenModel.vars) {
                    // helper list of parentVars simplifies templating
                    if (!propertyHash.containsKey(property.name)) {
                        final CodegenProperty parentVar = property.clone();
                        parentVar.isInherited = true;
                        last = parentVar;
                        LOGGER.debug("adding parent variable {}", property.name);
                        codegenModel.parentVars.add(parentVar);
                    }
                }
            }
        }

        // Cleanup possible duplicates. Currently, readWriteVars can contain the same
        // property twice. May or may not be isolated to C#.
        if (codegenModel != null && codegenModel.readWriteVars != null && codegenModel.readWriteVars.size() > 1) {
            int length = codegenModel.readWriteVars.size() - 1;
            for (int i = length; i > (length / 2); i--) {
                final CodegenProperty codegenProperty = codegenModel.readWriteVars.get(i);
                // If the property at current index is found earlier in the list, remove this
                // last instance.
                if (codegenModel.readWriteVars.indexOf(codegenProperty) < i) {
                    codegenModel.readWriteVars.remove(i);
                }
            }
        }

        Collections.sort(codegenModel.vars, propertyComparatorByName);
        Collections.sort(codegenModel.allVars, propertyComparatorByName);
        Collections.sort(codegenModel.requiredVars, propertyComparatorByName);
        Collections.sort(codegenModel.optionalVars, propertyComparatorByName);
        Collections.sort(codegenModel.readOnlyVars, propertyComparatorByName);
        Collections.sort(codegenModel.readWriteVars, propertyComparatorByName);
        Collections.sort(codegenModel.parentVars, propertyComparatorByName);

        Comparator<CodegenProperty> comparator = propertyComparatorByNullable
                .thenComparing(propertyComparatorByDefaultValue);
        Collections.sort(codegenModel.vars, comparator);
        Collections.sort(codegenModel.allVars, comparator);
        Collections.sort(codegenModel.requiredVars, comparator);
        Collections.sort(codegenModel.optionalVars, comparator);
        Collections.sort(codegenModel.readOnlyVars, comparator);
        Collections.sort(codegenModel.readWriteVars, comparator);
        Collections.sort(codegenModel.parentVars, comparator);
        // }

        return codegenModel;
    }

    public static Comparator<CodegenProperty> propertyComparatorByName = new Comparator<CodegenProperty>() {
        @Override
        public int compare(CodegenProperty one, CodegenProperty another) {
            return one.name.compareTo(another.name);
        }
    };

    public static Comparator<CodegenProperty> propertyComparatorByDefaultValue = new Comparator<CodegenProperty>() {
        @Override
        public int compare(CodegenProperty one, CodegenProperty another) {
            if ((one.defaultValue == null) == (another.defaultValue == null))
                return 0;
            else if (one.defaultValue == null)
                return -1;
            else
                return 1;
        }
    };

    public static Comparator<CodegenProperty> propertyComparatorByNullable = new Comparator<CodegenProperty>() {
        @Override
        public int compare(CodegenProperty one, CodegenProperty another) {
            if (one.isNullable == another.isNullable)
                return 0;
            else if (Boolean.FALSE.equals(one.isNullable))
                return -1;
            else
                return 1;
        }
    };

    public static Comparator<CodegenParameter> parameterComparatorByDataType = new Comparator<CodegenParameter>() {
        @Override
        public int compare(CodegenParameter one, CodegenParameter another) {
            return one.dataType.compareTo(another.dataType);
        }
    };

    public static Comparator<CodegenParameter> parameterComparatorByDefaultValue = new Comparator<CodegenParameter>() {
        @Override
        public int compare(CodegenParameter one, CodegenParameter another) {
            if ((one.defaultValue == null) == (another.defaultValue == null))
                return 0;
            else if (one.defaultValue == null)
                return -1;
            else
                return 1;
        }
    };

    public static Comparator<CodegenParameter> parameterComparatorByRequired = new Comparator<CodegenParameter>() {
        @Override
        public int compare(CodegenParameter one, CodegenParameter another) {
            if (one.required == another.required)
                return 0;
            else if (Boolean.TRUE.equals(one.required))
                return -1;
            else
                return 1;
        }
    };

    @Override
    public String getHelp() {
        return "Generates a C# client library (.NET Standard, .NET Core).";
    }

    @Override
    public String getName() {
        return "yaver-proxy";
    }

    @Override
    public CodegenType getTag() {
        return CodegenType.OTHER;
    }

    public void setNonPublicApi(final boolean nonPublicApi) {
        this.nonPublicApi = nonPublicApi;
    }

    @Override
    public void postProcessModelProperty(CodegenModel model, CodegenProperty property) {
        postProcessPattern(property.pattern, property.vendorExtensions);
        postProcessEmitDefaultValue(property.vendorExtensions);

        super.postProcessModelProperty(model, property);
    }

    @Override
    public String addRegularExpressionDelimiter(String pattern) {
        // Default implementation does add delimiters but it does not work for dotnet,
        // thats why we override it
        return pattern;
    }

    @Override
    public void postProcessParameter(CodegenParameter parameter) {
        super.postProcessParameter(parameter);
        postProcessEmitDefaultValue(parameter.vendorExtensions);
        parameter.paramName = camelize(parameter.paramName);
    }

    public void postProcessEmitDefaultValue(Map<String, Object> vendorExtensions) {
        vendorExtensions.put("x-emit-default-value", optionalEmitDefaultValuesFlag);
    }

    @Override
    public Mustache.Compiler processCompiler(Mustache.Compiler compiler) {
        // To avoid unexpected behaviors when options are passed programmatically such
        // as { "supportsAsync": "" }
        return super.processCompiler(compiler).emptyStringIsFalse(true);
    }

    @Override
    public void processOpts() {
        this.setLegacyDiscriminatorBehavior(false);

        super.processOpts();

        /*
         * NOTE: When supporting boolean additionalProperties, you should read the value
         * and write it back as a boolean.
         * This avoids oddities where additionalProperties contains "false" rather than
         * false, which will cause the
         * templating engine to behave unexpectedly.
         *
         * Use the pattern:
         * if (additionalProperties.containsKey(prop))
         * convertPropertyToBooleanAndWriteBack(prop);
         */

        if (additionalProperties.containsKey(CodegenConstants.DISALLOW_ADDITIONAL_PROPERTIES_IF_NOT_PRESENT)) {
            this.setDisallowAdditionalPropertiesIfNotPresent(Boolean.parseBoolean(additionalProperties
                    .get(CodegenConstants.DISALLOW_ADDITIONAL_PROPERTIES_IF_NOT_PRESENT).toString()));
        }

        if (additionalProperties.containsKey(CodegenConstants.OPTIONAL_EMIT_DEFAULT_VALUES)) {
            setOptionalEmitDefaultValuesFlag(
                    convertPropertyToBooleanAndWriteBack(CodegenConstants.OPTIONAL_EMIT_DEFAULT_VALUES));
        } else {
            additionalProperties.put(CodegenConstants.OPTIONAL_EMIT_DEFAULT_VALUES, optionalEmitDefaultValuesFlag);
        }

        if (additionalProperties.containsKey(CodegenConstants.OPTIONAL_CONDITIONAL_SERIALIZATION)) {
            setConditionalSerialization(
                    convertPropertyToBooleanAndWriteBack(CodegenConstants.OPTIONAL_CONDITIONAL_SERIALIZATION));
        } else {
            additionalProperties.put(CodegenConstants.OPTIONAL_CONDITIONAL_SERIALIZATION, conditionalSerialization);
        }

        if (additionalProperties.containsKey((CodegenConstants.LICENSE_ID))) {
            setLicenseId((String) additionalProperties.get(CodegenConstants.LICENSE_ID));
        }

        if (additionalProperties.containsKey(CodegenConstants.API_NAME)) {
            setApiName((String) additionalProperties.get(CodegenConstants.API_NAME));
        } else {
            additionalProperties.put(CodegenConstants.API_NAME, apiName);
        }

        if (isEmpty(apiPackage)) {
            setApiPackage("EndpointBase");
        }
        if (isEmpty(modelPackage)) {
            setModelPackage("Model");
        }

        String inputFramework = (String) additionalProperties.getOrDefault(CodegenConstants.DOTNET_FRAMEWORK,
                latestFramework.name);
        String[] frameworks;
        List<FrameworkStrategy> strategies = new ArrayList<>();

        if (inputFramework.contains(";")) {
            // multiple target framework
            frameworks = inputFramework.split(";");
            additionalProperties.put("multiTarget", true);
        } else {
            // just a single value
            frameworks = new String[] { inputFramework };
        }

        for (String framework : frameworks) {
            boolean strategyMatched = false;
            for (FrameworkStrategy frameworkStrategy : frameworkStrategies) {
                if (framework.equals(frameworkStrategy.name)) {
                    strategies.add(frameworkStrategy);
                    strategyMatched = true;
                }
            }

            if (!strategyMatched) {
                // throws exception if the input targetFramework is invalid
                throw new IllegalArgumentException(
                        "The input (" + inputFramework + ") contains Invalid .NET framework version: " +
                                framework + ". List of supported versions: " +
                                frameworkStrategies.stream()
                                        .map(p -> p.name)
                                        .collect(Collectors.joining(", ")));
            }
        }

        configureAdditionalPropertiesForFrameworks(additionalProperties, strategies);
        setTargetFrameworkNuget(strategies);
        setTargetFramework(strategies);
        setTestTargetFramework(strategies);

        setSupportsAsync(Boolean.TRUE);
        setNetStandard(strategies.stream().anyMatch(p -> Boolean.TRUE.equals(p.isNetStandard)));

        setNetCoreProjectFileFlag(true);
        setNullableReferenceTypes(true);

        final AtomicReference<Boolean> excludeTests = new AtomicReference<>();
        syncBooleanProperty(additionalProperties, CodegenConstants.EXCLUDE_TESTS, excludeTests::set, false);

        syncStringProperty(additionalProperties, "clientPackage", this::setClientPackage, clientPackage);

        syncStringProperty(additionalProperties, CodegenConstants.API_PACKAGE, this::setApiPackage, apiPackage);
        syncStringProperty(additionalProperties, CodegenConstants.MODEL_PACKAGE, this::setModelPackage, modelPackage);
        syncStringProperty(additionalProperties, CodegenConstants.OPTIONAL_PROJECT_GUID, this::setPackageGuid,
                packageGuid);
        syncStringProperty(additionalProperties, "targetFrameworkNuget", this::setTargetFrameworkNuget,
                this.targetFrameworkNuget);
        syncStringProperty(additionalProperties, "testTargetFramework", this::setTestTargetFramework,
                this.testTargetFramework);

        syncBooleanProperty(additionalProperties, "netStandard", this::setNetStandard, this.netStandard);

        syncBooleanProperty(additionalProperties, CodegenConstants.EQUATABLE, this::setEquatable, this.equatable);
        syncBooleanProperty(additionalProperties, CodegenConstants.VALIDATABLE, this::setValidatable, this.validatable);
        syncBooleanProperty(additionalProperties, CodegenConstants.SUPPORTS_ASYNC, this::setSupportsAsync,
                this.supportsAsync);
        syncBooleanProperty(additionalProperties, SUPPORTS_RETRY, this::setSupportsRetry, this.supportsRetry);
        syncBooleanProperty(additionalProperties, CodegenConstants.OPTIONAL_METHOD_ARGUMENT,
                this::setOptionalMethodArgumentFlag, optionalMethodArgumentFlag);

        syncBooleanProperty(additionalProperties, CodegenConstants.USE_ONEOF_DISCRIMINATOR_LOOKUP,
                this::setUseOneOfDiscriminatorLookup, this.useOneOfDiscriminatorLookup);
        syncBooleanProperty(additionalProperties, "supportsFileParameters", this::setSupportsFileParameters,
                this.supportsFileParameters);
        syncBooleanProperty(additionalProperties, "useSourceGeneration", this::setUseSourceGeneration,
                this.useSourceGeneration);

        String packageFolder = sourceFolder + File.separator + packageName;

        // Compute the relative path to the bin directory where the external assemblies
        // live
        // This is necessary to properly generate the project file
        int packageDepth = packageFolder.length() - packageFolder.replace(java.io.File.separator, "").length();
        String binRelativePath = "..\\";
        for (int i = 0; i < packageDepth; i = i + 1) {
            binRelativePath += "..\\";
        }
        binRelativePath += "vendor";
        additionalProperties.put("binRelativePath", binRelativePath);

        supportingFiles.add(new SupportingFile("README.mustache", "", "README.md"));
        supportingFiles.add(new SupportingFile("netcore_project.mustache", packageFolder, packageName + ".csproj"));
        // supportingFiles.add(new SupportingFile("Project.nuspec.mustache",
        // packageFolder, packageName + ".nuspec"));

        // include the spec in the output
        supportingFiles.add(new SupportingFile("openapi.mustache", "api", "openapi.yaml"));

        this.setTypeMapping();
    }

    @Override
    public void setUseSourceGeneration(final Boolean useSourceGeneration) {
        if (useSourceGeneration && !this.additionalProperties.containsKey(NET_80_OR_LATER)) {
            throw new RuntimeException("Source generation is only compatible with .Net 8 or later.");
        }
        this.useSourceGeneration = useSourceGeneration;
    }

    public void setClientPackage(String clientPackage) {
        this.clientPackage = clientPackage;
    }

    @Override
    public CodegenOperation fromOperation(String path,
            String httpMethod,
            Operation operation,
            List<Server> servers) {
        CodegenOperation op = super.fromOperation(path, httpMethod, operation, servers);

        Collections.sort(op.allParams, parameterComparatorByDataType);
        Collections.sort(op.bodyParams, parameterComparatorByDataType);
        Collections.sort(op.pathParams, parameterComparatorByDataType);
        Collections.sort(op.queryParams, parameterComparatorByDataType);
        Collections.sort(op.headerParams, parameterComparatorByDataType);
        Collections.sort(op.implicitHeadersParams, parameterComparatorByDataType);
        Collections.sort(op.formParams, parameterComparatorByDataType);
        Collections.sort(op.cookieParams, parameterComparatorByDataType);
        Collections.sort(op.requiredParams, parameterComparatorByDataType);
        Collections.sort(op.optionalParams, parameterComparatorByDataType);
        Collections.sort(op.notNullableParams, parameterComparatorByDataType);

        Comparator<CodegenParameter> comparator = parameterComparatorByRequired
                .thenComparing(parameterComparatorByDefaultValue);
        Collections.sort(op.allParams, comparator);
        Collections.sort(op.bodyParams, comparator);
        Collections.sort(op.pathParams, comparator);
        Collections.sort(op.queryParams, comparator);
        Collections.sort(op.headerParams, comparator);
        Collections.sort(op.implicitHeadersParams, comparator);
        Collections.sort(op.formParams, comparator);
        Collections.sort(op.cookieParams, comparator);
        Collections.sort(op.requiredParams, comparator);
        Collections.sort(op.optionalParams, comparator);
        Collections.sort(op.notNullableParams, comparator);

        return op;
    }

    public void setNetStandard(Boolean netStandard) {
        this.netStandard = netStandard;
    }

    public void setOptionalAssemblyInfoFlag(boolean flag) {
        this.optionalAssemblyInfoFlag = flag;
    }

    public void setOptionalEmitDefaultValuesFlag(boolean flag) {
        this.optionalEmitDefaultValuesFlag = flag;
    }

    public void setConditionalSerialization(boolean flag) {
        this.conditionalSerialization = flag;
    }

    public void setOptionalProjectFileFlag(boolean flag) {
        this.optionalProjectFileFlag = flag;
    }

    public void setPackageGuid(String packageGuid) {
        this.packageGuid = packageGuid;
    }

    // TODO: this does the same as super
    @Override
    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    /**
     * Sets the api name. This value must be a valid class name.
     *
     * @param apiName The api name
     */
    public void setApiName(String apiName) {
        if (!"".equals(apiName) && (Boolean.FALSE.equals(apiName.matches("^[a-zA-Z0-9_]*$"))
                || Boolean.FALSE.equals(apiName.matches("^[a-zA-Z].*")))) {
            throw new RuntimeException("Invalid project name " + apiName
                    + ". May only contain alphanumeric characters or underscore and start with a letter.");
        }
        this.apiName = apiName;
    }

    // TODO: this does the same as super
    @Override
    public void setPackageVersion(String packageVersion) {
        this.packageVersion = packageVersion;
    }

    public void setSupportsAsync(Boolean supportsAsync) {
        this.supportsAsync = supportsAsync;
    }

    public void setSupportsFileParameters(Boolean supportsFileParameters) {
        this.supportsFileParameters = supportsFileParameters;
    }

    public void setSupportsRetry(Boolean supportsRetry) {
        this.supportsRetry = supportsRetry;
    }

    public void setTargetFramework(String dotnetFramework) {
        if (!frameworks.containsKey(dotnetFramework)) {
            throw new IllegalArgumentException("Invalid .NET framework version: " +
                    dotnetFramework + ". List of supported versions: " +
                    frameworkStrategies.stream()
                            .map(p -> p.name)
                            .collect(Collectors.joining(", ")));
        } else {
            this.targetFramework = dotnetFramework;
        }
        LOGGER.info("Generating code for .NET Framework {}", this.targetFramework);
    }

    public void setTargetFramework(List<FrameworkStrategy> strategies) {
        for (FrameworkStrategy strategy : strategies) {
            if (!frameworks.containsKey(strategy.name)) {
                throw new IllegalArgumentException("Invalid .NET framework version: " +
                        strategy.name + ". List of supported versions: " +
                        frameworkStrategies.stream()
                                .map(p -> p.name)
                                .collect(Collectors.joining(", ")));
            }
        }
        this.targetFramework = strategies.stream().map(p -> p.name)
                .collect(Collectors.joining(";"));
        LOGGER.info("Generating code for .NET Framework {}", this.targetFramework);
    }

    public void setTestTargetFramework(String testTargetFramework) {
        this.testTargetFramework = testTargetFramework;
    }

    public void setTestTargetFramework(List<FrameworkStrategy> strategies) {
        this.testTargetFramework = strategies.stream().map(p -> p.testTargetFramework)
                .collect(Collectors.joining(";"));
    }

    public void setTargetFrameworkNuget(String targetFrameworkNuget) {
        this.targetFrameworkNuget = targetFrameworkNuget;
    }

    public void setTargetFrameworkNuget(List<FrameworkStrategy> strategies) {
        this.targetFrameworkNuget = strategies.stream().map(p -> p.getNugetFrameworkIdentifier())
                .collect(Collectors.joining(";"));
    }

    public void setValidatable(boolean validatable) {
        this.validatable = validatable;
    }

    public void setEquatable(boolean equatable) {
        this.equatable = equatable;
    }

    public void setCaseInsensitiveResponseHeaders(final Boolean caseInsensitiveResponseHeaders) {
        this.caseInsensitiveResponseHeaders = caseInsensitiveResponseHeaders;
    }

    public void setLicenseId(String licenseId) {
        this.licenseId = licenseId;
    }

    @Override
    public void setReleaseNote(String releaseNote) {
        this.releaseNote = releaseNote;
    }

    public void setPackageTags(String packageTags) {
        this.packageTags = packageTags;
    }

    public void setUseOneOfDiscriminatorLookup(boolean useOneOfDiscriminatorLookup) {
        this.useOneOfDiscriminatorLookup = useOneOfDiscriminatorLookup;
    }

    public boolean getUseOneOfDiscriminatorLookup() {
        return this.useOneOfDiscriminatorLookup;
    }

    @Override
    public String toEnumVarName(String value, String datatype) {
        if (value.length() == 0) {
            return "Empty";
        }

        // for symbol, e.g. $, #
        if (getSymbolName(value) != null) {
            return camelize(getSymbolName(value));
        }

        // number
        if (datatype.startsWith("int") || datatype.startsWith("uint") ||
                datatype.startsWith("long") || datatype.startsWith("ulong") ||
                datatype.startsWith("double") || datatype.startsWith("float")) {
            String varName = "NUMBER_" + value;
            varName = varName.replaceAll("-", "MINUS_");
            varName = varName.replaceAll("\\+", "PLUS_");
            varName = varName.replaceAll("\\.", "_DOT_");
            return varName;
        }

        // string
        String var = value.replaceAll(" ", "_");
        var = camelize(var);
        var = var.replaceAll("\\W+", "");

        if (var.matches("\\d.*")) {
            return "_" + var;
        } else {
            return var;
        }
    }

    @Override
    public String toModelDocFilename(String name) {
        return toModelFilename(name);
    }

    @Override
    public String toVarName(String name) {
        // obtain the name from nameMapping directly if provided
        if (nameMapping.containsKey(name)) {
            return nameMapping.get(name);
        }

        // sanitize name
        name = sanitizeName(name);

        // if it's all upper case, do nothing
        if (name.matches("^[A-Z_]*$")) {
            return name;
        }

        name = camelize(name);

        // for reserved word or word starting with number, append _
        if (isReservedWord(name) || name.matches("^\\d.*")) {
            name = escapeReservedWord(name);
        }

        // for function names in the model, escape with the "Property" prefix
        if (propertySpecialKeywords.contains(name)) {
            return camelize("property_" + name);
        }

        return name;
    }

    private CodegenModel reconcileInlineEnums(CodegenModel codegenModel, CodegenModel parentCodegenModel) {
        // This generator uses inline classes to define enums, which breaks when
        // dealing with models that have subTypes. To clean this up, we will analyze
        // the parent and child models, look for enums that match, and remove
        // them from the child models and leave them in the parent.
        // Because the child models extend the parents, the enums will be available via
        // the parent.

        // Only bother with reconciliation if the parent model has enums.
        if (parentCodegenModel.hasEnums) {

            // Get the properties for the parent and child models
            final List<CodegenProperty> parentModelCodegenProperties = parentCodegenModel.vars;
            List<CodegenProperty> codegenProperties = codegenModel.vars;

            // Iterate over all of the parent model properties
            boolean removedChildEnum = false;
            for (CodegenProperty parentModelCodegenProperty : parentModelCodegenProperties) {
                // Look for enums
                if (parentModelCodegenProperty.isEnum) {
                    // Now that we have found an enum in the parent class,
                    // and search the child class for the same enum.
                    Iterator<CodegenProperty> iterator = codegenProperties.iterator();
                    while (iterator.hasNext()) {
                        CodegenProperty codegenProperty = iterator.next();
                        if (codegenProperty.isEnum && codegenProperty.equals(parentModelCodegenProperty)) {
                            // We found an enum in the child class that is
                            // a duplicate of the one in the parent, so remove it.
                            iterator.remove();
                            removedChildEnum = true;
                        }
                    }
                }
            }

            if (removedChildEnum) {
                codegenModel.vars = codegenProperties;
            }
        }

        return codegenModel;
    }

    private void syncBooleanProperty(final Map<String, Object> additionalProperties, final String key,
            final Consumer<Boolean> setter, final Boolean defaultValue) {
        if (additionalProperties.containsKey(key)) {
            setter.accept(convertPropertyToBooleanAndWriteBack(key));
        } else {
            additionalProperties.put(key, defaultValue);
            setter.accept(defaultValue);
        }
    }

    private void syncStringProperty(final Map<String, Object> additionalProperties, final String key,
            final Consumer<String> setter, final String defaultValue) {
        if (additionalProperties.containsKey(key)) {
            setter.accept((String) additionalProperties.get(key));
        } else {
            additionalProperties.put(key, defaultValue);
            setter.accept(defaultValue);
        }
    }

    // https://docs.microsoft.com/en-us/dotnet/standard/net-standard
    @SuppressWarnings("Duplicates")
    private static abstract class FrameworkStrategy {

        private final Logger LOGGER = LoggerFactory.getLogger(YaverProxyCodegen.class);

        static FrameworkStrategy NET_7_0 = new FrameworkStrategy("net7.0", ".NET 7.0", "net7.0", Boolean.FALSE) {
        };
        static FrameworkStrategy NET_8_0 = new FrameworkStrategy("net8.0", ".NET 8.0", "net8.0", Boolean.FALSE) {
        };
        static FrameworkStrategy NET_10_0 = new FrameworkStrategy("net10.0", ".NET 10.0", "net10.0", Boolean.FALSE) {
        };
        protected String name;
        protected String description;
        protected String testTargetFramework;
        private Boolean isNetStandard = Boolean.TRUE;

        FrameworkStrategy(String name, String description, String testTargetFramework) {
            this.name = name;
            this.description = description;
            this.testTargetFramework = testTargetFramework;
        }

        FrameworkStrategy(String name, String description, String testTargetFramework, Boolean isNetStandard) {
            this.name = name;
            this.description = description;
            this.testTargetFramework = testTargetFramework;
            this.isNetStandard = isNetStandard;
        }

        protected void configureAdditionalProperties(final Map<String, Object> properties) {
            properties.putIfAbsent(CodegenConstants.DOTNET_FRAMEWORK, this.name);

            // not intended to be user-settable
            properties.put(TARGET_FRAMEWORK_IDENTIFIER, this.getTargetFrameworkIdentifier());
            properties.put(TARGET_FRAMEWORK_VERSION, this.getTargetFrameworkVersion());
            properties.putIfAbsent(MCS_NET_VERSION_KEY, "4.6-api");

            properties.put(NET_STANDARD, this.isNetStandard);
            if (properties.containsKey(SUPPORTS_UWP)) {
                LOGGER.warn(".NET {} generator does not support the UWP option. Use the csharp generator instead.",
                        this.name);
                properties.remove(SUPPORTS_UWP);
            }
        }

        protected String getNugetFrameworkIdentifier() {
            return this.name.toLowerCase(Locale.ROOT);
        }

        protected String getTargetFrameworkIdentifier() {
            if (this.isNetStandard)
                return ".NETStandard";
            else
                return ".NETCoreApp";
        }

        protected String getTargetFrameworkVersion() {
            if (this.isNetStandard)
                return "v" + this.name.replace("netstandard", "");
            else
                return "v" + this.name.replace("netcoreapp", "");
        }
    }

    protected void configureAdditionalPropertiesForFrameworks(final Map<String, Object> properties,
            List<FrameworkStrategy> strategies) {

        if (strategies.stream().anyMatch(p -> "net7.0".equals(p.name))) {
            properties.put(NET_70_OR_LATER, true);
        } else if (strategies.stream().anyMatch(p -> "net8.0".equals(p.name))) {
            properties.put(NET_70_OR_LATER, true);
            properties.put(NET_80_OR_LATER, true);
        } else if (strategies.stream().anyMatch(p -> "net10.0".equals(p.name))) {
            properties.put(NET_70_OR_LATER, true);
            properties.put(NET_80_OR_LATER, true);
            properties.put(NET_100_OR_LATER, true);
        } else {
            throw new RuntimeException("Unhandled case");
        }
    }

    /**
     * Return the instantiation type of the property, especially for map and array
     *
     * @param schema property schema
     * @return string presentation of the instantiation type of the property
     */
    @Override
    public String toInstantiationType(Schema schema) {
        if (ModelUtils.isMapSchema(schema)) {
            Schema additionalProperties = ModelUtils.getAdditionalProperties(schema);
            String inner = getSchemaType(additionalProperties);
            if (ModelUtils.isMapSchema(additionalProperties)) {
                inner = toInstantiationType(additionalProperties);
            }
            return instantiationTypes.get("map") + "<String, " + inner + ">";
        } else if (ModelUtils.isArraySchema(schema)) {
            ArraySchema arraySchema = (ArraySchema) schema;
            String inner = getSchemaType(arraySchema.getItems());
            return instantiationTypes.get("array") + "<" + inner + ">";
        } else {
            return null;
        }
    }

    @Override
    protected void patchProperty(Map<String, CodegenModel> enumRefs, CodegenModel model, CodegenProperty property) {
        super.patchProperty(enumRefs, model, property);

        // if (!GENERICHOST.equals(getLibrary())) {
        // if (!property.isContainer &&
        // (this.getNullableTypes().contains(property.dataType) || property.isEnum)) {
        // property.vendorExtensions.put("x-csharp-value-type", true);
        // }
        // } else {
        if (model.parentModel != null
                && model.parentModel.allVars.stream().anyMatch(v -> v.baseName.equals(property.baseName))) {
            property.isInherited = true;
        }
        // }
    }

    @Override
    protected void patchVendorExtensionNullableValueType(CodegenParameter parameter) {
        // if (getLibrary().equals(GENERICHOST)) {
        super.patchVendorExtensionNullableValueType(parameter);
        // } else {
        // super.patchVendorExtensionNullableValueTypeLegacy(parameter);
        // }
    }

    @Override
    public ModelsMap postProcessModels(ModelsMap objs) {
        objs = super.postProcessModels(objs);

        // add implements for serializable/parcelable to all models
        for (ModelMap mo : objs.getModels()) {
            CodegenModel cm = mo.getModel();

            if (cm.oneOf != null && !cm.oneOf.isEmpty() && cm.oneOf.contains("Null")) {
                // if oneOf contains "null" type
                cm.isNullable = true;
                cm.oneOf.remove("Null");
            }

            if (cm.anyOf != null && !cm.anyOf.isEmpty() && cm.anyOf.contains("Null")) {
                // if anyOf contains "null" type
                cm.isNullable = true;
                cm.anyOf.remove("Null");
            }

            if (cm.getComposedSchemas() != null && cm.getComposedSchemas().getOneOf() != null
                    && !cm.getComposedSchemas().getOneOf().isEmpty()) {
                cm.getComposedSchemas().getOneOf().removeIf(o -> o.dataType.equals("Null"));
            }

            if (cm.getComposedSchemas() != null && cm.getComposedSchemas().getAnyOf() != null
                    && !cm.getComposedSchemas().getAnyOf().isEmpty()) {
                cm.getComposedSchemas().getAnyOf().removeIf(o -> o.dataType.equals("Null"));
            }

            for (CodegenProperty cp : cm.readWriteVars) {
                // ISSUE: https://github.com/OpenAPITools/openapi-generator/issues/11844
                // allVars may not have all properties
                // see modules\openapi-generator\src\test\resources\3_0\allOf.yaml
                // property boosterSeat will be in readWriteVars but not allVars
                // the property is present in the model but gets removed at
                // CodegenModel#removeDuplicatedProperty
                if (Boolean.FALSE.equals(cm.allVars.stream().anyMatch(v -> v.baseName.equals(cp.baseName)))) {
                    LOGGER.debug("Property " + cp.baseName
                            + " was found in readWriteVars but not in allVars. Adding it back to allVars");
                    cm.allVars.add(cp);
                }
            }
        }

        return objs;
    }

    // https://github.com/OpenAPITools/openapi-generator/issues/15867
    @Override
    protected void removePropertiesDeclaredInComposedTypes(Map<String, ModelsMap> objs, CodegenModel model,
            List<CodegenProperty> composedProperties) {
        // if (!GENERICHOST.equals(getLibrary())) {
        // return;
        // }

        String discriminatorName = model.discriminator == null
                ? null
                : model.discriminator.getPropertyName();

        for (CodegenProperty oneOfProperty : composedProperties) {
            String ref = oneOfProperty.getRef();
            if (ref != null) {
                for (Map.Entry<String, ModelsMap> composedEntry : objs.entrySet()) {
                    CodegenModel composedModel = ModelUtils.getModelByName(composedEntry.getKey(), objs);
                    if (ref.endsWith("/" + composedModel.name)) {
                        for (CodegenProperty composedProperty : composedModel.allVars) {
                            if (discriminatorName != null && composedProperty.name.equals(discriminatorName)) {
                                continue;
                            }
                            model.vars.removeIf(v -> v.name.equals(composedProperty.name));
                            model.allVars.removeIf(v -> v.name.equals(composedProperty.name));
                            model.readOnlyVars.removeIf(v -> v.name.equals(composedProperty.name));
                            model.nonNullableVars.removeIf(v -> v.name.equals(composedProperty.name));
                            model.optionalVars.removeIf(v -> v.name.equals(composedProperty.name));
                            model.parentRequiredVars.removeIf(v -> v.name.equals(composedProperty.name));
                            model.readWriteVars.removeIf(v -> v.name.equals(composedProperty.name));
                            model.requiredVars.removeIf(v -> v.name.equals(composedProperty.name));
                        }
                    }
                }
            }
        }
    }

    /**
     * Return true if the property being passed is a C# value type
     *
     * @param var property
     * @return true if property is a value type
     */
    @Override
    protected boolean isValueType(CodegenProperty var) {
        // this is temporary until x-csharp-value-type is removed
        return // this.getLibrary().equals("generichost")
               // ?
        super.isValueType(var);
        // : this.getValueTypes().contains(var.dataType) || var.isEnum;
    }

    @Override
    protected void updateModelForObject(CodegenModel m, Schema schema) {
        /**
         * we have a custom version of this function so we only set isMap to true if
         * ModelUtils.isMapSchema
         * In other generators, isMap is true for all type object schemas
         */
        if (schema.getProperties() != null || schema.getRequired() != null && !(ModelUtils.isComposedSchema(schema))) {
            // passing null to allProperties and allRequired as there's no parent
            addVars(m, unaliasPropertySchema(schema.getProperties()), schema.getRequired(), null, null);
        }
        if (ModelUtils.isMapSchema(schema)) {
            // an object or anyType composed schema that has additionalProperties set
            addAdditionPropertiesToCodeGenModel(m, schema);
        } else {
            m.setIsMap(false);
            if (ModelUtils.isFreeFormObject(schema)) {
                // non-composed object type with no properties + additionalProperties
                // additionalProperties must be null, ObjectSchema, or empty Schema
                addAdditionPropertiesToCodeGenModel(m, schema);
            }
        }
        // process 'additionalProperties'
        setAddProps(schema, m);
    }

    @Override
    public Map<String, Object> postProcessSupportingFileData(Map<String, Object> objs) {
        generateYAMLSpecFile(objs);
        return objs;
    }

    @Override
    protected void processOperation(CodegenOperation operation) {
        super.processOperation(operation);

        // // HACK: Unlikely in the wild, but we need to clean operation paths for MVC
        // // Routing
        // if (operation.path != null) {
        // String original = operation.path;
        // operation.path = operation.path.replace("?", "/");
        // if (!original.equals(operation.path)) {
        // LOGGER.warn("Normalized {} to {}. Please verify generated source.", original,
        // operation.path);
        // }
        // }
        // operation.pu yaver ="asdasdad";
        // Converts, for example, PUT to HttpPut for controller attributes
        operation.httpMethod = operation.httpMethod.charAt(0)
                + operation.httpMethod.substring(1).toLowerCase(Locale.ROOT);
    }

    @Override
    public String toApiFilename(String name) {
        return camelize(name);
    }

    @Override
    public OperationsMap postProcessOperationsWithModels(OperationsMap objs, List<ModelMap> allModels) {
        OperationMap operations = objs.getOperations();
        List<CodegenOperation> operationList = operations.getOperation();

        for (CodegenOperation op : operationList) {

            CodegenResponse successResponse = findSuccessResponse(op);

            if (successResponse != null) {
                op.vendorExtensions.put("hasSuccessResponse", true);

                // Get response data type from different sources
                String responseModel = getResponseDataType(successResponse);
                if (responseModel != null && !responseModel.isEmpty()) {
                    op.vendorExtensions.put("isObjectResponse", false);
                    op.vendorExtensions.put("successResponseModel", responseModel);
                } else {
                    // If no data type is found, we assume it's an object
                    op.vendorExtensions.put("isObjectResponse", true);
                    op.vendorExtensions.put("successResponseModel", "EmptyResponse");
                }

                // Add response message if exists
                if (successResponse.message != null) {
                    op.vendorExtensions.put("successResponseMessage", successResponse.message);
                }

                // If it is an array
                if (Boolean.TRUE.equals(successResponse.isArray)) {
                    op.vendorExtensions.put("successResponseIsArray", true);
                    if (successResponse.items != null && successResponse.items.dataType != null) {
                        op.vendorExtensions.put("successResponseArrayType", successResponse.items.dataType);
                    }
                }

                // If it is a map
                if (Boolean.TRUE.equals(successResponse.isMap)) {
                    op.vendorExtensions.put("successResponseIsMap", true);
                    if (successResponse.additionalProperties != null
                            && successResponse.additionalProperties.dataType != null) {
                        op.vendorExtensions.put("successResponseMapType",
                                successResponse.additionalProperties.dataType);
                    }
                }
            } else {
                op.vendorExtensions.put("hasSuccessResponse", false);
            }
        }

        return super.postProcessOperationsWithModels(objs, allModels);
    }

    /**
     * Finds the success response (2xx status codes)
     */
    private CodegenResponse findSuccessResponse(CodegenOperation operation) {
        for (CodegenResponse response : operation.responses) {
            String code = response.code;
            // Check for 2xx success codes
            if (code != null && (code.equals("200") || code.equals("201") || code.equals("202") ||
                    code.equals("204") || code.startsWith("2"))) {
                return response;
            }
        }

        if (!operation.responses.isEmpty()) {
            return operation.responses.get(0);
        }

        return null;
    }

    /**
     * Tries to get the response data type from different sources
     */
    private String getResponseDataType(CodegenResponse response) {
        // If the response is a stream, return StreamResponse
        // if (response.dataType != null &&
        //         (response.dataType.equals("Stream") || response.dataType.equals("System.IO.Stream"))) {
        //     return "StreamResponse";
        // }
        // First check dataType
        if (response.dataType != null && !response.dataType.isEmpty()) {
            return response.dataType;
        }

        // Then check baseType
        if (response.baseType != null && !response.baseType.isEmpty()) {
            return response.baseType;
        }

        // If containerType exists, check it
        if (response.containerType != null && !response.containerType.isEmpty()) {
            return response.containerType;
        }

        return null;
    }
}
