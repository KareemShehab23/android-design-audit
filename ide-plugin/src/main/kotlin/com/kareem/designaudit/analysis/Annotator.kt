package com.kareem.designaudit.analysis

import com.kareem.designaudit.model.Finding
import com.kareem.designaudit.model.Severity
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt

/** Draws numbered boxes for hierarchy findings onto the layout-bounds screenshot. */
object Annotator {
    fun colorOf(sev: Severity): Color = when (sev) {
        Severity.ERROR -> Color(220, 38, 38)
        Severity.WARNING -> Color(234, 138, 0)
    }

    /** [numbers] gives the label for each finding (so numbers match the table); null → 1-based index. */
    fun annotate(screenshot: Path, out: Path, findings: List<Finding>, scale: Double, numbers: List<Int>? = null) {
        val img = ImageIO.read(screenshot.toFile()) ?: throw IOException("Cannot decode $screenshot")
        val canvas = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        val g = canvas.createGraphics()
        g.drawImage(img, 0, 0, null)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.stroke = BasicStroke(max(2, (1.5 * scale).roundToInt()).toFloat())
        g.font = Font(Font.SANS_SERIF, Font.BOLD, max(14, (9 * scale).roundToInt()))
        val fm = g.fontMetrics
        val offsets = HashMap<String, Int>()
        findings.forEachIndexed { i, f ->
            val b = f.boundsPx ?: return@forEachIndexed
            val c = colorOf(f.severity)
            g.color = c
            g.drawRect(b[0], b[1], max(1, b[2] - b[0]), max(1, b[3] - b[1]))
            val tag = "#" + (numbers?.getOrNull(i) ?: (i + 1))
            val tw = fm.stringWidth(tag) + 8
            val th = fm.height + 4
            val key = "${b[0]},${b[1]}"
            val ox = offsets[key] ?: 0
            val ty = max(0, b[1] - th)
            g.fillRect(b[0] + ox, ty, tw, th)
            g.color = Color.WHITE
            g.drawString(tag, b[0] + ox + 4, ty + fm.ascent + 2)
            offsets[key] = ox + tw + 2
        }
        g.dispose()
        ImageIO.write(canvas, "png", out.toFile())
    }
}
