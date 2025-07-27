package com.springurlextractor;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SpringUrlExtractor {
    private final Project project;
    private final Set<String> MAPPING_ANNOTATIONS = Set.of(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping",
            "DeleteMapping", "PatchMapping"
    );

    public SpringUrlExtractor(Project project) {
        this.project = project;
    }

    public String extractUrl(PsiMethod method) {
        String contextPath = getContextPath();
        String controllerPath = getControllerBasePath(method.getContainingClass());
        String methodPath = getMethodPath(method);

        if (methodPath == null) {
            return null;
        }

        String fullPath = buildFullPath(contextPath, controllerPath, methodPath);
        String serverUrl = getServerUrl();

        return serverUrl + fullPath;
    }

    private String getContextPath() {
        // Try YAML files first
        String contextPath = getContextPathFromYaml();
        if (contextPath != null) {
            return contextPath;
        }

        // Fall back to properties files
        return getContextPathFromProperties();
    }

    private String getServerUrl() {
        String host = getServerHost();
        String port = getServerPort();
        String protocol = getServerProtocol();

        // Default values
        if (host == null || host.isEmpty()) {
            host = "localhost";
        }
        if (port == null || port.isEmpty()) {
            port = "8080";
        }
        if (protocol == null || protocol.isEmpty()) {
            protocol = "http";
        }

        // Don't include port if it's default for the protocol
        if (("http".equals(protocol) && "80".equals(port)) ||
                ("https".equals(protocol) && "443".equals(port))) {
            return protocol + "://" + host;
        }

        return protocol + "://" + host + ":" + port;
    }

    private String getServerHost() {
        // Try YAML first
        String host = getServerPropertyFromYaml("address");
        if (host != null) {
            return host;
        }

        // Try properties
        host = getServerPropertyFromProperties("server.address");
        return host;
    }

    private String getServerPort() {
        // Try YAML first
        String port = getServerPropertyFromYaml("port");
        if (port != null) {
            return port;
        }

        // Try properties
        port = getServerPropertyFromProperties("server.port");
        return port;
    }

    private String getServerProtocol() {
        // Check for SSL configuration
        String sslEnabled = getServerPropertyFromYaml("ssl", "enabled");
        if (sslEnabled == null) {
            sslEnabled = getServerPropertyFromProperties("server.ssl.enabled");
        }

        if ("true".equalsIgnoreCase(sslEnabled)) {
            return "https";
        }

        return "http";
    }

    private String getContextPathFromYaml() {
        // Search for application.yml files
        Collection<VirtualFile> yamlFiles = FilenameIndex.getVirtualFilesByName(
                "application.yml", GlobalSearchScope.projectScope(project));

        if (yamlFiles.isEmpty()) {
            yamlFiles = FilenameIndex.getVirtualFilesByName(
                    "application.yaml", GlobalSearchScope.projectScope(project));
        }

        for (VirtualFile file : yamlFiles) {
            try {
                String content = new String(file.contentsToByteArray(), file.getCharset());
                String contextPath = extractContextPathFromYamlText(content);
                if (contextPath != null) {
                    return contextPath;
                }
            } catch (IOException e) {
                // Continue to next file
            }
        }
        return null;
    }

    private String extractContextPathFromYamlText(String yamlContent) {
        return extractYamlProperty(yamlContent, "server", "servlet", "context-path");
    }

    private String getServerPropertyFromYaml(String... propertyPath) {
        Collection<VirtualFile> yamlFiles = FilenameIndex.getVirtualFilesByName(
                "application.yml", GlobalSearchScope.projectScope(project));

        if (yamlFiles.isEmpty()) {
            yamlFiles = FilenameIndex.getVirtualFilesByName(
                    "application.yaml", GlobalSearchScope.projectScope(project));
        }

        for (VirtualFile file : yamlFiles) {
            try {
                String content = new String(file.contentsToByteArray(), file.getCharset());
                String[] fullPath = new String[propertyPath.length + 1];
                fullPath[0] = "server";
                System.arraycopy(propertyPath, 0, fullPath, 1, propertyPath.length);
                String value = extractYamlProperty(content, fullPath);
                if (value != null) {
                    return value;
                }
            } catch (IOException e) {
                // Continue to next file
            }
        }
        return null;
    }

    private String extractYamlProperty(String yamlContent, String... propertyPath) {
        String[] lines = yamlContent.split("\n");
        int[] sectionIndents = new int[propertyPath.length];
        boolean[] inSections = new boolean[propertyPath.length];
        Arrays.fill(sectionIndents, -1);

        for (String line : lines) {
            if (line.trim().isEmpty() || line.trim().startsWith("#")) {
                continue;
            }

            int currentIndent = getIndentLevel(line);
            String trimmed = line.trim();

            // Check each level of the property path
            for (int level = 0; level < propertyPath.length; level++) {
                String expectedKey = propertyPath[level] + ":";

                if (trimmed.equals(expectedKey)) {
                    // Found this level
                    inSections[level] = true;
                    sectionIndents[level] = currentIndent;

                    // Reset deeper levels
                    for (int i = level + 1; i < propertyPath.length; i++) {
                        inSections[i] = false;
                        sectionIndents[i] = -1;
                    }
                    break;
                } else if (inSections[level] && sectionIndents[level] != -1 &&
                        currentIndent <= sectionIndents[level] && trimmed.contains(":")) {
                    // We've moved out of this section
                    for (int i = level; i < propertyPath.length; i++) {
                        inSections[i] = false;
                        sectionIndents[i] = -1;
                    }
                }

                // Check if we found the final property
                if (level == propertyPath.length - 1 && inSections[level] &&
                        trimmed.startsWith(propertyPath[level] + ":")) {
                    return extractYamlValue(trimmed);
                }
            }

            // Handle special case for context-path (can be under server or server.servlet)
            if (propertyPath.length >= 2 && "context-path".equals(propertyPath[propertyPath.length - 1])) {
                if (trimmed.startsWith("context-path:")) {
                    // Check if we're in server section (with or without servlet)
                    if (inSections[0]) { // in server section
                        return extractYamlValue(trimmed);
                    }
                }
            }
        }
        return null;
    }

    private int getIndentLevel(String line) {
        int indent = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') {
                indent++;
            } else if (c == '\t') {
                indent += 4; // Treat tab as 4 spaces
            } else {
                break;
            }
        }
        return indent;
    }

    private String extractYamlValue(String line) {
        int colonIndex = line.indexOf(":");
        if (colonIndex != -1 && colonIndex < line.length() - 1) {
            String value = line.substring(colonIndex + 1).trim();
            // Remove quotes
            if ((value.startsWith("\"") && value.endsWith("\"")) ||
                    (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            return value;
        }
        return null;
    }

    private String getServerPropertyFromProperties(String propertyName) {
        Collection<VirtualFile> propFiles = FilenameIndex.getVirtualFilesByName(
                "application.properties", GlobalSearchScope.projectScope(project));

        for (VirtualFile file : propFiles) {
            try {
                String content = new String(file.contentsToByteArray(), file.getCharset());
                String value = extractPropertyFromPropertiesContent(content, propertyName);
                if (value != null) {
                    return value;
                }
            } catch (IOException e) {
                // Continue to next file
            }
        }
        return null;
    }

    private String extractPropertyFromPropertiesContent(String content, String propertyName) {
        Pattern pattern = Pattern.compile("^\\s*" + Pattern.quote(propertyName) + "\\s*=\\s*(.+)$", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private String getContextPathFromProperties() {
        return getServerPropertyFromProperties("server.servlet.context-path") != null ?
                getServerPropertyFromProperties("server.servlet.context-path") :
                getServerPropertyFromProperties("server.context-path");
    }

    private String getControllerBasePath(PsiClass controllerClass) {
        if (controllerClass == null) {
            return "";
        }

        for (PsiAnnotation annotation : controllerClass.getAnnotations()) {
            String shortName = getAnnotationShortName(annotation);
            if ("RequestMapping".equals(shortName)) {
                return extractPathFromAnnotation(annotation);
            }
        }

        return "";
    }

    private String getMethodPath(PsiMethod method) {
        for (PsiAnnotation annotation : method.getAnnotations()) {
            String annotationName = getAnnotationShortName(annotation);
            if (MAPPING_ANNOTATIONS.contains(annotationName)) {
                return extractPathFromAnnotation(annotation);
            }
        }
        return null;
    }

    private String getAnnotationShortName(PsiAnnotation annotation) {
        String qualifiedName = annotation.getQualifiedName();
        if (qualifiedName == null) {
            return "";
        }
        return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
    }

    private String extractPathFromAnnotation(PsiAnnotation annotation) {
        // Try 'value' attribute first
        PsiAnnotationMemberValue value = annotation.findAttributeValue("value");
        if (value != null) {
            String path = extractStringFromAnnotationValue(value);
            if (path != null && !path.isEmpty()) {
                return path;
            }
        }

        // Try 'path' attribute
        PsiAnnotationMemberValue path = annotation.findAttributeValue("path");
        if (path != null) {
            return extractStringFromAnnotationValue(path);
        }

        return "";
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

    private String buildFullPath(String contextPath, String controllerPath, String methodPath) {
        StringBuilder path = new StringBuilder();

        // Add context path
        if (contextPath != null && !contextPath.isEmpty()) {
            if (!contextPath.startsWith("/")) {
                path.append("/");
            }
            path.append(contextPath);
            if (contextPath.endsWith("/")) {
                path.setLength(path.length() - 1);
            }
        }

        // Add controller path
        if (controllerPath != null && !controllerPath.isEmpty()) {
            if (!controllerPath.startsWith("/")) {
                path.append("/");
            }
            path.append(controllerPath);
            if (controllerPath.endsWith("/")) {
                path.setLength(path.length() - 1);
            }
        }

        // Add method path
        if (methodPath != null && !methodPath.isEmpty()) {
            if (!methodPath.startsWith("/")) {
                path.append("/");
            }
            path.append(methodPath);
        }

        return path.length() > 0 ? path.toString() : "/";
    }
}