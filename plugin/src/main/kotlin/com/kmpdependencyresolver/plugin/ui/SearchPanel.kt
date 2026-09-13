package com.kmpdependencyresolver.plugin.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.kmpdependencyresolver.core.model.TargetFamily
import com.kmpdependencyresolver.plugin.DependencyResolverService
import java.awt.BorderLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.util.concurrent.Executors
import javax.swing.AbstractAction
import javax.swing.DefaultListModel
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class SearchPanel(project: Project) : JPanel(BorderLayout()), Disposable {
    private val service = project.service<DependencyResolverService>()
    private val query = SearchTextField(false)
    private val module = JComboBox<String>()
    private val target = JComboBox(arrayOf("Any target", "Android", "JVM", "iOS", "Desktop", "JS", "Wasm"))
    private val previews = JCheckBox("Include pre-release")
    private val status = JBLabel("Search Kotlin Multiplatform dependencies")
    private val listModel = DefaultListModel<SearchResultItem>()
    private val results = JBList(listModel)
    private val scheduled = Executors.newSingleThreadScheduledExecutor()
    private val workers = Executors.newFixedThreadPool(2)
    private val presenter: SearchPresenter

    init {
        border = JBUI.Borders.empty(8)
        query.textEditor.accessibleContext.accessibleName = "Dependency search"
        module.accessibleContext.accessibleName = "Gradle module"
        target.accessibleContext.accessibleName = "Required target"
        results.accessibleContext.accessibleName = "Dependency results"
        results.cellRenderer = ResultCellRenderer()

        val snapshot = runCatching(service::projectSnapshot).getOrNull()
        snapshot?.modules?.forEach { module.addItem(it.id) }
        service.activeModuleId(snapshot ?: returnSnapshot())?.let(module::setSelectedItem)

        val debounceScheduler = TaskScheduler { delay, task ->
            val future = scheduled.schedule(task, delay, java.util.concurrent.TimeUnit.MILLISECONDS)
            Cancellable { future.cancel(true) }
        }
        val workerScheduler = TaskScheduler { _, task ->
            val future = workers.submit(task)
            Cancellable { future.cancel(true) }
        }
        presenter = SearchPresenter(
            DependencySearchGateway(service::search), debounceScheduler, workerScheduler,
            UiDispatcher { task -> ApplicationManager.getApplication().invokeLater(task) },
            activeModule = { service.activeModuleId() }, onState = ::render,
        )

        val filters = JPanel().apply { add(module); add(target); add(previews) }
        val north = JPanel(BorderLayout()).apply { add(query, BorderLayout.NORTH); add(filters, BorderLayout.CENTER); add(status, BorderLayout.SOUTH) }
        add(north, BorderLayout.NORTH)
        add(JBScrollPane(results), BorderLayout.CENTER)

        query.textEditor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = search()
            override fun removeUpdate(e: DocumentEvent?) = search()
            override fun changedUpdate(e: DocumentEvent?) = search()
        })
        target.addActionListener { search() }
        previews.addActionListener { search() }
        installKeyboardActions()
    }

    private fun returnSnapshot() = com.kmpdependencyresolver.plugin.project.ProjectSnapshot("", null, emptyList(), emptyMap(), emptySet())

    private fun search() {
        presenter.onQueryChanged(query.text, selectedTargets(), previews.isSelected, module.selectedItem as? String)
    }

    private fun selectedTargets(): Set<TargetFamily> = when (target.selectedItem) {
        "Android" -> setOf(TargetFamily.ANDROID)
        "JVM" -> setOf(TargetFamily.JVM)
        "iOS" -> setOf(TargetFamily.IOS)
        "Desktop" -> setOf(TargetFamily.JVM, TargetFamily.MACOS, TargetFamily.LINUX, TargetFamily.MINGW)
        "JS" -> setOf(TargetFamily.JS)
        "Wasm" -> setOf(TargetFamily.WASM)
        else -> emptySet()
    }

    private fun render(state: SearchUiState) {
        listModel.clear()
        state.results.forEach(listModel::addElement)
        status.text = when {
            state.searching -> "Searching…"
            state.providerMessages.isNotEmpty() -> "${state.results.size} results · ${state.providerMessages.joinToString()}"
            else -> "${state.results.size} results"
        }
    }

    private fun installKeyboardActions() {
        query.textEditor.inputMap.put(KeyStroke.getKeyStroke("ESCAPE"), "cancelSearch")
        query.textEditor.actionMap.put("cancelSearch", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) { presenter.cancel(); status.text = "Search cancelled" }
        })
        results.inputMap.put(KeyStroke.getKeyStroke('C', Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx), "copyDependency")
        results.actionMap.put("copyDependency", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) = showCopyMenu()
        })
        results.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "confirmDependency")
        results.actionMap.put("confirmDependency", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) { status.text = "Choose Add to review changes" }
        })
    }

    private fun showCopyMenu() {
        val item = results.selectedValue ?: return
        val version = item.candidate.versions.firstOrNull()?.value ?: return
        val selection = CopySelection(item.candidate, version, "commonMain", "implementation")
        JPopupMenu().apply {
            CopyKind.entries.forEach { kind ->
                add(JMenuItem(kind.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)).apply {
                    addActionListener {
                        CopyPasteManager.getInstance().setContents(StringSelection(CopyFormatter().format(selection, kind)))
                    }
                })
            }
            show(results, 8, 8)
        }
    }

    override fun dispose() {
        presenter.cancel()
        scheduled.shutdownNow()
        workers.shutdownNow()
    }
}
