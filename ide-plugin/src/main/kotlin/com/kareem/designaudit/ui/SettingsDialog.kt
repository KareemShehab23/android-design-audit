package com.kareem.designaudit.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.kareem.designaudit.settings.AuditSettings
import javax.swing.JComponent

/** Thresholds and capture options. Values are saved application-wide. */
class SettingsDialog(project: Project) : DialogWrapper(project) {
    private val s = AuditSettings.current

    init {
        title = "Android Design Audit Settings"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        group("Material thresholds (dp)") {
            row("Minimum touch target:") { intTextField(24..96).bindIntText(s::minTouchDp).comment("Material 3 recommends 48dp") }
            row("Minimum gap between targets:") { intTextField(0..32).bindIntText(s::minTouchGapDp).comment("8dp") }
            row("Screen side margin:") { intTextField(0..48).bindIntText(s::screenMarginDp).comment("16dp on phones, 24dp on larger screens") }
            row("Minimum inner padding (cards/boxes):") { intTextField(0..48).bindIntText(s::minInnerPaddingDp).comment("Material cards use 16dp; 8dp is the floor") }
            row("Spacing grid:") { intTextField(1..16).bindIntText(s::gridDp).comment("4dp grid (8dp preferred)") }
        }
        group("Code scan") {
            row { checkBox("Highlight violations in the editor as you type (restart not needed)").bindSelected(s::liveHighlight) }
            row("Max findings per rule per file:") { intTextField(1..200).bindIntText(s::maxPerRule).comment("Keeps noisy files from flooding the table") }
        }
        group("Capture") {
            row { checkBox("Also take a clean screenshot (layout bounds off)").bindSelected(s::captureCleanScreenshot) }
            row { checkBox("Leave 'Show layout bounds' on after capture").bindSelected(s::keepLayoutBounds) }
            row("Output folder (inside project):") { textField().bindText(s::outputFolder).align(Align.FILL) }
        }
    }
}
