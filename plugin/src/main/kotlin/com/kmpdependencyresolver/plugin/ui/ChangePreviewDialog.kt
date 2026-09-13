package com.kmpdependencyresolver.plugin.ui

import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTabbedPane

class ChangePreviewDialog(
    private val project: Project,
    private val state: AddPreviewState,
) : DialogWrapper(project) {
    private val diffPanels = mutableListOf<DiffRequestPanel>()

    init {
        title = "Review dependency changes"
        setOKButtonText("Apply")
        init()
        isOKActionEnabled = state.applyEnabled
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))).apply {
        if (state.warnings.isNotEmpty()) {
            add(JBLabel(state.warnings.joinToString("<br>", "<html>", "</html>")), BorderLayout.NORTH)
        }
        val tabs = JTabbedPane()
        val requests = state.preview?.let(DiffRequestFactory(project)::create).orEmpty()
        requests.forEachIndexed { index, request ->
            val panel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
            panel.setRequest(request)
            diffPanels += panel
            tabs.addTab(state.preview!!.files[index].path.substringAfterLast('/'), panel.component)
        }
        add(tabs, BorderLayout.CENTER)
        preferredSize = JBUI.size(960, 640)
    }
}
