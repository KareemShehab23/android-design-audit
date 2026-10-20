package com.kareem.designaudit.analysis

import com.kareem.designaudit.model.Finding
import com.kareem.designaudit.model.Severity
import com.kareem.designaudit.model.Source
import java.nio.file.Path

/**
 * Static Jetpack Compose performance checks — the patterns that cause unnecessary recomposition,
 * work on every frame, or jank in lists. Each finding names the API that fixes it.
 *
 * Runs on the same files as [ComposeScanner]; call [scanFile] per file with the file's lines.
 */
class PerfScanner(private val add: (Severity, String, Path, Int, String, String, String, String?) -> Unit) {

    fun scanFile(file: Path, lines: List<String>, isTheme: Boolean, isPreviewFile: Boolean) {
        if (isTheme || isPreviewFile) return
        val text = lines.joinToString("\n")

        // Names of state / animation values declared in this file — reading them inside a non-lambda
        // modifier recomposes the whole composable on every frame instead of just re-laying-out/drawing.
        val stateNames = HashSet<String>()
        for (m in STATE_DECL.findAll(text)) stateNames += m.groupValues[1]

        // Track whether we are inside a @Composable function body (brace depth from its opening line).
        var composableDepth = -1      // -1 = not inside
        var pendingComposable = false
        var depth = 0
        var inPreview = 0

        for ((i, raw) in lines.withIndex()) {
            val line = stripStrings(raw)
            val s = line.trim(); val ln = i + 1
            if (s.startsWith("//") || s.startsWith("*") || s.startsWith("/*")) continue
            if ("@Preview" in s) inPreview = 60 else if (inPreview > 0) inPreview--

            if (ANNOT_COMPOSABLE.containsMatchIn(s)) pendingComposable = true
            if (pendingComposable && FUN_DECL.containsMatchIn(s)) { composableDepth = depth; pendingComposable = false }
            val opens = s.count { it == '{' }; val closes = s.count { it == '}' }
            val inComposable = composableDepth >= 0

            if (inComposable && inPreview == 0) {
                val prev = lines.getOrNull(i - 1)?.trim().orEmpty()
                val prev2 = lines.getOrNull(i - 2)?.trim().orEmpty()
                val remembered = "remember" in line || prev.endsWith("remember {") || prev.endsWith("rememberSaveable {") ||
                        prev.endsWith("{") && ("remember" in prev || "remember" in prev2)

                // 1. State created without remember → reset on every recomposition
                if (MUTABLE_STATE.containsMatchIn(line) && !remembered)
                    add(Severity.ERROR, "perf-state-no-remember", file, ln, "mutableStateOf/derivedStateOf created without remember.",
                        "Wrap it: val x by remember { mutableStateOf(...) } (or rememberSaveable for state that must survive rotation).",
                        "A new state object is created on every recomposition, so the value resets and can loop recomposition.", raw)

                // 2. Lazy list items without a stable key
                LAZY_ITEMS_NO_KEY.find(line)?.let { m ->
                    val call = callText(lines, i, m.range.first)
                    if (!Regex("\\bkey\\s*=").containsMatchIn(call))
                        add(Severity.WARNING, "perf-lazy-no-key", file, ln, "items(...) in a lazy list without key = { }.",
                            "items(list, key = { it.id }) { … } — the key must be unique and stable.",
                            "Without keys, inserting/removing one item recomposes and re-animates every item below it, and scroll position jumps.", raw)
                }

                // 3. Scrollable Column with a loop inside → should be LazyColumn
                if (VERTICAL_SCROLL.containsMatchIn(line)) {
                    val window = blockText(lines, i)
                    if (FOREACH_OR_FOR.containsMatchIn(window) && !window.contains("LazyColumn"))
                        add(Severity.WARNING, "perf-column-foreach", file, ln, "Column(verticalScroll) renders a list with forEach/for.",
                            "Use LazyColumn { items(list, key = …) { … } } so only visible rows are composed.",
                            "A plain Column composes and measures every row, even the ones off-screen — slow first frame and high memory on long lists.", raw)
                    if (window.contains("LazyColumn") || window.contains("LazyVerticalGrid"))
                        add(Severity.ERROR, "perf-lazy-in-scroll", file, ln, "LazyColumn/LazyVerticalGrid inside a verticalScroll Column.",
                            "Make the outer container the LazyColumn and put the other content in item { } blocks, or give the inner list a fixed height.",
                            "Nested scrollables with unbounded height crash (IllegalStateException) or measure every item at once.", raw)
                }

                // 4. State / animation read inside a non-lambda modifier → recomposes instead of re-laying-out
                for (m in MODIFIER_WITH_ARGS.findAll(line)) {
                    val args = m.groupValues[2]
                    val hit = stateNames.firstOrNull { n -> Regex("\\b${Regex.escape(n)}\\b").containsMatchIn(args) } ?: (if (".value" in args) ".value" else null)
                    if (hit != null) {
                        val mod = m.groupValues[1]
                        val fix = when (mod) {
                            "offset" -> "Modifier.offset { IntOffset(x.roundToPx(), y.roundToPx()) }"
                            "alpha", "rotate", "scale" -> "Modifier.graphicsLayer { alpha = …; rotationZ = …; scaleX = … }"
                            "graphicsLayer" -> "the lambda overload: Modifier.graphicsLayer { … }"
                            "background" -> "Modifier.drawBehind { drawRect(color) }"
                            else -> "a lambda-based modifier (offset { }, graphicsLayer { }, drawBehind { }, layout { })"
                        }
                        add(Severity.WARNING, "perf-state-in-modifier", file, ln, "State/animation '$hit' read in Modifier.$mod(…) during composition.",
                            "Read it in the lambda version instead: $fix. The read then happens in the layout/draw phase.",
                            "Every animation frame recomposes this composable and its children instead of only re-laying-out or redrawing.", raw)
                    }
                }

                // 5. Expensive object allocation in composition
                if (HEAVY_ALLOC.containsMatchIn(line) && !remembered)
                    add(Severity.WARNING, "perf-alloc-in-composition", file, ln, "Expensive object created during composition.",
                        "Wrap it in remember { } (or hoist it to the ViewModel / a top-level val).",
                        "Re-created on every recomposition — formatters, regexes and JSON parsers are costly to build.", raw)

                // 6. Collection work in composition
                if (COLLECTION_WORK.containsMatchIn(line) && !remembered && !line.contains("derivedStateOf"))
                    add(Severity.WARNING, "perf-compute-in-composition", file, ln, "List transformation (sorted/filter/map/groupBy) runs on every recomposition.",
                        "Compute it in the ViewModel, or wrap in remember(input) { … } / derivedStateOf.",
                        "Recomputed each time anything in this composable changes, even when the list did not.", raw)

                // 7. Blocking work on the UI thread
                if (BLOCKING.containsMatchIn(line))
                    add(Severity.ERROR, "perf-blocking-ui", file, ln, "Blocking call (runBlocking / Thread.sleep / blocking I/O) in a composable.",
                        "Move it to a coroutine in the ViewModel (viewModelScope.launch on Dispatchers.IO) and expose a StateFlow.",
                        "Freezes the UI thread — dropped frames or ANR.", raw)

                // 8. Bitmap decoding in composition
                if (BITMAP_DECODE.containsMatchIn(line))
                    add(Severity.WARNING, "perf-bitmap-decode", file, ln, "Bitmap decoded during composition.",
                        "Use Coil/Glide AsyncImage (with size), or painterResource for drawables; decode in the background and remember the result.",
                        "Decoding on the main thread stalls the frame and the bitmap is re-decoded on each recomposition.", raw)

                // 9. collectAsState vs lifecycle-aware
                if (COLLECT_AS_STATE.containsMatchIn(line))
                    add(Severity.WARNING, "perf-collect-lifecycle", file, ln, "collectAsState() keeps collecting while the app is in the background.",
                        "Use collectAsStateWithLifecycle() from lifecycle-runtime-compose.",
                        "Flows keep emitting and the UI keeps recomposing when the screen is not visible, wasting CPU and battery.", raw)

                // 10. LaunchedEffect keyed on a value that changes every composition
                if (LAUNCHED_EFFECT_LAMBDA_KEY.containsMatchIn(line))
                    add(Severity.WARNING, "perf-effect-unstable-key", file, ln, "LaunchedEffect keyed on a lambda or a freshly created object.",
                        "Key on a stable value (id, state value) or Unit; pass lambdas through rememberUpdatedState.",
                        "The effect is cancelled and restarted on every recomposition.", raw)

                // 11. GlobalScope
                if (GLOBAL_SCOPE.containsMatchIn(line))
                    add(Severity.WARNING, "perf-global-scope", file, ln, "GlobalScope used from UI code.",
                        "Use rememberCoroutineScope() in composables or viewModelScope in the ViewModel.",
                        "Coroutines outlive the screen and keep running (and leaking) after the user leaves.", raw)
            }

            // Unstable collection parameters on composable functions (checked on the declaration line)
            if (inPreview == 0 && FUN_DECL.containsMatchIn(s) && UNSTABLE_PARAM.containsMatchIn(s) && (pendingComposable || composableDepth == depth)) {
                add(Severity.WARNING, "perf-unstable-param", file, ln, "Composable takes a List/Set/Map parameter.",
                    "Use kotlinx.collections.immutable (ImmutableList) or wrap the list in an @Immutable data class; make sure strong skipping is enabled (Kotlin ≥ 2.0.20).",
                    "The compiler cannot prove the collection unchanged, so the composable recomposes whenever its parent does.", raw)
            }

            // 12. Modifier.composed (deprecated for performance)
            if (COMPOSED.containsMatchIn(line))
                add(Severity.WARNING, "perf-modifier-composed", file, ln, "Modifier.composed { } used.",
                    "Implement custom modifiers with Modifier.Node (ModifierNodeElement) — see Compose 1.5+ docs.",
                    "composed modifiers re-run their composable lambda per use and cannot be skipped.", raw)

            depth += opens - closes
            if (composableDepth >= 0 && depth <= composableDepth && closes > 0) composableDepth = -1
        }
    }

