package com.kareem.designaudit.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.kareem.designaudit.adb.Adb
import com.kareem.designaudit.analysis.Annotator
import com.kareem.designaudit.analysis.AuditService
import com.kareem.designaudit.model.Finding
import com.kareem.designaudit.model.Severity
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.ListSelectionModel
import javax.swing.RowFilter
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

/** The "Design Audit" tool window. */
class AuditPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private val service = AuditService.get(project)

    private val deviceCombo = ComboBox<Adb.Device>().apply { prototypeDisplayValue = Adb.Device("emulator-5554", "Pixel 8 Pro") }
    private val screenName = JTextField(service.screenName, 12)
    private val captureButton = JButton("Capture screen", AllIcons.Actions.Preview)
    private val scanButton = JButton("Scan code", AllIcons.Actions.Find)
    private val perfButton = JButton("Measure perf", AllIcons.Actions.Profile).apply { toolTipText = "Reset frame counters, use the app for 10 s, then report jank, frame times and memory" }
    private val exportButton = JButton("Export .md", AllIcons.ToolbarDecorator.Export).apply { toolTipText = "Save all findings with why + how to fix as a Markdown file" }

    private val errToggle = SeverityToggle(Severity.ERROR)
    private val warnToggle = SeverityToggle(Severity.WARNING)
    private val search = SearchTextField(false).apply { textEditor.emptyText.text = "Filter by rule, area, file, text…" }

    private val model = FindingsModel()
    private val table = JBTable(model)
    private val sorter = TableRowSorter(model)
    private val details = DetailsPanel(service)
    private val status = JBLabel("", SwingConstants.LEFT).apply { foreground = JBColor.GRAY; border = JBUI.Borders.empty(4, 10) }

    private val pluginVersion: String = com.intellij.ide.plugins.PluginManagerCore.getPlugin(
        com.intellij.openapi.extensions.PluginId.getId("com.kareem.designaudit"))?.version ?: "?"
    private val cards = CardLayout()
    private val content = JPanel(cards)
    private val listener: () -> Unit = { refresh() }

    init {
        // IDE toolbar (same actions as Tools → Android Design Audit)
        val group = ActionManager.getInstance().getAction("DesignAudit.Toolbar") as DefaultActionGroup
        val tb: ActionToolbar = ActionManager.getInstance().createActionToolbar("DesignAudit.Toolbar", group, true)
        tb.targetComponent = this
        toolbar = tb.component

        // Workflow header
        captureButton.addActionListener { commitChoices(); service.captureCurrent() }
        scanButton.addActionListener { service.scanCode() }
        perfButton.addActionListener { commitChoices(); service.measurePerformance(10) }
        exportButton.addActionListener {
            val action = ActionManager.getInstance().getAction("DesignAudit.ExportMd")
            ActionManager.getInstance().tryToExecute(action, null, exportButton, "DesignAudit.Panel", true)
        }
        deviceCombo.addActionListener { service.selectedDevice = deviceCombo.selectedItem as? Adb.Device }
        screenName.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = commitChoices()
            override fun removeUpdate(e: DocumentEvent) = commitChoices()
            override fun changedUpdate(e: DocumentEvent) = commitChoices()
        })
        val header = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(6))).apply {
            add(JBLabel("Device:")); add(deviceCombo)
            add(JBLabel("Screen:")); add(screenName)
            add(captureButton); add(perfButton); add(scanButton); add(exportButton)
        }

        // Filters
        listOf(errToggle, warnToggle).forEach { it.addActionListener { applyFilter() } }
        search.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })
        val filters = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), JBUI.Borders.empty(2, 6))
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply { add(errToggle); add(warnToggle) }, BorderLayout.WEST)
            add(search, BorderLayout.CENTER)
        }

        // Table
        table.rowSorter = sorter
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.setShowGrid(false)
        table.intercellSpacing = JBUI.emptySize()
        table.rowHeight = JBUI.scale(24)
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        with(table.columnModel) {
            getColumn(0).apply { maxWidth = JBUI.scale(40); minWidth = JBUI.scale(36) }
            getColumn(1).apply { preferredWidth = JBUI.scale(90); maxWidth = JBUI.scale(110); cellRenderer = SeverityRenderer() }
            getColumn(2).preferredWidth = JBUI.scale(130)
            getColumn(3).preferredWidth = JBUI.scale(160)
            getColumn(4).preferredWidth = JBUI.scale(200)
            getColumn(5).preferredWidth = JBUI.scale(520)
        }
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) details.show(selected()) }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) selected()?.let(service::navigate) }
        })

        val results = JPanel(BorderLayout()).apply {
            add(filters, BorderLayout.NORTH)
            add(OnePixelSplitter(false, 0.62f).apply {
                firstComponent = JBScrollPane(table).apply { border = JBUI.Borders.empty() }
                secondComponent = details
            }, BorderLayout.CENTER)
        }

        // Empty state
        val empty = JBPanelWithEmptyText().apply {
            emptyText.appendLine("No audit yet", com.intellij.ui.SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES, null)
            emptyText.appendLine("Connect a device, open the screen you want to check, then press Capture screen.", com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
            emptyText.appendLine("Capture screen", com.intellij.ui.SimpleTextAttributes.LINK_ATTRIBUTES) { commitChoices(); service.captureCurrent() }
            emptyText.appendLine("Scan code only (no device needed)", com.intellij.ui.SimpleTextAttributes.LINK_ATTRIBUTES) { service.scanCode() }
        }
        content.add(empty, "empty")
        content.add(results, "results")

        val root = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(content, BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        }
        setContent(root)

        service.addListener(listener)
        service.refreshDevices()
        refresh()
    }

    private fun commitChoices() {
        service.screenName = screenName.text
        service.selectedDevice = deviceCombo.selectedItem as? Adb.Device
    }

    private fun selected(): Finding? {
        val r = table.selectedRow
        return if (r < 0) null else model.get(table.convertRowIndexToModel(r))
    }

    /** Called on the EDT whenever the service state changes. */
    private fun refresh() {
        val prev = deviceCombo.selectedItem as? Adb.Device
        val devices = service.devices
        if (devices.map { it.serial } != (0 until deviceCombo.itemCount).map { deviceCombo.getItemAt(it).serial }) {
            deviceCombo.removeAllItems()
            devices.forEach { deviceCombo.addItem(it) }
            (devices.firstOrNull { it.serial == prev?.serial } ?: devices.firstOrNull())?.let { deviceCombo.selectedItem = it }
        }
        service.selectedDevice = deviceCombo.selectedItem as? Adb.Device
        captureButton.isEnabled = !service.busy && devices.isNotEmpty()
        scanButton.isEnabled = !service.busy
        perfButton.isEnabled = !service.busy && devices.isNotEmpty()
        exportButton.isEnabled = !service.busy && service.all.isNotEmpty()
        captureButton.text = if (service.busy) "Working…" else "Capture screen"

        val all = service.all
        val keep = selected()
        model.set(all)
        errToggle.count = all.count { it.severity == Severity.ERROR }
        warnToggle.count = all.count { it.severity == Severity.WARNING }
        applyFilter()
        if (keep != null) { val i = all.indexOf(keep); if (i >= 0) { val v = table.convertRowIndexToView(i); if (v >= 0) table.setRowSelectionInterval(v, v) } }
        cards.show(content, if (all.isEmpty()) "empty" else "results")
        status.text = "v$pluginVersion · " + service.status
    }

    private fun applyFilter() {
        val q = search.text.trim().lowercase()
        val sev = buildSet { if (errToggle.isSelected) add(Severity.ERROR); if (warnToggle.isSelected) add(Severity.WARNING) }
        sorter.rowFilter = object : RowFilter<FindingsModel, Int>() {
            override fun include(e: Entry<out FindingsModel, out Int>): Boolean {
                val f = e.model.get(e.identifier)
                if (f.severity !in sev) return false
                if (q.isEmpty()) return true
                return listOf(f.rule, f.area, f.location(), f.message, f.suggestion, f.snippet).any { it.lowercase().contains(q) }
            }
        }
    }

    override fun dispose() { service.removeListener(listener) }

    // ---- table pieces --------------------------------------------------------------------------
    private class FindingsModel : AbstractTableModel() {
        private val cols = arrayOf("#", "Severity", "Area", "Rule", "Location", "Problem")
        private var rows: List<Finding> = emptyList()
        fun set(f: List<Finding>) { rows = f; fireTableDataChanged() }
        fun get(i: Int) = rows[i]
        override fun getRowCount() = rows.size
        override fun getColumnCount() = cols.size
        override fun getColumnName(c: Int) = cols[c]
        override fun getColumnClass(c: Int): Class<*> = when (c) { 0 -> Int::class.javaObjectType; 1 -> Severity::class.java; else -> String::class.java }
        override fun getValueAt(r: Int, c: Int): Any {
            val f = rows[r]
            return when (c) { 0 -> r + 1; 1 -> f.severity; 2 -> f.area; 3 -> f.rule; 4 -> f.location(); else -> f.message }
        }
    }

    private class SeverityRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(t: JTable, v: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int): Component {
            super.getTableCellRendererComponent(t, v, sel, focus, row, col)
            if (v is Severity) {
                text = v.label
                icon = when (v) { Severity.ERROR -> AllIcons.General.Error; Severity.WARNING -> AllIcons.General.Warning }
                if (!sel) foreground = JBColor(Annotator.colorOf(v), Annotator.colorOf(v).brighter())
            }
            return this
        }
    }

    /** "● 4 Errors" toggle used as a filter chip. */
    private class SeverityToggle(val severity: Severity) : JToggleButton("", true) {
        var count = 0
            set(v) { field = v; text = "$v ${severity.label}${if (v == 1) "" else "s"}" }
        init {
            icon = when (severity) { Severity.ERROR -> AllIcons.General.Error; Severity.WARNING -> AllIcons.General.Warning }
            count = 0
            toolTipText = "Show / hide ${severity.label.lowercase()} findings"
            isFocusable = false
        }
    }
}
