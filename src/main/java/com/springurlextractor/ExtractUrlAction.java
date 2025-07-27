package com.springurlextractor;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

import java.awt.datatransfer.StringSelection;

public class ExtractUrlAction extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);

        if (project == null || editor == null || psiFile == null) {
            return;
        }

        PsiElement element = psiFile.findElementAt(editor.getCaretModel().getOffset());
        PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);

        if (method == null) {
            Messages.showInfoMessage("Please place cursor inside a controller method", "No Method Found");
            return;
        }

        SpringUrlExtractor extractor = new SpringUrlExtractor(project);
        String url = extractor.extractUrl(method);

        if (url != null && !url.isEmpty()) {
            // Copy to clipboard
            CopyPasteManager.getInstance().setContents(new StringSelection(url));
            Messages.showInfoMessage("URL copied to clipboard: " + url, "Spring URL Extracted");
        } else {
            Messages.showInfoMessage("No Spring mapping annotation found", "No URL Found");
        }
    }

    @Override
    public void update(AnActionEvent e) {
//        Project project = e.getProject();
//        Editor editor = e.getData(CommonDataKeys.EDITOR);
//        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
//
//        boolean enabled = project != null && editor != null && psiFile != null &&
//                psiFile.getName().endsWith(".java");
//        e.getPresentation().setEnabled(enabled);

        e.getPresentation().setEnabled(true);
        e.getPresentation().setVisible(true);

    }
}