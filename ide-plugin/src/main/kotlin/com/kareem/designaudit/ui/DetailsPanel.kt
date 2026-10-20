package com.kareem.designaudit.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.kareem.designaudit.analysis.Annotator
import com.kareem.designaudit.analysis.AuditService
import com.kareem.designaudit.model.Finding
import com.kareem.designaudit.model.Source
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.SwingConstants

/** Right-hand side: what the selected finding is, why it matters, how to fix it, and where it is on the screen. */
class DetailsPanel(private val service: AuditService) : JPanel(BorderLayout()) {
    private val text = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder.simple()
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty(8, 12)
    }
    private val goButton = JButton("Open", AllIcons.Actions.EditSource)
    private val copyButton = JButton("Copy fix", AllIcons.Actions.Copy)
    private val preview = ScreenshotPreview { current?.let { service.navigate(it) } }
    private val showAnnotated = JToggleButton("Annotated", true)
    private val showBounds = JToggleButton("Layout bounds")
    private val showClean = JToggleButton("Clean")
    private val previewHeader = JBLabel("", SwingConstants.LEFT).apply { foreground = JBColor.GRAY; border = JBUI.Borders.empty(0, 8) }
    private var current: Finding? = null

    init {
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
            isOpaque = false
            add(goButton); add(copyButton)
        }
        goButton.addActionListener { current?.let { service.navigate(it) } }
        copyButton.addActionListener { current?.let { f ->
            CopyPasteManager.getInstance().setContents(StringSelection("${f.rule} — ${f.location()}\n${f.message}\nFix: ${f.suggestion}"))
        } }

        val top = JPanel(BorderLayout()).apply { isOpaque = false; add(JBScrollPane(text).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER); add(buttons, BorderLayout.SOUTH) }

        ButtonGroup().apply { add(showAnnotated); add(showBounds); add(showClean) }
        listOf(showAnnotated, showBounds, showClean).forEach { it.addActionListener { refreshPreview() } }
        val previewBar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))).apply {
            isOpaque = false; add(previewHeader); add(showAnnotated); add(showBounds); add(showClean)
        }
        val bottom = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.customLineTop(JBColor.border())
            add(previewBar, BorderLayout.NORTH); add(preview, BorderLayout.CENTER)
            preferredSize = JBUI.size(300, 320)
        }
        add(top, BorderLayout.CENTER)
        add(bottom, BorderLayout.SOUTH)
        show(null)
    }

    fun show(f: Finding?) {
        current = f
        if (f == null) {
            text.text = "<html><body style='${bodyStyle()}'><p style='color:${hex(JBColor.GRAY)}'>Select a finding to see why it matters and how to fix it.</p></body></html>"
            goButton.isEnabled = false; copyButton.isEnabled = false
            preview.show(null, null); previewHeader.text = ""
            return
        }
        goButton.isEnabled = true; copyButton.isEnabled = true
        goButton.text = when (f.source) { Source.CODE -> "Open file"; Source.HIERARCHY -> "Open screenshot"; Source.PROJECT -> "Open report" }
        val c = Annotator.colorOf(f.severity)
        val sb = StringBuilder("<html><body style='${bodyStyle()}'>")
        sb.append("<div><span style='background:${hex(c)};color:#fff;padding:2px 8px;border-radius:6px;font-weight:bold'>${f.severity.label}</span> &nbsp;<b><code>${esc(f.rule)}</code></b> &nbsp;<span style='color:${hex(JBColor.GRAY)}'>${esc(f.area)}</span></div>")
        sb.append("<p style='margin-top:10px;font-size:1.05em'>${esc(f.message)}</p>")
        sb.append("<p style='color:${hex(JBColor.GRAY)}'>${esc(f.location())}</p>")
        if (f.snippet.isNotEmpty()) sb.append("<pre style='background:${hex(UIUtil.getPanelBackground().darker())};padding:6px 8px;border-radius:6px'>${esc(f.snippet)}</pre>")
        if (f.why.isNotEmpty()) sb.append("<p><b>Why it matters</b><br>${esc(f.why)}</p>")
        if (f.suggestion.isNotEmpty()) sb.append("<p><b>How to fix</b><br>${esc(f.suggestion)}</p>")
        sb.append("</body></html>")
        text.text = sb.toString()
        text.caretPosition = 0
        refreshPreview()
    }

    private fun refreshPreview() {
        val f = current
        val cap = f?.let { service.captureFor(it) }
        if (cap == null) { preview.show(null, null); previewHeader.text = ""; preview.placeholder = if (f?.source == Source.CODE) "Code finding — open the file to see it" else "Select a screen finding to preview it here"; return }
        val path = when {
            showBounds.isSelected -> cap.boundsPng
            showClean.isSelected -> cap.cleanPng ?: cap.boundsPng
            else -> cap.annotatedPng ?: cap.boundsPng
        }
        previewHeader.text = "${cap.name} · ${cap.device.summary()}"
        preview.show(path, f)
    }

    private fun bodyStyle() = "font-family:${UIUtil.getLabelFont().family};font-size:${UIUtil.getLabelFont().size}pt;color:${hex(UIUtil.getLabelForeground())}"
    private fun hex(c: Color) = String.format("#%02x%02x%02x", c.red, c.green, c.blue)
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
