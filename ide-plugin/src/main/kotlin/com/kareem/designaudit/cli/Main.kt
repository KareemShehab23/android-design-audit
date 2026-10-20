package com.kareem.designaudit.cli

import com.kareem.designaudit.analysis.Annotator
import com.kareem.designaudit.analysis.ComposeScanner
import com.kareem.designaudit.analysis.HierarchyAnalyzer
import com.kareem.designaudit.analysis.Reports
import com.kareem.designaudit.model.Areas
import com.kareem.designaudit.model.AuditConfig
import com.kareem.designaudit.model.DeviceInfo
import com.kareem.designaudit.model.Finding
import com.kareem.designaudit.model.Severity
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.system.exitProcess

/**
 * audit-core command line.
 *
 *   audit     <project>  [--out DIR] [--format md|json|text] [--margin 16] [--max-per-rule 8]
 *   review    <project>  (--files f1 f2 … | --git [--base REF]) [--format …] [--project-rules]
 *   hierarchy <capture-dir> --density DPI --size WxH [--name screen] [--format …]
 *   rules
 *
 * Exit code: 0 = no errors, 1 = errors found, 2 = usage / I/O problem.
 */
object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) usage()
        val cmd = args[0]
        val opts = parse(args.drop(1))
        try {
            when (cmd) {
                "audit" -> audit(opts)
                "review" -> review(opts)
                "hierarchy" -> hierarchy(opts)
                "rules" -> rules()
                "-h", "--help", "help" -> usage()
                else -> usage("Unknown command '$cmd'")
            }
        } catch (e: Exception) {
            System.err.println("error: ${e.message}")
            exitProcess(2)
        }
    }

    private class Opts(val positional: List<String>, val flags: Map<String, List<String>>) {
        fun value(name: String): String? = flags[name]?.lastOrNull()
        fun values(name: String): List<String> = flags[name] ?: emptyList()
        fun has(name: String) = name in flags
    }

    private fun parse(args: List<String>): Opts {
        val pos = mutableListOf<String>(); val flags = mutableMapOf<String, MutableList<String>>()
        var i = 0; var current: String? = null
        while (i < args.size) {
            val a = args[i]
            if (a.startsWith("--")) { current = a.removePrefix("--"); flags.getOrPut(current) { mutableListOf() } }
            else if (current != null) flags[current]!!.add(a)
            else pos += a
            i++
        }
        return Opts(pos, flags)
    }

    private fun applyConfig(o: Opts) {
        val c = AuditConfig.current
        o.value("margin")?.toIntOrNull()?.let { c.screenMarginDp = it }
        o.value("touch")?.toIntOrNull()?.let { c.minTouchDp = it }
        o.value("grid")?.toIntOrNull()?.let { c.gridDp = it }
        o.value("inner-padding")?.toIntOrNull()?.let { c.minInnerPaddingDp = it }
        o.value("max-per-rule")?.toIntOrNull()?.let { c.maxPerRule = it }
        o.value("out")?.let { c.outputFolder = it }
    }

    // ---- audit ---------------------------------------------------------------------------------
    private fun audit(o: Opts) {
        val project = projectDir(o)
        applyConfig(o)
        val result = ComposeScanner(AuditConfig.current.maxPerRule).scan(project)
        emit(o, "code (${result.ktFiles} Kotlin files)", result.findings, project, result.truncated)
    }

    // ---- review (changed files only) ----------------------------------------------------------
    private fun review(o: Opts) {
        val project = projectDir(o)
        applyConfig(o)
        val files: List<Path> = when {
            o.has("files") -> o.values("files").map { project.resolve(it).toAbsolutePath().normalize() }
            o.has("git") -> gitChanged(project, o.value("base"))
            else -> usage("review needs --files <paths…> or --git [--base REF]")
        }.filter { it.exists() }
        if (files.isEmpty()) { println("No changed files to review."); exitProcess(0) }
        val kt = files.filter { it.toString().endsWith(".kt") }.toSet()
        val touchesResources = files.any { val s = it.toString(); s.endsWith(".xml") || s.contains("/res/") || s.endsWith("AndroidManifest.xml") }
        val result = ComposeScanner(AuditConfig.current.maxPerRule, reportOnly = kt).scan(project)
        val projectLevel = result.findings.filter { it.file == null }
        val fileLevel = result.findings.filter { it.file != null }
        val selected = fileLevel + if (o.has("project-rules") || touchesResources) projectLevel.filter { it.rule.startsWith("unused-resource") || it.rule.startsWith("duplicate") || o.has("project-rules") } else emptyList()
        emit(o, "review of ${files.size} changed file(s)", selected, project, null, reviewedFiles = files.map { project.relativize(it).toString() })
    }

    private fun gitChanged(project: Path, base: String?): List<Path> {
        fun run(vararg cmd: String): List<String> {
            val p = ProcessBuilder(*cmd).directory(project.toFile()).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readLines(); p.waitFor()
            return if (p.exitValue() == 0) out else emptyList()
        }
        val changed = LinkedHashSet<String>()
        if (base != null) changed += run("git", "diff", "--name-only", "$base...HEAD")
        changed += run("git", "diff", "--name-only")                 // unstaged
        changed += run("git", "diff", "--name-only", "--cached")      // staged
        changed += run("git", "ls-files", "--others", "--exclude-standard")   // untracked
        val top = run("git", "rev-parse", "--show-toplevel").firstOrNull()?.let { Paths.get(it) } ?: project
        return changed.filter { it.isNotBlank() }.map { top.resolve(it).toAbsolutePath().normalize() }
    }

    // ---- hierarchy (device capture folder) ----------------------------------------------------
    private fun hierarchy(o: Opts) {
        val dir = o.positional.firstOrNull()?.let { Paths.get(it) } ?: usage("hierarchy needs a capture directory")
        applyConfig(o)
        val device = DeviceInfo().apply {
            densityDpi = o.value("density")?.toIntOrNull() ?: readDeviceTxt(dir, "dpi") ?: usage("--density DPI is required")
            val size = o.value("size") ?: readDeviceTxtSize(dir)
            size?.let { s -> Regex("(\\d+)[x×](\\d+)").find(s)?.let { widthPx = it.groupValues[1].toInt(); heightPx = it.groupValues[2].toInt() } }
        }
        val name = o.value("name") ?: dir.fileName.toString()
        val c = AuditConfig.current
        val res = Files.newInputStream(dir.resolve("hierarchy.xml")).use {
            HierarchyAnalyzer(name, device, c.minTouchDp.toDouble(), c.minTouchGapDp.toDouble(), c.screenMarginDp.toDouble(), c.gridDp.toDouble(), c.minInnerPaddingDp.toDouble()).analyze(it)
        }
        val shot = dir.resolve("screen_bounds.png")
        if (shot.exists()) runCatching { Annotator.annotate(shot, dir.resolve("annotated.png"), res.findings, device.scale) }
        emit(o, "screen '$name'", res.findings, dir, null, deviceLine = device.summary())
    }

    private fun readDeviceTxt(dir: Path, key: String): Int? {
        val f = dir.resolve("device.txt"); if (!f.exists()) return null
        return Regex("(\\d+)dpi").find(f.readText())?.groupValues?.get(1)?.toInt()
    }
    private fun readDeviceTxtSize(dir: Path): String? {
        val f = dir.resolve("device.txt"); if (!f.exists()) return null
        return Regex("(\\d+)[x×](\\d+)px").find(f.readText())?.value
    }

    // ---- rules catalog -------------------------------------------------------------------------
    private fun rules() {
        println("Rule catalog (area → rule ids). Severity is decided per finding: errors block, warnings must be fixed or justified.")
        for (area in Areas.all) {
            val ids = Areas.rulesIn(area)
            if (ids.isNotEmpty()) println("\n$area\n  " + ids.joinToString("\n  "))
        }
    }

    // ---- output --------------------------------------------------------------------------------
    private fun emit(o: Opts, title: String, findings: List<Finding>, root: Path, truncated: Map<String, Int>?, deviceLine: String? = null, reviewedFiles: List<String>? = null) {
        val format = o.value("format") ?: "text"
        val errors = findings.count { it.severity == Severity.ERROR }
        when (format) {
            "json" -> println(Json.render(title, findings, root, truncated, reviewedFiles))
            "md" -> println(Reports.fullMarkdown(title, findings, if (deviceLine != null) mapOf(title to deviceLine) else emptyMap(), emptyMap(), truncated, root.toAbsolutePath()))
            else -> {
                println("Android Design Audit — $title")
                if (reviewedFiles != null) println("Reviewed: " + reviewedFiles.joinToString(", "))
                println("$errors errors, ${findings.size - errors} warnings\n")
                for ((i, f) in findings.withIndex()) {
                    val loc = if (f.file != null) root.toAbsolutePath().relativize(f.file.toAbsolutePath()).toString() + ":" + f.line else f.location()
                    println("${i + 1}. [${f.severity.label.uppercase()}] ${f.rule} — $loc")
                    println("   ${f.message}")
                    if (f.snippet.isNotEmpty()) println("   > ${f.snippet}")
                    if (f.suggestion.isNotEmpty()) println("   Fix: ${f.suggestion}")
                    if (f.why.isNotEmpty()) println("   Why: ${f.why}")
                }
                if (!truncated.isNullOrEmpty()) println("\nTruncated: " + truncated.entries.joinToString("; ") { "${it.key} (+${it.value})" })
            }
        }
        o.value("report")?.let { Files.writeString(Paths.get(it), Reports.fullMarkdown(title, findings, emptyMap(), emptyMap(), truncated, root.toAbsolutePath())) }
        exitProcess(if (errors > 0) 1 else 0)
    }

    private fun projectDir(o: Opts): Path {
        val p = o.positional.firstOrNull()?.let { Paths.get(it) } ?: Paths.get(".")
        if (!p.isDirectory()) usage("'$p' is not a directory")
        return p.toAbsolutePath().normalize()
    }

    private fun usage(msg: String? = null): Nothing {
        if (msg != null) System.err.println("error: $msg\n")
        System.err.println("""
            audit-core — Android / Jetpack Compose design, performance & hygiene audit

            usage:
              audit     <project-dir> [options]                 audit the whole project
              review    <project-dir> --files <f…> | --git [--base REF] [--project-rules]
                                                                review only changed files (while developing / in PRs)
              hierarchy <capture-dir> [--density DPI --size WxH] [--name screen]
                                                                measure a device capture (hierarchy.xml + screen_bounds.png)
              rules                                             print the rule catalog

            options:
              --format text|md|json      output format (default text)
              --report FILE.md           also write the full Markdown report
              --margin 16  --touch 48  --grid 4  --inner-padding 8  --max-per-rule 8
              --out DIR                  output folder name inside the project (default audit-captures)

            exit code: 0 clean, 1 errors found, 2 usage/I/O error
        """.trimIndent())
        exitProcess(2)
    }
}

