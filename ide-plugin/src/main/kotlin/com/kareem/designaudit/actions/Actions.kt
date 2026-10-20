package com.kareem.designaudit.actions

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.LocalFileSystem
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.kareem.designaudit.analysis.AuditService
import com.kareem.designaudit.ui.SettingsDialog
import javax.swing.Icon

/** Base: enabled when a project is open; shows the tool window before running. */
abstract class AuditAction(text: String, description: String, icon: Icon?) : AnAction(text, description, icon), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        val p = e.project
        e.presentation.isEnabledAndVisible = p != null
        if (p != null) e.presentation.isEnabled = enabled(AuditService.get(p))
    }
    open fun enabled(s: AuditService): Boolean = !s.busy
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ToolWindowManager.getInstance(project).getToolWindow("Design Audit")?.show()
        perform(project, AuditService.get(project))
    }
    abstract fun perform(project: Project, s: AuditService)
}

class CaptureScreenAction : AuditAction("Capture Screen", "Enable 'Show layout bounds', capture the current screen and measure spacing and touch targets", AllIcons.Actions.Preview) {
    override fun perform(project: Project, s: AuditService) = s.captureCurrent()
}

class ScanCodeAction : AuditAction("Scan Code", "Check Compose code against Material 3 design basics", AllIcons.Actions.Find) {
    override fun perform(project: Project, s: AuditService) = s.scanCode()
}

class AuditAllAction : AuditAction("Capture + Scan", "Capture the current screen, then scan the code", AllIcons.Actions.Execute) {
    override fun perform(project: Project, s: AuditService) {
        val d = s.selectedDevice ?: s.devices.firstOrNull()
        if (d == null) s.captureCurrent() else s.auditAll(d, s.screenName)
    }
}

class MeasurePerfAction : AuditAction("Measure Performance (10s)", "Reset frame counters, interact with the app for 10 seconds, then report janky frames, frame times and memory", AllIcons.Actions.Profile) {
    override fun perform(project: Project, s: AuditService) = s.measurePerformance(10)
}

class RefreshDevicesAction : AuditAction("Refresh Devices", "Re-detect adb and connected devices", AllIcons.Actions.Refresh) {
    override fun perform(project: Project, s: AuditService) = s.refreshDevices()
}

class OpenAnnotatedAction : AuditAction("Open Annotated Screenshot", "Open the last annotated screenshot", AllIcons.FileTypes.Image) {
    override fun enabled(s: AuditService) = s.captures.isNotEmpty()
    override fun perform(project: Project, s: AuditService) = s.open(s.captures.values.lastOrNull()?.annotatedPng)
}

class OpenReportAction : AuditAction("Open Markdown Report", "Open the combined Markdown report", AllIcons.FileTypes.Text) {
    override fun enabled(s: AuditService) = s.reportMd != null
    override fun perform(project: Project, s: AuditService) = s.open(s.reportMd)
}

class OpenHtmlReportAction : AuditAction("Open HTML Report in Browser", "Open the shareable HTML report (screenshots embedded)", AllIcons.Nodes.PpWeb) {
    override fun enabled(s: AuditService) = s.reportHtml != null
    override fun perform(project: Project, s: AuditService) { s.reportHtml?.let { BrowserUtil.browse(it.toFile()) } }
}

class ExportMarkdownAction : AuditAction("Export Markdown…", "Save every finding with 'why it matters' and 'how to fix' as a .md file", AllIcons.ToolbarDecorator.Export) {
    override fun enabled(s: AuditService) = !s.busy && s.all.isNotEmpty()
    override fun perform(project: Project, s: AuditService) {
        val descriptor = FileSaverDescriptor("Export Design Audit Report", "Choose where to save the Markdown report", "md")
        val startDir = s.projectPath?.let { LocalFileSystem.getInstance().findFileByNioFile(it) }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save(startDir, "design-audit-$stamp.md") ?: return
        s.exportMarkdown(wrapper.file.toPath())
    }
}

class ClearAction : AuditAction("Clear Results", "Remove all findings from the tool window", AllIcons.Actions.GC) {
    override fun enabled(s: AuditService) = !s.busy && (s.captures.isNotEmpty() || s.scan != null)
    override fun perform(project: Project, s: AuditService) = s.clear()
}

class SettingsAction : AuditAction("Audit Settings…", "Thresholds (48dp, 8dp, 16dp…), output folder and capture options", AllIcons.General.Settings) {
    override fun enabled(s: AuditService) = true
    override fun perform(project: Project, s: AuditService) { SettingsDialog(project).show() }
}