    /** Text of the call starting at [col] on line [i] up to its matching ')' (max 12 lines). */
    private fun callText(lines: List<String>, i: Int, col: Int): String {
        val sb = StringBuilder(); var depth = 0; var started = false
        for (li in i until minOf(lines.size, i + 12)) {
            val t = if (li == i) lines[li].substring(col) else lines[li]
            for (ch in t) { sb.append(ch); if (ch == '(') { depth++; started = true } else if (ch == ')') { depth--; if (started && depth == 0) return sb.toString() } }
            sb.append('\n')
        }
        return sb.toString()
    }

    /** Text of the { … } block that opens on line [i] (or the next lines) up to its matching '}' (max 80 lines). */
    private fun blockText(lines: List<String>, i: Int): String {
        val sb = StringBuilder(); var depth = 0; var started = false
        for (li in i until minOf(lines.size, i + 80)) {
            val t = stripStrings(lines[li])
            for (ch in t) { sb.append(ch); if (ch == '{') { depth++; started = true } else if (ch == '}') { depth--; if (started && depth == 0) return sb.toString() } }
            sb.append('\n')
        }
        return sb.toString()
    }

    /** Remove string literal contents so braces/keywords inside strings don't confuse the heuristics. */
    private fun stripStrings(s: String): String = s.replace(STRING_LIT, "\"\"").substringBefore("//")

