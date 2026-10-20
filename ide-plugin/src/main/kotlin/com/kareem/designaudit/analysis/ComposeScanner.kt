package com.kareem.designaudit.analysis

import com.kareem.designaudit.model.Finding
import com.kareem.designaudit.model.Severity
import com.kareem.designaudit.model.Source
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.asSequence

/**
 * Static design audit of a Jetpack Compose project: per-file regex rules plus project-level checks
 * (edge-to-edge, dark theme, RTL support, predictive back, adaptive icon, ...).
 * Heuristics are conservative; every finding points at file:line so a reviewer can confirm it.
 */
class ComposeScanner(private val maxPerRule: Int = 8, private val reportOnly: Set<Path>? = null) {

    class Result {
        val findings = mutableListOf<Finding>()
        var ktFiles = 0
        val truncated = linkedMapOf<String, Int>()
    }

    private val result = Result()
    private val perFileRule = HashMap<String, Int>()
    private val signals = HashSet<String>()
    private val perf = PerfScanner { sev, rule, file, line, msg, fix, why, snippet -> add(sev, rule, file, line, msg, fix, why, snippet) }
    private val design = DesignSystemScanner(::add, ::proj)

    fun scan(root: Path): Result {
        val kt = mutableListOf<Path>()
        var manifest: Path? = null
        val resDirs = mutableListOf<Path>()
        Files.walk(root).use { stream ->
            stream.asSequence().filter { !skip(root, it) }.forEach { p ->
                val name = p.name
                val s = p.toString()
                when {
                    name.endsWith(".kt") && p.isRegularFile() -> kt.add(p)
                    name == "AndroidManifest.xml" && "src" in s && "androidTest" !in s && !s.contains("${File.separator}test${File.separator}") -> if (manifest == null) manifest = p
                    name == "res" && p.isDirectory() && "src" in s -> resDirs.add(p)
                }
            }
        }
        result.ktFiles = kt.size
        if (kt.isEmpty()) throw IOException("No .kt files found under $root")
        design.discover(kt)
        kt.forEach { scanFile(it) }
        projectRules(manifest, resDirs)
        design.projectRules()
        HygieneScanner(::add, ::proj).scan(kt, listOfNotNull(manifest), resDirs) {
            buildString {
                kt.forEach { append(runCatching { it.readText() }.getOrDefault("")).append('\n') }
                manifest?.let { append(it.readText()).append('\n') }
                for (rd in resDirs) Files.walk(rd).use { w -> w.filter { it.isRegularFile() && it.name.endsWith(".xml") }.forEach { append(runCatching { it.readText() }.getOrDefault("")).append('\n') } }
            }
        }
        perFileRule.filterValues { it > maxPerRule }.forEach { (k, v) -> result.truncated[k] = v - maxPerRule }
        return result
    }

    /**
     * Live mode for editors: per-file rules on unsaved [text] of [file]. Spacing/shape token discovery runs on
     * the project once and is cached for a minute; project-level and cross-file rules (unused code,
     * resources) are skipped because they need the whole tree.
     */
    fun scanLive(root: Path, file: Path, text: String): List<Finding> {
        if (SourceFilters.isTestPath(root.relativize(file.toAbsolutePath().normalize()))) return emptyList()
        design.discover(tokenFiles(root))
        scanFile(file, text)
        HygieneScanner(::add, ::proj).unusedImportsOnly(file, text)
        return result.findings
    }

    private fun tokenFiles(root: Path): List<Path> {
        val now = System.currentTimeMillis()
        val cached = tokenCache[root]
        if (cached != null && now - cached.first < 60_000) return cached.second
        val kt = mutableListOf<Path>()
        runCatching {
            Files.walk(root).use { st -> st.asSequence().filter { !skip(root, it) && it.name.endsWith(".kt") && it.isRegularFile() }
                .filter { val n = it.name; THEME_FILE.containsMatchIn(n) || Regex("(?i)(dimens?|spacing|tokens?|shapes?)\\.kt$").containsMatchIn(n) || runCatching { it.readText() }.getOrDefault("").contains(Regex("\\b(?:object|class)\\s+\\w*(?:Spacing|Dimens|Tokens|Sizes)\\w*")) }
                .forEach { kt.add(it) } }
        }
        tokenCache[root] = now to kt
        return kt
    }

