package com.kareem.designaudit.adb

import com.kareem.designaudit.model.DeviceInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around the adb binary. Locates adb from the project's local.properties (sdk.dir),
 * ANDROID_HOME / ANDROID_SDK_ROOT, the usual SDK locations, or PATH.
 */
class Adb(val adb: Path, private val serial: String?) {

    companion object {
        private val isWindows = System.getProperty("os.name", "").lowercase().contains("win")

        fun locate(projectDir: Path?): Path? {
            val exe = if (isWindows) "adb.exe" else "adb"
            val candidates = mutableListOf<Path>()
            projectDir?.resolve("local.properties")?.takeIf { Files.exists(it) }?.let { lp ->
                runCatching {
                    val p = Properties().apply { Files.newInputStream(lp).use { load(it) } }
                    p.getProperty("sdk.dir")?.let { sdk ->
                        candidates.add(Paths.get(sdk.replace("\\:", ":").replace("\\\\", "\\"), "platform-tools", exe))
                    }
                }
            }
            for (env in listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
                System.getenv(env)?.takeIf { it.isNotBlank() }?.let { candidates.add(Paths.get(it, "platform-tools", exe)) }
            }
            val home = System.getProperty("user.home")
            candidates.add(Paths.get(home, "Library", "Android", "sdk", "platform-tools", exe))   // macOS
            candidates.add(Paths.get(home, "Android", "Sdk", "platform-tools", exe))                // Linux
            System.getenv("LOCALAPPDATA")?.let { candidates.add(Paths.get(it, "Android", "Sdk", "platform-tools", exe)) } // Windows
            System.getenv("PATH")?.split(File.pathSeparator)?.filter { it.isNotBlank() }?.forEach { candidates.add(Paths.get(it, exe)) }
            return candidates.firstOrNull { Files.isExecutable(it) }
        }

        /** Serials of devices in "device" state, plus a human label. */
        fun devices(adb: Path): List<Device> {
            val out = Adb(adb, null).run(30, "devices", "-l")
            return out.lines().drop(1).mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 2 || parts[1] != "device") return@mapNotNull null
                val model = parts.firstOrNull { it.startsWith("model:") }?.removePrefix("model:")?.replace('_', ' ')
                Device(parts[0], model)
            }
        }
    }

    data class Device(val serial: String, val model: String?) {
        override fun toString() = if (model != null) "$model ($serial)" else serial
    }

    fun run(timeoutSec: Long, vararg args: String): String = String(runBytes(timeoutSec, *args), Charsets.UTF_8)

    fun runBytes(timeoutSec: Long, vararg args: String): ByteArray {
        val cmd = mutableListOf(adb.toString())
        if (!serial.isNullOrEmpty()) { cmd += "-s"; cmd += serial }
        cmd += args
        val p = ProcessBuilder(cmd).start()
        val out = ByteArrayOutputStream()
        val err = Thread { runCatching { p.errorStream.readAllBytes() } }.apply { start() }
        p.inputStream.use { it.transferTo(out) }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); throw IOException("adb ${args.joinToString(" ")} timed out") }
        err.join(1000)
        return out.toByteArray()
    }

    fun shell(vararg args: String): String = run(30, "shell", *args).trim()

    /** Toggle the same property Developer options → "Show layout bounds" flips. */
    fun setLayoutBounds(on: Boolean) {
        shell("setprop", "debug.layout", if (on) "true" else "false")
        runCatching { shell("service", "call", "activity", "1599295570") }  // SYSPROPS_TRANSACTION: processes re-read debug.* props
        Thread.sleep(800)
    }

    fun screenshotPng(): ByteArray {
        val png = runBytes(60, "exec-out", "screencap", "-p")
        if (png.size < 100) throw IOException("Empty screenshot from device")
        return png
    }

    fun dumpHierarchy(): String {
        repeat(2) { attempt ->
            shell("uiautomator", "dump", "/sdcard/_audit_hierarchy.xml")
            val xml = run(60, "exec-out", "cat", "/sdcard/_audit_hierarchy.xml")
            runCatching { shell("rm", "-f", "/sdcard/_audit_hierarchy.xml") }
            val start = xml.indexOf("<?xml").takeIf { it >= 0 } ?: xml.indexOf("<hierarchy")
            if (start >= 0) return xml.substring(start)
            if (attempt == 0) Thread.sleep(1000)
        }
        throw IOException("uiautomator dump failed — close dialogs, wait for animations to finish, and retry")
    }

    fun deviceInfo(): DeviceInfo = DeviceInfo().also { d ->
        d.serial = serial ?: ""
        d.densityDpi = lastInt(shell("wm", "density"))
        Regex("(\\d+)x(\\d+)").findAll(shell("wm", "size")).lastOrNull()?.let { d.widthPx = it.groupValues[1].toInt(); d.heightPx = it.groupValues[2].toInt() }
        d.sdk = lastInt(shell("getprop", "ro.build.version.sdk"))
        d.androidVersion = shell("getprop", "ro.build.version.release")
        d.locale = shell("getprop", "persist.sys.locale").ifEmpty { shell("getprop", "ro.product.locale") }
        d.fontScale = shell("settings", "get", "system", "font_scale")
        runCatching { d.nightMode = shell("cmd", "uimode", "night").replace("Night mode: ", "night=") }
        runCatching {
            Regex("mCurrentFocus=Window\\{[^ ]+ [^ ]+ ([^/ ]+)/([^ }]+)}").find(shell("dumpsys", "window"))?.let {
                d.packageName = it.groupValues[1]; d.activity = it.groupValues[2]
            }
        }
    }

    private fun lastInt(s: String) = Regex("(\\d+)").findAll(s).lastOrNull()?.value?.toInt() ?: 0
}
