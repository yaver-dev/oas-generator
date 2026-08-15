package dev.yaver.codegen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.openapitools.codegen.CodegenMediaType;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.CodegenResponse;
import org.openapitools.codegen.model.ModelMap;

/**
 * Validates the response contract supported by the Yaver RPC-to-HTTP bridge.
 *
 * <p>The bridge has exactly one successful result value and maps all HTTP
 * failures through FastEndpoints problem details. Domain-specific error DTOs
 * and multiple successful response alternatives belong in the OpenAPI design,
 * not in transport-specific generated envelopes.</p>
 */
final class ResponseContractValidator {
    static final String PROBLEM_DETAILS_MEDIA_TYPE = "application/problem+json";
    static final String PROBLEM_DETAILS_MODEL = "ProblemDetails";

    private static final Pattern EXACT_SUCCESS_CODE = Pattern.compile("2\\d{2}");
    private static final List<String> REQUIRED_PROBLEM_DETAILS_PROPERTIES = List.of(
            "type", "title", "status", "instance", "traceId", "errors");

    private ResponseContractValidator() {
    }

    static CodegenResponse requireSingleSuccessResponse(CodegenOperation operation, List<ModelMap> allModels) {
        List<CodegenResponse> successResponses = operation.responses.stream()
                .filter(ResponseContractValidator::isSuccessResponse)
                .collect(Collectors.toList());

        if (successResponses.size() != 1) {
            String declaredResponses = operation.responses.stream()
                    .map(ResponseContractValidator::describeResponse)
                    .collect(Collectors.joining(", "));
            throw new IllegalArgumentException(
                    "Operation '" + operation.operationId
                            + "' must declare exactly one concrete 2xx response; found "
                            + successResponses.size() + ". Declared responses: ["
                            + declaredResponses + "]");
        }

        CodegenResponse response = successResponses.get(0);
        if (response.code == null || !EXACT_SUCCESS_CODE.matcher(response.code).matches()) {
            throw new IllegalArgumentException(
                    "Operation '" + operation.operationId
                            + "' must declare a concrete numeric 2xx response code; found '"
                            + response.code + "'.");
        }

        requireSingleSuccessRepresentation(operation, response, allModels);
        return response;
    }

    static void requireProblemDetailsErrors(CodegenOperation operation, List<ModelMap> allModels) {
        boolean hasProblemDetailsError = false;

        for (CodegenResponse response : operation.responses) {
            if (!isErrorResponse(response)) {
                continue;
            }

            hasProblemDetailsError = true;

            Map<String, CodegenMediaType> content = response.getContent();
            List<String> mediaTypes = content == null
                    ? List.of()
                    : new ArrayList<>(content.keySet());
            String responseModel = getResponseDataType(response);

            if (mediaTypes.size() != 1
                    || !PROBLEM_DETAILS_MEDIA_TYPE.equalsIgnoreCase(mediaTypes.get(0))
                    || !PROBLEM_DETAILS_MODEL.equals(responseModel)) {
                throw new IllegalArgumentException(
                        "Operation '" + operation.operationId + "' response '" + response.code
                                + "' must use " + PROBLEM_DETAILS_MEDIA_TYPE
                                + " with the canonical " + PROBLEM_DETAILS_MODEL
                                + " schema; found media types " + mediaTypes
                                + " and model '" + responseModel + "'.");
            }
        }

        if (hasProblemDetailsError) {
            requireCanonicalProblemDetailsModels(operation, allModels);
        }
    }

    static boolean isBodylessSuccess(CodegenResponse response) {
        return response != null && ("204".equals(response.code) || "205".equals(response.code));
    }

    static String getResponseDataType(CodegenResponse response) {
        if (response.dataType != null && !response.dataType.isEmpty()) {
            return response.dataType;
        }
        if (response.baseType != null && !response.baseType.isEmpty()) {
            return response.baseType;
        }
        if (response.containerType != null && !response.containerType.isEmpty()) {
            return response.containerType;
        }
        return null;
    }

    private static boolean isSuccessResponse(CodegenResponse response) {
        return response.is2xx
                || (response.code != null && response.code.startsWith("2"));
    }

    private static boolean isErrorResponse(CodegenResponse response) {
        return response.isDefault
                || response.is4xx
                || response.is5xx
                || (response.code != null
                        && (response.code.startsWith("4") || response.code.startsWith("5")));
    }

    static boolean hasResponseBody(CodegenResponse response) {
        Map<String, CodegenMediaType> content = response.getContent();
        return (content != null && !content.isEmpty())
                || getResponseDataType(response) != null;
    }

    private static void requireSingleSuccessRepresentation(
            CodegenOperation operation, CodegenResponse response, List<ModelMap> allModels) {
        Map<String, CodegenMediaType> content = response.getContent();
        List<String> mediaTypes = content == null ? List.of() : new ArrayList<>(content.keySet());

        if (isBodylessSuccess(response)) {
            if (hasResponseBody(response)) {
                throw new IllegalArgumentException(
                        "Operation '" + operation.operationId + "' response '" + response.code
                                + "' must not declare a response body.");
            }
            return;
        }

        if (mediaTypes.size() > 1) {
            throw new IllegalArgumentException(
                    "Operation '" + operation.operationId + "' response '" + response.code
                            + "' must declare at most one success representation; found media types "
                            + mediaTypes + ".");
        }

        if (mediaTypes.size() == 1) {
            CodegenProperty schema = content.get(mediaTypes.get(0)).getSchema();
            if (schema == null) {
                throw new IllegalArgumentException(
                        "Operation '" + operation.operationId + "' response '" + response.code
                                + "' must declare exactly one schema for its success representation.");
            }
            CodegenModel responseModel = findModel(allModels, getResponseDataType(response));
            if (hasAlternativeSchemas(schema) || hasAlternativeSchemas(responseModel)) {
                throw new IllegalArgumentException(
                        "Operation '" + operation.operationId + "' response '" + response.code
                                + "' must not use oneOf or anyOf success alternatives.");
            }
        }
    }

