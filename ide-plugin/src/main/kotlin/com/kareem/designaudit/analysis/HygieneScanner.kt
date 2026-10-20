package com.kareem.designaudit.analysis

import com.kareem.designaudit.model.Finding
import com.kareem.designaudit.model.Severity
import com.kareem.designaudit.model.Source
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Code & resource hygiene: unused imports, unused declarations, unused resources, duplicated strings,
 * duplicated images, oversized assets. Everything here is a WARNING. Heuristic by design — findings
 * name the symbol and location so a reviewer confirms in seconds.
 *
 * Inputs: all audited Kotlin files (tests already excluded), the manifest(s) and res/ folders.
 */
class HygieneScanner(private val add: (Severity, String, Path, Int, String, String, String, String?) -> Unit,
                     private val proj: (Severity, String, String, String, String) -> Unit) {

    fun scan(ktFiles: List<Path>, manifests: List<Path>, resDirs: List<Path>, allSourceText: () -> String) {
        val texts = ktFiles.associateWith { runCatching { it.readText() }.getOrDefault("") }
        val corpus = allSourceText()                         // every audited .kt + manifests + xml, joined
        val wordCounts = countWords(corpus)

        for ((file, text) in texts) {
            unusedImports(file, text)
            unusedDeclarations(file, text, wordCounts)
        }
        resources(resDirs, corpus)
    }

    /** Editor/live mode: only the per-file rule. */
    fun unusedImportsOnly(file: Path, text: String) = unusedImports(file, text)

    // ---- 1. Unused imports --------------------------------------------------------------------
    private fun unusedImports(file: Path, text: String) {
        val lines = text.split("\n")
        val body = lines.filterNot { it.trim().startsWith("import ") }.joinToString("\n")
        for ((i, line) in lines.withIndex()) {
            val m = IMPORT.find(line.trim()) ?: continue
            val fq = m.groupValues[1]; val alias = m.groupValues[2]
            if (fq.endsWith(".*")) continue
            val simple = alias.ifEmpty { fq.substringAfterLast('.') }
            if (simple in ALWAYS_USED) continue
            if (!Regex("(?<![\\w.])${Regex.escape(simple)}\\b").containsMatchIn(body) &&
                !Regex("\\.${Regex.escape(simple)}\\b").containsMatchIn(body))     // extension / member usage (x.dp, list.toImmutableList())
                add(Severity.WARNING, "unused-import", file, i + 1, "Unused import $fq.",
                    "Remove it (⌥⏎ / Optimize Imports), and enable 'Optimize imports on the fly' in the IDE.",
                    "Dead imports hide real dependencies and create merge noise.", line)
        }
    }

    // ---- 2. Unused declarations ---------------------------------------------------------------
    private fun unusedDeclarations(file: Path, text: String, wordCounts: Map<String, Int>) {
        val lines = text.split("\n")
        for ((i, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.startsWith("//") || line.startsWith("*")) continue
            val m = DECL.find(line) ?: continue
            val modifiers = m.groupValues[1]; val kind = m.groupValues[2]; val name = m.groupValues[3]
            if (name.isEmpty() || name in ENTRY_POINTS) continue
            if ("override" in modifiers || "expect" in modifiers || "actual" in modifiers || "operator" in modifiers || "external" in modifiers) continue
            // only private members and top-level / internal declarations: public API of a library can be used elsewhere
            val isPrivate = "private" in modifiers
            val topLevel = !raw.startsWith(" ") && !raw.startsWith("\t")
            if (!isPrivate && !topLevel) continue
            if (!isPrivate && kind in setOf("val", "var")) continue          // top-level public constants are often used via reflection/DI
            // framework entry points: annotations on this or the previous lines
            val above = (maxOf(0, i - 3) until i).joinToString(" ") { lines[it] } + " " + line.substringBefore(kind)
            if (FRAMEWORK_ANNOT.containsMatchIn(above)) continue
            val count = if (isPrivate) countWord(text, name) else (wordCounts[name] ?: 0)
            if (count <= 1)
                add(Severity.WARNING, "unused-code", file, i + 1,
                    "${if (isPrivate) "Private" else "Top-level"} $kind '$name' is never used${if (isPrivate) " in this file" else " anywhere in the project"}.",
                    "Delete it, or wire it in if it was meant to be used. (Entry points used only by reflection/DI can be marked with @Keep.)",
                    "Dead code costs build time, APK size and reviewer attention, and drifts out of date silently.", raw)
        }
    }

    // ---- 3. Resources: duplicates, unused, oversized -----------------------------------------
    private fun resources(resDirs: List<Path>, corpus: String) {
        val stringDecls = mutableMapOf<String, MutableList<Pair<Path, Int>>>()   // name -> declarations
        val drawables = mutableMapOf<String, MutableList<Path>>()               // name -> files (all densities)
        for (rd in resDirs) {
            Files.list(rd).use { dirs ->
                for (d in dirs) {
                    if (!d.isDirectory()) continue
                    val dn = d.name
                    when {
                        dn.startsWith("values") -> Files.list(d).use { fs -> fs.filter { it.name.endsWith(".xml") }.forEach { f -> stringsFile(f, stringDecls) } }
                        dn.startsWith("drawable") || dn.startsWith("mipmap") -> Files.list(d).use { fs ->
                            fs.filter { it.isRegularFile() }.forEach { f -> drawables.getOrPut(f.name.substringBeforeLast('.')) { mutableListOf() }.add(f); imageSize(f) }
                        }
                    }
                }
            }
        }
        // duplicate images: identical bytes (or identical normalized vector XML) under different names
        val byHash = mutableMapOf<String, MutableList<Path>>()
        for ((_, files) in drawables) for (f in files) byHash.getOrPut(hashOf(f)) { mutableListOf() }.add(f)
        for ((_, files) in byHash) {
            val names = files.map { it.name.substringBeforeLast('.') }.distinct()
            if (names.size >= 2)
                proj(Severity.WARNING, "duplicate-image", "Identical image stored under ${names.size} names: ${names.joinToString(", ")} (${files.first().parent.name}).",
                    "Keep one file and reference it everywhere; delete the others.",
                    "Duplicate assets inflate the APK and get edited inconsistently.")
        }
        // unused drawables
        for ((name, files) in drawables) {
            if (name.startsWith("ic_launcher") || name == "splash" || name.startsWith("splash_")) continue
            if (!Regex("\\bR\\.(?:drawable|mipmap)\\.${Regex.escape(name)}\\b|@(?:drawable|mipmap)/${Regex.escape(name)}\\b").containsMatchIn(corpus))
                proj(Severity.WARNING, "unused-resource", "Drawable '$name' (${files.size} file${if (files.size > 1) "s" else ""}) is never referenced.",
                    "Delete it, or use it. Run 'Refactor → Remove Unused Resources' to clean up in bulk.",
                    "Unused images ship in the APK and slow resource compilation.")
        }
        // strings: unused + duplicates per locale
        for ((name, _) in stringDecls) {
            if (!Regex("\\bR\\.string\\.${Regex.escape(name)}\\b|@string/${Regex.escape(name)}\\b").containsMatchIn(corpus) && name != "app_name")
                proj(Severity.WARNING, "unused-resource", "String '$name' is never referenced.", "Delete it (Refactor → Remove Unused Resources).", "Unused strings are translated and shipped for nothing.")
        }
        for ((key, names) in stringValuesByLocale) {
            val distinct = names.distinct()
            if (distinct.size >= 2)
                proj(Severity.WARNING, "duplicate-string", "Same text \"${key.second.take(40)}\" under ${distinct.size} keys in ${key.first}: ${distinct.joinToString(", ")}.",
                    "Keep one key (name it by meaning, e.g. action_save) and replace the others.",
                    "Duplicate strings get translated twice and drift apart.")
        }
    }

    private val stringValuesByLocale = mutableMapOf<Pair<String, String>, MutableList<String>>()

    private fun stringsFile(f: Path, decls: MutableMap<String, MutableList<Pair<Path, Int>>>) {
        val text = runCatching { f.readText() }.getOrNull() ?: return
        val locale = f.parent.name
        val lines = text.split("\n")
        for ((i, line) in lines.withIndex()) for (m in STRING_RES.findAll(line)) {
            val name = m.groupValues[1]; val value = m.groupValues[2].trim()
            decls.getOrPut(name) { mutableListOf() }.add(f to i + 1)
            if (value.length >= 2) stringValuesByLocale.getOrPut(locale to value) { mutableListOf() }.add(name)
        }
    }

    private fun imageSize(f: Path) {
        val size = runCatching { Files.size(f) }.getOrDefault(0L)
        val ext = f.extension.lowercase()
        if (ext in setOf("png", "jpg", "jpeg") && size > 300 * 1024)
            proj(Severity.WARNING, "large-image", "${f.parent.name}/${f.name} is ${size / 1024}KB.",
                "Convert to WebP (right-click → Convert to WebP) and export at the display size; use a vector if it is an icon.",
                "Large bitmaps bloat the APK and stall decoding on first draw.")
        if (ext == "xml" && size > 0) {
            val text = runCatching { f.readText() }.getOrDefault("")
            val w = Regex("android:width=\"(\\d+)dp\"").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (text.contains("<vector") && (w > 200 || size > 50 * 1024))
                proj(Severity.WARNING, "large-vector", "Vector ${f.name} is ${w}dp wide / ${size / 1024}KB.",
                    "Vectors above ~200dp or complex paths rasterize slowly; export large illustrations as WebP instead.",
                    "Complex vectors are re-rasterized at every size and cause jank.")
        }
    }

    private fun hashOf(f: Path): String {
        val bytes = runCatching { Files.readAllBytes(f) }.getOrDefault(ByteArray(0))
        val data = if (f.extension.equals("xml", true)) String(bytes).replace(Regex("\\s+"), "").replace(Regex("android:name=\"[^\"]*\""), "").toByteArray() else bytes
        return MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val IMPORT = Regex("^import\\s+([\\w.]+(?:\\.\\*)?)(?:\\s+as\\s+(\\w+))?\\s*$")
        val ALWAYS_USED = setOf("Composable", "Preview", "Inject", "HiltViewModel", "AndroidEntryPoint", "Keep", "Suppress", "OptIn")
        // modifiers, kind, name
        val DECL = Regex("^((?:(?:private|internal|public|protected|override|open|abstract|inline|suspend|operator|infix|expect|actual|external|data|sealed|enum|annotation|value|const|lateinit|tailrec)\\s+)*)(fun|class|object|interface|val|var|typealias)\\s+(?:<[^>]+>\\s+)?(?:[\\w.]+\\.)?(\\w+)")
        val ENTRY_POINTS = setOf("main", "onCreate", "onStart", "onResume", "onPause", "onStop", "onDestroy", "onNewIntent", "onBind",
            "onReceive", "onCreateView", "onViewCreated", "attachBaseContext", "Companion", "invoke", "equals", "hashCode", "toString", "copy", "component1")
        val FRAMEWORK_ANNOT = Regex("@(?:Preview|PreviewLightDark|PreviewScreenSizes|PreviewFontScale|PreviewDynamicColors|Test|Provides|Binds|Module|HiltViewModel|HiltAndroidApp|AndroidEntryPoint|Keep|JvmStatic|BindingAdapter|Entity|Dao|Database|Serializable|Parcelize|JsonClass|Query|Insert|Update|Delete|Subscribe|OnLifecycleEvent|Composable\\s+(?:private\\s+)?fun\\s+\\w*Preview|GET|POST|PUT|PATCH|DELETE|Multipart|Headers|BeforeEach|AfterEach|Before|After)\\b")
        val STRING_RES = Regex("<string\\s+name=\"(\\w+)\"[^>]*>(.*?)</string>")

        fun countWords(text: String): Map<String, Int> {
            val m = HashMap<String, Int>()
            for (w in Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(text)) m.merge(w.value, 1, Int::plus)
            return m
        }
        fun countWord(text: String, w: String) = Regex("\\b${Regex.escape(w)}\\b").findAll(text).count()
    }
}
