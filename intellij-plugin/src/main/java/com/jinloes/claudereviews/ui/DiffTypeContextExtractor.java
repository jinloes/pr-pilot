package com.jinloes.claudereviews.ui;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

/**
 * Extracts non-private method and field signatures from files named in a unified diff, using
 * IntelliJ's PSI index. The result is injected into the review prompt as a {@code <type_context>}
 * block so Claude can verify types without reading files at review time.
 *
 * <p>For each changed Java file the extractor produces two layers of context:
 *
 * <ol>
 *   <li>Signatures declared in the file itself (the types being modified).
 *   <li>Signatures of project-source classes imported by that file — the types the changed code
 *       calls. This is where unknown return types typically live.
 * </ol>
 */
@Slf4j
class DiffTypeContextExtractor {

    // Cap type context to keep the prompt from bloating on large PRs.
    private static final int MAX_TYPE_CONTEXT_CHARS = 4000;

    /**
     * Parses the diff for changed file paths and extracts PSI-based type signatures for each Java
     * file found in the project. Returns an empty string when no Java files are changed or the
     * project index has no match. Output is capped at {@value MAX_TYPE_CONTEXT_CHARS} characters.
     */
    static String extract(String diff, Project project) {
        Set<String> filePaths = parseDiffFilePaths(diff);
        if (filePaths.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (String filePath : filePaths) {
            if (!filePath.endsWith(".java")) continue;
            if (sb.length() >= MAX_TYPE_CONTEXT_CHARS) break;
            String filename = filePath.substring(filePath.lastIndexOf('/') + 1);
            String signatures = extractSignaturesFromFile(project, filename, filePath);
            if (StringUtils.isNotBlank(signatures)) {
                sb.append("// ").append(filePath).append("\n");
                sb.append(signatures).append("\n\n");
            }
        }
        String result = sb.toString().strip();
        if (result.length() > MAX_TYPE_CONTEXT_CHARS) {
            result = result.substring(0, MAX_TYPE_CONTEXT_CHARS);
        }
        return result;
    }

    /**
     * Returns the set of new-file paths added or modified in the diff (lines starting with {@code
     * +++ b/}). {@code /dev/null} (deleted files) is excluded.
     */
    static Set<String> parseDiffFilePaths(String diff) {
        Set<String> paths = new LinkedHashSet<>();
        for (String line : diff.split("\n")) {
            if (line.startsWith("+++ b/")) {
                String path = line.substring(6).trim();
                if (!path.equals("/dev/null")) {
                    paths.add(path);
                }
            }
        }
        return paths;
    }

    private static String extractSignaturesFromFile(
            Project project, String filename, String fullPath) {
        try {
            return ReadAction.compute(
                    () -> {
                        PsiFile[] files =
                                FilenameIndex.getFilesByName(
                                        project, filename, GlobalSearchScope.projectScope(project));
                        for (PsiFile psiFile : files) {
                            String vPath = psiFile.getVirtualFile().getPath().replace('\\', '/');
                            // Match on path suffix with a slash boundary to avoid partial matches.
                            if (!vPath.endsWith("/" + fullPath)) continue;
                            if (psiFile instanceof PsiJavaFile javaFile) {
                                StringBuilder result =
                                        new StringBuilder(extractFileSignatures(javaFile));
                                String imported =
                                        extractImportedProjectSignatures(javaFile, project);
                                if (StringUtils.isNotBlank(imported)) {
                                    if (result.length() > 0) result.append("\n\n");
                                    result.append("// Referenced project types:\n")
                                            .append(imported);
                                }
                                return result.toString().strip();
                            }
                        }
                        return "";
                    });
        } catch (Exception e) {
            log.debug("PSI extraction failed for {}: {}", filename, e.getMessage());
            return "";
        }
    }

    /** Extracts signatures from all top-level classes declared in a Java file. */
    private static String extractFileSignatures(PsiJavaFile javaFile) {
        StringBuilder sb = new StringBuilder();
        for (PsiClass cls : javaFile.getClasses()) {
            String sigs = extractClassSignatures(cls);
            if (StringUtils.isNotBlank(sigs)) {
                sb.append(sigs).append("\n");
            }
        }
        return sb.toString().strip();
    }

    /**
     * Follows non-wildcard imports in {@code javaFile} and returns signatures for any imported
     * class that lives in the project source (not a JDK or third-party library jar). This surfaces
     * return types of methods the changed code calls — the most common source of type uncertainty.
     */
    private static String extractImportedProjectSignatures(PsiJavaFile javaFile, Project project) {
        var importList = javaFile.getImportList();
        if (importList == null) return "";

        ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
        StringBuilder sb = new StringBuilder();

        for (PsiImportStatement stmt : importList.getImportStatements()) {
            if (stmt.isOnDemand()) continue; // skip wildcard imports
            PsiElement resolved = stmt.resolve();
            if (!(resolved instanceof PsiClass importedClass)) continue;
            PsiFile containingFile = importedClass.getContainingFile();
            if (containingFile == null) continue;
            VirtualFile vf = containingFile.getVirtualFile();
            // Only include classes whose source is in the project, not compiled jars or JDK.
            if (vf == null || !fileIndex.isInContent(vf)) continue;
            String sigs = extractClassSignatures(importedClass);
            if (StringUtils.isNotBlank(sigs)) {
                sb.append(sigs).append("\n\n");
            }
        }
        return sb.toString().strip();
    }

    /** Extracts non-private field and method signatures for a single class. */
    private static String extractClassSignatures(PsiClass cls) {
        String className = cls.getName();
        if (className == null) return "";
        StringBuilder sb = new StringBuilder();
        for (PsiField field : cls.getFields()) {
            if (field.hasModifierProperty(PsiModifier.PRIVATE)) continue;
            sb.append(className)
                    .append("#")
                    .append(field.getName())
                    .append(": ")
                    .append(field.getType().getPresentableText())
                    .append("\n");
        }
        for (PsiMethod method : cls.getMethods()) {
            if (method.hasModifierProperty(PsiModifier.PRIVATE)) continue;
            PsiType returnType = method.getReturnType();
            if (returnType == null) continue; // constructors
            sb.append(className).append("#").append(method.getName()).append("(");
            PsiParameter[] params = method.getParameterList().getParameters();
            List<String> paramTypes = new ArrayList<>();
            for (PsiParameter p : params) {
                paramTypes.add(p.getType().getPresentableText());
            }
            sb.append(String.join(", ", paramTypes))
                    .append("): ")
                    .append(returnType.getPresentableText())
                    .append("\n");
        }
        return sb.toString().strip();
    }
}
