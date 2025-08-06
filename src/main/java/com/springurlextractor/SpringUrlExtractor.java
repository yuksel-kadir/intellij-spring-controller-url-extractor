package com.springurlextractor;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
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
        String contextPath = getContextPath(method);
        String controllerPath = getControllerBasePath(method.getContainingClass());
        String methodPath = getMethodPath(method);

        if (methodPath == null) {
            return null;
        }

        String fullPath = buildFullPath(contextPath, controllerPath, methodPath);
        String serverUrl = getServerUrl(method);

        return serverUrl + fullPath;
    }

    private GlobalSearchScope getSearchScope(PsiMethod method) {
        // Get the module that contains this method
        Module module = ModuleUtilCore.findModuleForPsiElement(method);

        if (module != null) {
            // Create scope for this specific module only, including test sources
            GlobalSearchScope moduleScope = GlobalSearchScope.moduleScope(module);

            // Also create a scope that includes dependencies but restrict it to the same project
            GlobalSearchScope moduleWithDepsScope = GlobalSearchScope.moduleWithDependenciesScope(module);

            // Intersect with project scope to ensure we don't leak into other projects
            GlobalSearchScope projectScope = GlobalSearchScope.projectScope(method.getProject());
            GlobalSearchScope restrictedScope = moduleWithDepsScope.intersectWith(projectScope);

            return restrictedScope;
        }

        // Fallback to project scope if we can't determine the module
        return GlobalSearchScope.projectScope(method.getProject());
    }

    private String getContextPath(PsiMethod method) {
        // Try YAML files first
        String contextPath = getContextPathFromYaml(method);
        if (contextPath != null) {
            return contextPath;
        }

        // Fall back to properties files
        return getContextPathFromProperties(method);
    }

    private String getServerUrl(PsiMethod method) {
        String host = getServerHost(method);
        String port = getServerPort(method);
        String protocol = getServerProtocol(method);

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

    private String getServerHost(PsiMethod method) {
        // Try YAML first
        String host = getServerPropertyFromYaml(method, "address");
        if (host != null) {
            return host;
        }

        // Try properties
        host = getServerPropertyFromProperties(method, "server.address");
        return host;
    }

    private String getServerPort(PsiMethod method) {
        // Try YAML first
        String port = getServerPropertyFromYaml(method, "port");
        if (port != null) {
            return port;
        }

        // Try properties
        port = getServerPropertyFromProperties(method, "server.port");
        return port;
    }

    private String getServerProtocol(PsiMethod method) {
        // Check for SSL configuration
        String sslEnabled = getServerPropertyFromYaml(method, "ssl", "enabled");
        if (sslEnabled == null) {
            sslEnabled = getServerPropertyFromProperties(method, "server.ssl.enabled");
        }

        if ("true".equalsIgnoreCase(sslEnabled)) {
            return "https";
        }

        return "http";
    }

    private String getContextPathFromYaml(PsiMethod method) {
        GlobalSearchScope searchScope = getSearchScope(method);

        // Debug: Log the project and module info
        Module module = ModuleUtilCore.findModuleForPsiElement(method);
        System.out.println("DEBUG - Method project: " + method.getProject().getName());
        System.out.println("DEBUG - Method module: " + (module != null ? module.getName() : "null"));
        System.out.println("DEBUG - Extractor project: " + this.project.getName());

        // Search for application.yml files in the specific scope
        Collection<VirtualFile> yamlFiles = FilenameIndex.getVirtualFilesByName(
                "application.yml", searchScope);

        if (yamlFiles.isEmpty()) {
            yamlFiles = FilenameIndex.getVirtualFilesByName(
                    "application.yaml", searchScope);
        }

        // Also look for profile-specific files
        if (yamlFiles.isEmpty()) {
            // Look for any application-*.yml files
            Collection<VirtualFile> allYamlFiles = new ArrayList<>();

            // Search for common profile-specific files
            String[] profileFiles = {
                    "application-dev.yml", "application-test.yml", "application-prod.yml",
                    "application-dev.yaml", "application-test.yaml", "application-prod.yaml",
                    "application-local.yml", "application-local.yaml"
            };

            for (String filename : profileFiles) {
                Collection<VirtualFile> profileYamlFiles = FilenameIndex.getVirtualFilesByName(
                        filename, searchScope);
                allYamlFiles.addAll(profileYamlFiles);
            }

            yamlFiles.addAll(allYamlFiles);
        }

        System.out.println("DEBUG - Found " + yamlFiles.size() + " YAML files");

        for (VirtualFile file : yamlFiles) {
            System.out.println("DEBUG - Checking file: " + file.getPath());
            // Verify this file is actually in our module's source roots
            if (isFileInModuleSourceRoots(file, method)) {
                System.out.println("DEBUG - File validated: " + file.getPath());
                try {
                    String content = new String(file.contentsToByteArray(), file.getCharset());
                    String contextPath = extractContextPathFromYamlText(content);
                    if (contextPath != null) {
                        System.out.println("DEBUG - Found context path: " + contextPath);
                        return contextPath;
                    }
                } catch (IOException e) {
                    System.out.println("DEBUG - Error reading file: " + e.getMessage());
                    // Continue to next file
                }
            } else {
                System.out.println("DEBUG - File rejected: " + file.getPath());
            }
        }
        return null;
    }

    private String extractContextPathFromYamlText(String yamlContent) {
        return extractYamlProperty(yamlContent, "server", "servlet", "context-path");
    }

    private String getServerPropertyFromYaml(PsiMethod method, String... propertyPath) {
        GlobalSearchScope searchScope = getSearchScope(method);

        Collection<VirtualFile> yamlFiles = FilenameIndex.getVirtualFilesByName(
                "application.yml", searchScope);

        if (yamlFiles.isEmpty()) {
            yamlFiles = FilenameIndex.getVirtualFilesByName(
                    "application.yaml", searchScope);
        }

        // Also look for profile-specific files
        if (yamlFiles.isEmpty()) {
            // Look for any application-*.yml files
            Collection<VirtualFile> allYamlFiles = new ArrayList<>();

            // Search for common profile-specific files
            String[] profileFiles = {
                    "application-dev.yml", "application-test.yml", "application-prod.yml",
                    "application-dev.yaml", "application-test.yaml", "application-prod.yaml",
                    "application-local.yml", "application-local.yaml"
            };

            for (String filename : profileFiles) {
                Collection<VirtualFile> profileYamlFiles = FilenameIndex.getVirtualFilesByName(
                        filename, searchScope);
                allYamlFiles.addAll(profileYamlFiles);
            }

            yamlFiles.addAll(allYamlFiles);
        }

        for (VirtualFile file : yamlFiles) {
            // Verify this file is actually in our module's source roots
            if (isFileInModuleSourceRoots(file, method)) {
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
        }
        return null;
    }

    private String getServerPropertyFromProperties(PsiMethod method, String propertyName) {
        GlobalSearchScope searchScope = getSearchScope(method);

        Collection<VirtualFile> propFiles = FilenameIndex.getVirtualFilesByName(
                "application.properties", searchScope);

        // Also look for profile-specific files
        Collection<VirtualFile> allPropFiles = new ArrayList<>();

        // Search for common profile-specific property files
        String[] profileFiles = {
                "application-dev.properties", "application-test.properties", "application-prod.properties",
                "application-local.properties"
        };

        for (String filename : profileFiles) {
            Collection<VirtualFile> profilePropFiles = FilenameIndex.getVirtualFilesByName(
                    filename, searchScope);
            allPropFiles.addAll(profilePropFiles);
        }

        propFiles.addAll(allPropFiles);

        for (VirtualFile file : propFiles) {
            // Verify this file is actually in our module's source roots
            if (isFileInModuleSourceRoots(file, method)) {
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
        }
        return null;
    }

    private boolean isFileInModuleSourceRoots(VirtualFile file, PsiMethod method) {
        Module module = ModuleUtilCore.findModuleForPsiElement(method);
        if (module == null) {
            return false; // If we can't determine the module, be more restrictive
        }

        // Double-check that the file belongs to the same project
        Project methodProject = method.getProject();
        if (!methodProject.equals(this.project)) {
            return false;
        }

        ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
        VirtualFile[] sourceRoots = rootManager.getSourceRoots();
        VirtualFile[] contentRoots = rootManager.getContentRoots();

        // Check if the file is under any source root
        for (VirtualFile sourceRoot : sourceRoots) {
            if (isUnder(file, sourceRoot)) {
                // Additional check: make sure the source root belongs to the same project
                if (isFileInProject(sourceRoot, methodProject)) {
                    return true;
                }
            }
        }

        // Also check content roots (for files in src/main/resources, etc.)
        for (VirtualFile contentRoot : contentRoots) {
            if (isUnder(file, contentRoot)) {
                // Additional check: make sure the content root belongs to the same project
                if (isFileInProject(contentRoot, methodProject)) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isFileInProject(VirtualFile file, Project targetProject) {
        try {
            PsiManager psiManager = PsiManager.getInstance(targetProject);
            PsiFile psiFile = psiManager.findFile(file);
            return psiFile != null && psiFile.getProject().equals(targetProject);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isUnder(VirtualFile file, VirtualFile ancestor) {
        VirtualFile current = file;
        while (current != null) {
            if (current.equals(ancestor)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
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

    private String extractPropertyFromPropertiesContent(String content, String propertyName) {
        Pattern pattern = Pattern.compile("^\\s*" + Pattern.quote(propertyName) + "\\s*=\\s*(.+)$", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private String getContextPathFromProperties(PsiMethod method) {
        String contextPath = getServerPropertyFromProperties(method, "server.servlet.context-path");
        if (contextPath != null) {
            return contextPath;
        }
        return getServerPropertyFromProperties(method, "server.context-path");
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