    private static boolean hasAlternativeSchemas(CodegenProperty schema) {
        if (schema.getComposedSchemas() == null) {
            return false;
        }

        return (schema.getComposedSchemas().getOneOf() != null
                && !schema.getComposedSchemas().getOneOf().isEmpty())
                || (schema.getComposedSchemas().getAnyOf() != null
                && !schema.getComposedSchemas().getAnyOf().isEmpty());
    }

    private static boolean hasAlternativeSchemas(CodegenModel model) {
        if (model == null) {
            return false;
        }

        return (model.oneOf != null && !model.oneOf.isEmpty())
                || (model.anyOf != null && !model.anyOf.isEmpty())
                || (model.getComposedSchemas() != null
                && ((model.getComposedSchemas().getOneOf() != null
                        && !model.getComposedSchemas().getOneOf().isEmpty())
                    || (model.getComposedSchemas().getAnyOf() != null
                        && !model.getComposedSchemas().getAnyOf().isEmpty())));
    }

    private static void requireCanonicalProblemDetailsModels(CodegenOperation operation, List<ModelMap> allModels) {
        CodegenModel problemDetails = findModel(allModels, PROBLEM_DETAILS_MODEL);
        CodegenModel problemDetailsError = findModel(allModels, "ProblemDetailsError");

        if (problemDetails == null || problemDetailsError == null) {
            throw new IllegalArgumentException(
                    "Operation '" + operation.operationId
                            + "' must define the canonical ProblemDetails and ProblemDetailsError schemas.");
        }

        for (String propertyName : REQUIRED_PROBLEM_DETAILS_PROPERTIES) {
            CodegenProperty property = findProperty(problemDetails, propertyName);
            if (property == null || !property.required) {
                throw invalidProblemDetailsSchema(operation, propertyName + " must be required");
            }
        }

        requireStringProperty(operation, problemDetails, "type");
        requireStringProperty(operation, problemDetails, "title");
        requireIntegerProperty(operation, problemDetails, "status");
        requireStringProperty(operation, problemDetails, "instance");
        requireStringProperty(operation, problemDetails, "traceId");

        CodegenProperty errors = findProperty(problemDetails, "errors");
        if (errors == null || !errors.isArray || errors.items == null
                || !"ProblemDetailsError".equals(firstNonBlank(
                        errors.items.complexType, errors.items.baseType, errors.items.dataType))) {
            throw invalidProblemDetailsSchema(operation, "errors must be an array of ProblemDetailsError");
        }

        requireRequiredStringProperty(operation, problemDetailsError, "name");
        requireRequiredStringProperty(operation, problemDetailsError, "reason");
        requireOptionalStringProperty(operation, problemDetailsError, "code");
        requireOptionalStringProperty(operation, problemDetailsError, "severity");
    }

    private static CodegenModel findModel(List<ModelMap> allModels, String modelName) {
        return allModels.stream()
                .map(ModelMap::getModel)
                .filter(model -> modelName.equals(model.classname) || modelName.equals(model.name))
                .findFirst()
                .orElse(null);
    }

    private static CodegenProperty findProperty(CodegenModel model, String propertyName) {
        if (model == null || model.allVars == null) {
            return null;
        }

        return model.allVars.stream()
                .filter(property -> propertyName.equals(property.baseName))
                .findFirst()
                .orElse(null);
    }

    private static void requireRequiredStringProperty(
            CodegenOperation operation, CodegenModel model, String propertyName) {
        CodegenProperty property = findProperty(model, propertyName);
        if (property == null || !property.required || !property.isString) {
            throw invalidProblemDetailsSchema(operation, propertyName + " must be a required string");
        }
    }

    private static void requireOptionalStringProperty(
            CodegenOperation operation, CodegenModel model, String propertyName) {
        CodegenProperty property = findProperty(model, propertyName);
        if (property == null || property.required || !property.isString) {
            throw invalidProblemDetailsSchema(operation, propertyName + " must be an optional string");
        }
    }

    private static void requireStringProperty(
            CodegenOperation operation, CodegenModel model, String propertyName) {
        CodegenProperty property = findProperty(model, propertyName);
        if (property == null || !property.isString) {
            throw invalidProblemDetailsSchema(operation, propertyName + " must be a string");
        }
    }

    private static void requireIntegerProperty(
            CodegenOperation operation, CodegenModel model, String propertyName) {
        CodegenProperty property = findProperty(model, propertyName);
        if (property == null || !property.isInteger) {
            throw invalidProblemDetailsSchema(operation, propertyName + " must be an integer");
        }
    }

    private static IllegalArgumentException invalidProblemDetailsSchema(
            CodegenOperation operation, String reason) {
        return new IllegalArgumentException(
                "Operation '" + operation.operationId
                        + "' must use the canonical ProblemDetails schema: " + reason + ".");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String describeResponse(CodegenResponse response) {
        String model = getResponseDataType(response);
        return response.code + (model == null ? "" : " " + model);
    }
}
