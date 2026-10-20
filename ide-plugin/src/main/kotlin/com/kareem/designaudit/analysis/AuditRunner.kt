package com.kareem.designaudit.analysis

import com.kareem.designaudit.adb.Adb
import com.kareem.designaudit.model.DeviceInfo
import com.kareem.designaudit.model.Finding
import com.kareem.designaudit.model.AuditConfig
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/** Orchestrates a capture-and-measure run or a code scan, writing artifacts under the project's audit folder. */
object AuditRunner {

    class Capture(val name: String, val dir: Path) {
        lateinit var device: DeviceInfo
        lateinit var result: HierarchyAnalyzer.Result
        var boundsPng: Path? = null
        var cleanPng: Path? = null
        var annotatedPng: Path? = null
        var hierarchyXml: Path? = null
        var reportMd: Path? = null
        var perf: PerfProbe.FrameStats? = null
        val findings: List<Finding> get() = result.findings
    }

    class CodeScan(val dir: Path) {
        lateinit var result: ComposeScanner.Result
        var reportMd: Path? = null
        val findings: List<Finding> get() = result.findings
    }

    fun outputRoot(project: Path): Path = project.resolve(AuditConfig.current.outputFolder)

    private fun analyzer(name: String, device: DeviceInfo): HierarchyAnalyzer {
        val s = AuditConfig.current
        return HierarchyAnalyzer(name, device, s.minTouchDp.toDouble(), s.minTouchGapDp.toDouble(), s.screenMarginDp.toDouble(), s.gridDp.toDouble(), s.minInnerPaddingDp.toDouble())
    }

    /** Enables layout bounds, captures, dumps, restores, measures, annotates, writes report. */
    fun capture(adb: Adb, project: Path, name: String, progress: (String) -> Unit): Capture {
        val s = AuditConfig.current
        val c = Capture(name, outputRoot(project).resolve(name).createDirectories())

        progress("Reading device info…")
        c.device = adb.deviceInfo()
        if (c.device.densityDpi == 0) throw IOException("Could not read device density (adb shell wm density)")

        progress("Enabling 'Show layout bounds' and capturing…")
        adb.setLayoutBounds(true)
        val bounds: ByteArray
        val xml: String
        try {
            bounds = adb.screenshotPng()
            progress("Dumping view hierarchy…")
            xml = adb.dumpHierarchy()
        } finally {
            if (!s.keepLayoutBounds) { progress("Restoring layout bounds setting…"); adb.setLayoutBounds(false) }
        }
        c.boundsPng = c.dir.resolve("screen_bounds.png").also { it.writeBytes(bounds) }
        if (s.captureCleanScreenshot) {
            if (s.keepLayoutBounds) adb.setLayoutBounds(false)
            c.cleanPng = c.dir.resolve("screen_clean.png").also { it.writeBytes(adb.screenshotPng()) }
            if (s.keepLayoutBounds) adb.setLayoutBounds(true)
        }
        c.hierarchyXml = c.dir.resolve("hierarchy.xml").also { it.writeText(xml) }
        c.dir.resolve("device.txt").writeText("${c.device.summary()}\npackage=${c.device.packageName} activity=${c.device.activity}\n")

        progress("Measuring spacing and touch targets…")
        c.result = analyzer(name, c.device).analyze(xml.byteInputStream())
        if (c.device.packageName.isNotEmpty()) {
            progress("Reading frame statistics…")
            runCatching { PerfProbe.read(adb, c.device.packageName) }.onSuccess { st ->
                c.perf = st
                c.result.findings += PerfProbe.findings(name, st, c.device.packageName)
            }
        }
        finish(c)
        return c
    }

