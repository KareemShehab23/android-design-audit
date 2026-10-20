package com.kareem.designaudit.analysis

import com.kareem.designaudit.model.Severity
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Design-system discipline: are tokens (spacing, shape, elevation, text styles, colors) used instead of
 * literals, are Material 3 components used the Material way, and is nothing deprecated or half-finished
 * left in UI code. Complements ComposeScanner (which covers grid, typography scale, RTL, theme, states).
 */
class DesignSystemScanner(private val add: (Severity, String, Path, Int, String, String, String, String?) -> Unit,
                          private val proj: (Severity, String, String, String, String) -> Unit) {

    /** Names of files that define tokens (so literals inside them are allowed). */
    private val tokenFiles = HashSet<Path>()
    private var hasSpacingTokens = false
    private var hasShapeTokens = false

    /** First pass over all files: discover token definitions. */
    fun discover(ktFiles: List<Path>) {
        for (f in ktFiles) {
            val text = runCatching { f.readText() }.getOrDefault("")
            val dpConsts = DP_CONST.findAll(text).count()
            val isTheme = THEME_FILE.containsMatchIn(f.name)
            val tokenObject = dpConsts >= 3 && Regex("\\b(?:object|class)\\s+\\w*(?:Spacing|Dimens|Dimen|Tokens|Sizes|Space|Gap)s?\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
            if (tokenObject || isTheme) tokenFiles.add(f)
            if (tokenObject) hasSpacingTokens = true
            if (SHAPES_DEF.containsMatchIn(text)) { hasShapeTokens = true; tokenFiles.add(f) }
        }
    }

    fun projectRules() {
        if (!hasSpacingTokens)
            proj(Severity.WARNING, "no-spacing-tokens", "No spacing tokens found (an object with Dp constants such as Spacing.sm = 8.dp).",
                "Create ui/theme/Dimens.kt: object Spacing { val xs = 4.dp; val sm = 8.dp; val md = 16.dp; val lg = 24.dp; val xl = 32.dp } and use it for every padding/gap.",
                "Without tokens every screen picks its own numbers and the 8dp rhythm drifts.")
        if (!hasShapeTokens)
            proj(Severity.WARNING, "no-shape-tokens", "No Shapes() definition found — corner radii are hardcoded per component.",
                "Define Shapes(small = RoundedCornerShape(8.dp), medium = 12.dp, large = 16.dp) in Theme.kt and use MaterialTheme.shapes.*.",
                "Inconsistent corner radii are one of the most visible design-system breaks.")
    }

    fun scanFile(file: Path, lines: List<String>, isPreviewFile: Boolean) {
        if (isPreviewFile) return
        val isToken = file in tokenFiles
        var inPreview = 0
        for ((i, raw) in lines.withIndex()) {
            val line = raw.substringBefore("//")
            val s = line.trim(); val ln = i + 1
            if (TODO.containsMatchIn(raw))
                add(Severity.WARNING, "todo-left", file, ln, "TODO/FIXME left in UI code.",
                    "Resolve it or move it to a ticket before merging.", "Unfinished UI reaches users.", raw)
            if (s.startsWith("*") || s.startsWith("/*") || s.isEmpty()) continue
            if ("@Preview" in s) inPreview = 60 else if (inPreview > 0) inPreview--
            if (inPreview > 0) continue

            if (!isToken) {
                // 1. Spacing literals where tokens exist
                if (hasSpacingTokens && SPACING_LITERAL.containsMatchIn(line) && !STROKE_CTX.containsMatchIn(line))
                    add(Severity.WARNING, "spacing-literal", file, ln, "Spacing literal instead of a token.",
                        "Use the project's spacing tokens (e.g. Spacing.md) in padding / spacedBy / Spacer.",
                        "Literals bypass the design system; changing the scale later means touching every screen.", raw)
                // 2. Hardcoded shapes
                if (SHAPE_LITERAL.containsMatchIn(line))
                    add(Severity.WARNING, "hardcoded-shape", file, ln, "RoundedCornerShape with a literal radius in a composable.",
                        "Use MaterialTheme.shapes.small/medium/large (define them in Theme.kt) or a shape token.",
                        "Corner radii must come from one place to stay consistent.", raw)
                // 3. Hardcoded elevation outside the tonal scale
                ELEVATION_LITERAL.find(line)?.let { m ->
                    val v = m.groupValues[1].toDouble()
                    if (v !in TONAL_LEVELS)
                        add(Severity.WARNING, "hardcoded-elevation", file, ln, "Elevation ${m.groupValues[1]}dp is not a Material 3 tonal level.",
                            "Use CardDefaults.cardElevation()/elevatedCardElevation() or one of 0, 1, 3, 6, 8, 12dp.",
                            "Material 3 elevation is a 6-step scale; odd values look wrong next to standard components.", raw)
                }
                // 4. TextStyle built inline
                if (TEXTSTYLE_INLINE.containsMatchIn(line))
                    add(Severity.WARNING, "hardcoded-textstyle", file, ln, "TextStyle(…) constructed inside UI code.",
                        "Add the style to Typography in Type.kt and reference MaterialTheme.typography.*, or derive with .copy() from an existing step.",
                        "Ad-hoc text styles break the type scale.", raw)
                // 5. Named color literals
                if (COLOR_NAMED.containsMatchIn(line))
                    add(Severity.WARNING, "hardcoded-color", file, ln, "Named Color literal (Color.White/Black/Gray…) in a composable.",
                        "Use MaterialTheme.colorScheme roles (surface, onSurface, outline…); Color.Transparent is the only acceptable literal.",
                        "Literal colors ignore dark mode and dynamic color.", raw)
            }

            // 6. Icon sizes off the Material set
            if (ICON_CTX.containsMatchIn(line)) {
                ICON_SIZE.find(line)?.let { m ->
                    val v = m.groupValues[1].toDouble()
                    if (v !in ICON_SIZES)
                        add(Severity.WARNING, "icon-size", file, ln, "Icon sized ${m.groupValues[1]}dp.",
                            "Material icons are 24dp (18/20dp in dense components, 40/48dp for large). Pick one of those.",
                            "Odd icon sizes look blurry and misalign with text.", raw)
                }
            }
            // 7. Button height override
            if (BUTTON_HEIGHT.containsMatchIn(line))
                add(Severity.WARNING, "button-height", file, ln, "Button height set manually.",
                    "Remove the height modifier; Material buttons are 40dp (FAB 56dp, extended FAB 56dp). Use contentPadding for touch size.",
                    "Custom heights break the component's internal padding and ripple.", raw)
            // 8. Card with clickable modifier instead of onClick
            if (CARD_CLICKABLE.containsMatchIn(line))
                add(Severity.WARNING, "card-clickable", file, ln, "Card with Modifier.clickable instead of Card(onClick = …).",
                    "Use Card(onClick = { … }) — it clips the ripple to the shape and sets the Button role for TalkBack.",
                    "Modifier.clickable on a Card gives a square ripple and no semantics role.", raw)
            // 9. Custom Dialog instead of AlertDialog
            if (CUSTOM_DIALOG.containsMatchIn(line))
                add(Severity.WARNING, "custom-dialog", file, ln, "Raw Dialog(…) used.",
                    "Use AlertDialog (title/text/confirmButton) or BasicAlertDialog with Surface for custom content.",
                    "Raw Dialog has no Material surface, shape, tonal elevation or button layout.", raw)
            // 10. Deprecated / replaced APIs
            DEPRECATED.find(line)?.let { m ->
                val api = m.value
                add(Severity.WARNING, "deprecated-api", file, ln, "Deprecated API: $api.",
                    REPLACEMENTS.entries.firstOrNull { api.contains(it.key) }?.value ?: "Replace with the Material 3 / AndroidX equivalent.",
                    "Deprecated APIs stop receiving fixes and break on new Android versions.", raw)
            }
            // 11. Empty click handlers
            if (EMPTY_CLICK.containsMatchIn(line))
                add(Severity.WARNING, "empty-click", file, ln, "Empty onClick / clickable handler.",
                    "Wire the action, or remove the clickable state (and the ripple) until it exists.",
                    "A button that does nothing is a shipped bug.", raw)
            // 13. Strings via resources.getString instead of stringResource
            if (GET_STRING.containsMatchIn(line))
                add(Severity.WARNING, "string-via-context", file, ln, "resources.getString()/context.getString() in a composable.",
                    "Use stringResource(R.string.x) / pluralStringResource — they recompose on locale change.",
                    "getString does not update when the configuration changes.", raw)
        }
    }

    private companion object {
        val THEME_FILE = Regex("(?i)^(color|colour|theme|palette|type|typography|dimens?|spacing|tokens?|shapes?)\\.kt$")
        val DP_CONST = Regex("\\bval\\s+\\w+\\s*(?::\\s*Dp)?\\s*=\\s*\\d+(?:\\.\\d+)?\\.dp\\b")
        val SHAPES_DEF = Regex("\\bShapes\\s*\\(")
        val SPACING_LITERAL = Regex("\\b(?:padding|spacedBy|width|height|size|offset)\\s*\\([^)]*?(?<![\\w.])\\d+(?:\\.\\d+)?\\.dp\\b|\\bSpacer\\s*\\(\\s*(?:modifier\\s*=\\s*)?Modifier\\.(?:width|height|size)\\(\\s*\\d+")
        val STROKE_CTX = Regex("(?i)border|stroke|elevation|thickness|Divider|blur|CornerSize|shadow|alpha|Icon\\(|Image\\(")
        val SHAPE_LITERAL = Regex("\\bRoundedCornerShape\\s*\\(\\s*(?:\\d+(?:\\.\\d+)?\\.dp|\\d+(?:\\.\\d+)?\\s*\\))|\\bCutCornerShape\\s*\\(\\s*\\d")
        val ELEVATION_LITERAL = Regex("\\b(?:elevation|tonalElevation|shadowElevation|defaultElevation)\\s*=\\s*(\\d+(?:\\.\\d+)?)\\.dp\\b")
        val TONAL_LEVELS = setOf(0.0, 1.0, 3.0, 6.0, 8.0, 12.0)
        val TEXTSTYLE_INLINE = Regex("(?<![\\w.])TextStyle\\s*\\(")
        val COLOR_NAMED = Regex("\\bColor\\.(?:White|Black|Red|Green|Blue|Yellow|Cyan|Magenta|Gray|LightGray|DarkGray)\\b")
        val ICON_CTX = Regex("\\bIcon\\s*\\(|\\bIcon\\b.*Modifier|imageVector\\s*=|painter\\s*=\\s*painterResource")
        val ICON_SIZE = Regex("\\.size\\(\\s*(\\d+(?:\\.\\d+)?)\\.dp\\s*\\)")
        val ICON_SIZES = setOf(16.0, 18.0, 20.0, 24.0, 32.0, 36.0, 40.0, 48.0, 56.0, 64.0)
        val BUTTON_HEIGHT = Regex("\\b(?:Button|TextButton|OutlinedButton|FilledTonalButton|ElevatedButton)\\s*\\([^\\n]*\\.height\\(\\s*\\d+")
        val CARD_CLICKABLE = Regex("\\b(?:Card|ElevatedCard|OutlinedCard)\\s*\\([^\\n]*\\.clickable\\s*[({]")
        val CUSTOM_DIALOG = Regex("(?<![\\w.])Dialog\\s*\\(\\s*onDismissRequest")
        val DEPRECATED = Regex("\\brememberRipple\\b|(?<![\\w.])Divider\\s*\\(|\\bwindow\\.statusBarColor\\b|\\bwindow\\.navigationBarColor\\b|com\\.google\\.accompanist\\.(?:systemuicontroller|pager|swiperefresh|flowlayout|insets|placeholder|navigation-animation|navigation-material|web)|\\bLocalRippleTheme\\b|\\bSwipeToDismiss\\s*\\(|\\bModalBottomSheetLayout\\s*\\(|\\bBackdropScaffold\\s*\\(|\\bonBackPressed\\s*\\(")
        val REPLACEMENTS = mapOf(
            "rememberRipple" to "Use ripple() from material3 (or compose.material.ripple).",
            "Divider" to "Use HorizontalDivider / VerticalDivider.",
            "statusBarColor" to "Use enableEdgeToEdge() and draw behind the system bars instead.",
            "navigationBarColor" to "Use enableEdgeToEdge() and draw behind the system bars instead.",
            "accompanist" to "Accompanist libraries are deprecated: pager → foundation Pager, swiperefresh → PullToRefreshBox, insets → foundation WindowInsets, systemuicontroller → enableEdgeToEdge, flowlayout → FlowRow/FlowColumn.",
            "LocalRippleTheme" to "Use RippleConfiguration via LocalRippleConfiguration.",
            "SwipeToDismiss" to "Use SwipeToDismissBox.",
            "ModalBottomSheetLayout" to "Use material3 ModalBottomSheet.",
            "BackdropScaffold" to "Use a BottomSheetScaffold or a custom layout; Backdrop was removed from M3.",
            "ArrowBack" to "Use Icons.AutoMirrored.Filled.ArrowBack.",
            "KeyboardArrowLeft" to "Use Icons.AutoMirrored.Filled.KeyboardArrowLeft.",
            "onBackPressed" to "Use OnBackPressedDispatcher / BackHandler with predictive back enabled.",
        )
        val EMPTY_CLICK = Regex("\\b(?:onClick|onLongClick|onValueChange|onCheckedChange)\\s*=\\s*\\{\\s*\\}|\\.clickable\\s*\\{\\s*\\}")
        val TODO = Regex("//\\s*(?:TODO|FIXME|HACK|XXX)\\b|\\bTODO\\(")
        val GET_STRING = Regex("\\b(?:resources|context|LocalContext\\.current)\\.(?:resources\\.)?getString\\s*\\(")
    }
}
