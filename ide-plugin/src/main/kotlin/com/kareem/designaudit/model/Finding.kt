package com.kareem.designaudit.model

import java.nio.file.Path
import java.util.Locale

enum class Severity(val label: String) { ERROR("Error"), WARNING("Warning") }

enum class Source { HIERARCHY, CODE, PROJECT }

/** One audit finding, from the device hierarchy, the code scan, or project-level checks. */
data class Finding(
    val source: Source,
    val severity: Severity,
    val rule: String,
    val message: String,
    val suggestion: String,
    val why: String,
    // code findings
    val file: Path? = null,
    val line: Int = 0,
    val snippet: String = "",
    // hierarchy findings
    val screen: String? = null,
    val node: String? = null,
    val boundsPx: IntArray? = null,
    val boundsDp: DoubleArray? = null,
) {
    val area: String get() = Areas.of(rule)

    fun location(): String = when {
        file != null -> "${file.fileName}:$line"
        screen != null && boundsDp != null -> String.format(
            Locale.ROOT, "%s [%.0f,%.0f %.0f×%.0fdp]", screen, boundsDp[0], boundsDp[1],
            boundsDp[2] - boundsDp[0], boundsDp[3] - boundsDp[1]
        )
        screen != null -> screen
        else -> "(project)"
    }

    override fun toString() = "[$severity] $rule ${location()}: $message"
}

/** Facts about the device a screen was captured from. */
class DeviceInfo {
    var serial = ""
    var densityDpi = 0
    var widthPx = 0
    var heightPx = 0
    var sdk = 0
    var androidVersion = ""
    var locale = ""
    var fontScale = ""
    var nightMode = ""
    var packageName = ""
    var activity = ""

    val scale: Double get() = if (densityDpi > 0) densityDpi / 160.0 else 2.625

    fun summary(): String = buildString {
        append("${widthPx}×${heightPx}px · ${densityDpi}dpi · Android $androidVersion (API $sdk)")
        if (locale.isNotEmpty()) append(" · $locale")
        if (nightMode.isNotEmpty()) append(" · $nightMode")
        if (fontScale.isNotEmpty() && fontScale != "null") append(" · font ×$fontScale")
    }
}

/** Maps rule ids to the checklist area they belong to (used for grouping and the report). */
object Areas {
    private val map = linkedMapOf<String, String>().apply {
        listOf("spacing-off-grid", "edge-margin", "edge-margin-inconsistent", "misaligned", "overlap", "dp-off-grid", "overflow-horizontal", "fixed-width", "container-padding", "container-padding-inconsistent").forEach { put(it, "Spacing & layout") }
        listOf("touch-target-size", "touch-target-gap", "touch-target-small").forEach { put(it, "Touch targets") }
        listOf("bottom-nav-count", "material2-import", "toast-usage").forEach { put(it, "Native components") }
        listOf("predictive-back").forEach { put(it, "Navigation & back") }
        listOf("font-size-dp", "font-size-small", "font-size-hardcoded", "typography-scale", "text-overflow", "text-no-style", "font-family-missing", "font-family-inline").forEach { put(it, "Typography") }
        listOf("hardcoded-color", "dark-theme", "dynamic-color").forEach { put(it, "Color & theme") }
        listOf("edge-to-edge", "window-insets", "splash-screen", "adaptive-icon").forEach { put(it, "System UI") }
        listOf("rtl-unsafe", "rtl-icon", "supports-rtl", "hardcoded-string", "localization").forEach { put(it, "RTL & localization") }
        listOf("adaptive-layout").forEach { put(it, "Adaptive layouts") }
        listOf("loading-state", "empty-error-state").forEach { put(it, "States") }
        listOf("a11y-unlabeled", "a11y-icon-unlabeled", "a11y-duplicate-label").forEach { put(it, "Accessibility") }
        listOf("spacing-literal", "no-spacing-tokens", "hardcoded-shape", "no-shape-tokens", "hardcoded-elevation", "hardcoded-textstyle",
            "icon-size", "button-height", "card-clickable", "custom-dialog", "deprecated-api", "empty-click", "todo-left", "string-via-context").forEach { put(it, "Design system") }
        listOf("unused-import", "unused-code", "unused-resource", "duplicate-string", "duplicate-image", "large-image", "large-vector").forEach { put(it, "Code & resource hygiene") }
        listOf("perf-state-no-remember", "perf-lazy-no-key", "perf-column-foreach", "perf-lazy-in-scroll", "perf-state-in-modifier",
            "perf-alloc-in-composition", "perf-compute-in-composition", "perf-blocking-ui", "perf-bitmap-decode", "perf-collect-lifecycle",
            "perf-effect-unstable-key", "perf-global-scope", "perf-unstable-param", "perf-modifier-composed",
            "perf-jank", "perf-frame-time", "perf-missed-vsync", "perf-slow-ui-thread", "perf-memory").forEach { put(it, "Performance") }
    }
    val all: List<String> = map.values.distinct() + "Other"
    fun of(rule: String): String = map[rule] ?: "Other"
    fun rulesIn(area: String): List<String> = map.filterValues { it == area }.keys.toList()
}
