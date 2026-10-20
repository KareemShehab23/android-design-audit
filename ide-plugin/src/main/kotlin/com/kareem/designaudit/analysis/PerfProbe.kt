package com.kareem.designaudit.analysis

import com.kareem.designaudit.adb.Adb
import com.kareem.designaudit.model.Finding
import com.kareem.designaudit.model.Severity
import com.kareem.designaudit.model.Source

/**
 * Reads rendering statistics from the device (`dumpsys gfxinfo <package>`) and memory (`dumpsys meminfo`)
 * and turns them into findings. These are real measurements of the frames the app produced.
 */
object PerfProbe {

    data class FrameStats(
        val totalFrames: Int, val jankyFrames: Int, val jankyPercent: Double,
        val p50: Int, val p90: Int, val p95: Int, val p99: Int,
        val missedVsync: Int, val slowUiThread: Int, val slowBitmapUploads: Int, val slowDrawCommands: Int, val frameDeadlineMissed: Int,
        val totalPssKb: Int,
    ) {
        fun summary() = "$totalFrames frames · ${"%.1f".format(jankyPercent)}% janky · p50 ${p50}ms p90 ${p90}ms p95 ${p95}ms p99 ${p99}ms" +
                (if (totalPssKb > 0) " · ${totalPssKb / 1024}MB PSS" else "")
    }

    fun reset(adb: Adb, pkg: String) { adb.shell("dumpsys", "gfxinfo", pkg, "reset") }

    fun read(adb: Adb, pkg: String): FrameStats {
        val gfx = adb.shell("dumpsys", "gfxinfo", pkg)
        if (!gfx.contains("Total frames rendered")) throw IllegalStateException("No frame stats for '$pkg' — is the app in the foreground and drawing?")
        fun int(label: String): Int = Regex("$label:\\s*(\\d+)").find(gfx)?.groupValues?.get(1)?.toInt() ?: 0
        fun pct(label: String): Int = Regex("$label percentile:\\s*(\\d+)ms").find(gfx)?.groupValues?.get(1)?.toInt() ?: 0
        val total = int("Total frames rendered")
        val janky = int("Janky frames")
        val jankyPct = Regex("Janky frames:\\s*\\d+\\s*\\(([\\d.]+)%\\)").find(gfx)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: if (total > 0) janky * 100.0 / total else 0.0
        val pss = runCatching {
            val mem = adb.shell("dumpsys", "meminfo", pkg)
            Regex("TOTAL PSS:\\s*(\\d+)").find(mem)?.groupValues?.get(1)?.toInt() ?: Regex("TOTAL:?\\s+(\\d+)").find(mem)?.groupValues?.get(1)?.toInt() ?: 0
        }.getOrDefault(0)
        return FrameStats(total, janky, jankyPct, pct("50th"), pct("90th"), pct("95th"), pct("99th"),
            int("Number Missed Vsync"), int("Number Slow UI thread"), int("Number Slow bitmap uploads"), int("Number Slow issue draw commands"),
            int("Number Frame deadline missed"), pss)
    }

    /** Converts measured stats into findings for screen [screen]. Thresholds follow Android's jank guidance (16.7ms @60Hz). */
    fun findings(screen: String, s: FrameStats, pkg: String): List<Finding> {
        val out = mutableListOf<Finding>()
        fun f(sev: Severity, rule: String, msg: String, fix: String, why: String) {
            out += Finding(Source.HIERARCHY, sev, rule, msg, fix, why, screen = screen, node = pkg)
        }
        if (s.totalFrames < 30) return out   // not enough frames to judge; the summary line shows the count
        when {
            s.jankyPercent >= 25 -> f(Severity.ERROR, "perf-jank", "${"%.1f".format(s.jankyPercent)}% janky frames (${s.jankyFrames} of ${s.totalFrames}).",
                "Profile with Layout Inspector (recomposition counts) and the Profiler's System Trace; fix the 'perf-*' code findings first (lazy keys, state in modifiers, allocations).",
                "A quarter of frames miss the 16.7ms deadline — visible stutter while scrolling and animating.")
            s.jankyPercent >= 10 -> f(Severity.WARNING, "perf-jank", "${"%.1f".format(s.jankyPercent)}% janky frames (${s.jankyFrames} of ${s.totalFrames}).",
                "Check recomposition counts in Layout Inspector; prefer lambda modifiers, add lazy keys, remember expensive objects.",
                "Users notice stutter above ~10% janky frames.")
            else -> {}
        }
        if (s.p95 > 33) f(Severity.WARNING, "perf-frame-time", "95th-percentile frame time is ${s.p95}ms (p90 ${s.p90}ms, p99 ${s.p99}ms); budget is 16.7ms at 60Hz.",
            "Find the slow frames in System Trace; typical causes are heavy composition in list items, large bitmaps, or layout nesting.",
            "1 in 20 frames takes two or more frame periods — perceived as hitching.")
        else if (s.p90 > 20) f(Severity.WARNING, "perf-frame-time", "90th-percentile frame time is ${s.p90}ms (p95 ${s.p95}ms, p99 ${s.p99}ms); budget is 16.7ms.",
            "Profile the screen; aim for p90 ≤ 16ms.", "One frame in ten misses the deadline.")
        if (s.slowUiThread > s.totalFrames * 0.1) f(Severity.WARNING, "perf-slow-ui-thread", "${s.slowUiThread} frames with slow UI-thread work (composition/measure/layout).",
            "Reduce work in composition: remember { } expensive values, move list transforms to the ViewModel, avoid deep nesting of Boxes/Columns.",
            "The main thread is the bottleneck — usually too much recomposition or layout.")
        if (s.missedVsync > s.totalFrames * 0.1) f(Severity.WARNING, "perf-missed-vsync", "${s.missedVsync} missed vsync signals.",
            "Often a side effect of the slow-UI-thread frames above.", "")
        if (s.slowBitmapUploads > s.totalFrames * 0.02) f(Severity.WARNING, "perf-frame-time", "${s.slowBitmapUploads} frames with slow bitmap uploads.",
            "Downsample images to their display size (Coil/Glide size()), avoid decoding full-resolution photos.", "Large bitmaps stall the GPU upload.")
        if (s.totalPssKb > 400 * 1024) f(Severity.WARNING, "perf-memory", "App uses ${s.totalPssKb / 1024}MB PSS.",
            "Check for cached full-size bitmaps, leaked screens (LeakCanary), and large in-memory lists.", "High memory causes GC pauses and background kills.")
        return out
    }
}
