package com.springurlextractor;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;

import java.util.*;
import java.util.stream.Collectors;

public class CurlGenerator {
    private final Project project;
    private final SpringUrlExtractor urlExtractor;

    private final Set<String> MAPPING_ANNOTATIONS = Set.of(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping",
            "DeleteMapping", "PatchMapping"
    );

    private final Set<String> BODY_ANNOTATIONS = Set.of(
            "RequestBody"
    );

    private final Set<String> PATH_VARIABLE_ANNOTATIONS = Set.of(
            "PathVariable"
    );

    private final Set<String> REQUEST_PARAM_ANNOTATIONS = Set.of(
            "RequestParam"
    );

    public CurlGenerator(Project project) {
        this.project = project;
        this.urlExtractor = new SpringUrlExtractor(project);
    }

    public String generateCurl(PsiMethod method) {
        String url = urlExtractor.extractUrl(method);
        if (url == null) {
            return null;
        }

        String httpMethod = getHttpMethod(method);
        List<PathVariable> pathVariables = extractPathVariables(method);
        List<RequestParam> requestParams = extractRequestParams(method);
        RequestBodyInfo requestBody = extractRequestBody(method);
        String contentType = getContentType(method, requestBody);

        return buildCurlCommand(url, httpMethod, pathVariables, requestParams, requestBody, contentType);
    }

    private String getHttpMethod(PsiMethod method) {
        for (PsiAnnotation annotation : method.getAnnotations()) {
            String annotationName = getAnnotationShortName(annotation);
            switch (annotationName) {
                case "GetMapping":
                    return "GET";
                case "PostMapping":
                    return "POST";
                case "PutMapping":
                    return "PUT";
                case "DeleteMapping":
                    return "DELETE";
                case "PatchMapping":
                    return "PATCH";
                case "RequestMapping":
                    return extractMethodFromRequestMapping(annotation);
            }
        }
        return "GET"; // Default
    }

    private String extractMethodFromRequestMapping(PsiAnnotation annotation) {
        PsiAnnotationMemberValue methodValue = annotation.findAttributeValue("method");
        if (methodValue != null) {
            String methodText = methodValue.getText();
            if (methodText.contains("POST")) return "POST";
            if (methodText.contains("PUT")) return "PUT";
            if (methodText.contains("DELETE")) return "DELETE";
            if (methodText.contains("PATCH")) return "PATCH";
        }
        return "GET";
    }

    private List<PathVariable> extractPathVariables(PsiMethod method) {
        List<PathVariable> pathVars = new ArrayList<>();
        PsiParameter[] parameters = method.getParameterList().getParameters();

        for (PsiParameter parameter : parameters) {
            for (PsiAnnotation annotation : parameter.getAnnotations()) {
                String annotationName = getAnnotationShortName(annotation);
                if (PATH_VARIABLE_ANNOTATIONS.contains(annotationName)) {
                    String name = extractAnnotationStringValue(annotation, "value");
                    if (name == null || name.isEmpty()) {
                        name = extractAnnotationStringValue(annotation, "name");
                    }
                    if (name == null || name.isEmpty()) {
                        name = parameter.getName();
                    }

                    String type = getParameterType(parameter);
                    pathVars.add(new PathVariable(name, type));
                    break;
                }
            }
        }
        return pathVars;
    }

    private List<RequestParam> extractRequestParams(PsiMethod method) {
        List<RequestParam> requestParams = new ArrayList<>();
        PsiParameter[] parameters = method.getParameterList().getParameters();

        for (PsiParameter parameter : parameters) {
            boolean hasSpecialAnnotation = false;

            // Check if parameter has body or path variable annotation
            for (PsiAnnotation annotation : parameter.getAnnotations()) {
                String annotationName = getAnnotationShortName(annotation);
                if (BODY_ANNOTATIONS.contains(annotationName) ||
                        PATH_VARIABLE_ANNOTATIONS.contains(annotationName)) {
                    hasSpecialAnnotation = true;
                    break;
                }

                if (REQUEST_PARAM_ANNOTATIONS.contains(annotationName)) {
                    String name = extractAnnotationStringValue(annotation, "value");
                    if (name == null || name.isEmpty()) {
                        name = extractAnnotationStringValue(annotation, "name");
                    }
                    if (name == null || name.isEmpty()) {
                        name = parameter.getName();
                    }

                    String defaultValue = extractAnnotationStringValue(annotation, "defaultValue");
                    boolean required = extractAnnotationBooleanValue(annotation, "required", true);
                    String type = getParameterType(parameter);

                    requestParams.add(new RequestParam(name, type, required, defaultValue));
                    hasSpecialAnnotation = true;
                    break;
                }
            }

            // If no special annotation, treat as request param for GET requests
            if (!hasSpecialAnnotation && !isComplexType(parameter.getType())) {
                String type = getParameterType(parameter);
                requestParams.add(new RequestParam(parameter.getName(), type, false, null));
            }
        }
        return requestParams;
    }

