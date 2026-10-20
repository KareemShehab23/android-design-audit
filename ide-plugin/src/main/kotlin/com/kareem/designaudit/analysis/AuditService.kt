package com.kareem.designaudit.analysis

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.kareem.designaudit.adb.Adb
import com.kareem.designaudit.model.Finding
import com.kareem.designaudit.model.Severity
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList

/** Project-level state: captures, last code scan, devices; runs operations in the background and notifies listeners. */
@Service(Service.Level.PROJECT)
class AuditService(private val project: Project) {

    val captures = LinkedHashMap<String, AuditRunner.Capture>()
    var scan: AuditRunner.CodeScan? = null
        private set
    var devices: List<Adb.Device> = emptyList()
        private set
    var adbPath: Path? = null
        private set
    var reportMd: Path? = null
        private set
    var reportHtml: Path? = null
        private set
    var busy = false
        private set
    /** Current choices in the tool window, so menu actions and shortcuts use the same values. */
    @Volatile var selectedDevice: Adb.Device? = null
    @Volatile var screenName: String = "home"
    var status: String = "Ready — connect a device and capture a screen, or scan the code."
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun fire() = ApplicationManager.getApplication().invokeLater { listeners.forEach { it() } }

    val projectPath: Path? get() = project.basePath?.let { Paths.get(it) }

    /** All findings in display order; hierarchy findings first (per screen), then code. */
    val all: List<Finding> get() = captures.values.flatMap { it.findings } + (scan?.findings ?: emptyList())

    fun captureFor(f: Finding): AuditRunner.Capture? = f.screen?.let { captures[it] }

    // ---- devices -------------------------------------------------------------------------------
    fun refreshDevices(then: (() -> Unit)? = null) {
        ApplicationManager.getApplication().executeOnPooledThread {
            adbPath = Adb.locate(projectPath)
            val path = adbPath
            if (path == null) {
                devices = emptyList()
                status = "adb not found — set sdk.dir in local.properties or ANDROID_HOME, or add platform-tools to PATH."
            } else {
                val error = runCatching { devices = Adb.devices(path) }.exceptionOrNull()
                status = when {
                    error != null -> { devices = emptyList(); "adb devices failed: ${error.message}" }
                    devices.isEmpty() -> "No device online. Enable USB debugging or start an emulator, then refresh."
                    else -> "${devices.size} device(s) · adb: $path"
                }
            }
            fire()
            then?.let { ApplicationManager.getApplication().invokeLater(it) }
        }
    }

    // ---- operations ----------------------------------------------------------------------------
    fun captureScreen(device: Adb.Device, screenName: String, then: (() -> Unit)? = null) {
        val proj = projectPath ?: return notify("Open a project first.", NotificationType.ERROR)
        val adb = adbPath ?: return notify("adb not found. Set sdk.dir in local.properties or ANDROID_HOME.", NotificationType.ERROR)
        val name = screenName.trim().replace(Regex("[^\\w\\-]+"), "_").ifEmpty { "screen" }
        runTask("Design audit: capturing '$name'", {
            val c = AuditRunner.capture(Adb(adb, device.serial), proj, name, it::setText)
            captures[name] = c
            it.text = "Writing reports…"
            afterChange()
            c
        }, { c ->
            status = "Captured '$name': ${summary(c.findings)} · ${c.device.summary()}"
            notify("Screen '$name' captured — ${summary(c.findings)}", NotificationType.INFORMATION, c.annotatedPng, "Open annotated screenshot")
            then?.invoke()
        })
    }

    fun scanCode(then: (() -> Unit)? = null) {
        val proj = projectPath ?: return notify("Open a project first.", NotificationType.ERROR)
        runTask("Design audit: scanning Compose code", {
            val s = AuditRunner.scanCode(proj)
            scan = s
            it.text = "Writing reports…"
            afterChange()
            s
        }, { s ->
            status = "Scanned ${s.result.ktFiles} Kotlin files: ${summary(s.findings)}"
            notify("Code scan done — ${summary(s.findings)}", NotificationType.INFORMATION, reportHtml, "Open report")
            then?.invoke()
        })
    }

    fun auditAll(device: Adb.Device, screenName: String) = captureScreen(device, screenName) { scanCode() }

    /** Reset frame counters, let the user interact for [seconds], then report jank / frame times / memory. */
    fun measurePerformance(seconds: Int = 10) {
        val proj = projectPath ?: return notify("Open a project first.", NotificationType.ERROR)
        val adb = adbPath ?: return notify("adb not found. Set sdk.dir in local.properties or ANDROID_HOME.", NotificationType.ERROR)
        val d = selectedDevice ?: devices.firstOrNull() ?: return notify("No device selected.", NotificationType.WARNING)
        val name = screenName.trim().replace(Regex("[^\\w\\-]+"), "_").ifEmpty { "screen" }
        runTask("Design audit: measuring performance", {
            val c = AuditRunner.measurePerformance(Adb(adb, d.serial), proj, name, seconds, it::setText)
            captures[c.name] = c
            it.text = "Writing reports…"
            afterChange()
            c
        }, { c ->
            status = "Performance '${c.name}': ${c.perf?.summary()}"
            notify("Performance measured — ${c.perf?.summary()}", NotificationType.INFORMATION, c.reportMd, "Open report")
        })
    }

