package com.kareem.designaudit.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.kareem.designaudit.analysis.Annotator
import com.kareem.designaudit.model.Finding
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JComponent
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shows a screenshot scaled to fit, spotlighting the selected finding's bounds
 * (everything else dimmed) so the problem is visible without opening the full image.
 */
class ScreenshotPreview(private val onDoubleClick: () -> Unit) : JComponent() {
    private var image: BufferedImage? = null
    private var imagePath: Path? = null
    private var highlight: Finding? = null
    var placeholder: String = "Select a screen finding to preview it here"

    init {
        isOpaque = false
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) onDoubleClick() }
        })
        toolTipText = "Double-click to open the full image"
    }

    fun show(path: Path?, finding: Finding?) {
        highlight = finding
        if (path == null) { image = null; imagePath = null; repaint(); return }
        if (path == imagePath && image != null) { repaint(); return }
        imagePath = path
        ApplicationManager.getApplication().executeOnPooledThread {
            val img = runCatching { ImageIO.read(path.toFile()) }.getOrNull()
            ApplicationManager.getApplication().invokeLater { if (imagePath == path) { image = img; repaint() } }
        }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val img = image
        val pad = JBUI.scale(8)
        if (img == null) {
            g2.color = JBColor.GRAY
            g2.font = JBUI.Fonts.label()
            val fm = g2.fontMetrics
            g2.drawString(placeholder, (width - fm.stringWidth(placeholder)) / 2, height / 2)
            return
        }
        val s = min((width - 2.0 * pad) / img.width, (height - 2.0 * pad) / img.height)
        val dw = (img.width * s).roundToInt(); val dh = (img.height * s).roundToInt()
        val dx = (width - dw) / 2; val dy = (height - dh) / 2
        g2.drawImage(img, dx, dy, dw, dh, null)
        val b = highlight?.boundsPx ?: return
        val x1 = dx + (b[0] * s).roundToInt(); val y1 = dy + (b[1] * s).roundToInt()
        val x2 = dx + (b[2] * s).roundToInt(); val y2 = dy + (b[3] * s).roundToInt()
        // dim everything but the finding
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f)
        g2.color = Color.BLACK
        g2.fillRect(dx, dy, dw, y1 - dy)
        g2.fillRect(dx, y2, dw, dy + dh - y2)
        g2.fillRect(dx, y1, x1 - dx, y2 - y1)
        g2.fillRect(x2, y1, dx + dw - x2, y2 - y1)
        g2.composite = AlphaComposite.SrcOver
        g2.color = Annotator.colorOf(highlight!!.severity)
        g2.stroke = BasicStroke(JBUI.scale(3).toFloat())
        val m = JBUI.scale(3)
        g2.drawRoundRect(x1 - m, y1 - m, (x2 - x1) + 2 * m, (y2 - y1) + 2 * m, JBUI.scale(6), JBUI.scale(6))
    }
}