    private RequestBodyInfo extractRequestBody(PsiMethod method) {
        PsiParameter[] parameters = method.getParameterList().getParameters();

        for (PsiParameter parameter : parameters) {
            for (PsiAnnotation annotation : parameter.getAnnotations()) {
                String annotationName = getAnnotationShortName(annotation);
                if (BODY_ANNOTATIONS.contains(annotationName)) {
                    String type = getParameterType(parameter);
                    PsiClass psiClass = getPsiClassFromType(parameter.getType());
                    return new RequestBodyInfo(parameter.getName(), type, psiClass);
                }
            }
        }
        return null;
    }

    private String getContentType(PsiMethod method, RequestBodyInfo requestBody) {
        if (requestBody == null) {
            return null;
        }

        // Check for @Consumes annotation or similar
        for (PsiAnnotation annotation : method.getAnnotations()) {
            String annotationName = getAnnotationShortName(annotation);
            if ("RequestMapping".equals(annotationName)) {
                PsiAnnotationMemberValue consumes = annotation.findAttributeValue("consumes");
                if (consumes != null) {
                    String consumesValue = extractStringFromAnnotationValue(consumes);
                    if (consumesValue != null && !consumesValue.isEmpty()) {
                        return consumesValue;
                    }
                }
            }
        }

        return "application/json"; // Default for request body
    }

    private String buildCurlCommand(String url, String httpMethod, List<PathVariable> pathVariables,
                                    List<RequestParam> requestParams, RequestBodyInfo requestBody,
                                    String contentType) {
        StringBuilder curl = new StringBuilder();
        curl.append("curl -X ").append(httpMethod);

        // Replace path variables in URL
        String finalUrl = url;
        for (PathVariable pathVar : pathVariables) {
            String placeholder = "{" + pathVar.name + "}";
            String replacement = "${" + pathVar.name.toUpperCase() + "}";
            finalUrl = finalUrl.replace(placeholder, replacement);
        }

        // Add query parameters
        if (!requestParams.isEmpty()) {
            List<String> queryParams = requestParams.stream()
                    .map(param -> param.name + "=${" + param.name.toUpperCase() + "}")
                    .collect(Collectors.toList());

            if (finalUrl.contains("?")) {
                finalUrl += "&" + String.join("&", queryParams);
            } else {
                finalUrl += "?" + String.join("&", queryParams);
            }
        }

        // Add headers
        if (contentType != null) {
            curl.append(" \\\n  -H \"Content-Type: ").append(contentType).append("\"");
        }
        curl.append(" \\\n  -H \"Accept: application/json\"");

        // Add request body
        if (requestBody != null) {
            String jsonBody = generateJsonFromClass(requestBody.psiClass);
            curl.append(" \\\n  -d '").append(jsonBody).append("'");
        }

        // Add URL
        curl.append(" \\\n  \"").append(finalUrl).append("\"");

        // Add comments with variable explanations
        StringBuilder comments = new StringBuilder();
        comments.append("\n\n# Variables to replace:");

        for (PathVariable pathVar : pathVariables) {
            comments.append("\n# ").append(pathVar.name.toUpperCase())
                    .append(" - Path variable (").append(pathVar.type).append(")");
        }

        for (RequestParam requestParam : requestParams) {
            comments.append("\n# ").append(requestParam.name.toUpperCase())
                    .append(" - Request parameter (").append(requestParam.type).append(")");
            if (!requestParam.required) {
                comments.append(" [optional]");
            }
            if (requestParam.defaultValue != null) {
                comments.append(" [default: ").append(requestParam.defaultValue).append("]");
            }
        }

        return curl.toString() + comments.toString();
    }

    private String getAnnotationShortName(PsiAnnotation annotation) {
        String qualifiedName = annotation.getQualifiedName();
        if (qualifiedName == null) {
            return "";
        }
        return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
    }