    private fun skip(root: Path, p: Path): Boolean {
        val rel = root.relativize(p)
        if (rel.any { part -> val n = part.toString(); n in SKIP_DIRS || (n.startsWith(".") && n != ".") }) return true
        return SourceFilters.isTestPath(rel)
    }


    private fun add(sev: Severity, rule: String, file: Path, line: Int, msg: String, fix: String, why: String, snippet: String?) {
        if (reportOnly != null && file.toAbsolutePath().normalize() !in reportOnly) return
        val key = "$rule in ${file.fileName}"
        val c = perFileRule.merge(key, 1, Int::plus)!!
        if (c > maxPerRule) return
        result.findings += Finding(Source.CODE, sev, rule, msg, fix, why, file = file, line = line, snippet = snippet?.trim().orEmpty())
    }

    private fun proj(sev: Severity, rule: String, msg: String, fix: String, why: String) {
        result.findings += Finding(Source.PROJECT, sev, rule, msg, fix, why)
    }

    private fun scanFile(file: Path, text: String = file.readText()) {
        val lines = text.split("\n")
        val name = file.name
        val isTheme = THEME_FILE.containsMatchIn(name)
        val isPreviewFile = PREVIEW_FILE.containsMatchIn(name)
        for ((k, p) in SIGNALS) if (p.containsMatchIn(text)) signals += k
        perf.scanFile(file, lines, isTheme, isPreviewFile)
        design.scanFile(file, lines, isPreviewFile)

        val navItems = NAV_ITEM.findAll(text).count()
        if (navItems > 5 && NAV_BAR.containsMatchIn(text))
            add(Severity.WARNING, "bottom-nav-count", file, 1, "$navItems NavigationBarItem calls in one file (Material recommends 3–5).",
                "Keep 3–5 destinations; move the rest under 'More', tabs or a drawer.", "Cramped bars lose labels and tap accuracy.", null)
        M2_IMPORT.find(text)?.let { m ->
            if (M3_IMPORT.containsMatchIn(text)) {
                val ln = text.substring(0, m.range.first).count { it == '\n' } + 1
                add(Severity.WARNING, "material2-import", file, ln, "File mixes androidx.compose.material (M2) and material3 imports.",
                    "Replace M2 components with their material3 equivalents.", "Mixing M2/M3 yields inconsistent shapes, colors and typography.", lines[ln - 1])
            }
        }

        var inPreview = 0
        for ((i, line) in lines.withIndex()) {
            val s = line.trim(); val ln = i + 1
            if (s.startsWith("//") || s.startsWith("*") || s.startsWith("/*")) continue
            if ("@Preview" in s) inPreview = 60 else if (inPreview > 0) inPreview--

            for (m in DP.findAll(line)) {
                val v = m.groupValues[1].toDouble()
                if (v >= 2 && v % 4 > 0.01 && 4 - v % 4 > 0.01 && !STROKE_CTX.containsMatchIn(line))
                    add(Severity.WARNING, "dp-off-grid", file, ln, "${m.groupValues[1]}dp is not on the 4dp grid.",
                        "Snap to 4/8/12/16/24/32dp; use Arrangement.spacedBy() and a Dimens object.", "The 8dp grid keeps rhythm consistent across screens.", line)
            }
            if (FONT_DP.containsMatchIn(line))
                add(Severity.ERROR, "font-size-dp", file, ln, "fontSize uses dp.", "Use .sp so text follows the user's font-size setting.", "dp text ignores the accessibility font-size setting.", line)
            for (m in SP.findAll(line)) if (m.groupValues[1].toDouble() < 12)
                add(Severity.WARNING, "font-size-small", file, ln, "${m.groupValues[1]}sp text is below the 12sp minimum.", "Use ≥12sp (labelSmall 11sp is the only exception); body text 14sp+.", "Small text is unreadable on most devices.", line)
            if (!isTheme && FONT_SP.containsMatchIn(line))
                add(Severity.WARNING, "font-size-hardcoded", file, ln, "Hardcoded fontSize outside the theme.", "Use style = MaterialTheme.typography.bodyMedium (etc.).", "Bypassing the type scale breaks text hierarchy.", line)
            if (!isTheme && COLOR_HEX.containsMatchIn(line))
                add(Severity.WARNING, "hardcoded-color", file, ln, "Hardcoded hex color in a composable.", "Move it to Color.kt and use a MaterialTheme.colorScheme role.", "Literal colors break dark mode and dynamic color.", line)
            if (RTL_ABS.containsMatchIn(line))
                add(Severity.WARNING, "rtl-unsafe", file, ln, "Left/Right or Absolute layout API that does not mirror in RTL.", "Use start/end, TextAlign.Start/End, Alignment.CenterStart/End.", "Does not mirror for Arabic/Hebrew.", line)
            if (RTL_ICON.containsMatchIn(line))
                add(Severity.WARNING, "rtl-icon", file, ln, "Directional icon without AutoMirrored variant.", "Use Icons.AutoMirrored.Filled.ArrowBack (etc.).", "Arrows must point the right way in RTL.", line)
            if (CLICKABLE.containsMatchIn(line)) {
                val window = statementWindow(lines, i)
                SIZE_SMALL.findAll(window).firstOrNull { it.groupValues[1].toDouble() < 48 }?.let { m ->
                    add(Severity.ERROR, "touch-target-small", file, ln, "Clickable element sized ${m.groupValues[1]}dp (< 48dp).", "Keep the icon 24dp but wrap in IconButton or add Modifier.minimumInteractiveComponentSize().", "Targets under 48dp are hard to hit.", line)
                }
                if (CD_NULL.containsMatchIn(window) && ICON_OR_IMAGE.containsMatchIn(window))
                    add(Severity.WARNING, "a11y-icon-unlabeled", file, ln, "Interactive Icon/Image near contentDescription = null.", "Give it contentDescription = stringResource(R.string.cd_xxx) or move the click to a labelled parent.", "Invisible to TalkBack users.", line)
            }
            // Every Text() must carry a typography style, otherwise it renders in the platform default font
            // (Roboto) instead of the app's font. Text placed in a Material slot (Button content, label = {},
            // title = {}, ListItem headlineContent …) inherits the slot's style and is skipped.
            if (inPreview == 0 && !isPreviewFile && !isTheme) {
                val tm = TEXT_CALL.find(line)
                if (tm != null && !inheritsSlotStyle(lines, i, tm.range.first)) {
                    val call = callText(lines, i, tm.range.first)
                    if (!TEXT_STYLED.containsMatchIn(call))
                        add(Severity.WARNING, "text-no-style", file, ln, "Text() without a typography style — it will use the default font, not yours.",
                            "Add style = MaterialTheme.typography.bodyMedium (or the matching scale step). Never set fontFamily per Text; put it in Type.kt.",
                            "Unstyled text ignores the app's FontFamily and type scale, so fonts and sizes drift between screens.", line)
                }
                if (FONT_FAMILY_INLINE.containsMatchIn(line))
                    add(Severity.WARNING, "font-family-inline", file, ln, "fontFamily set directly on a Text.",
                        "Define the FontFamily once in Type.kt's Typography and use style = MaterialTheme.typography.*.", "Inline fonts bypass the type scale and are easy to forget on other screens.", line)
            }
            if (inPreview == 0 && !isPreviewFile) for (m in TEXT_STR.findAll(line)) {
                val v = m.groupValues[1]
                if (v.startsWith("$") || v.matches(Regex("[\\W\\d_]+"))) continue
                add(Severity.WARNING, "hardcoded-string", file, ln, "Hardcoded UI string \"${v.take(40)}\".", "Use stringResource(R.string.xxx).", "Cannot be translated or pluralized.", line)
            }
            for (m in FIXED_WIDE.findAll(line))
                add(Severity.WARNING, "fixed-width", file, ln, "Fixed width of ${m.groupValues[1]}dp.", "Use fillMaxWidth() with padding, weight(1f), or widthIn(max = 600.dp).", "Overflows small phones, looks lost on tablets.", line)
            if (TOAST.containsMatchIn(line))
                add(Severity.WARNING, "toast-usage", file, ln, "Toast used for feedback.", "Use SnackbarHostState.showSnackbar inside Scaffold.", "Snackbar is themed, dismissible and can carry an action.", line)
            if (MAXLINES.containsMatchIn(line)) {
                val window = lines.subList(maxOf(0, i - 3), minOf(lines.size, i + 4)).joinToString("\n")
                if (!OVERFLOW.containsMatchIn(window))
                    add(Severity.WARNING, "text-overflow", file, ln, "maxLines set without overflow.", "Add overflow = TextOverflow.Ellipsis.", "Clips words mid-glyph.", line)
            }
        }
    }