    private companion object {
        val STRING_LIT = Regex("\"(?:\\\\.|[^\"\\\\])*\"")
        val ANNOT_COMPOSABLE = Regex("@Composable\\b")
        val FUN_DECL = Regex("\\bfun\\s+[A-Za-z_][\\w<>.,? ]*\\s*\\(")
        val MUTABLE_STATE = Regex("\\b(?:mutableStateOf|mutableIntStateOf|mutableFloatStateOf|mutableLongStateOf|mutableDoubleStateOf|mutableStateListOf|mutableStateMapOf|derivedStateOf)\\s*[({]")
        val LAZY_ITEMS_NO_KEY = Regex("\\b(?:items|itemsIndexed)\\s*\\(\\s*(?!count\\s*=)[A-Za-z_][\\w.]*\\s*(?:,|\\))")
        val VERTICAL_SCROLL = Regex("\\.verticalScroll\\s*\\(")
        val FOREACH_OR_FOR = Regex("\\.forEach(?:Indexed)?\\s*\\{|\\bfor\\s*\\(")
        val MODIFIER_WITH_ARGS = Regex("\\.(offset|alpha|rotate|scale|graphicsLayer|background|padding|size|height|width)\\s*\\(([^)]*)\\)")
        val STATE_DECL = Regex("\\b(?:val|var)\\s+(\\w+)\\s*(?::[^=]+)?\\s*(?:by|=)\\s*(?:remember\\s*\\{\\s*)?(?:mutableStateOf|mutableIntStateOf|mutableFloatStateOf|animate\\w*AsState|rememberInfiniteTransition|Animatable|rememberScrollState|rememberLazyListState|derivedStateOf|transition\\.animate\\w*)\\b")
        val HEAVY_ALLOC = Regex("\\b(?:Regex|SimpleDateFormat|DecimalFormat|Gson|GsonBuilder|ObjectMapper|Paint|TextPaint|Typeface\\.create|NumberFormat\\.get\\w+|DateTimeFormatter\\.ofPattern|Json\\s*\\{)\\s*\\(?")
        val COLLECTION_WORK = Regex("\\.(?:sortedBy|sortedByDescending|sorted|sortedWith|filter|groupBy|associateBy|distinctBy|flatMap)\\s*[({]")
        val BLOCKING = Regex("\\brunBlocking\\s*[({]|\\bThread\\.sleep\\s*\\(|\\.readText\\s*\\(\\)|\\.readBytes\\s*\\(\\)|\\bFile\\([^)]*\\)\\.(?:readLines|exists)")
        val BITMAP_DECODE = Regex("\\bBitmapFactory\\.decode\\w*\\s*\\(|\\bImageBitmap\\.imageResource\\s*\\(|\\bImageDecoder\\.decode")
        val COLLECT_AS_STATE = Regex("\\.collectAsState\\s*\\(")
        val LAUNCHED_EFFECT_LAMBDA_KEY = Regex("\\bLaunchedEffect\\s*\\(\\s*(?:\\{|listOf\\(|[A-Z]\\w*\\()")
        val GLOBAL_SCOPE = Regex("\\bGlobalScope\\.")
        val UNSTABLE_PARAM = Regex(":\\s*(?:Mutable)?(?:List|Set|Map|Collection|ArrayList|HashMap)\\s*<")
        val COMPOSED = Regex("\\bModifier\\.composed\\s*\\{|\\.composed\\s*\\{")
    }
}
