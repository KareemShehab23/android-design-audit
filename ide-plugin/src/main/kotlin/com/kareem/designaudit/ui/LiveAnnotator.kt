package com.kareem.designaudit.ui

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.kareem.designaudit.analysis.ComposeScanner
import com.kareem.designaudit.model.AuditConfig
import com.kareem.designaudit.model.Finding
import com.kareem.designaudit.model.Severity
import com.kareem.designaudit.settings.AuditSettings
import java.nio.file.Paths

/**
 * Runs the per-file design rules on the editor's current text (unsaved included) and underlines
 * violations like Lint does: red for errors, yellow for warnings, message + fix on hover, and the
 * entries appear in the Problems view. Project-wide rules stay in the Scan code action.
 */
class LiveAnnotator : ExternalAnnotator<LiveAnnotator.Input, List<Finding>>() {

    class Input(val text: String, val path: String, val root: String)

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Input? {
        if (!AuditSettings.current.liveHighlight) return null
        if (!file.name.endsWith(".kt")) return null
        val vf = file.virtualFile ?: return null
        val root = file.project.basePath ?: return null
        return Input(editor.document.text, vf.path, root)
    }

    override fun doAnnotate(collectedInfo: Input?): List<Finding> {
        val i = collectedInfo ?: return emptyList()
        AuditSettings.apply()
        return runCatching {
            ComposeScanner(AuditConfig.current.maxPerRule).scanLive(Paths.get(i.root), Paths.get(i.path), i.text)
        }.getOrDefault(emptyList())
    }

    override fun apply(file: PsiFile, annotationResult: List<Finding>?, holder: AnnotationHolder) {
        val findings = annotationResult ?: return
        val doc = file.viewProvider.document ?: return
        for (f in findings) {
            if (f.line < 1 || f.line > doc.lineCount) continue
            val start = doc.getLineStartOffset(f.line - 1)
            val end = doc.getLineEndOffset(f.line - 1)
            val lineText = doc.getText(TextRange(start, end))
            val lead = lineText.length - lineText.trimStart().length
            val range = TextRange(start + lead, maxOf(start + lead + 1, end))
            val sev = if (f.severity == Severity.ERROR) HighlightSeverity.ERROR else HighlightSeverity.WARNING
            val msg = buildString {
                append("[Design audit] ").append(f.rule).append(": ").append(f.message)
                if (f.suggestion.isNotEmpty()) append("  Fix: ").append(f.suggestion)
            }
            holder.newAnnotation(sev, msg).range(range).tooltip(tooltip(f)).create()
        }
    }

    private fun tooltip(f: Finding): String = buildString {
        append("<html><b>").append(esc(f.rule)).append("</b> · ").append(f.severity.label).append("<br>")
        append(esc(f.message))
        if (f.why.isNotEmpty()) append("<br><i>Why:</i> ").append(esc(f.why))
        if (f.suggestion.isNotEmpty()) append("<br><i>Fix:</i> ").append(esc(f.suggestion))
        append("</html>")
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