    /** The statement around line [i]: neighbouring lines that continue the same call / modifier chain. */
    private fun statementWindow(lines: List<String>, i: Int): String {
        fun continues(prev: String, next: String): Boolean {
            val p = prev.trim(); val n = next.trim()
            return p.endsWith(",") || p.endsWith("(") || p.endsWith(".") || p.endsWith("{") && !p.startsWith("}") || n.startsWith(".") || n.startsWith(")") || n.startsWith("}")
        }
        var start = i; while (start > 0 && start > i - 8 && continues(lines[start - 1], lines[start])) start--
        var end = i; while (end < lines.size - 1 && end < i + 8 && continues(lines[end], lines[end + 1])) end++
        return lines.subList(start, end + 1).joinToString("\n")
    }

    /** Text of the Text(...) call starting at [col] on line [i], up to its closing paren (max 12 lines). */
    private fun callText(lines: List<String>, i: Int, col: Int): String {
        val sb = StringBuilder()
        var depth = 0
        var started = false
        for (li in i until minOf(lines.size, i + 12)) {
            val text = if (li == i) lines[li].substring(col) else lines[li]
            for (ch in text) {
                sb.append(ch)
                if (ch == '(') { depth++; started = true } else if (ch == ')') { depth--; if (started && depth == 0) return sb.toString() }
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /** True when the Text sits in a Material slot that already provides a text style. */
    private fun inheritsSlotStyle(lines: List<String>, i: Int, col: Int): Boolean {
        val before = lines[i].substring(0, col)
        if (SLOT_SAME_LINE.containsMatchIn(before)) return true
        // Look at the previous few non-blank lines for an open slot lambda or a styled container.
        var seen = 0
        var li = i - 1
        while (li >= 0 && seen < 3) {
            val t = lines[li].trim()
            if (t.isNotEmpty()) {
                if (SLOT_OPENER.containsMatchIn(t)) return true
                if (t.endsWith("}") || t.endsWith(")")) return false
                seen++
            }
            li--
        }
        return false
    }

    private fun projectRules(manifest: Path?, resDirs: List<Path>) {
        val mf = manifest?.readText().orEmpty()
        val locales = sortedSetOf<String>()
        var adaptiveIcon = false
        for (rd in resDirs) Files.list(rd).use { s ->
            s.forEach { d ->
                val n = d.name
                if (n.startsWith("values") && d.resolve("strings.xml").exists()) locales += n
                if (n.startsWith("mipmap-anydpi") && d.isDirectory())
                    Files.list(d).use { f -> if (f.anyMatch { it.name.startsWith("ic_launcher") && it.name.endsWith(".xml") }) adaptiveIcon = true }
            }
        }
        if ("edgeToEdge" !in signals)
            proj(Severity.WARNING, "edge-to-edge", "No enableEdgeToEdge() call found in any Activity.", "Call enableEdgeToEdge() before setContent and handle insets with Scaffold innerPadding / safeDrawingPadding().", "Android 15 enforces edge-to-edge; apps that don't opt in look dated.")
        else if ("insets" !in signals)
            proj(Severity.WARNING, "window-insets", "Edge-to-edge is enabled but no WindowInsets / Scaffold handling was found.", "Use Scaffold's innerPadding or Modifier.windowInsetsPadding(WindowInsets.safeDrawing).", "Content slides under the system bars.")
        if ("darkScheme" !in signals)
            proj(Severity.WARNING, "dark-theme", "No darkColorScheme() definition found.", "Define darkColorScheme() in Theme.kt and switch with isSystemInDarkTheme().", "Ignores the user's dark-mode setting.")
        else if ("isDark" !in signals)
            proj(Severity.WARNING, "dark-theme", "darkColorScheme exists but isSystemInDarkTheme() is never read.", "Pass darkTheme = isSystemInDarkTheme() into your AppTheme.", "Dark theme is defined but never used.")
        if ("dynamic" !in signals)
            proj(Severity.WARNING, "dynamic-color", "No dynamicDark/LightColorScheme — Material You dynamic color not supported.", "On SDK ≥ 31 use dynamicDarkColorScheme(context) / dynamicLightColorScheme(context).", "Optional, but makes the app feel native on Android 12+.")
        if ("typographyDef" !in signals || "typographyUse" !in signals)
            proj(Severity.WARNING, "typography-scale", "Typography scale not defined or MaterialTheme.typography.* never used.", "Define Typography() in Type.kt (Arabic-capable font) and use style = MaterialTheme.typography.* everywhere.", "Keeps text hierarchy consistent.")
        if ("fontFamily" !in signals)
            proj(Severity.WARNING, "font-family-missing", "No FontFamily defined anywhere — all text falls back to the platform default (Roboto).",
                "Add your font files under res/font (or use Google Fonts provider), build a FontFamily in Type.kt and pass it to every TextStyle in Typography().",
                "Without an explicit font the app looks generic and Arabic text may render in a mismatched fallback font.")
        if ("adaptive" !in signals)
            proj(Severity.WARNING, "adaptive-layout", "No window-size-class / adaptive layout handling found.", "Use currentWindowAdaptiveInfo() to switch NavigationBar→NavigationRail and single→two-pane on Medium/Expanded widths.", "Tablets and foldables get a stretched phone layout.")
        if (manifest != null) {
            if ("android:supportsRtl=\"true\"" !in mf)
                proj(Severity.ERROR, "supports-rtl", "AndroidManifest.xml lacks android:supportsRtl=\"true\".", "Add android:supportsRtl=\"true\" to <application>.", "Without it nothing mirrors for Arabic.")
            if ("android:enableOnBackInvokedCallback=\"true\"" !in mf)
                proj(Severity.WARNING, "predictive-back", "Predictive back not enabled in the manifest.", "Add android:enableOnBackInvokedCallback=\"true\" to <application> and use BackHandler, not overridden onBackPressed().", "Opts into the Android 13+/14 predictive back animation.")
            if ("splash" !in signals && "Theme.SplashScreen" !in mf)
                proj(Severity.WARNING, "splash-screen", "SplashScreen API not used.", "Add core-splashscreen, set Theme.SplashScreen as launch theme and call installSplashScreen().", "Gives the standard launch animation.")
        }
        if (resDirs.isNotEmpty() && !adaptiveIcon)
            proj(Severity.WARNING, "adaptive-icon", "No adaptive launcher icon (mipmap-anydpi-v26/ic_launcher.xml).", "Generate foreground + background (+ monochrome) layers via Image Asset Studio.", "Icon won't adapt to the device's shape or theme.")
        if ("progress" !in signals)
            proj(Severity.WARNING, "loading-state", "No progress indicator / pull-to-refresh found.", "Add a loading state (CircularProgressIndicator or skeleton) to screens that fetch data.", "Screens need a loading state.")
        if ("retry" !in signals)
            proj(Severity.WARNING, "empty-error-state", "No retry / empty-state wording found.", "Give list screens an empty state with an action and an error state with Retry.", "Screens need empty and error states.")
        if (locales.size == 1)
            proj(Severity.WARNING, "localization", "Only one strings locale found (${locales.first()}).", "Add values-ar/strings.xml (or values-en) and test both directions.", "Ship the languages your users speak.")
    }

    private companion object {
        val tokenCache = java.util.concurrent.ConcurrentHashMap<Path, Pair<Long, List<Path>>>()
        val SKIP_DIRS = setOf("build", ".gradle", ".idea", "node_modules", ".git", "generated")
        val THEME_FILE = Regex("(?i)(color|colour|theme|palette|type|typography|dimens?|tokens?)\\.kt$")
        val PREVIEW_FILE = Regex("(?i)preview")
        val DP = Regex("(?<![\\w.])(\\d+(?:\\.\\d+)?)\\.dp\\b")
        val SP = Regex("(?<![\\w.])(\\d+(?:\\.\\d+)?)\\.sp\\b")
        val FONT_DP = Regex("fontSize\\s*=\\s*\\d+(?:\\.\\d+)?\\.dp\\b")
        val FONT_SP = Regex("fontSize\\s*=\\s*\\d+(?:\\.\\d+)?\\.sp\\b")
        val COLOR_HEX = Regex("\\bColor\\(\\s*0x[0-9A-Fa-f]{6,8}\\s*\\)")
        val TEXT_STR = Regex("(?:\\bText\\(\\s*(?:text\\s*=\\s*)?|\\btext\\s*=\\s*|\\blabel\\s*=\\s*\\{?\\s*Text\\(\\s*|\\bplaceholder\\s*=\\s*\\{?\\s*Text\\(\\s*|\\btitle\\s*=\\s*\\{?\\s*Text\\(\\s*|\\bcontentDescription\\s*=\\s*)\"([^\"\\n]{2,})\"")
        val RTL_ABS = Regex("\\babsolutePadding\\(|PaddingValues\\.Absolute|Arrangement\\.Absolute|AbsoluteAlignment|TextAlign\\.(?:Left|Right)\\b|Alignment\\.(?:Top|Center|Bottom)(?:Left|Right)\\b|LayoutDirection\\.Ltr\\b|\\bAbsoluteCutCornerShape\\(|\\bAbsoluteRoundedCornerShape\\(")
        val RTL_ICON = Regex("Icons\\.(?:Default|Filled|Outlined|Rounded|Sharp|TwoTone)\\.(?:ArrowBack|ArrowForward|ArrowBackIos|ArrowForwardIos|KeyboardArrowLeft|KeyboardArrowRight|ArrowLeft|ArrowRight|NavigateBefore|NavigateNext|ChevronLeft|ChevronRight|Send|Reply|ReplyAll|Undo|Redo|Help|HelpOutline|List|ListAlt|Label|Logout|Login|ExitToApp|OpenInNew|FormatListBulleted|Sort|TrendingUp|TrendingDown|CallMade|CallReceived|Shortcut|Launch|Message|Comment|Chat|ShowChart)\\b")
        val M2_IMPORT = Regex("(?m)^\\s*import\\s+androidx\\.compose\\.material\\.(?!icons)")
        val M3_IMPORT = Regex("(?m)^\\s*import\\s+androidx\\.compose\\.material3\\.")
        val CLICKABLE = Regex("\\.(?:clickable|combinedClickable|toggleable|selectable)\\s*[({]|\\bonClick\\s*=")
        val SIZE_SMALL = Regex("\\.(?:size|requiredSize|width|height|requiredWidth|requiredHeight)\\(\\s*(\\d+(?:\\.\\d+)?)\\.dp\\s*\\)")
        val CD_NULL = Regex("contentDescription\\s*=\\s*null")
        val ICON_OR_IMAGE = Regex("\\b(Icon|Image)\\(")
        val FIXED_WIDE = Regex("\\.(?:width|requiredWidth)\\(\\s*(\\d{3,})\\.dp\\s*\\)")
        val NAV_ITEM = Regex("\\bNavigationBarItem\\(")
        val NAV_BAR = Regex("\\bNavigationBar\\s*[({]")
        val TOAST = Regex("\\bToast\\.makeText\\(")
        val MAXLINES = Regex("\\bmaxLines\\s*=")
        val OVERFLOW = Regex("\\boverflow\\s*=")
        val TEXT_CALL = Regex("(?<![\\w.])Text\\s*\\(")
        val TEXT_STYLED = Regex("\\bstyle\\s*=|\\bfontFamily\\s*=|MaterialTheme\\.typography|LocalTextStyle")
        val FONT_FAMILY_INLINE = Regex("(?<![\\w.])Text\\s*\\([^\\n]*\\bfontFamily\\s*=|^\\s*fontFamily\\s*=\\s*(?!null)")
        val FONT_FAMILY_DEF = Regex("\\bFontFamily\\s*\\(|\\bfontFamily\\s*=\\s*(?!null)|GoogleFont\\(|Font\\(R\\.font")
        // Text inside a slot that supplies its own style: label = { Text(..) }, Button { Text(..) }, ListItem(headlineContent = { Text(..) }) …
        val SLOT_SAME_LINE = Regex("\\b(label|title|text|placeholder|supportingText|headlineContent|overlineContent|trailingContent|leadingContent|confirmButton|dismissButton|action|prefix|suffix|navigationIcon|actions|content)\\s*=\\s*\\{\\s*$|\\b(Button|TextButton|OutlinedButton|FilledTonalButton|ElevatedButton|FilledButton|AssistChip|FilterChip|SuggestionChip|InputChip|Tab|Badge|ExtendedFloatingActionButton|DropdownMenuItem|Snackbar|ProvideTextStyle)\\b.*\\{\\s*$")
        val SLOT_OPENER = Regex("\\b(label|title|text|placeholder|supportingText|headlineContent|overlineContent|trailingContent|leadingContent|confirmButton|dismissButton|action|prefix|suffix|navigationIcon|actions|content)\\s*=\\s*\\{\\s*$|\\b(Button|TextButton|OutlinedButton|FilledTonalButton|ElevatedButton|FilledButton|AssistChip|FilterChip|SuggestionChip|InputChip|Tab|Badge|ExtendedFloatingActionButton|DropdownMenuItem|Snackbar|ProvideTextStyle)\\b.*\\{\\s*$")
        val STROKE_CTX = Regex("(?i)border|stroke|elevation|Shape\\(|thickness|Divider|blur|CornerSize|shadow|offset\\(|alpha")
        val SIGNALS = linkedMapOf(
            "edgeToEdge" to Regex("\\benableEdgeToEdge\\(|setDecorFitsSystemWindows\\("),
            "insets" to Regex("WindowInsets|safeDrawing|safeContent|systemBarsPadding|navigationBarsPadding|statusBarsPadding|imePadding|contentWindowInsets|\\bScaffold\\("),
            "darkScheme" to Regex("\\bdarkColorScheme\\("),
            "isDark" to Regex("\\bisSystemInDarkTheme\\("),
            "dynamic" to Regex("\\bdynamic(?:Dark|Light)ColorScheme\\("),
            "typographyDef" to Regex("\\bTypography\\("),
            "typographyUse" to Regex("MaterialTheme\\.typography\\."),
            "adaptive" to Regex("WindowSizeClass|calculateWindowSizeClass|currentWindowAdaptiveInfo|BoxWithConstraints|NavigationRail\\(|NavigationSuiteScaffold|ListDetailPaneScaffold"),
            "splash" to Regex("\\binstallSplashScreen\\("),
            "progress" to Regex("(?:Circular|Linear)(?:Wavy)?ProgressIndicator\\(|PullToRefresh|isRefreshing"),
            "fontFamily" to FONT_FAMILY_DEF,
            "retry" to Regex("(?i)retry|try again|إعادة المحاولة|حاول مرة|no results|لا توجد|empty"),
        )
    }
}

/** Which paths are audited. Test sources are never audited. */
object SourceFilters {
    private val TEST_DIRS = setOf("test", "androidTest", "testFixtures", "screenshotTest", "testDebug", "testRelease", "sharedTest")
    private val TEST_SOURCE_SETS = listOf("test", "androidTest", "screenshotTest", "testFixtures")

    /** src/test, src/androidTest, testFixtures, screenshotTest, *Test.kt, *Tests.kt, *Spec.kt */
    fun isTestPath(rel: Path): Boolean {
        val parts = rel.map { it.toString() }
        for ((i, part) in parts.withIndex()) {
            if (part in TEST_DIRS) return true
            if (part == "src" && i + 1 < parts.size && TEST_SOURCE_SETS.any { parts[i + 1].startsWith(it) }) return true
        }
        val name = parts.lastOrNull() ?: return false
        return name.endsWith("Test.kt") || name.endsWith("Tests.kt") || name.endsWith("Spec.kt")
    }
}