    private String extractAnnotationStringValue(PsiAnnotation annotation, String attributeName) {
        PsiAnnotationMemberValue value = annotation.findAttributeValue(attributeName);
        if (value != null) {
            return extractStringFromAnnotationValue(value);
        }
        return null;
    }

    private boolean extractAnnotationBooleanValue(PsiAnnotation annotation, String attributeName, boolean defaultValue) {
        PsiAnnotationMemberValue value = annotation.findAttributeValue(attributeName);
        if (value instanceof PsiLiteralExpression) {
            Object literalValue = ((PsiLiteralExpression) value).getValue();
            if (literalValue instanceof Boolean) {
                return (Boolean) literalValue;
            }
        }
        return defaultValue;
    }

    private String extractStringFromAnnotationValue(PsiAnnotationMemberValue value) {
        if (value instanceof PsiLiteralExpression) {
            Object literalValue = ((PsiLiteralExpression) value).getValue();
            return literalValue != null ? literalValue.toString() : "";
        } else if (value instanceof PsiArrayInitializerMemberValue) {
            PsiAnnotationMemberValue[] initializers = ((PsiArrayInitializerMemberValue) value).getInitializers();
            if (initializers.length > 0 && initializers[0] instanceof PsiLiteralExpression) {
                Object literalValue = ((PsiLiteralExpression) initializers[0]).getValue();
                return literalValue != null ? literalValue.toString() : "";
            }
        }
        return "";
    }

    private String getParameterType(PsiParameter parameter) {
        PsiType type = parameter.getType();
        return type.getPresentableText();
    }

    private boolean isComplexType(PsiType type) {
        String typeName = type.getPresentableText();
        // Consider primitive types and their wrappers as simple
        Set<String> simpleTypes = Set.of(
                "String", "int", "Integer", "long", "Long", "double", "Double",
                "float", "Float", "boolean", "Boolean", "char", "Character",
                "byte", "Byte", "short", "Short"
        );
        return !simpleTypes.contains(typeName);
    }

    private PsiClass getPsiClassFromType(PsiType type) {
        if (type instanceof PsiClassType) {
            return ((PsiClassType) type).resolve();
        }
        return null;
    }

    private String generateJsonFromClass(PsiClass psiClass) {
        if (psiClass == null) {
            return "{}";
        }

        Set<String> processedClasses = new HashSet<>();
        return generateJsonFromClass(psiClass, processedClasses, 0);
    }

    private String generateJsonFromClass(PsiClass psiClass, Set<String> processedClasses, int depth) {
        if (psiClass == null || depth > 5) { // Prevent infinite recursion
            return "{}";
        }

        String className = psiClass.getQualifiedName();
        if (className != null && processedClasses.contains(className)) {
            return "{}"; // Circular reference prevention
        }

        if (className != null) {
            processedClasses.add(className);
        }

        StringBuilder json = new StringBuilder();
        json.append("{");

        boolean first = true;

        // Get all fields including inherited ones
        List<PsiField> allFields = getAllFields(psiClass);

        for (PsiField field : allFields) {
            // Skip static and final fields
            if (field.hasModifierProperty(PsiModifier.STATIC) ||
                    field.hasModifierProperty(PsiModifier.FINAL)) {
                continue;
            }

            // Skip fields with @JsonIgnore or similar annotations
            if (hasIgnoreAnnotation(field)) {
                continue;
            }

            if (!first) {
                json.append(",");
            }
            first = false;

            json.append("\n").append("  ".repeat(depth + 1));
            json.append("\"").append(getJsonFieldName(field)).append("\": ");

            String fieldValue = generateFieldValue(field.getType(), processedClasses, depth + 1);
            json.append(fieldValue);
        }

        if (!first) {
            json.append("\n").append("  ".repeat(depth));
        }
        json.append("}");

        if (className != null) {
            processedClasses.remove(className);
        }

        return json.toString();
    }

    private List<PsiField> getAllFields(PsiClass psiClass) {
        List<PsiField> allFields = new ArrayList<>();

        // Get fields from current class
        allFields.addAll(Arrays.asList(psiClass.getFields()));

        // Get fields from superclasses (excluding Object)
        PsiClass superClass = psiClass.getSuperClass();
        while (superClass != null && !"java.lang.Object".equals(superClass.getQualifiedName())) {
            allFields.addAll(Arrays.asList(superClass.getFields()));
            superClass = superClass.getSuperClass();
        }

        return allFields;
    }

