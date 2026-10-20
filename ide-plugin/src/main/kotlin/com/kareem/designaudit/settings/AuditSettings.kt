package com.kareem.designaudit.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** Thresholds and behaviour, persisted at application level. */
@Service(Service.Level.APP)
@State(name = "AndroidDesignAuditSettings", storages = [Storage("androidDesignAudit.xml")])
class AuditSettings : PersistentStateComponent<AuditSettings.State> {

    class State {
        var minTouchDp = 48
        var minTouchGapDp = 8
        var screenMarginDp = 16
        var gridDp = 4
        var minInnerPaddingDp = 8
        var maxPerRule = 8
        var keepLayoutBounds = false
        var captureCleanScreenshot = true
        var outputFolder = "audit-captures"
        var liveHighlight = true
    }

    private var state = State()

    override fun getState() = state
    override fun loadState(s: State) { state = s }

    companion object {
        val instance: AuditSettings get() = service()
        val current: State get() = instance.state
        /** Push the IDE settings into the shared core configuration. */
        fun apply() { val s = current; com.kareem.designaudit.model.AuditConfig.current = com.kareem.designaudit.model.AuditConfig(
            s.minTouchDp, s.minTouchGapDp, s.screenMarginDp, s.gridDp, s.minInnerPaddingDp, s.maxPerRule, s.keepLayoutBounds, s.captureCleanScreenshot, s.outputFolder) }
    }
}
