package com.kareem.designaudit.analysis

import com.kareem.designaudit.model.Areas
import com.kareem.designaudit.model.Finding
import com.kareem.designaudit.model.Severity
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

/** Markdown and self-contained HTML reports. */
object Reports {

    private fun now() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    private fun verdict(err: Int, warn: Int) = when {
        err == 0 && warn <= 3 -> "Close to ready — polish items only."
        err <= 2 -> "Needs work — fix the errors before release."
        else -> "Major rework — several fundamentals are missing."
    }

    /** Rules ranked: errors first, then by count. Returns (rule, findings) pairs. */
    fun topRules(findings: List<Finding>, limit: Int = 5): List<Pair<String, List<Finding>>> =
        findings.groupBy { it.rule }.entries
            .sortedWith(compareBy({ e -> e.value.minOf { it.severity.ordinal } }, { e -> -e.value.size }))
            .take(limit).map { it.key to it.value }

    fun markdown(title: String, deviceLine: String?, findings: List<Finding>, gapHistogram: Map<Int, Int>? = null, truncated: Map<String, Int>? = null): String {
        val err = findings.count { it.severity == Severity.ERROR }
        val warn = findings.size - err
        val b = StringBuilder()
        b.append("# Android design audit — $title\n\n${now()}")
        if (!deviceLine.isNullOrEmpty()) b.append(" · $deviceLine")
        b.append("\n\n## Score\n**$err errors**, $warn warnings\nVerdict: ${verdict(err, warn)}\n")
        if (!gapHistogram.isNullOrEmpty()) b.append("Sibling gaps measured on screen: ${gapHistogram.entries.joinToString(", ") { "${it.key}dp×${it.value}" }}\n")
        b.append("\n## Top fixes\n")
        topRules(findings).forEachIndexed { i, (rule, fs) ->
            val f = fs.first()
            b.append("${i + 1}. **$rule** (${fs.size}×, ${f.severity.label.lowercase()}) — ${f.message}\n")
            if (f.suggestion.isNotEmpty()) b.append("   → ${f.suggestion}\n")
            if (f.why.isNotEmpty()) b.append("   Why: ${f.why}\n")
            b.append("   Where: ${fs.map { it.location() }.distinct().take(6).joinToString(", ")}${if (fs.size > 6) ", …" else ""}\n")
        }
        b.append("\n## By area\n")
        for (area in Areas.all) {
            val fs = findings.filter { it.area == area }
            if (fs.isEmpty()) continue
            b.append("\n### $area (${fs.size})\n")
            fs.sortedWith(compareBy({ it.severity.ordinal }, { it.location() })).forEach { f ->
                b.append("- [${f.severity.label.lowercase()}] **${f.rule}** `${f.location()}`: ${f.message}")
                if (f.snippet.isNotEmpty()) b.append(" — `${f.snippet}`")
                b.append('\n')
                if (f.suggestion.isNotEmpty()) b.append("  - Fix: ${f.suggestion}\n")
            }
        }
        if (!truncated.isNullOrEmpty()) b.append("\nTruncated (more of the same): ${truncated.entries.joinToString("; ") { "${it.key} (+${it.value})" }}\n")
        return b.toString()
    }