/** Minimal JSON writer (no dependencies). */
private object Json {
    private fun esc(s: String) = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
        }
    }
    fun render(title: String, findings: List<Finding>, root: Path, truncated: Map<String, Int>?, reviewed: List<String>?): String {
        val sb = StringBuilder("{")
        sb.append("\"title\":\"${esc(title)}\",")
        sb.append("\"errors\":${findings.count { it.severity == Severity.ERROR }},\"warnings\":${findings.count { it.severity == Severity.WARNING }},")
        if (reviewed != null) sb.append("\"reviewed\":[${reviewed.joinToString(",") { "\"${esc(it)}\"" }}],")
        sb.append("\"findings\":[")
        findings.forEachIndexed { i, f ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":${i + 1},\"severity\":\"${f.severity.name.lowercase()}\",\"rule\":\"${esc(f.rule)}\",\"area\":\"${esc(f.area)}\",")
            sb.append("\"file\":${f.file?.let { "\"${esc(runCatching { root.toAbsolutePath().relativize(it.toAbsolutePath()).toString() }.getOrDefault(it.toString()))}\"" } ?: "null"},")
            sb.append("\"line\":${f.line},\"screen\":${f.screen?.let { "\"${esc(it)}\"" } ?: "null"},")
            sb.append("\"boundsDp\":${f.boundsDp?.joinToString(",", "[", "]") { "%.1f".format(it) } ?: "null"},")
            sb.append("\"message\":\"${esc(f.message)}\",\"fix\":\"${esc(f.suggestion)}\",\"why\":\"${esc(f.why)}\",\"snippet\":\"${esc(f.snippet)}\"}")
        }
        sb.append("]")
        if (!truncated.isNullOrEmpty()) sb.append(",\"truncated\":{${truncated.entries.joinToString(",") { "\"${esc(it.key)}\":${it.value}" }}}")
        sb.append("}")
        return sb.toString()
    }
}
