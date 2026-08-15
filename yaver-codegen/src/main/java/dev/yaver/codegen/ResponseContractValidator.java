package dev.yaver.codegen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.openapitools.codegen.CodegenMediaType;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenResponse;

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

    private ResponseContractValidator() {
    }

    static CodegenResponse requireSingleSuccessResponse(CodegenOperation operation) {
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

        return response;
    }

    static void requireProblemDetailsErrors(CodegenOperation operation) {
        for (CodegenResponse response : operation.responses) {
            if (!isErrorResponse(response) || !hasResponseBody(response)) {
                continue;
            }

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

    private static boolean hasResponseBody(CodegenResponse response) {
        Map<String, CodegenMediaType> content = response.getContent();
        return (content != null && !content.isEmpty())
                || getResponseDataType(response) != null;
    }

    private static String describeResponse(CodegenResponse response) {
        String model = getResponseDataType(response);
        return response.code + (model == null ? "" : " " + model);
    }
}
