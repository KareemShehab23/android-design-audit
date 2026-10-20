package com.kareem.designaudit.analysis

import com.kareem.designaudit.model.DeviceInfo
import com.kareem.designaudit.model.Finding
import com.kareem.designaudit.model.Severity
import com.kareem.designaudit.model.Source
import org.w3c.dom.Element
import java.io.InputStream
import java.util.Locale
import java.util.TreeMap
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Measures spacing, touch targets and accessibility from a uiautomator hierarchy dump, in dp.
 * Material Design 3 fundamentals: 48dp targets, 8dp between targets, 16dp side margins, 4/8dp grid,
 * aligned edges, labelled interactive elements, 3–5 bottom-nav destinations.
 */
class HierarchyAnalyzer(
    private val screen: String,
    private val device: DeviceInfo,
    private val minTouchDp: Double = 48.0,
    private val minTouchGapDp: Double = 8.0,
    private val screenMarginDp: Double = 16.0,
    private val gridDp: Double = 4.0,
    private val minInnerPaddingDp: Double = 8.0,
) {
    private val tol = 0.75
    private val edgeZoneDp = 40.0
    private val scale = device.scale

    class Result {
        val findings = mutableListOf<Finding>()
        val gapHistogram = TreeMap<Int, Int>()
        var nodes = 0
        var interactive = 0
        var screenWdp = 0.0
        var screenHdp = 0.0
    }

    private class Node(el: Element, scale: Double, val parent: Node?, val index: Int) {
        val x1: Double; val y1: Double; val x2: Double; val y2: Double
        val w: Double; val h: Double
        val cls: String; val rid: String; val text: String; val desc: String
        val clickable: Boolean
        val children = mutableListOf<Node>()

        init {
            val m = BOUNDS.find(el.getAttribute("bounds"))
            val (px1, py1, px2, py2) = m?.groupValues?.drop(1)?.map { it.toInt() } ?: listOf(0, 0, 0, 0)
            x1 = px1 / scale; y1 = py1 / scale; x2 = px2 / scale; y2 = py2 / scale; w = x2 - x1; h = y2 - y1
            cls = el.getAttribute("class").substringAfterLast('.')
            rid = el.getAttribute("resource-id").substringAfterLast('/')
            text = el.getAttribute("text").trim()
            desc = el.getAttribute("content-desc").trim()
            clickable = el.getAttribute("clickable") == "true" || el.getAttribute("long-clickable") == "true" || el.getAttribute("checkable") == "true"
        }

        val visible get() = w > 0 && h > 0
        val hasContent get() = text.isNotEmpty() || desc.isNotEmpty()
        fun subtreeHasText(): Boolean = hasContent || children.any { it.subtreeHasText() }
        fun isAncestorOf(n: Node): Boolean { var p = n.parent; while (p != null) { if (p === this) return true; p = p.parent }; return false }
        fun boundsPx(scale: Double) = intArrayOf((x1 * scale).roundToInt(), (y1 * scale).roundToInt(), (x2 * scale).roundToInt(), (y2 * scale).roundToInt())
        fun boundsDp() = doubleArrayOf(x1, y1, x2, y2)
        fun label(): String = buildString {
            append(cls)
            if (rid.isNotEmpty()) append(" #").append(rid)
            if (text.isNotEmpty()) append(" \"").append(text.take(30)).append('"')
            else if (desc.isNotEmpty()) append(" [").append(desc.take(30)).append(']')
        }
    }

    private val all = mutableListOf<Node>()
    private val result = Result()

    fun analyze(xml: InputStream): Result {
        val f = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val doc = f.newDocumentBuilder().parse(xml)
        doc.documentElement.childElements("node").forEach { walk(it, null) }
        check(all.isNotEmpty()) { "hierarchy.xml contains no nodes" }
        val sw = if (device.widthPx > 0) device.widthPx / scale else all.maxOf { it.x2 }
        val sh = if (device.heightPx > 0) device.heightPx / scale else all.maxOf { it.y2 }
        result.screenWdp = sw; result.screenHdp = sh
        run(sw, sh)
        return result
    }

    private fun Element.childElements(tag: String): List<Element> {
        val list = childNodes
        return (0 until list.length).mapNotNull { list.item(it) as? Element }.filter { it.tagName == tag }
    }

    private fun walk(el: Element, parent: Node?) {
        val n = Node(el, scale, parent, all.size)
        all += n
        parent?.children?.add(n)
        el.childElements("node").forEach { walk(it, n) }
    }

    private fun add(rule: String, sev: Severity, n: Node?, msg: String, fix: String, why: String) {
        result.findings += Finding(
            Source.HIERARCHY, sev, rule, msg, fix, why,
            screen = screen, node = n?.label(), boundsPx = n?.boundsPx(scale), boundsDp = n?.boundsDp()
        )
    }

    private fun near(a: Double, b: Double, t: Double = tol) = abs(a - b) <= t
    private fun onGrid(v: Double): Boolean { val r = v % gridDp; return r <= tol || gridDp - r <= tol }
    private fun fmt(v: Double) = if (near(v, v.roundToInt().toDouble(), 0.3)) v.roundToInt().toString() else String.format(Locale.ROOT, "%.1f", v)

    /** Full-width row of equal-width tappable segments (NavigationBar, TabRow): segments may touch. */
    private fun isSegmentedBar(p: Node?, sw: Double): Boolean {
        if (p == null || p.w < sw * 0.9) return false
        val kids = p.children.filter { it.visible && it.clickable }
        if (kids.size < 2) return false
        val sameRow = kids.maxOf { it.y1 } - kids.minOf { it.y1 } <= 4
        return sameRow && kids.maxOf { it.w } - kids.minOf { it.w } <= 4
    }

    private fun run(sw: Double, sh: Double) {
        val visible = all.filter { it.visible }
        val clickables = visible.filter { it.clickable }
        result.nodes = all.size; result.interactive = clickables.size

        // 1. Touch target size
        for (n in clickables) {
            if (n.w + tol < minTouchDp || n.h + tol < minTouchDp) {
                if (n.w >= sw * 0.6 && n.h + tol >= minTouchDp) continue   // full-width list rows are fine
                add("touch-target-size", Severity.ERROR, n,
                    "Interactive element is ${fmt(n.w)}×${fmt(n.h)}dp; minimum is ${fmt(minTouchDp)}×${fmt(minTouchDp)}dp.",
                    "Wrap in IconButton (48dp by default) or add Modifier.minimumInteractiveComponentSize() / sizeIn(minWidth = 48.dp, minHeight = 48.dp). The icon can stay 24dp; only the tappable area must grow.",
                    "Targets under 48dp are hard to hit and fail accessibility review.")
            }
        }

        // 2. Gap between adjacent small targets
        val leaves = clickables.filter { n -> n.children.none { it.clickable } }
        for (i in leaves.indices) for (j in i + 1 until leaves.size) {
            val a = leaves[i]; val b = leaves[j]
            if (a.isAncestorOf(b) || b.isAncestorOf(a)) continue
            if (a.w >= sw * 0.6 && b.w >= sw * 0.6) continue
            if (a.parent === b.parent && isSegmentedBar(a.parent, sw)) continue
            val vOverlap = min(a.y2, b.y2) - max(a.y1, b.y1) > 0
            val hOverlap = min(a.x2, b.x2) - max(a.x1, b.x1) > 0
            val gap = when {
                vOverlap && !hOverlap -> max(b.x1 - a.x2, a.x1 - b.x2)
                hOverlap && !vOverlap -> max(b.y1 - a.y2, a.y1 - b.y2)
                else -> -1.0
            }
            if (gap >= 0 && gap < minTouchGapDp - tol)
                add("touch-target-gap", Severity.WARNING, a,
                    "Only ${fmt(gap)}dp between two tappable elements (${a.label()} and ${b.label()}); minimum is ${fmt(minTouchGapDp)}dp.",
                    "Add Spacer(Modifier.width(8.dp)) or Arrangement.spacedBy(8.dp) between them.",
                    "Targets that touch cause mis-taps.")
        }

        // 3. Screen edge margins — every piece of content that sits near a screen edge must sit exactly at the
        //    design-system margin (screenMarginDp). Accepted: the margin itself, 24dp (large-screen margin) and
        //    2× the margin (content inside a card that is itself at the margin). Everything else is a warning.
        val content = visible.filter { it.hasContent && it.w < sw * 0.98 }
        // Outermost content only: nodes whose parent spans the full width. Text inside a card is measured
        // against the card by the container-padding rule, not against the screen edge.
        val edgeCandidates = visible.filter { n ->
            n.w < sw * 0.98 && (n.parent == null || n.parent.w >= sw * 0.98) && (n.hasContent || n.subtreeHasText())
        }
        val accepted = listOf(screenMarginDp, 24.0, screenMarginDp * 2)
        val mTol = max(1.0, 2.0 / scale)                                   // 1dp slack (margins differ by ≥4dp), ≥2px at low densities
        fun isIconButton(n: Node) = n.clickable && n.w <= 56 && n.h <= 56   // 48dp touch box around a 24dp icon: sits at margin-12
        for (side in listOf("left", "right")) {
            val counts = HashMap<Long, Int>()
            val offenders = mutableListOf<Pair<Node, Double>>()
            for (n in edgeCandidates) {
                if (isSegmentedBar(n.parent, sw) || isSegmentedBar(n, sw)) continue
                if (isIconButton(n)) continue
                val m = if (side == "left") n.x1 else sw - n.x2
                if (m < 0 || m >= edgeZoneDp) continue
                counts.merge(m.roundToInt().toLong(), 1, Int::plus)
                if (m + mTol < 8)
                    add("edge-margin", Severity.WARNING, n, "Content is only ${fmt(m)}dp from the $side screen edge; the screen margin is ${fmt(screenMarginDp)}dp.",
                        "Apply Modifier.padding(horizontal = ${fmt(screenMarginDp)}.dp) once on the screen's root content container.", "Content hugging the edge looks cramped and is hard to read.")
                else if (accepted.none { near(m, it, mTol) })
                    offenders += n to m
            }
            // Per-element warnings (capped), then one screen-level summary so the pattern is obvious.
            offenders.take(12).forEach { (n, m) ->
                add("edge-margin", Severity.WARNING, n, "Content is ${fmt(m)}dp from the $side screen edge; the screen margin is ${fmt(screenMarginDp)}dp.",
                    "Remove per-element padding and let the screen root carry one Modifier.padding(horizontal = ${fmt(screenMarginDp)}.dp).",
                    "Content that is not on the screen margin breaks alignment with every other screen.")
            }
            if (offenders.size > 12)
                add("edge-margin", Severity.WARNING, null, "…and ${offenders.size - 12} more elements off the $side margin on this screen.", "", "")
            val dominant = counts.maxByOrNull { it.value }
            if (dominant != null && dominant.value >= 2 && accepted.none { near(dominant.key.toDouble(), it, 1.0) })
                add("edge-margin", Severity.WARNING, null,
                    "Most content on this screen (${dominant.value} elements) sits ${dominant.key}dp from the $side edge instead of ${fmt(screenMarginDp)}dp.",
                    "Change the root horizontal padding of this screen to ${fmt(screenMarginDp)}.dp (or update the margin in the plugin settings if your design system uses ${dominant.key}dp).",
                    "The whole screen is shifted relative to the rest of the app.")
            val sig = counts.filterValues { it >= 2 }.keys.sorted()
            if (sig.size >= 3)
                add("edge-margin-inconsistent", Severity.WARNING, null, "${side.replaceFirstChar { it.uppercase() }} edge margin varies across the screen: ${sig}dp.",
                    "Pick one screen margin (${fmt(screenMarginDp)}dp) and apply it once at the root; nested elements should not add their own side padding.", "Mixed margins make sections look misaligned.")
        }

        // 4. Sibling gaps on the grid, misalignment, overlap
        var offGrid = 0
        for (p in visible) {
            val kids = p.children.filter { it.visible && (it.hasContent || it.clickable || it.children.isNotEmpty()) }
            if (kids.size < 2) continue
            val v = kids.sortedBy { it.y1 }
            for (i in 0 until v.size - 1) {
                val a = v[i]; val b = v[i + 1]
                if (b.y1 >= a.y2 - tol && min(a.x2, b.x2) - max(a.x1, b.x1) > 0) {
                    val g = b.y1 - a.y2
                    if (g > tol) { result.gapHistogram.merge(g.roundToInt(), 1, Int::plus); if (!onGrid(g) && offGrid++ < 40) offGrid(a, b, g, "Vertical") }
                    if (a.hasContent && b.hasContent) {
                        val dx = abs(a.x1 - b.x1)
                        if (dx > tol && dx <= 8)
                            add("misaligned", Severity.WARNING, b, "${b.label()} starts ${fmt(dx)}dp off from ${a.label()} above it.",
                                "Align both to the same start edge: let the parent Column carry one horizontal padding instead of per-item padding.", "Edges that almost line up read as sloppy.")
                    }
                }
            }
            val hz = kids.sortedBy { it.x1 }
            for (i in 0 until hz.size - 1) {
                val a = hz[i]; val b = hz[i + 1]
                if (b.x1 >= a.x2 - tol && min(a.y2, b.y2) - max(a.y1, b.y1) > 0) {
                    val g = b.x1 - a.x2
                    if (g > tol) { result.gapHistogram.merge(g.roundToInt(), 1, Int::plus); if (!onGrid(g) && offGrid++ < 40) offGrid(a, b, g, "Horizontal") }
                }
            }
            for (i in kids.indices) for (j in i + 1 until kids.size) {
                val a = kids[i]; val b = kids[j]
                if (!a.hasContent || !b.hasContent) continue
                val ox = min(a.x2, b.x2) - max(a.x1, b.x1); val oy = min(a.y2, b.y2) - max(a.y1, b.y1)
                if (ox <= 2 || oy <= 2) continue
                // One element fully inside the other is nesting (Compose flattens a labelled Icon inside a clickable
                // card into a sibling node), not a collision. Flag only the duplicate label it usually comes with.
                val smallArea = min(a.w * a.h, b.w * b.h)
                if (ox * oy >= smallArea * 0.9) {
                    val outer = if (a.w * a.h >= b.w * b.h) a else b
                    val inner = if (outer === a) b else a
                    val la = (outer.desc.ifEmpty { outer.text }); val lb = (inner.desc.ifEmpty { inner.text })
                    if (la.isNotEmpty() && la == lb)
                        add("a11y-duplicate-label", Severity.WARNING, inner,
                            "${inner.label()} inside ${outer.label()} repeats the same label \"$la\".",
                            "Keep the label on the clickable container and set the Icon/Image contentDescription = null (decorative).",
                            "TalkBack reads the same text twice for one card.")
                    continue
                }
                add("overlap", Severity.WARNING, a, "${a.label()} overlaps ${b.label()} by ${fmt(ox)}×${fmt(oy)}dp.",
                    "Check for a Box where a Column/Row was intended, or text growing past its container (maxLines/overflow).", "Overlapping content is unreadable.")
            }
        }

        // 4b. Inner padding of cards / boxes (container → its content)
        // A "padded container" is a non-full-screen node whose content sits inset from at least two of its edges.
        // We measure the start-side inset (min of left/right, so RTL works) and the top/bottom inset.
        val paddedInsets = mutableListOf<Pair<Node, Double>>()
        for (p in visible) {
            if (p.w >= sw * 0.98 && p.h >= sh * 0.9) continue             // whole screen, handled by edge-margin
            if (p.w < 48 || p.h < 32) continue                             // too small to be a card
            val kids = p.children.filter { it.visible && (it.hasContent || it.children.isNotEmpty()) }
            if (kids.isEmpty()) continue
            val bx1 = kids.minOf { it.x1 }; val by1 = kids.minOf { it.y1 }
            val bx2 = kids.maxOf { it.x2 }; val by2 = kids.maxOf { it.y2 }
            val left = bx1 - p.x1; val right = p.x2 - bx2; val top = by1 - p.y1; val bottom = p.y2 - by2
            val insets = listOf(left, right, top, bottom)
            if (insets.count { it > tol } < 2) continue                    // plain layout container, not a padded card
            if (insets.any { it < -tol }) continue                         // children overflow: reported by overflow rules
            val hPad = min(left, right); val vPad = min(top, bottom)
            for ((name, v) in listOf("horizontal" to hPad, "vertical" to vPad)) {
                if (v <= tol) continue
                if (v + tol < minInnerPaddingDp)
                    add("container-padding", Severity.WARNING, p,
                        "${p.label()} has only ${fmt(v)}dp $name inner padding around its content; cards and boxes need ≥${fmt(minInnerPaddingDp)}dp (Material cards use 16dp).",
                        "Give the Card/Box content one Modifier.padding(16.dp) (or contentPadding) instead of relying on child margins.",
                        "Content hugging the card edge looks cramped and breaks the visual grouping.")
                else if (!onGrid(v))
                    add("container-padding", Severity.WARNING, p,
                        "${p.label()} has ${fmt(v)}dp $name inner padding — not on the ${fmt(gridDp)}dp grid.",
                        "Use 8, 12, 16 or 24dp inside containers; 16dp is the Material card default.", "")
            }
            if (hPad > tol) paddedInsets += p to hPad
        }
        // Cards of the same width should share the same inner padding
        paddedInsets.groupBy { (p, _) -> (p.w / 8).roundToInt() }.values.filter { it.size >= 2 }.forEach { group ->
            val values = group.map { (_, v) -> v.roundToInt() }.distinct().sorted()
            if (values.size >= 2 && values.last() - values.first() > 2) {
                val sample = group.first().first
                add("container-padding-inconsistent", Severity.WARNING, sample,
                    "${group.size} containers of the same width use different inner padding: ${values}dp.",
                    "Define one card padding (e.g. 16dp) in a shared composable or Dimens object and reuse it.",
                    "Uneven padding across cards makes a list look uneven even when each card is fine alone.")
            }
        }

        // 5. Unlabeled interactive elements
        for (n in clickables) if (!n.subtreeHasText())
            add("a11y-unlabeled", Severity.ERROR, n, "Tappable element ${n.label()} has no text or contentDescription.",
                "Give the Icon/Image a meaningful contentDescription, or if decorative set it to null and move the click to a labelled parent.", "TalkBack reads it as just 'Button'.")

        // 6. Bottom navigation count
        for (p in visible) {
            if (p.y1 < sh * 0.8 || p.h > 120) continue
            val kids = p.children.filter { it.visible && it.clickable }
            if (kids.size >= 2 && p.w >= sw * 0.9 && isSegmentedBar(p, sw)) {
                if (kids.size < 3 || kids.size > 5)
                    add("bottom-nav-count", Severity.WARNING, p, "Bottom navigation has ${kids.size} destinations; Material recommends 3–5.",
                        "Keep 3–5 top-level destinations; move extras under 'More', a drawer or tabs. For 2 items prefer tabs or a segmented button.", "Cramped bars lose labels and tap accuracy.")
                break
            }
        }

        // 7. Horizontal overflow
        for (n in content) if (n.x1 < -tol || n.x2 > sw + tol)
            add("overflow-horizontal", Severity.WARNING, n, "${n.label()} extends past the screen horizontally (${fmt(n.x1)}..${fmt(n.x2)}dp of ${fmt(sw)}dp).",
                "Use fillMaxWidth()/weight(1f) in Rows and let text wrap or ellipsize instead of fixed widths.", "Content is cut off on this screen size.")
    }

    private fun offGrid(a: Node, b: Node, g: Double, dir: String) =
        add("spacing-off-grid", Severity.WARNING, a, "$dir gap of ${fmt(g)}dp between ${a.label()} and ${b.label()} is not on the ${fmt(gridDp)}dp grid.",
            "Use 4, 8, 12, 16, 24 or 32dp; prefer Arrangement.spacedBy(8.dp) over manual Spacers.", "The 8dp grid keeps rhythm consistent across screens.")

    private companion object {
        val BOUNDS = Regex("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")
    }
}