    /**
     * Resets the frame counters, waits [seconds] while the user interacts with the app, then reads
     * the stats. Produces a Capture named "<name>-perf" with performance findings only.
     */
    fun measurePerformance(adb: Adb, project: Path, name: String, seconds: Int, progress: (String) -> Unit): Capture {
        val device = adb.deviceInfo()
        if (device.packageName.isEmpty()) throw IOException("Could not detect the foreground app — open your app on the device first")
        val c = Capture("$name-perf", outputRoot(project).resolve("$name-perf").createDirectories())
        c.device = device
        PerfProbe.reset(adb, device.packageName)
        for (t in seconds downTo 1) { progress("Scroll / tap / animate in the app — ${t}s left (${device.packageName})"); Thread.sleep(1000) }
        progress("Reading frame statistics…")
        val st = PerfProbe.read(adb, device.packageName)
        c.perf = st
        c.result = HierarchyAnalyzer.Result().also { it.findings += PerfProbe.findings(c.name, st, device.packageName) }
        c.dir.resolve("gfxinfo.txt").writeText(adb.shell("dumpsys", "gfxinfo", device.packageName))
        c.reportMd = c.dir.resolve("findings.md").also {
            it.writeText(Reports.markdown("performance '${c.name}' — ${st.summary()}", device.summary(), c.findings))
        }
        return c
    }

    /** Re-analyzes an existing capture folder (hierarchy.xml + device info) without a device. */
    fun reanalyze(dir: Path, name: String, device: DeviceInfo): Capture {
        val c = Capture(name, dir)
        c.device = device
        c.hierarchyXml = dir.resolve("hierarchy.xml")
        c.boundsPng = dir.resolve("screen_bounds.png").takeIf { it.exists() }
        c.cleanPng = dir.resolve("screen_clean.png").takeIf { it.exists() }
        c.result = Files.newInputStream(c.hierarchyXml!!).use { analyzer(name, device).analyze(it) }
        finish(c)
        return c
    }

    private fun finish(c: Capture) {
        c.boundsPng?.let { src ->
            runCatching { Annotator.annotate(src, c.dir.resolve("annotated.png"), c.findings, c.device.scale) }
                .onSuccess { c.annotatedPng = c.dir.resolve("annotated.png") }
        }
        c.reportMd = c.dir.resolve("findings.md").also {
            it.writeText(Reports.markdown("screen '${c.name}'", c.device.summary(), c.findings, c.result.gapHistogram))
        }
    }

    fun scanCode(project: Path): CodeScan {
        val s = CodeScan(outputRoot(project).createDirectories())
        s.result = ComposeScanner(AuditConfig.current.maxPerRule).scan(project)
        s.reportMd = s.dir.resolve("code_findings.md").also {
            it.writeText(Reports.markdown("code (${s.result.ktFiles} Kotlin files)", null, s.findings, null, s.result.truncated))
        }
        return s
    }

    /** Complete Markdown (every finding with why + fix), as shown in the tool window. */
    fun fullMarkdown(project: Path, captures: Collection<Capture>, scan: CodeScan?): String {
        val all = captures.flatMap { it.findings } + (scan?.findings ?: emptyList())
        return Reports.fullMarkdown(
            project.fileName.toString(), all,
            captures.associate { it.name to (it.device.summary() + (it.perf?.let { p -> " · " + p.summary() } ?: "")) },
            captures.mapNotNull { c -> c.annotatedPng?.let { c.name to it } }.toMap(),
            scan?.result?.truncated,
            project.toAbsolutePath(),
        )
    }

    fun writeCombined(project: Path, captures: Collection<Capture>, scan: CodeScan?): Pair<Path, Path> {
        val all = captures.flatMap { it.findings } + (scan?.findings ?: emptyList())
        val root = outputRoot(project).createDirectories()
        val md = root.resolve("audit-report.md").also { it.writeText(fullMarkdown(project, captures, scan)) }
        val html = root.resolve("audit-report.html").also {
            it.writeText(Reports.html(project.fileName.toString(), all,
                captures.mapNotNull { c -> c.annotatedPng?.let { p -> c.name to p } }.toMap(),
                captures.associate { c -> c.name to (c.device.summary() + (c.perf?.let { " · " + it.summary() } ?: "")) }))
        }
        return md to html
    }
}