    /**
     * Full Markdown export: every finding, numbered to match the tool window, each with its
     * location, code snippet, "why it matters" and "how to fix" — plus a checklist at the end.
     */
    fun fullMarkdown(
        title: String,
        findings: List<Finding>,
        deviceLines: Map<String, String> = emptyMap(),
        screenshots: Map<String, Path> = emptyMap(),
        truncated: Map<String, Int>? = null,
        root: Path? = null,
    ): String {
        fun rel(p: Path): String = runCatching { root?.relativize(p.toAbsolutePath())?.toString() }.getOrNull() ?: p.toString()
        val err = findings.count { it.severity == Severity.ERROR }
        val warn = findings.size - err
        val numbers = java.util.IdentityHashMap<Finding, Int>().also { m -> findings.forEachIndexed { i, f -> m[f] = i + 1 } }
        val b = StringBuilder()

        b.append("# Android Design Audit — $title\n\n")
        b.append("_Generated ${now()}_\n\n")
        if (deviceLines.isNotEmpty()) {
            b.append("| Screen | Device |\n|---|---|\n")
            deviceLines.forEach { (k, v) -> b.append("| $k | $v |\n") }
            b.append('\n')
        }

        b.append("## Summary\n\n")
        b.append("| Errors | Warnings | Total |\n|---:|---:|---:|\n| **$err** | $warn | ${findings.size} |\n\n")
        b.append("**Verdict:** ${verdict(err, warn)}\n\n")
        b.append("### By area\n\n| Area | Errors | Warnings |\n|---|---:|---:|\n")
        for (area in Areas.all) {
            val fs = findings.filter { it.area == area }
            if (fs.isEmpty()) continue
            b.append("| $area | ${fs.count { it.severity == Severity.ERROR }} | ${fs.count { it.severity == Severity.WARNING }} |\n")
        }
        b.append('\n')

        b.append("## Top fixes (highest impact first)\n\n")
        topRules(findings, 10).forEachIndexed { i, (rule, fs) ->
            val f = fs.first()
            b.append("${i + 1}. **`$rule`** — ${fs.size} × ${f.severity.label.lowercase()}: ${f.message}\n")
            if (f.suggestion.isNotEmpty()) b.append("   - **Fix:** ${f.suggestion}\n")
            if (f.why.isNotEmpty()) b.append("   - **Why:** ${f.why}\n")
            b.append("   - **Where:** ${fs.map { "#${numbers[it]} ${it.location()}" }.joinToString(", ")}\n")
        }
        b.append('\n')

        if (screenshots.isNotEmpty()) {
            b.append("## Screenshots\n\nNumbers drawn on the images match the finding numbers below.\n\n")
            screenshots.forEach { (name, p) -> b.append("- **$name**: `${rel(p)}`\n") }
            b.append('\n')
        }

        b.append("## All findings\n\n")
        for (sev in Severity.values()) {
            val fs = findings.filter { it.severity == sev }
            if (fs.isEmpty()) continue
            b.append("### ${sev.label}s (${fs.size})\n\n")
            for (f in fs) {
                b.append("#### #${numbers[f]} · `${f.rule}` · ${f.area}\n\n")
                b.append("- **Where:** `${f.location()}`")
                if (f.file != null) b.append(" — `${rel(f.file)}`")
                b.append('\n')
                b.append("- **Problem:** ${f.message}\n")
                if (f.snippet.isNotEmpty()) b.append("- **Code:**\n\n  ```kotlin\n  ${f.snippet}\n  ```\n")
                if (f.why.isNotEmpty()) b.append("- **Why it matters:** ${f.why}\n")
                if (f.suggestion.isNotEmpty()) b.append("- **How to fix:** ${f.suggestion}\n")
                b.append('\n')
            }
        }

        if (!truncated.isNullOrEmpty()) {
            b.append("## Not listed (per-file cap reached)\n\n")
            b.append("Raise *Max findings per rule per file* in the plugin settings to include them.\n\n")
            truncated.forEach { (k, v) -> b.append("- $k: +$v more\n") }
            b.append('\n')
        }

        b.append("## Fix checklist\n\n")
        findings.forEach { f -> b.append("- [ ] #${numbers[f]} `${f.rule}` — ${f.location()}\n") }
        return b.toString()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Self-contained HTML (images embedded) for sharing with the team. [images] = screen name → annotated png. */
    fun html(title: String, findings: List<Finding>, images: Map<String, Path>, deviceLines: Map<String, String>): String {
        val err = findings.count { it.severity == Severity.ERROR }
        val warn = findings.size - err
        val sb = StringBuilder()
        sb.append("""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Design audit — ${esc(title)}</title>
<style>
:root{--bg:#0f1115;--card:#171a21;--line:#262b36;--fg:#e8eaf0;--muted:#9aa3b2;--err:#ef4444;--warn:#f59e0b;--info:#3b82f6;--ok:#22c55e}
@media (prefers-color-scheme: light){:root{--bg:#f6f7f9;--card:#fff;--line:#e3e6ec;--fg:#1a1d24;--muted:#5d6676}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.5 -apple-system,Segoe UI,Roboto,Inter,sans-serif}
.wrap{max-width:1100px;margin:0 auto;padding:32px 20px}h1{font-size:22px;margin:0 0 4px}h2{font-size:16px;margin:32px 0 12px;color:var(--muted);text-transform:uppercase;letter-spacing:.06em}
.sub{color:var(--muted);margin-bottom:24px}.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px}
.card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:16px}.card .n{font-size:30px;font-weight:700}.card .l{color:var(--muted);font-size:12px}
.err .n{color:var(--err)}.warn .n{color:var(--warn)}.info .n{color:var(--info)}.verdict{margin-top:12px;font-weight:600}
.fix{background:var(--card);border:1px solid var(--line);border-left:4px solid var(--line);border-radius:10px;padding:14px 16px;margin-bottom:10px}
.fix.error{border-left-color:var(--err)}.fix.warning{border-left-color:var(--warn)}.fix.info{border-left-color:var(--info)}
.fix b{font-family:ui-monospace,Menlo,Consolas,monospace}.fix .how{margin-top:6px}.fix .why,.fix .where{color:var(--muted);font-size:12.5px;margin-top:4px}
table{width:100%;border-collapse:collapse;background:var(--card);border:1px solid var(--line);border-radius:10px;overflow:hidden}
th,td{padding:8px 10px;border-bottom:1px solid var(--line);text-align:left;vertical-align:top}th{color:var(--muted);font-weight:600;font-size:12px}
td.sev{white-space:nowrap;font-weight:600}td.sev.error{color:var(--err)}td.sev.warning{color:var(--warn)}td.sev.info{color:var(--info)}
code{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;background:rgba(127,127,127,.12);padding:1px 5px;border-radius:4px}
.shots{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:16px}.shot{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:12px}
.shot img{width:100%;border-radius:8px;border:1px solid var(--line)}.shot .cap{margin-top:8px;color:var(--muted);font-size:12px}
.area{margin-top:22px}.area h3{margin:0 0 8px;font-size:15px}
</style></head><body><div class="wrap">
<h1>Android design audit — ${esc(title)}</h1><div class="sub">${now()}""")
        deviceLines.forEach { (k, v) -> sb.append(" · <b>${esc(k)}</b>: ${esc(v)}") }
        sb.append("""</div>
<div class="cards"><div class="card err"><div class="n">$err</div><div class="l">Errors</div></div><div class="card warn"><div class="n">$warn</div><div class="l">Warnings</div></div></div>
<div class="verdict">${esc(verdict(err, warn))}</div>""")

        sb.append("<h2>Top fixes</h2>")
        topRules(findings).forEach { (rule, fs) ->
            val f = fs.first()
            sb.append("""<div class="fix ${f.severity.name.lowercase()}"><b>${esc(rule)}</b> · ${fs.size}× · ${f.severity.label}<div>${esc(f.message)}</div>""")
            if (f.suggestion.isNotEmpty()) sb.append("""<div class="how">→ ${esc(f.suggestion)}</div>""")
            if (f.why.isNotEmpty()) sb.append("""<div class="why">Why: ${esc(f.why)}</div>""")
            sb.append("""<div class="where">Where: ${esc(fs.map { it.location() }.distinct().take(8).joinToString(", "))}${if (fs.size > 8) ", …" else ""}</div></div>""")
        }

        if (images.isNotEmpty()) {
            sb.append("<h2>Screens</h2><div class=\"shots\">")
            for ((name, png) in images) if (Files.exists(png)) {
                val b64 = Base64.getEncoder().encodeToString(Files.readAllBytes(png))
                val n = findings.count { it.screen == name }
                sb.append("""<div class="shot"><img alt="${esc(name)}" src="data:image/png;base64,$b64"><div class="cap"><b>${esc(name)}</b> · $n findings · numbers match the table</div></div>""")
            }
            sb.append("</div>")
        }

        sb.append("<h2>All findings</h2>")
        for (area in Areas.all) {
            val fs = findings.filter { it.area == area }
            if (fs.isEmpty()) continue
            sb.append("""<div class="area"><h3>${esc(area)} <span style="color:var(--muted);font-weight:400">(${fs.size})</span></h3><table><tr><th>#</th><th>Severity</th><th>Rule</th><th>Location</th><th>Problem</th><th>Fix</th></tr>""")
            fs.forEach { f ->
                val idx = findings.indexOfFirst { it === f } + 1
                sb.append("""<tr><td>$idx</td><td class="sev ${f.severity.name.lowercase()}">${f.severity.label}</td><td><code>${esc(f.rule)}</code></td><td>${esc(f.location())}</td><td>${esc(f.message)}${if (f.snippet.isNotEmpty()) "<br><code>${esc(f.snippet)}</code>" else ""}</td><td>${esc(f.suggestion)}</td></tr>""")
            }
            sb.append("</table></div>")
        }
        sb.append("</div></body></html>")
        return sb.toString()
    }
}
