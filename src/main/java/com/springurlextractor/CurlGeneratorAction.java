package com.springurlextractor;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;

import java.awt.datatransfer.StringSelection;

public class CurlGeneratorAction extends AnAction {

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

        CurlGenerator generator = new CurlGenerator(project);
        String curlCommand = generator.generateCurl(method);

        if (curlCommand != null && !curlCommand.isEmpty()) {
            // Copy to clipboard
            CopyPasteManager.getInstance().setContents(new StringSelection(curlCommand));
            Messages.showInfoMessage("cURL command copied to clipboard", "cURL Generated");
        } else {
            Messages.showInfoMessage("No Spring mapping annotation found", "No cURL Generated");
        }
    }

    @Override
    public void update(AnActionEvent e) {
        e.getPresentation().setEnabled(true);
        e.getPresentation().setVisible(true);
    }
}