    private boolean hasIgnoreAnnotation(PsiField field) {
        for (PsiAnnotation annotation : field.getAnnotations()) {
            String annotationName = getAnnotationShortName(annotation);
            if ("JsonIgnore".equals(annotationName) ||
                    "Transient".equals(annotationName)) {
                return true;
            }
        }
        return false;
    }

    private String getJsonFieldName(PsiField field) {
        // Check for @JsonProperty annotation
        for (PsiAnnotation annotation : field.getAnnotations()) {
            String annotationName = getAnnotationShortName(annotation);
            if ("JsonProperty".equals(annotationName)) {
                String value = extractAnnotationStringValue(annotation, "value");
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
        }

        // Default to field name
        return field.getName();
    }

    private String generateFieldValue(PsiType fieldType, Set<String> processedClasses, int depth) {
        String typeName = fieldType.getPresentableText();

        // Handle primitive types and common types
        if (typeName.equals("String")) {
            return "\"example\"";
        } else if (typeName.equals("int") || typeName.equals("Integer")) {
            return "0";
        } else if (typeName.equals("long") || typeName.equals("Long")) {
            return "0";
        } else if (typeName.equals("double") || typeName.equals("Double")) {
            return "0.0";
        } else if (typeName.equals("float") || typeName.equals("Float")) {
            return "0.0";
        } else if (typeName.equals("boolean") || typeName.equals("Boolean")) {
            return "false";
        } else if (typeName.equals("BigDecimal")) {
            return "0.00";
        } else if (typeName.equals("Date") || typeName.equals("LocalDate")) {
            return "\"2024-01-01\"";
        } else if (typeName.equals("LocalDateTime") || typeName.equals("Timestamp")) {
            return "\"2024-01-01T12:00:00\"";
        } else if (typeName.equals("UUID")) {
            return "\"123e4567-e89b-12d3-a456-426614174000\"";
        }

        // Handle collections
        if (fieldType instanceof PsiClassType) {
            PsiClassType classType = (PsiClassType) fieldType;
            PsiClass psiClass = classType.resolve();

            if (psiClass != null) {
                String qualifiedName = psiClass.getQualifiedName();

                // Handle List, Set, Collection
                if (qualifiedName != null && (qualifiedName.startsWith("java.util.List") ||
                        qualifiedName.startsWith("java.util.Set") ||
                        qualifiedName.startsWith("java.util.Collection"))) {

                    PsiType[] typeParameters = classType.getParameters();
                    if (typeParameters.length > 0) {
                        String elementValue = generateFieldValue(typeParameters[0], processedClasses, depth);
                        return "[" + elementValue + "]";
                    }
                    return "[]";
                }

                // Handle Map
                if (qualifiedName != null && qualifiedName.startsWith("java.util.Map")) {
                    PsiType[] typeParameters = classType.getParameters();
                    if (typeParameters.length > 1) {
                        String valueType = generateFieldValue(typeParameters[1], processedClasses, depth);
                        return "{\"key\": " + valueType + "}";
                    }
                    return "{}";
                }

                // Handle custom objects
                if (qualifiedName != null && !qualifiedName.startsWith("java.")) {
                    return generateJsonFromClass(psiClass, new HashSet<>(processedClasses), depth);
                }
            }
        }

        // Handle arrays
        if (fieldType instanceof PsiArrayType) {
            PsiType componentType = ((PsiArrayType) fieldType).getComponentType();
            String elementValue = generateFieldValue(componentType, processedClasses, depth);
            return "[" + elementValue + "]";
        }

        // Default fallback
        return "null";
    }

    // Inner classes for data structures
    private static class PathVariable {
        final String name;
        final String type;

        PathVariable(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    private static class RequestParam {
        final String name;
        final String type;
        final boolean required;
        final String defaultValue;

        RequestParam(String name, String type, boolean required, String defaultValue) {
            this.name = name;
            this.type = type;
            this.required = required;
            this.defaultValue = defaultValue;
        }
    }

    private static class RequestBodyInfo {
        final String name;
        final String type;
        final PsiClass psiClass;

        RequestBodyInfo(String name, String type, PsiClass psiClass) {
            this.name = name;
            this.type = type;
            this.psiClass = psiClass;
        }
    }
}