    /** Capture using the tool window's current device/name; falls back to a device refresh if none is selected. */
    fun captureCurrent() {
        val d = selectedDevice ?: devices.firstOrNull()
        if (d == null) { refreshDevices { val d2 = selectedDevice ?: devices.firstOrNull(); if (d2 != null) captureScreen(d2, screenName) else notify(status, NotificationType.WARNING) }; return }
        captureScreen(d, screenName)
    }

    fun clear() { captures.clear(); scan = null; reportMd = null; reportHtml = null; status = "Cleared."; fire() }

    fun removeCapture(name: String) { captures.remove(name); ApplicationManager.getApplication().executeOnPooledThread { afterChange(); fire() } }

    private fun <T> runTask(title: String, work: (ProgressIndicator) -> T, onDone: (T) -> Unit) {
        if (busy) return notify("An audit is already running.", NotificationType.WARNING)
        com.kareem.designaudit.settings.AuditSettings.apply()
        busy = true; fire()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, false) {
            var out: T? = null
            override fun run(indicator: ProgressIndicator) { indicator.isIndeterminate = true; out = work(indicator) }
            override fun onSuccess() { busy = false; @Suppress("UNCHECKED_CAST") onDone(out as T); fire() }
            override fun onThrowable(error: Throwable) {
                busy = false; status = "Failed: ${error.message}"; fire()
                notify("$title failed: ${error.message}", NotificationType.ERROR)
            }
        })
    }

    /** Re-number screenshots so the labels match the table's # column, then write the combined reports. */
    private fun afterChange() {
        val proj = projectPath ?: return
        var idx = 0
        for (c in captures.values) {
            val numbers = c.findings.map { ++idx }
            val src = c.boundsPng ?: continue
            runCatching { Annotator.annotate(src, c.dir.resolve("annotated.png"), c.findings, c.device.scale, numbers) }
                .onSuccess { c.annotatedPng = c.dir.resolve("annotated.png") }
        }
        runCatching { AuditRunner.writeCombined(proj, captures.values, scan) }.onSuccess { (md, html) -> reportMd = md; reportHtml = html }
        ApplicationManager.getApplication().executeOnPooledThread {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(AuditRunner.outputRoot(proj))?.refresh(true, true)
        }
    }

    /** Writes the complete Markdown report (all findings, why + fix) to [target]. */
    fun exportMarkdown(target: Path) {
        val proj = projectPath ?: return notify("Open a project first.", NotificationType.ERROR)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                java.nio.file.Files.createDirectories(target.parent)
                java.nio.file.Files.writeString(target, AuditRunner.fullMarkdown(proj, captures.values, scan))
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
            }
            ApplicationManager.getApplication().invokeLater {
                result.onSuccess { notify("Exported ${all.size} findings to ${target.fileName}", NotificationType.INFORMATION, target, "Open") }
                    .onFailure { notify("Export failed: ${it.message}", NotificationType.ERROR) }
            }
        }
    }

    // ---- navigation ----------------------------------------------------------------------------
    fun open(path: Path?) {
        if (path == null) return
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
        FileEditorManager.getInstance(project).openFile(vf, true)
    }

    fun navigate(f: Finding) {
        when {
            f.file != null -> LocalFileSystem.getInstance().refreshAndFindFileByNioFile(f.file)?.let {
                OpenFileDescriptor(project, it, maxOf(0, f.line - 1), 0).navigate(true)
            }
            f.screen != null -> open(captureFor(f)?.annotatedPng)
            else -> open(reportMd)
        }
    }

    private fun summary(fs: List<Finding>): String {
        val e = fs.count { it.severity == Severity.ERROR }
        val w = fs.count { it.severity == Severity.WARNING }
        return "$e errors, $w warnings"
    }

    private fun notify(text: String, type: NotificationType, openPath: Path? = null, actionText: String? = null) {
        val n = NotificationGroupManager.getInstance().getNotificationGroup("Android Design Audit").createNotification("Android Design Audit", text, type)
        if (openPath != null && actionText != null) n.addAction(NotificationAction.createSimpleExpiring(actionText) { open(openPath) })
        n.notify(project)
    }

    companion object {
        fun get(project: Project): AuditService = project.getService(AuditService::class.java)
    }
}
