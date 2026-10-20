package com.kareem.designaudit.model

/** Thresholds and behaviour shared by the IDE plugin, the CLI and the skill. Plain Kotlin, no IDE types. */
data class AuditConfig(
    var minTouchDp: Int = 48,
    var minTouchGapDp: Int = 8,
    var screenMarginDp: Int = 16,
    var gridDp: Int = 4,
    var minInnerPaddingDp: Int = 8,
    var maxPerRule: Int = 8,
    var keepLayoutBounds: Boolean = false,
    var captureCleanScreenshot: Boolean = true,
    var outputFolder: String = "audit-captures",
) {
    companion object {
        /** The configuration in effect for the next run. The IDE plugin sets it from its settings page. */
        @Volatile var current: AuditConfig = AuditConfig()
    }
}
